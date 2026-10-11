package escalator.agent.providers

import scala.collection.mutable

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.agent.McpResultJson
import escalator.agent.protocol._

/**
 * ==`codex exec --json`, as JSON==
 *
 * Pure and free of any process, exactly like [[AnthropicTurnMapper]] and for
 * the same reason: the only thing that can be WRONG here is the mapping, and a
 * mapping you cannot feed a captured line to is a mapping you cannot test.
 *
 * The stream is one JSON object per line. Its vocabulary (confirmed against
 * codex-cli 0.153.4, both by running it and by the tags in the binary):
 *
 * {{{
 * {"type":"thread.started","thread_id":"…"}
 * {"type":"turn.started"}
 * {"type":"item.started"  ,"item":{…}}
 * {"type":"item.updated"  ,"item":{…}}
 * {"type":"item.completed","item":{…}}
 * {"type":"turn.completed","usage":{input_tokens,cached_input_tokens,
 *                                  cache_write_input_tokens,output_tokens,
 *                                  reasoning_output_tokens}}
 * {"type":"turn.failed","error":{"message":"…"}}
 * {"type":"error","message":"…"}
 * }}}
 *
 * with item `type` one of `agent_message | reasoning | command_execution |
 * file_change | mcp_tool_call | web_search | todo_list | error`.
 *
 * Two things about it shape everything below.
 *
 * **Codex does not stream text deltas in `exec --json`.** An `agent_message`
 * arrives whole on `item.completed`. Rather than special-case that, the mapper
 * treats every text-bearing item as "the full text so far" and emits the SUFFIX
 * that is new — so a whole message becomes one [[AgentEvent.ContentDelta]]
 * today, and if a future Codex starts sending `item.updated` for partial text,
 * the same code streams it without a change. The UI cannot tell the difference,
 * which is the point.
 *
 * **Item ids restart per thread** (`item_0`, `item_1`, …), so they are prefixed
 * with the turn id before they become [[ItemId]]s — otherwise turn 2's first
 * tool card would overwrite turn 1's in a reducer keyed by item id.
 */
