package escalator.agent.providers

import scala.collection.mutable

import io.circe.{Json, JsonObject}
import io.circe.parser.{parse => parseJson}

import escalator.agent.StoredMessage
import escalator.agent.protocol._

/**
 * ==The Messages API, as JSON==
 *
 * Everything in this file is PURE and works on `io.circe.Json`, deliberately:
 * the Anthropic SDK's stream events and message params are Jackson-serializable
 * both ways ([[AnthropicSdk]] does the two-line conversion), so the mapping and
 * the history rules can be written — and tested — without a client, a network,
 * or a single SDK type.
 *
 * Two jobs live here:
 *
 *  - [[AnthropicHistory]]: our stored `agent_messages` rows → the `messages`
 *    array a request needs, INCLUDING the repairs the API insists on (roles
 *    alternate, every `tool_use` is answered by a `tool_result` in the very
 *    next message);
 *  - [[AnthropicTurnMapper]]: the streamed `RawMessageStreamEvent`s → our
 *    provider-neutral [[AgentEvent]]s, plus the assistant content blocks the
 *    next request has to replay verbatim.
 */
object AnthropicHistory {

  /** Roles as the API knows them. Our store also has "tool", which IS a user
    * message carrying `tool_result` blocks. */
  private val User      = "user"
  private val Assistant = "assistant"

  /** A message the API would accept: `{"role":…, "content":[blocks]}`. */
  def message(role: String, blocks: List[Json]): Json =
    Json.obj("role" -> Json.fromString(role), "content" -> Json.arr(blocks: _*))

