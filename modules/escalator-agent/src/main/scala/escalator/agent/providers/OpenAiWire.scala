package escalator.agent.providers

import scala.collection.mutable

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.agent.{StoredMessage, ToolDef}
import escalator.agent.protocol._

/**
 * ==The Responses API, as JSON==
 *
 * The OpenAI half of what [[AnthropicWire]] does for Anthropic, and written the
 * same way and for the same reason: the SDK's models are Jackson-serializable
 * both ways ([[OpenAiSdk]] is the two-line bridge), so history conversion and
 * stream mapping are pure `io.circe.Json` and can be tested without a client, a
 * network or a key.
 *
 * The one thing worth stating up front is that BOTH direct-API providers read
 * and write the SAME `agent_messages` rows — provider-neutral blocks in the
 * Anthropic vocabulary (`text` / `tool_use` / `tool_result` / `thinking`),
 * because that vocabulary was there first and duplicating it per provider is
 * how a thread stops being portable. So the job here is a TRANSLATION, both
 * ways: stored blocks → Responses input items on the way in
 * ([[OpenAiHistory]]), streamed output → stored blocks on the way out
 * ([[OpenAiTurnMapper.assistantContent]]). A conversation started on
 * `anthropic-api` can be picked up by `openai-api` and back.
 */
object OpenAiHistory {

  /**
   * Stored rows → the `input` array of a Responses request.
   *
   * Four rules, each of which is a 400 we would otherwise meet in production:
   *
   *  1. a text block becomes the SIMPLE message form (`{role, content: "…"}`),
   *     which the API accepts for both roles and which cannot be confused with
   *     the structured form's `input_text`/`output_text` split;
   *  2. a `tool_use` becomes a `function_call` keyed by `call_id` — the same id
   *     the result will quote, because that pairing is the only thing tying the
   *     two items together;
   *  3. a `tool_result` becomes a `function_call_output` whose `output` is a
   *     STRING (the API's type), so a JSON result is rendered compactly rather
   *     than nested;
   *  4. a `thinking` block is DROPPED. It is Anthropic's signed record of its
   *     own reasoning; replaying it to another vendor is meaningless at best.
   *
   * Unpaired calls and orphaned results are dropped for the same reason
   * [[AnthropicHistory]] drops them: an interrupted turn should cost the next
   * one a missing tool card, not the whole thread.
   */
  def toInput(stored: Seq[StoredMessage]): List[Json] = {
    val items = mutable.ListBuffer.empty[Json]
    // call_id → whether a result for it has been seen, in order.
    val calls   = mutable.LinkedHashSet.empty[String]
    val answers = mutable.LinkedHashSet.empty[String]

    stored.foreach { m =>
      val role   = if (m.role == "assistant") "assistant" else "user"
      val blocks = blocksOf(m.content)
      blocks.foreach { b =>
        val c = b.hcursor
        c.get[String]("type").toOption.getOrElse("") match {
          case "text" =>
            c.get[String]("text").toOption.filter(_.trim.nonEmpty).foreach(t => items += message(role, t))

          case "tool_use" =>
            for {
              id   <- c.get[String]("id").toOption
              name <- c.get[String]("name").toOption
            } {
              calls += id
              items += functionCall(id, name, c.downField("input").focus.getOrElse(Json.obj()))
            }

          case "tool_result" =>
            c.get[String]("tool_use_id").toOption.foreach { id =>
              answers += id
              items += functionCallOutput(id, textOf(c.downField("content").focus))
            }

          case _ => () // thinking, and anything a future provider invents
        }
      }
    }

    val paired = calls.intersect(answers)
    items.toList.filter { j =>
      val c = j.hcursor
      c.get[String]("type").toOption match {
        case Some("function_call")        => c.get[String]("call_id").toOption.exists(paired.contains)
        case Some("function_call_output") => c.get[String]("call_id").toOption.exists(paired.contains)
        case _                            => true
      }
    }
  }