final class CodexEventMapper(
    turnId:  TurnId,
    /** The context to attach to `SessionStarted`; read late, so a context
      * refreshed between turns is the one the UI is handed. */
    context: () => SessionContextView = () => SessionContextView.empty,
    model:   String                   = ""
) {

  import CodexEventMapper._

  private final class ItemState(val itemId: ItemId, val itemType: ItemType) {
    /** Text already emitted as deltas, for the suffix rule. */
    var emitted: String = ""
    var completed: Boolean = false
  }

  private val items = mutable.LinkedHashMap.empty[String, ItemState]

  private var threadIdOpt = Option.empty[String]
  private var usageAcc    = Usage.empty
  private var failureOpt  = Option.empty[(String, String)]
  private var turnEnded   = false

  /** Codex's own session id, once it has told us. */
  def threadId: Option[String] = threadIdOpt

  /** Token usage for the turn. Codex reports no cost; the provider prices it. */
  def usage: Usage = usageAcc

  /** `(kind, message)` if the turn failed, for the terminal event. */
  def failure: Option[(String, String)] = failureOpt

  /** True once `turn.completed`/`turn.failed` arrived — a non-zero exit after
    * one of those is the CLI tidying up, not a lost turn. */
  def sawTurnEnd: Boolean = turnEnded

  /** Map one JSONL line. Returns what to emit (often nothing). */
  def onEvent(json: Json): List[AgentEvent] = {
    val c = json.hcursor
    c.get[String]("type").toOption match {

      case Some("thread.started") =>
        val id = c.get[String]("thread_id").toOption
        threadIdOpt = id.orElse(threadIdOpt)
        List(
          AgentEvent.SessionStarted(
            provider          = CodexCliProvider.Id,
            model             = model,
            providerSessionId = id,
            context           = context()
          )
        )

      case Some("turn.started") => Nil

      case Some("item.started")   => onItem(c.downField("item").focus, terminal = false)
      case Some("item.updated")   => onItem(c.downField("item").focus, terminal = false)
      case Some("item.completed") => onItem(c.downField("item").focus, terminal = true)

      case Some("turn.completed") =>
        turnEnded = true
        usageAcc = usageAcc + readUsage(c.downField("usage").focus)
        Nil

      case Some("turn.failed") =>
        turnEnded = true
        val msg = c.downField("error").get[String]("message").toOption
          .orElse(c.get[String]("message").toOption)
          .getOrElse("the Codex CLI reported a failed turn")
        failureOpt = failureOpt.orElse(Some((classify(msg), msg)))
        Nil

      case Some("error") =>
        val msg = c.get[String]("message").toOption.getOrElse("the Codex CLI reported an error")
        val kind = classify(msg)
        // Recorded as the turn's failure only if nothing better arrives: a
        // `turn.failed` right after says the same thing with more detail, and
        // the FIRST message is the one worth showing.
        failureOpt = failureOpt.orElse(Some((kind, msg)))
        Nil

      case _ => Nil
    }
  }

  /**
   * Anything still open when the process ends. An interrupted turn leaves a
   * tool card spinning otherwise, and a spinner that never resolves is worse
   * than an honest "did not finish".
   */
  def closeOpenItems(): List[AgentEvent] =
    items.values.filterNot(_.completed).toList.map { st =>
      st.completed = true
      AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Failed)
    }

  // ── items ─────────────────────────────────────────────────────────────────

  private def onItem(item: Option[Json], terminal: Boolean): List[AgentEvent] =
    item.map(_.hcursor) match {
      case None => Nil
      case Some(c) =>
        val codexId  = c.get[String]("id").toOption.getOrElse(s"item-${items.size}")
        val itemKind = c.get[String]("type").toOption.getOrElse("")

        itemKind match {
          case "agent_message" =>
            textItem(codexId, c.get[String]("text").toOption.getOrElse(""),
                     ItemType.AssistantMessage, StreamKind.AssistantText, "", terminal)

          case "reasoning" =>
            textItem(codexId,
                     c.get[String]("text").toOption
                       .orElse(c.get[String]("summary_text").toOption)
                       .getOrElse(""),
                     ItemType.Reasoning, StreamKind.ReasoningText, "Thinking", terminal)

          case "command_execution" =>
            val command = c.get[String]("command").toOption.getOrElse("")
            val started = ensureStarted(
              codexId,
              ItemType.CommandExecution,
              title    = if (command.isEmpty) "Running a command" else command,
              toolName = Some("Bash"),
              input    = Some(Json.obj("command" -> Json.fromString(command)))
            )
            if (!terminal) started
            else {
              val exit   = c.get[Int]("exit_code").toOption
              val output = c.get[String]("aggregated_output").toOption.getOrElse("")
              val failed = statusFailed(c) || exit.exists(_ != 0)
              started ::: List(
                AgentEvent.ItemCompleted(
                  turnId,
                  idOf(codexId),
                  if (failed) ItemStatus.Failed else ItemStatus.Completed,
                  Some(
                    Json.obj(
                      "output"   -> Json.fromString(truncate(output)),
                      "exitCode" -> exit.map(Json.fromInt).getOrElse(Json.Null)
                    )
                  ),
                  failed
                )
              ) ::: complete(codexId)
            }

          case "mcp_tool_call" =>
            val server = c.get[String]("server").toOption.getOrElse("mcp")
            val tool   = c.get[String]("tool").toOption.getOrElse("tool")
            // The SAME name the Claude adapter produces, so one presentation
            // table and one reducer serve both engines.
            val name   = s"mcp__${server}__$tool"
            val started = ensureStarted(
              codexId,
              ToolPresentation.itemType(name),
              title    = ToolPresentation.presentLabel(name),
              toolName = Some(name),
              input    = Some(asJson(c.downField("arguments").focus))
            )
            if (!terminal) started
            else {
              val error  = c.get[String]("error").toOption.filter(_.trim.nonEmpty)
              val failed = statusFailed(c) || error.isDefined
              // Codex hands the MCP result through verbatim, envelope and all;
              // unwrap it so `result.artifacts[]` reads the same as it does on
              // every other engine. Unwrap BEFORE the cap: it is what drops the
              // image / resource_link items the cap would otherwise keep.
              val result = error.map(Json.fromString).getOrElse(truncateJson(McpResultJson.unwrap(asJson(c.downField("result").focus))))
              started ::: List(
                AgentEvent.ItemCompleted(
                  turnId,
                  idOf(codexId),
                  if (failed) ItemStatus.Failed else ItemStatus.Completed,
                  Some(result),
                  failed
                )
              ) ::: complete(codexId)
            }

          case "file_change" =>
            val changes = normaliseChanges(c.downField("changes").focus)
            val paths   = changes.flatMap(_.hcursor.get[String]("path").toOption)
            val started = ensureStarted(
              codexId,
              ItemType.FileChange,
              title    = if (paths.isEmpty) "Editing files" else s"Editing ${paths.mkString(", ")}",
              toolName = Some("Edit"),
              input    = Some(Json.arr(changes: _*))
            )
            if (!terminal) started
            else {
              val failed = statusFailed(c)
              started ::: List(
                AgentEvent.ItemCompleted(
                  turnId,
                  idOf(codexId),
                  if (failed) ItemStatus.Failed else ItemStatus.Completed,
                  Some(Json.arr(changes: _*)),
                  failed
                )
              ) ::: complete(codexId)
            }

          case "web_search" =>
            val query = c.get[String]("query").toOption.getOrElse("")
            val started = ensureStarted(
              codexId,
              ItemType.WebSearch,
              title    = if (query.isEmpty) "Searching the web" else s"""Searching the web for "$query"""",
              toolName = Some("WebSearch"),
              input    = Some(Json.obj("query" -> Json.fromString(query)))
            )
            if (!terminal) started
            else
              started ::: List(
                AgentEvent.ItemCompleted(
                  turnId, idOf(codexId), ItemStatus.Completed,
                  c.downField("results").focus.map(truncateJson)
                )
              ) ::: complete(codexId)

          case "todo_list" =>
            val plan    = c.downField("items").focus.getOrElse(Json.arr())
            val started = ensureStarted(codexId, ItemType.Unknown, "Plan", None, Some(plan))
            if (!terminal) started
            else
              started ::: List(
                AgentEvent.ItemCompleted(turnId, idOf(codexId), ItemStatus.Completed, Some(plan))
              ) ::: complete(codexId)

          case "error" =>
            // An error the CLI reports as an ITEM (a bad model id, an MCP server
            // that would not start). Surfaced, but it does NOT decide the turn's
            // outcome — `turn.failed` does, and Codex often carries on.
            val msg = c.get[String]("message").toOption.getOrElse("the Codex CLI reported an error")
            List(AgentEvent.RuntimeError(Some(turnId), classify(msg), msg, retryable = true))

          case _ => Nil
        }
    }

  /**
   * A text item, streamed as the SUFFIX that is new. Codex sends the whole text
   * once today; this is what makes that indistinguishable from a stream.
   */
  private def textItem(
      codexId:  String,
      text:     String,
      itemType: ItemType,
      kind:     StreamKind,
      title:    String,
      terminal: Boolean
  ): List[AgentEvent] = {
    val started = ensureStarted(codexId, itemType, title, None, None)
    val st      = items(codexId)
    val delta   = if (text.startsWith(st.emitted)) text.drop(st.emitted.length) else text
    st.emitted = text
    val deltas =
      if (delta.isEmpty) Nil
      else List(AgentEvent.ContentDelta(turnId, st.itemId, kind, delta))
    val done =
      if (!terminal) Nil
      else List(AgentEvent.ItemCompleted(turnId, st.itemId, ItemStatus.Completed)) ::: complete(codexId)
    started ::: deltas ::: done
  }

  private def idOf(codexId: String): ItemId = ItemId(s"${turnId.value}-$codexId")

  private def ensureStarted(
      codexId:  String,
      itemType: ItemType,
      title:    String,
      toolName: Option[String],
      input:    Option[Json]
  ): List[AgentEvent] =
    if (items.contains(codexId)) Nil
    else {
      val st = new ItemState(idOf(codexId), itemType)
      items.put(codexId, st)
      List(AgentEvent.ItemStarted(turnId, st.itemId, itemType, title, toolName, input))
    }

  private def complete(codexId: String): List[AgentEvent] = {
    items.get(codexId).foreach(_.completed = true)
    Nil
  }

  private def statusFailed(c: io.circe.HCursor): Boolean =
    c.get[String]("status").toOption.exists(s => s == "failed" || s == "errored")
}