  def textBlock(text: String): Json =
    Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))

  /** A user message with one text block — the shape a plain question takes. */
  def userText(text: String): Json = message(User, List(textBlock(text)))

  /** A base64 image, as a content block. `mediaType` is a real MIME type. */
  def imageBlock(base64: String, mediaType: String = "image/png"): Json = Json.obj(
    "type" -> Json.fromString("image"),
    "source" -> Json.obj(
      "type"       -> Json.fromString("base64"),
      "media_type" -> Json.fromString(mediaType),
      "data"       -> Json.fromString(base64)
    )
  )

  /**
   * Tool results go back as ONE user message holding every result, with
   * `is_error` on the ones that failed. `content` is a list of text blocks
   * rather than a bare object because that is what the API accepts — and it is
   * also what the CLI provider produces, so the UI renders both identically.
   *
   * When the tool drew a chart, the rendered PNG rides along as a second block:
   * the Messages API accepts an image inside a `tool_result`, so the model SEES
   * the picture it just made and can talk about what is in it instead of
   * re-reading its own numbers. The text block still carries the whole payload,
   * so a result is never image-only.
   */
  def toolResultBlock(toolUseId: String, payload: Json, isError: Boolean, imagePngBase64: Option[String] = None): Json = {
    val textBlocks = payload.asArray match {
      case Some(items) => items.toList
      case None        => List(textBlock(payload.asString.getOrElse(payload.noSpaces)))
    }
    // Never on an error: an image attached to a failure is a picture of the
    // previous answer, and the model reads it as this one.
    val blocks =
      if (isError) textBlocks
      else textBlocks ++ imagePngBase64.filter(_.nonEmpty).map(b => imageBlock(b)).toList
    Json.obj(
      "type"        -> Json.fromString("tool_result"),
      "tool_use_id" -> Json.fromString(toolUseId),
      "content"     -> Json.arr(blocks: _*),
      "is_error"    -> Json.fromBoolean(isError)
    )
  }

  private def blocksOf(content: Json): List[Json] =
    content.asArray.map(_.toList).getOrElse {
      // A row written before the block shape existed, or a bare string.
      content.asString.map(s => List(textBlock(s))).getOrElse(Nil)
    }

  private def blockType(b: Json): String =
    b.hcursor.get[String]("type").toOption.getOrElse("")

  private def roleOf(stored: StoredMessage): String =
    stored.role match {
      case "assistant" => Assistant
      case _           => User // "user" and "tool" are both user turns to the API
    }

  /**
   * Stored rows → a valid `messages` array.
   *
   * Three repairs, each fixing a 400 we would otherwise only see in production:
   *
   *  1. **merge** consecutive same-role messages — a turn's tool results are
   *     stored separately from the next turn's question, and the API requires
   *     roles to alternate;
   *  2. **pair** tool calls with their results — an interrupted turn leaves a
   *     `tool_use` nothing ever answered, and an unanswered call is rejected;
   *  3. **drop** what is left empty, and any leading assistant message (a
   *     conversation starts with a user turn).
   *
   * Thinking blocks are passed through EXACTLY as stored: they are the model's
   * own record of how it got somewhere, and editing them is what invalidates
   * them.
   */
  def toMessages(stored: Seq[StoredMessage]): List[Json] = {
    val converted = stored.iterator
      .map(m => (roleOf(m), blocksOf(m.content)))
      .filter(_._2.nonEmpty)
      .toList

    val merged = mergeSameRole(converted)
    val paired = pairToolCalls(merged)

    paired
      .dropWhile(_._1 == Assistant)
      .filter(_._2.nonEmpty)
      .map { case (role, blocks) => message(role, blocks) }
  }

  private def mergeSameRole(msgs: List[(String, List[Json])]): List[(String, List[Json])] =
    msgs.foldLeft(List.empty[(String, List[Json])]) {
      case (acc, (role, blocks)) =>
        acc.lastOption match {
          case Some((prevRole, prevBlocks)) if prevRole == role =>
            acc.init :+ ((role, prevBlocks ++ blocks))
          case _ => acc :+ ((role, blocks))
        }
    }

  /**
   * Every `tool_use` must be answered in the NEXT message and every
   * `tool_result` must answer a call in the PREVIOUS one. Anything unmatched is
   * dropped — a half-finished tool round is not worth failing a whole thread
   * for.
   */
  private def pairToolCalls(msgs: List[(String, List[Json])]): List[(String, List[Json])] = {
    val arr = msgs.toArray

    // Pass 1: drop tool_use blocks whose result never arrived.
    arr.indices.foreach { i =>
      val (role, blocks) = arr(i)
      if (role == Assistant && blocks.exists(b => blockType(b) == "tool_use")) {
        val answered: Set[String] =
          if (i + 1 < arr.length)
            arr(i + 1)._2.iterator
              .filter(b => blockType(b) == "tool_result")
              .flatMap(_.hcursor.get[String]("tool_use_id").toOption)
              .toSet
          else Set.empty
        arr(i) = (
          role,
          blocks.filter { b =>
            blockType(b) != "tool_use" ||
            b.hcursor.get[String]("id").toOption.exists(answered.contains)
          }
        )
      }
    }

    // Pass 2: drop tool_results that now answer nothing.
    arr.indices.foreach { i =>
      val (role, blocks) = arr(i)
      if (blocks.exists(b => blockType(b) == "tool_result")) {
        val called: Set[String] =
          if (i > 0)
            arr(i - 1)._2.iterator
              .filter(b => blockType(b) == "tool_use")
              .flatMap(_.hcursor.get[String]("id").toOption)
              .toSet
          else Set.empty
        arr(i) = (
          role,
          blocks.filter { b =>
            blockType(b) != "tool_result" ||
            b.hcursor.get[String]("tool_use_id").toOption.exists(called.contains)
          }
        )
      }
    }

    arr.toList
  }
}

/**
 * ==One request's stream, folded==
 *
 * Feed it the raw stream events in order; it returns the [[AgentEvent]]s to
 * emit and keeps the assistant's content blocks, the usage and the stop reason
 * for the loop that owns it.
 *
 * The fiddly part is `input_json_delta`: a tool call's arguments arrive as a
 * string in pieces, so the tool card cannot be shown until
 * `content_block_stop`. That is why an `ItemStarted(McpToolCall)` is emitted at
 * the END of the block rather than at its start — a card with half a JSON
 * fragment in it is worse than a card a beat later.
 *
 * Not thread-safe, and not meant to be: one mapper belongs to one request.
 */
final class AnthropicTurnMapper(turnId: TurnId, itemPrefix: String) {

  private sealed trait BlockKind
  private case object TextBlock     extends BlockKind
  private case object ThinkingBlock extends BlockKind
  private final case class ToolBlock(id: String, name: String) extends BlockKind
  private case object OtherBlock    extends BlockKind

  private final class BlockState(val kind: BlockKind, val itemId: ItemId) {
    val buffer    = new StringBuilder
    val signature = new StringBuilder
  }

