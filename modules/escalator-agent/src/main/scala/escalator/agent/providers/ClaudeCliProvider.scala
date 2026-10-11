package escalator.agent.providers

import java.io.{BufferedReader, BufferedWriter, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import org.apache.pekko.stream.{ActorAttributes, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.Source

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.util.logging.Logger

import escalator.agent._
import escalator.agent.protocol._

/**
 * ==The Claude Code CLI as an [[AgentProvider]]==
 *
 * One short-lived `claude --print --output-format stream-json` process per
 * turn, with continuity via a deterministic session id: the first turn passes
 * `--session-id <uuid>`, every later turn `--resume <uuid>`.
 *
 * `--include-partial-messages` is what makes the stream a stream: thinking,
 * text, tool calls and tool RESULTS map onto the shared event model as they
 * arrive.
 *
 * Credentials come from the config home, never from the parent environment:
 * the child runs with `CLAUDE_CONFIG_DIR` set (default `~/.claude-cliproxy`,
 * whose `settings.json` points the CLI at the cliproxy) — see [[ClaudeCliEnv]].
 *
 * Permissions are per-profile rather than a blanket bypass: a profile that does
 * not ask for [[Capability.FileSystem]]/[[Capability.Shell]] runs with ONLY its
 * own `allowedTools` (`--tools` limits the built-ins, `--allowedTools` names
 * what may run, `--permission-mode dontAsk` denies the rest without a prompt,
 * and a deny list closes the filesystem/shell/network tools). A profile with no
 * tools gets `--tools ""`. Only a workspace profile gets
 * `--dangerously-skip-permissions`.
 *
 * Cancel is `Process.destroy()`; the session id survives, so the next turn
 * resumes.
 */
class ClaudeCliProvider(cfg: ClaudeCliConfig)(implicit logger: Logger) extends AgentProvider {

  override def id: String = ClaudeCliProvider.Id

  override val models: Seq[ModelInfo] = cfg.models

  override def capabilities: ProviderCapabilities = ProviderCapabilities(
    caps = Set(
      Capability.FileSystem,
      Capability.Shell,
      Capability.McpNative,
      Capability.Reasoning
    ),
    resumeKind = ResumeKind.ProviderSessionId
  )

  override def credentialLabel: String = CredentialSource.CliConfig

  override def open(spec: SessionSpec): Future[ProviderSession] =
    Future.successful(new ClaudeCliSession(cfg, spec))
}

/**
 * Everything the Claude CLI adapter reads from config (see `AgentSettings`).
 *
 * @param configDir     the `CLAUDE_CONFIG_DIR` every child runs with
 * @param credential    `Some(ApiKey)` passes `ANTHROPIC_API_KEY` to the child;
 *                      anything else leaves auth to `<configDir>/settings.json`
 * @param disableHooks  run the child with `disableAllHooks` (the owner's config
 *                      home may carry interactive-session hooks)
 * @param parentEnv     the environment the child env is derived from
 */
final case class ClaudeCliConfig(
    bin:          String                     = "claude",
    models:       Seq[ModelInfo]             = ClaudeCliProvider.DefaultModels,
    configDir:    Path,
    credential:   Option[ProviderCredential] = None,
    disableHooks: Boolean                    = true,
    parentEnv:    Map[String, String]        = sys.env,
    /** Resolved per turn when set, e.g. `cli.ClaudeEngine.bin`, so a version switch applies from the next turn. */
    binResolver:  Option[() => String]       = None
) {
  /** The binary for the next turn. */
  def currentBin: String = binResolver.map(_()).getOrElse(bin)

  override def toString: String =
    s"ClaudeCliConfig($bin, ${models.map(_.id)}, $configDir, ${credential.map(_.source).getOrElse("config-home")}, hooks=${!disableHooks})"

  /** The child process environment for one turn. */
  def childEnv: Map[String, String] = ClaudeCliEnv.build(parentEnv, configDir, credential)
}

object ClaudeCliProvider {
  val Id: String = "claude-cli"

  /** Fallback model list (the CLI also accepts aliases such as `opus`, `sonnet`). */
  val DefaultModels: Seq[ModelInfo] = Seq(
    ModelInfo("claude-opus-5-5", "Opus 5.5", isDefault = true),
    ModelInfo("claude-sonnet-5-5", "Sonnet 5.5")
  )

  /** Hard deny list for a locked-down profile — writes, shell and network. */
  val DeniedForChat: Seq[String] =
    Seq("Bash", "BashOutput", "KillShell", "Write", "Edit", "MultiEdit", "NotebookEdit", "Glob", "Grep", "WebFetch", "WebSearch", "Task")

  /** Tool result bodies are truncated to this many characters before they ride on an event. */
  val MaxResultChars: Int = 8000

  /**
   * The whole argv for one turn. Pure, so the permission rules can be tested
   * without a process. `mcpConfig` is passed only when the file exists.
   */
  def command(
      bin:          String,
      profile:      AgentProfile,
      model:        String,
      systemPrompt: String,
      mcpConfig:    Option[Path],
      resume:       Boolean,
      sessionId:    String,
      userMessage:  String,
      disableHooks: Boolean
  ): Seq[String] = {
    val args = mutable.ArrayBuffer[String](
      bin,
      "--print",
      "--output-format", "stream-json",
      "--include-partial-messages",
      "--verbose",
      "--append-system-prompt", systemPrompt
    )
    if (disableHooks) { args += "--settings"; args += """{"disableAllHooks":true}""" }

    if (profile.needsWorkspaceTools) args += "--dangerously-skip-permissions"
    else {
      val (mcpTools, builtIns) = profile.allowedTools.map(_.trim).filter(_.nonEmpty).partition(_.startsWith("mcp__"))
      // `--tools` limits the BUILT-IN set ("" = none); MCP tools are governed by
      // --allowedTools and the MCP config alone.
      args += "--tools"; args += builtIns.mkString(",")
      if (mcpTools.nonEmpty || builtIns.nonEmpty) {
        args += "--allowedTools"; args += (mcpTools ++ builtIns).mkString(" ")
      }
      // `dontAsk`: allowed tools run, anything else is denied without a prompt —
      // the headless semantic we want (a prompt can never be answered in --print).
      args += "--permission-mode"; args += "dontAsk"
      val denied = DeniedForChat.filterNot(builtIns.contains)
      if (denied.nonEmpty) { args += "--disallowedTools"; args += denied.mkString(" ") }
    }

    val m = model.trim
    if (m.nonEmpty && m != "default") { args += "--model"; args += m }

    mcpConfig.foreach { p =>
      args += "--mcp-config"; args += p.toString
      args += "--strict-mcp-config"
    }
    if (resume) { args += "--resume"; args += sessionId }
    else { args += "--session-id"; args += sessionId }
    args += "--"
    args += userMessage
    args.toList
  }
}

/**
 * One conversation with the CLI. Not thread-safe for concurrent turns by
 * design: a turn is a subprocess, and the layer above serializes them.
 */
final class ClaudeCliSession(cfg: ClaudeCliConfig, spec: SessionSpec)(implicit logger: Logger)
    extends ProviderSession {

  import ClaudeCliProvider._

  override def providerId: String = ClaudeCliProvider.Id

  /** The `claude` session id — also the conversation/workspace id. */
  private val sessionId: String =
    spec.resume.flatMap(_.sessionId).getOrElse(spec.conversationId)

  private val workspace: Path =
    spec.workspace.getOrElse(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))

  @volatile private var started: Boolean         = spec.resume.isDefined
  @volatile private var current: Option[Process] = None
  @volatile private var killed:  Boolean         = false
  @volatile private var context: Option[SessionContext] = spec.context
  // The system prompt is recomposed per turn from the editable prompt blocks,
  // so the profile this session SENDS is a holder, not the captured spec.
  @volatile private var profile: AgentProfile = spec.profile

  override def resumeCursor: ResumeCursor = ResumeCursor.forSession(sessionId)

  override def updateContext(ctx: SessionContext): Unit = context = Some(ctx)

  override def updateProfile(p: AgentProfile): Unit = profile = p

  override def interrupt(): Unit = destroyCurrent()

  override def close(): Unit = destroyCurrent()

  private def destroyCurrent(): Unit = current.foreach { p =>
    if (p.isAlive) {
      killed = true
      try p.destroy()
      catch { case _: Throwable => () }
    }
  }

  override def sendTurnRaw(
      turnId:      TurnId,
      message:     String,
      attachments: Seq[Path]
  ): Source[ProviderEvent, UniqueKillSwitch] = {
    val before = AgentSourceFiles.list(workspace, spec.profile.sourceExtensions)
    val src = Source
      .fromIterator(() => new TurnIterator(turnId, message, before))
      .addAttributes(ActorAttributes.dispatcher("pekko.actor.default-blocking-io-dispatcher"))
    ProviderSession.withKillSwitch(src, () => destroyCurrent())
  }

  // ── the system prompt actually sent ───────────────────────────────────────

  /**
   * Profile prompt first, session context AFTER it. The order matters for
   * prompt caching later (the stable half is the profile), and it matters now
   * because the context is the LAST thing the model reads before the question.
   */
  private def appendedSystemPrompt: String =
    context.map(SessionContextRenderer.systemBlock(_)) match {
      case Some(block) if block.trim.nonEmpty => profile.systemPrompt + "\n\n" + block
      case _                                  => profile.systemPrompt
    }

  private def buildCommand(userMessage: String): java.util.List[String] =
    ClaudeCliProvider
      .command(
        bin          = cfg.currentBin,
        profile      = profile,
        model        = spec.model,
        systemPrompt = appendedSystemPrompt,
        mcpConfig    = spec.tools.mcpConfigPath.filter(Files.exists(_)),
        resume       = started,
        sessionId    = sessionId,
        userMessage  = userMessage,
        disableHooks = cfg.disableHooks
      )
      .asJava

  /**
   * Runs one `claude` turn, yielding events in order: `SessionStarted` on init,
   * then reasoning/text/tool items as they stream, then `FileUpdated` for
   * changed canonical files, then exactly one terminal `TurnCompleted`
   * (or `RuntimeError` if the process could not start).
   */
  private final class TurnIterator(
      turnId:      TurnId,
      userMessage: String,
      before:      Map[String, String]
  ) extends Iterator[ProviderEvent] {

    private val pending = mutable.Queue[ProviderEvent]()
    private var reader: BufferedReader = _
    private var proc:   Process        = _
    private var log:    BufferedWriter = _
    private var phase   = 0 // 0 = reading stdout, 1 = finalize, 2 = drained
    private var t0      = 0L

    private var usage    = Usage.empty
    private var resDurMs = 0L
    private var failure: Option[(String, String)] = None
    private var interrupted = false
    private var sawResult   = false
    private var sawTextDelta = false
    private var initialized  = false
    private var itemSeq      = 0

    /** content-block index → the item it belongs to (per assistant message). */
    private val blockItems = mutable.Map[Int, (ItemId, ItemType)]()
    /** `tool_use_id` → tool name; the id IS the item id, so results match up. */
    private val toolItems  = mutable.Map[String, String]()

    private def nextItemId(prefix: String): ItemId = {
      itemSeq += 1
      ItemId(s"${turnId.value}-$prefix-$itemSeq")
    }

    private def emit(e: AgentEvent, raw: Option[Json] = None): Unit =
      pending.enqueue(ProviderEvent(e, raw))

    private def start(): Unit = {
      val pb = new ProcessBuilder(buildCommand(userMessage))
      pb.directory(workspace.toFile)
      // stderr goes to a workspace file rather than an unread pipe (a full pipe
      // would block the CLI); failures still surface through the stream-json `result`.
      pb.redirectError(ProcessBuilder.Redirect.appendTo(workspace.resolve("agent.stderr.log").toFile))
      val env = pb.environment()
      env.clear()
      cfg.childEnv.foreach { case (k, v) => env.put(k, v) }
      t0   = System.currentTimeMillis()
      proc = pb.start()
      // The prompt is an ARGUMENT, never stdin. Left open, the CLI waits three
      // seconds for input it will never get ("no stdin data received in 3s")
      // and every turn pays for it; closing the pipe is the EOF it is waiting
      // for.
      try proc.getOutputStream.close()
      catch { case _: Throwable => () }
      current = Some(proc)
      killed  = false
      reader = new BufferedReader(new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
      openLog()
      logLine(s"\n=== turn ${turnId.value} @ ${java.time.Instant.now()} ===\nUSER: $userMessage")
    }

    private def ensureStarted(): Unit =
      if (!initialized) {
        initialized = true
        try start()
        catch {
          case t: Throwable =>
            logger.warning(s"[agent] failed to start claude: ${t.getMessage}")
            emit(AgentEvent.RuntimeError(Some(turnId), "internal", s"could not start agent: ${t.getMessage}", retryable = true))
            phase = 2
        }
      }

    override def hasNext: Boolean = {
      ensureStarted()
      while (pending.isEmpty && phase < 2) step()
      pending.nonEmpty
    }

    override def next(): ProviderEvent = {
      if (!hasNext) throw new NoSuchElementException("no more agent events")
      pending.dequeue()
    }

    private def step(): Unit = phase match {
      case 0 => readLinePhase()
      case 1 => finalizePhase()
      case _ => ()
    }

    private def readLinePhase(): Unit = {
      val line = try reader.readLine() catch { case _: Throwable => null }
      if (line == null) { phase = 1; return }
      val trimmed = line.trim
      if (trimmed.nonEmpty) parseLine(trimmed)
    }

    private def finalizePhase(): Unit = {
      AgentSourceFiles.list(workspace, spec.profile.sourceExtensions).foreach { case (name, content) =>
        if (content.nonEmpty && content != before.getOrElse(name, "")) {
          logLine(s"FILE: $name (${content.length} chars)")
          emit(AgentEvent.FileUpdated(turnId, name, content))
        }
      }
      val code = try proc.waitFor() catch { case _: Throwable => -1 }
      val dur  = if (resDurMs > 0) resDurMs else System.currentTimeMillis() - t0
      val wasKilled = killed || interrupted

      val outcome =
        if (wasKilled) TurnOutcome.Interrupted
        else failure match {
          case Some(_)              => TurnOutcome.Failed
          case None if !sawResult && code != 0 => TurnOutcome.Failed
          case None                 => TurnOutcome.Completed
        }

      failure.foreach { case (kind, msg) =>
        logLine(s"FAILED [$kind]: $msg")
        emit(AgentEvent.RuntimeError(Some(turnId), kind, msg, retryable = kind == "rate_limited"))
      }
      if (failure.isEmpty && !sawResult && code != 0 && !wasKilled) {
        val msg = s"agent exited with code $code"
        logger.warning(s"[agent] claude exited with code $code")
        emit(AgentEvent.RuntimeError(Some(turnId), "internal", msg, retryable = true))
      }
      logLine(s"DONE outcome=${outcome.ident} in=${usage.inputTokens} out=${usage.outputTokens} elapsedMs=$dur")
      emit(AgentEvent.TurnCompleted(turnId, outcome, usage, dur))

      cleanup()
      started = true
      phase   = 2
    }

    private def cleanup(): Unit = {
      try reader.close() catch { case _: Throwable => () }
      try if (log != null) log.close() catch { case _: Throwable => () }
      current = None
    }

    // ── line → events ────────────────────────────────────────────────────

    private def parseLine(line: String): Unit =
      parseJson(line) match {
        case Left(_)     => ()
        case Right(json) => handle(json)
      }

    private def handle(json: Json): Unit = {
      val raw = Some(json)
      val c   = json.hcursor
      c.get[String]("type").toOption match {

        // ---- session bootstrap -------------------------------------------
        case Some("system") =>
          if (c.get[String]("subtype").toOption.contains("init")) {
            val sid   = c.get[String]("session_id").toOption
            val model = c.get[String]("model").toOption.getOrElse(spec.model)
            emit(
              AgentEvent.SessionStarted(
                provider          = ClaudeCliProvider.Id,
                model             = model,
                providerSessionId = sid,
                context           = context.map(_.view).getOrElse(SessionContextView.empty)
              ),
              raw
            )
          }

        // ---- incremental blocks -------------------------------------------
        case Some("stream_event") =>
          val ev = c.downField("event")
          ev.get[String]("type").toOption match {
            case Some("message_start") =>
              blockItems.clear()

            case Some("content_block_start") =>
              val idx = ev.get[Int]("index").toOption.getOrElse(-1)
              ev.downField("content_block").get[String]("type").toOption match {
                case Some("thinking") =>
                  val id = nextItemId("reasoning")
                  blockItems.put(idx, (id, ItemType.Reasoning))
                  emit(AgentEvent.ItemStarted(turnId, id, ItemType.Reasoning, "Thinking"), raw)
                case Some("text") =>
                  val id = nextItemId("text")
                  blockItems.put(idx, (id, ItemType.AssistantMessage))
                  emit(AgentEvent.ItemStarted(turnId, id, ItemType.AssistantMessage, ""), raw)
                case _ =>
                  // tool_use starts here with an EMPTY input; the full-fidelity
                  // ItemStarted is emitted from the assistant message below,
                  // which carries the arguments the card needs to show.
                  ()
              }

            case Some("content_block_delta") =>
              val idx = ev.get[Int]("index").toOption.getOrElse(-1)
              val d   = ev.downField("delta")
              d.get[String]("type").toOption match {
                case Some("text_delta") =>
                  d.get[String]("text").toOption.filter(_.nonEmpty).foreach { t =>
                    sawTextDelta = true
                    logRaw(t)
                    val id = itemFor(idx, ItemType.AssistantMessage, "text")
                    emit(AgentEvent.ContentDelta(turnId, id, StreamKind.AssistantText, t))
                  }
                case Some("thinking_delta") =>
                  d.get[String]("thinking").toOption.filter(_.nonEmpty).foreach { th =>
                    logRaw(th)
                    val id = itemFor(idx, ItemType.Reasoning, "reasoning")
                    emit(AgentEvent.ContentDelta(turnId, id, StreamKind.ReasoningText, th))
                  }
                case _ => ()
              }

            case Some("content_block_stop") =>
              val idx = ev.get[Int]("index").toOption.getOrElse(-1)
              blockItems.remove(idx).foreach { case (id, _) =>
                emit(AgentEvent.ItemCompleted(turnId, id, ItemStatus.Completed), raw)
              }

            case _ => ()
          }

        // ---- the assembled assistant message ------------------------------
        case Some("assistant") =>
          val blocks = c.downField("message").downField("content").values.toList.flatten
          blocks.foreach { block =>
            val bc = block.hcursor
            bc.get[String]("type").toOption match {
              case Some("tool_use") =>
                val toolId = bc.get[String]("id").toOption.getOrElse(nextItemId("tool").value)
                val name   = bc.get[String]("name").toOption.getOrElse("tool")
                if (!toolItems.contains(toolId)) {
                  toolItems.put(toolId, name)
                  val input = bc.downField("input").focus
                  val title = ToolPresentation.presentLabel(name)
                  logLine(s"TOOL: $title (${ToolPresentation.bareName(name)})")
                  emit(
                    AgentEvent.ItemStarted(
                      turnId, ItemId(toolId), ToolPresentation.itemType(name), title, Some(name), input
                    ),
                    raw
                  )
                }
              case Some("text") if !sawTextDelta =>
                bc.get[String]("text").toOption.filter(_.nonEmpty).foreach { t =>
                  logLine(s"ASSISTANT: $t")
                  val id = nextItemId("text")
                  emit(AgentEvent.ItemStarted(turnId, id, ItemType.AssistantMessage, ""), raw)
                  emit(AgentEvent.ContentDelta(turnId, id, StreamKind.AssistantText, t))
                  emit(AgentEvent.ItemCompleted(turnId, id, ItemStatus.Completed))
                }
              case _ => ()
            }
          }

        // ---- tool results come back as a "user" message --------------------
        case Some("user") =>
          val blocks = c.downField("message").downField("content").values.toList.flatten
          blocks.foreach { block =>
            val bc = block.hcursor
            if (bc.get[String]("type").toOption.contains("tool_result")) {
              bc.get[String]("tool_use_id").toOption.foreach { toolId =>
                val isError = bc.get[Boolean]("is_error").toOption.getOrElse(false)
                // An MCP tool answers with a content envelope; the chat wants
                // the tool's own JSON (that is where `artifacts[]` lives), and
                // a plain text result is left exactly as it arrived.
                // ORDER MATTERS: unwrap first (it prefers `structuredContent`
                // and drops the image / resource_link items), THEN cap. Capping
                // an envelope keeps its base64 chart and throws the JSON away.
                val result  = bc.downField("content").focus.map(j => truncateJson(McpResultJson.unwrap(j)))
                val name    = toolItems.getOrElse(toolId, "tool")
                logLine(s"RESULT: ${ToolPresentation.pastLabel(name)}${if (isError) " [error]" else ""}")
                emit(
                  AgentEvent.ItemCompleted(
                    turnId, ItemId(toolId),
                    if (isError) ItemStatus.Failed else ItemStatus.Completed,
                    result, isError
                  ),
                  raw
                )
              }
            }
          }

        // ---- terminal result ----------------------------------------------
        case Some("result") =>
          sawResult = true
          val u = c.downField("usage")
          usage = Usage(
            inputTokens      = u.get[Long]("input_tokens").toOption.getOrElse(0L),
            outputTokens     = u.get[Long]("output_tokens").toOption.getOrElse(0L),
            cacheReadTokens  = u.get[Long]("cache_read_input_tokens").toOption.getOrElse(0L),
            cacheWriteTokens = u.get[Long]("cache_creation_input_tokens").toOption.getOrElse(0L),
            costUsd          = c.get[BigDecimal]("total_cost_usd").toOption
          )
          resDurMs = c.get[Long]("duration_ms").toOption.getOrElse(0L)
          val subtype = c.get[String]("subtype").toOption.getOrElse("")
          if (c.get[Boolean]("is_error").toOption.getOrElse(false)) {
            val msg = c.get[String]("result").toOption
              .orElse(c.get[String]("error").toOption)
              .getOrElse("agent reported an error")
            // An aborted stream is a CANCELLATION, not a failure: the user (or a
            // kill switch) stopped it, and the next turn resumes the session.
            if (isAbort(subtype) || isAbort(msg)) interrupted = true
            else failure = Some((classifyError(msg), msg))
          }

        case _ => ()
      }
    }

    /** The item for a content-block index, creating one if the start was missed. */
    private def itemFor(idx: Int, itemType: ItemType, prefix: String): ItemId =
      blockItems.get(idx).map(_._1).getOrElse {
        val id = nextItemId(prefix)
        blockItems.put(idx, (id, itemType))
        id
      }

    private def isAbort(s: String): Boolean = {
      val l = s.toLowerCase
      l.contains("abort") || l.contains("interrupt") || l.contains("cancell")
    }

    private def classifyError(msg: String): String = {
      val l = msg.toLowerCase
      if (l.contains("rate limit") || l.contains("429")) "rate_limited"
      else if (l.contains("unauthorized") || l.contains("401") || l.contains("api key") ||
               l.contains("authenticat") || l.contains("oauth")) "auth"
      else if (l.contains("not found") && l.contains("command")) "not_configured"
      else "internal"
    }

    /** Tool results can be enormous; the event carries a bounded head. */
    private def truncateJson(j: Json): Json = j.asString match {
      case Some(s) if s.length > MaxResultChars =>
        Json.fromString(s.take(MaxResultChars) + s"\n…[truncated ${s.length - MaxResultChars} chars]")
      case Some(_) => j
      case None =>
        val s = j.noSpaces
        if (s.length > MaxResultChars)
          Json.fromString(s.take(MaxResultChars) + s"\n…[truncated ${s.length - MaxResultChars} chars]")
        else j
    }

    // ── workspace log ──────────────────────────────────────────────────────
    private def openLog(): Unit =
      try log = Files.newBufferedWriter(
        workspace.resolve("agent.log"), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND
      ) catch { case _: Throwable => log = null }

    private def logLine(s: String): Unit = logRaw(s + "\n")
    private def logRaw(s: String): Unit =
      if (log != null) try { log.write(s); log.flush() } catch { case _: Throwable => () }
  }
}