  /** `{role, content}` — the simple message form, valid for either role. */
  def message(role: String, text: String): Json =
    Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(text))

  def functionCall(callId: String, name: String, input: Json): Json = Json.obj(
    "type"      -> Json.fromString("function_call"),
    "call_id"   -> Json.fromString(callId),
    "name"      -> Json.fromString(name),
    "arguments" -> Json.fromString(if (input.isNull) "{}" else input.noSpaces)
  )

  def functionCallOutput(callId: String, output: String): Json = Json.obj(
    "type"    -> Json.fromString("function_call_output"),
    "call_id" -> Json.fromString(callId),
    "output"  -> Json.fromString(output)
  )

  /**
   * One tool, as a Responses function tool. `strict` is turned on ONLY when the
   * schema can actually satisfy it — every property required and
   * `additionalProperties:false` — because a strict tool whose schema does not
   * qualify is a 400 on every request, and the MCP registry has both kinds.
   */
  def functionTool(t: ToolDef): Json = {
    val schema = if (t.inputSchema.isObject) t.inputSchema
                 else Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj())
    Json.obj(
      "type"        -> Json.fromString("function"),
      "name"        -> Json.fromString(t.name),
      "description" -> Json.fromString(t.description),
      "parameters"  -> schema,
      "strict"      -> Json.fromBoolean(strictable(schema))
    )
  }

  /** True when a schema meets the Responses API's structural-outputs rules. */
  def strictable(schema: Json): Boolean = {
    val c     = schema.hcursor
    val closed = c.get[Boolean]("additionalProperties").toOption.contains(false)
    val props  = c.downField("properties").focus.flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty[String])
    val req    = c.downField("required").focus.flatMap(_.asArray)
      .map(_.flatMap(_.asString).toSet).getOrElse(Set.empty[String])
    closed && props.subsetOf(req)
  }

  private def blocksOf(content: Json): List[Json] =
    content.asArray.map(_.toList).getOrElse {
      content.asString.map(s => List(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(s))))
        .getOrElse(Nil)
    }

  /** A tool result's payload flattened to the string the API's `output` wants. */
  private def textOf(content: Option[Json]): String = content match {
    case None    => ""
    case Some(j) =>
      j.asString.getOrElse {
        j.asArray match {
          case Some(blocks) =>
            val texts = blocks.flatMap(_.hcursor.get[String]("text").toOption)
            if (texts.nonEmpty) texts.mkString("\n") else j.noSpaces
          case None => j.noSpaces
        }
      }
  }
}

/**
 * ==One request's stream, folded==
 *
 * Feed it the Responses stream events in order; it returns the [[AgentEvent]]s
 * to emit and keeps, for the loop that owns it, the assistant blocks to store,
 * the tool calls to run, the usage, and how the request ended.
 *
 * Two id systems meet here and it matters which one wins. Every streamed event
 * carries an `item_id` (`msg_…`, `rs_…`, `fc_…`); a function call ALSO has a
 * `call_id` (`call_…`), and the `call_id` is what the tool result must quote.
 * So text and reasoning items are keyed by `item_id`, but a tool card's
 * [[ItemId]] is the `call_id` — which is what lets the executor's
 * `ItemCompleted` land on the card the `ItemStarted` opened.
 *
 * The arguments of a call arrive as a string in pieces, so — exactly as in the
 * Anthropic mapper — the tool card is emitted when the arguments are DONE, not
 * when the item is added. A card showing half a JSON fragment is worse than a
 * card a beat later.
 *
 * Not thread-safe, and not meant to be: one mapper belongs to one request.
 */
final class OpenAiTurnMapper(turnId: TurnId) {

  private sealed trait Kind
  private case object TextItem      extends Kind
  private case object ReasoningItem extends Kind
  private final case class CallItem(callId: String, name: String) extends Kind
  private case object OtherItem     extends Kind

  private final class ItemState(val kind: Kind, val itemId: ItemId) {
    val buffer = new StringBuilder
    var emittedCard = false
  }

  private val open    = mutable.Map.empty[String, ItemState]
  private val content = mutable.ListBuffer.empty[Json]
  private val calls   = mutable.ListBuffer.empty[(String, String, Json)]
  private var usageAcc = Usage.empty
  private var statusOpt = Option.empty[String]
  private var errorOpt  = Option.empty[(String, String)]