object CodexEventMapper {

  /** Command output and tool results ride on events; a bounded head is enough. */
  val MaxResultChars: Int = CodexCliProvider.MaxResultChars

  def truncate(s: String): String =
    if (s.length <= MaxResultChars) s
    else s.take(MaxResultChars) + s"\n…[truncated ${s.length - MaxResultChars} chars]"

  def truncateJson(j: Json): Json = j.asString match {
    case Some(s) => Json.fromString(truncate(s))
    case None =>
      val s = j.noSpaces
      if (s.length <= MaxResultChars) j else Json.fromString(truncate(s))
  }

  /**
   * Codex sends tool arguments as a JSON *string* in some versions and as an
   * object in others. Both become an object, because the tool card renders one.
   */
  def asJson(field: Option[Json]): Json = field.filterNot(_.isNull) match {
    case None    => Json.obj()
    case Some(j) =>
      j.asString match {
        case Some(s) => parseJson(s).getOrElse(Json.fromString(s))
        case None    => j
      }
  }

  /**
   * `changes` is a list of `{path, kind}` in the exec stream, but a
   * `path -> kind` map in some builds. One shape out, so the UI has one.
   */
  def normaliseChanges(field: Option[Json]): List[Json] = field match {
    case None => Nil
    case Some(j) =>
      j.asArray
        .map(_.toList)
        .orElse(
          j.asObject.map(_.toList.map { case (path, kind) =>
            Json.obj(
              "path" -> Json.fromString(path),
              "kind" -> kind.asString.map(Json.fromString).getOrElse(kind)
            )
          })
        )
        .getOrElse(Nil)
  }

  def readUsage(u: Option[Json]): Usage =
    u.map(_.hcursor).map { h =>
      Usage(
        inputTokens = h.get[Long]("input_tokens").toOption.getOrElse(0L),
        // Codex reports OUTPUT and REASONING separately; the turn footer wants
        // what was billed as output, which is both.
        outputTokens = h.get[Long]("output_tokens").toOption.getOrElse(0L) +
          h.get[Long]("reasoning_output_tokens").toOption.getOrElse(0L),
        cacheReadTokens  = h.get[Long]("cached_input_tokens").toOption.getOrElse(0L),
        cacheWriteTokens = h.get[Long]("cache_write_input_tokens").toOption.getOrElse(0L)
      )
    }.getOrElse(Usage.empty)

  def classify(message: String): String = {
    val l = message.toLowerCase
    if (l.contains("429") || l.contains("rate limit") || l.contains("overloaded") || l.contains("usage limit"))
      "rate_limited"
    else if (l.contains("401") || l.contains("403") || l.contains("not logged in") || l.contains("unauthorized") ||
             l.contains("api key") || l.contains("authentication"))
      "auth"
    else if (l.contains("not supported") || l.contains("not found") && l.contains("model"))
      "not_configured"
    else "internal"
  }
}