  private val open        = mutable.Map.empty[Int, BlockState]
  private val content     = mutable.ListBuffer.empty[Json]
  private val calls       = mutable.ListBuffer.empty[(String, String, Json)]
  private val text        = new StringBuilder
  private var itemSeq     = 0
  private var usageAcc    = Usage.empty
  private var stop        = Option.empty[String]
  private var stopDetail  = Option.empty[Json]

  /** The assistant content blocks of this request, in order, ready to replay. */
  def assistantContent: List[Json] = content.toList

  /** `(tool_use_id, name, input)` for every call this request asked for. */
  def toolCalls: List[(String, String, Json)] = calls.toList

  def usage: Usage = usageAcc

  /** end_turn | tool_use | max_tokens | refusal | pause_turn | stop_sequence. */
  def stopReason: Option[String] = stop

  /** `stop_details` — the category/explanation behind a refusal. */
  def stopDetails: Option[Json] = stopDetail

  def assistantText: String = text.toString

  private def nextItemId(prefix: String): ItemId = {
    itemSeq += 1
    ItemId(s"$itemPrefix-$prefix-$itemSeq")
  }

  /** Map one stream event. Returns what to emit (often nothing). */
  def onEvent(event: Json): List[AgentEvent] = {
    val c = event.hcursor
    c.get[String]("type").toOption match {

      case Some("message_start") =>
        // Input + cache tokens are only reported here; output arrives on the
        // delta. Both are per REQUEST, and a tool loop is many requests, so
        // they accumulate rather than replace.
        usageAcc = usageAcc + readUsage(c.downField("message").downField("usage").focus)
        Nil

      case Some("content_block_start") =>
        val idx   = c.get[Int]("index").toOption.getOrElse(-1)
        val block = c.downField("content_block")
        block.get[String]("type").toOption match {
          case Some("text") =>
            val id = nextItemId("text")
            open.put(idx, new BlockState(TextBlock, id))
            List(AgentEvent.ItemStarted(turnId, id, ItemType.AssistantMessage, ""))
          case Some("thinking") =>
            val id = nextItemId("reasoning")
            open.put(idx, new BlockState(ThinkingBlock, id))
            List(AgentEvent.ItemStarted(turnId, id, ItemType.Reasoning, "Thinking"))
          case Some("tool_use") =>
            val id   = block.get[String]("id").toOption.getOrElse(nextItemId("tool").value)
            val name = block.get[String]("name").toOption.getOrElse("tool")
            // No event yet: the arguments are still arriving as
            // `input_json_delta` and a tool card without them says nothing.
            open.put(idx, new BlockState(ToolBlock(id, name), ItemId(id)))
            Nil
          case _ =>
            open.put(idx, new BlockState(OtherBlock, nextItemId("block")))
            Nil
        }

      case Some("content_block_delta") =>
        val idx = c.get[Int]("index").toOption.getOrElse(-1)
        val d   = c.downField("delta")
        d.get[String]("type").toOption match {
          case Some("text_delta") =>
            val t = d.get[String]("text").toOption.getOrElse("")
            if (t.isEmpty) Nil
            else
              open.get(idx) match {
                case Some(st) =>
                  st.buffer.append(t); text.append(t)
                  List(AgentEvent.ContentDelta(turnId, st.itemId, StreamKind.AssistantText, t))
                case None => Nil
              }
          case Some("thinking_delta") =>
            val t = d.get[String]("thinking").toOption.getOrElse("")
            if (t.isEmpty) Nil
            else
              open.get(idx) match {
                case Some(st) =>
                  st.buffer.append(t)
                  List(AgentEvent.ContentDelta(turnId, st.itemId, StreamKind.ReasoningText, t))
                case None => Nil
              }
          case Some("signature_delta") =>
            d.get[String]("signature").toOption.foreach(s => open.get(idx).foreach(_.signature.append(s)))
            Nil
          case Some("input_json_delta") =>
            d.get[String]("partial_json").toOption.foreach(s => open.get(idx).foreach(_.buffer.append(s)))
            Nil
          case _ => Nil
        }

      case Some("content_block_stop") =>
        val idx = c.get[Int]("index").toOption.getOrElse(-1)
        open.remove(idx).toList.flatMap { st =>
          st.kind match {
            case TextBlock =>
              content += AnthropicHistory.textBlock(st.buffer.toString)
              List(AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Completed))
            case ThinkingBlock =>
              // Replayed verbatim on the next request, signature included: a
              // thinking block is only valid as the model wrote it.
              val obj = JsonObject(
                "type"     -> Json.fromString("thinking"),
                "thinking" -> Json.fromString(st.buffer.toString)
              )
              val sig = st.signature.toString
              content += Json.fromJsonObject(
                if (sig.isEmpty) obj else obj.add("signature", Json.fromString(sig))
              )
              List(AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Completed))
            case ToolBlock(id, name) =>
              val input = parseJson(st.buffer.toString).getOrElse(Json.obj()) match {
                case j if j.isObject => j
                case _               => Json.obj()
              }
              content += Json.obj(
                "type"  -> Json.fromString("tool_use"),
                "id"    -> Json.fromString(id),
                "name"  -> Json.fromString(name),
                "input" -> input
              )
              calls += ((id, name, input))
              List(
                AgentEvent.ItemStarted(
                  turnId,
                  ItemId(id),
                  ToolPresentation.itemType(name),
                  ToolPresentation.presentLabel(name),
                  Some(name),
                  Some(input)
                )
              )
            case OtherBlock => Nil
          }
        }