  /** Provider-neutral assistant blocks for the store, in order. */
  def assistantContent: List[Json] = content.toList

  /** `(call_id, name, arguments)` for every call this request asked for. */
  def toolCalls: List[(String, String, Json)] = calls.toList

  def usage: Usage = usageAcc

  /** completed | incomplete | failed — how the request ended, if it did. */
  def status: Option[String] = statusOpt

  /** `(kind, message)` when the request itself reported an error. */
  def error: Option[(String, String)] = errorOpt

  def onEvent(event: Json): List[AgentEvent] = {
    val c = event.hcursor
    c.get[String]("type").toOption match {

      case Some("response.output_item.added") =>
        val item = c.downField("item")
        val id   = item.get[String]("id").toOption.getOrElse("")
        item.get[String]("type").toOption match {
          case Some("message") =>
            val st = new ItemState(TextItem, ItemId(id))
            open.put(id, st)
            List(AgentEvent.ItemStarted(turnId, st.itemId, ItemType.AssistantMessage, ""))
          case Some("reasoning") =>
            val st = new ItemState(ReasoningItem, ItemId(id))
            open.put(id, st)
            List(AgentEvent.ItemStarted(turnId, st.itemId, ItemType.Reasoning, "Thinking"))
          case Some("function_call") =>
            val callId = item.get[String]("call_id").toOption.getOrElse(id)
            val name   = item.get[String]("name").toOption.getOrElse("tool")
            // Keyed by item_id (that is what the deltas quote) but IDENTIFIED
            // by call_id (that is what the result must quote).
            open.put(id, new ItemState(CallItem(callId, name), ItemId(callId)))
            Nil
          case _ =>
            open.put(id, new ItemState(OtherItem, ItemId(id)))
            Nil
        }

      case Some("response.output_text.delta") =>
        delta(c).map { case (st, d) =>
          st.buffer.append(d)
          List(AgentEvent.ContentDelta(turnId, st.itemId, StreamKind.AssistantText, d))
        }.getOrElse(Nil)

      // Reasoning reaches us as a SUMMARY (`reasoning_summary_text`) on the
      // models that summarise and as raw text on the ones that do not. Both are
      // the same thing to the UI's collapsed thinking block.
      case Some("response.reasoning_summary_text.delta") | Some("response.reasoning_text.delta") =>
        delta(c).map { case (st, d) =>
          List(AgentEvent.ContentDelta(turnId, st.itemId, StreamKind.ReasoningText, d))
        }.getOrElse(Nil)

      case Some("response.function_call_arguments.delta") =>
        delta(c).foreach { case (st, d) => st.buffer.append(d) }
        Nil

      case Some("response.function_call_arguments.done") =>
        val itemId = c.get[String]("item_id").toOption.getOrElse("")
        open.get(itemId).toList.flatMap { st =>
          st.kind match {
            case CallItem(callId, name) =>
              val raw   = c.get[String]("arguments").toOption.getOrElse(st.buffer.toString)
              val input = parseJson(raw).toOption.filter(_.isObject).getOrElse(Json.obj())
              st.emittedCard = true
              content += Json.obj(
                "type"  -> Json.fromString("tool_use"),
                "id"    -> Json.fromString(callId),
                "name"  -> Json.fromString(name),
                "input" -> input
              )
              calls += ((callId, name, input))
              List(
                AgentEvent.ItemStarted(
                  turnId, st.itemId, ToolPresentation.itemType(name),
                  ToolPresentation.presentLabel(name), Some(name), Some(input)
                )
              )
            case _ => Nil
          }
        }

      case Some("response.output_item.done") =>
        val item = c.downField("item")
        val id   = item.get[String]("id").toOption.getOrElse("")
        open.remove(id).toList.flatMap { st =>
          st.kind match {
            case TextItem =>
              val text =
                if (st.buffer.nonEmpty) st.buffer.toString
                else
                  item.downField("content").focus
                    .flatMap(_.asArray)
                    .map(_.flatMap(_.hcursor.get[String]("text").toOption).mkString)
                    .getOrElse("")
              val pre =
                if (st.buffer.isEmpty && text.nonEmpty)
                  // A model that sent no deltas still owes the UI its answer.
                  List(AgentEvent.ContentDelta(turnId, st.itemId, StreamKind.AssistantText, text))
                else Nil
              if (text.nonEmpty)
                content += Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))
              pre ::: List(AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Completed))

            case ReasoningItem =>
              List(AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Completed))

            case CallItem(callId, name) if !st.emittedCard =>
              // No `arguments.done` arrived (a very short call, or a provider
              // that skips it): the item itself carries them.
              val raw   = item.get[String]("arguments").toOption.getOrElse(st.buffer.toString)
              val input = parseJson(raw).toOption.filter(_.isObject).getOrElse(Json.obj())
              content += Json.obj(
                "type"  -> Json.fromString("tool_use"),
                "id"    -> Json.fromString(callId),
                "name"  -> Json.fromString(name),
                "input" -> input
              )
              calls += ((callId, name, input))
              List(
                AgentEvent.ItemStarted(
                  turnId, st.itemId, ToolPresentation.itemType(name),
                  ToolPresentation.presentLabel(name), Some(name), Some(input)
                )
              )

            case _ => Nil
          }
        }

      case Some("response.completed") =>
        statusOpt = Some("completed")
        usageAcc = usageAcc + OpenAiTurnMapper.readUsage(c.downField("response").downField("usage").focus)
        Nil

      case Some("response.incomplete") =>
        statusOpt = Some("incomplete")
        usageAcc = usageAcc + OpenAiTurnMapper.readUsage(c.downField("response").downField("usage").focus)
        errorOpt = errorOpt.orElse(
          c.downField("response").downField("incomplete_details").get[String]("reason").toOption
            .map(r => ("incomplete", s"the model stopped early ($r)"))
        )
        Nil

      case Some("response.failed") =>
        statusOpt = Some("failed")
        val err = c.downField("response").downField("error")
        val msg = err.get[String]("message").toOption.getOrElse("the request failed")
        errorOpt = errorOpt.orElse(Some((OpenAiTurnMapper.classify(err.get[String]("code").toOption.getOrElse("") + " " + msg), msg)))
        Nil

      case Some("error") =>
        val msg = c.get[String]("message").toOption.getOrElse("the request failed")
        statusOpt = statusOpt.orElse(Some("failed"))
        errorOpt = errorOpt.orElse(Some((OpenAiTurnMapper.classify(c.get[String]("code").toOption.getOrElse("") + " " + msg), msg)))
        Nil

      case _ => Nil
    }
  }

  /** The `(item, delta)` an incremental event refers to, if we know the item. */
  private def delta(c: io.circe.HCursor): Option[(ItemState, String)] =
    for {
      itemId <- c.get[String]("item_id").toOption
      st     <- open.get(itemId)
      d      <- c.get[String]("delta").toOption
      if d.nonEmpty
    } yield (st, d)
}

object OpenAiTurnMapper {

  def readUsage(u: Option[Json]): Usage =
    u.map(_.hcursor).map { h =>
      Usage(
        inputTokens      = h.get[Long]("input_tokens").toOption.getOrElse(0L),
        outputTokens     = h.get[Long]("output_tokens").toOption.getOrElse(0L),
        // Cached input is reported in a details object, not at the top level —
        // and it is the number the second turn of a thread is judged on.
        cacheReadTokens  = h.downField("input_tokens_details").get[Long]("cached_tokens").toOption.getOrElse(0L),
        cacheWriteTokens = 0L
      )
    }.getOrElse(Usage.empty)

  def classify(message: String): String = {
    val l = message.toLowerCase
    if (l.contains("429") || l.contains("rate limit") || l.contains("quota") || l.contains("overloaded"))
      "rate_limited"
    else if (l.contains("401") || l.contains("403") || l.contains("api key") || l.contains("authentication") ||
             l.contains("invalid_api_key") || l.contains("permission"))
      "auth"
    else "internal"
  }
}