      case Some("message_delta") =>
        stop = c.downField("delta").get[String]("stop_reason").toOption.orElse(stop)
        stopDetail = c.downField("delta").downField("stop_details").focus.filterNot(_.isNull).orElse(stopDetail)
        usageAcc = usageAcc + readUsage(c.downField("usage").focus)
        Nil

      case _ => Nil
    }
  }

  private def readUsage(u: Option[Json]): Usage =
    u.map(_.hcursor).map { h =>
      Usage(
        inputTokens      = h.get[Long]("input_tokens").toOption.getOrElse(0L),
        outputTokens     = h.get[Long]("output_tokens").toOption.getOrElse(0L),
        cacheReadTokens  = h.get[Long]("cache_read_input_tokens").toOption.getOrElse(0L),
        cacheWriteTokens = h.get[Long]("cache_creation_input_tokens").toOption.getOrElse(0L)
      )
    }.getOrElse(Usage.empty)
}

/**
 * What a turn costs, from a config price table (USD per million tokens).
 * Optional by design: an unknown model reports no cost rather than a wrong one.
 */
final case class ModelPrice(input: BigDecimal, output: BigDecimal, cacheWrite: BigDecimal, cacheRead: BigDecimal) {

  def cost(u: Usage): BigDecimal = {
    val m = BigDecimal(1000000)
    (BigDecimal(u.inputTokens) * input +
      BigDecimal(u.outputTokens) * output +
      BigDecimal(u.cacheWriteTokens) * cacheWrite +
      BigDecimal(u.cacheReadTokens) * cacheRead) / m
  }
}

object ModelPrice {

  /** Published list prices (USD / MTok), cache write at 1.25× input. An unknown model reports no cost. */
  val defaults: Map[String, ModelPrice] = Map(
    "claude-opus-5-5"           -> ModelPrice(4, 20, 5, BigDecimal("0.2")),
    "claude-sonnet-5-5"         -> ModelPrice(2, 10, BigDecimal("2.5"), BigDecimal("0.2")),
    "claude-fable-5-1"          -> ModelPrice(10, 50, BigDecimal("12.5"), BigDecimal("0.25")),
    "claude-haiku-4-5"          -> ModelPrice(1, 5, BigDecimal("1.25"), BigDecimal("0.1")),
    "claude-haiku-4-5-20251001" -> ModelPrice(1, 5, BigDecimal("1.25"), BigDecimal("0.1"))
  )

  /**
   * Parse `model=input:output[,model=input:output…]` (USD / MTok), e.g. from
   * `<PREFIX>_AGENT_PRICES`. Cache rates default to 1.25× / 0.1× of input.
   * Malformed entries are skipped.
   */
  def parse(spec: String): Map[String, ModelPrice] =
    spec.split(',').iterator.map(_.trim).filter(_.nonEmpty).flatMap { entry =>
      entry.split('=') match {
        case Array(model, rates) =>
          rates.split(':').map(_.trim) match {
            case Array(in, out) =>
              scala.util.Try(BigDecimal(in) -> BigDecimal(out)).toOption.map { case (i, o) =>
                model.trim -> ModelPrice(i, o, i * BigDecimal("1.25"), i * BigDecimal("0.1"))
              }
            case _ => None
          }
        case _ => None
      }
    }.toMap
}
