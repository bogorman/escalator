package escalator.agent.providers

import java.io.{BufferedReader, BufferedWriter, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.control.NonFatal

import org.apache.pekko.stream.{ActorAttributes, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.Source

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.util.logging.Logger

import escalator.agent._
import escalator.agent.protocol._

/**
 * ==The Codex CLI as an [[AgentProvider]]==
 *
 * Shaped exactly like [[ClaudeCliProvider]] — one short-lived process per turn,
 * JSONL on stdout, a provider-owned session id for continuity — because that is
 * what makes the two interchangeable behind the router. Everything below is
 * where `codex` differs from `claude`, and each difference is a decision:
 *
 *  - **Continuity is Codex's, not ours.** `claude` accepts a session id we
 *    invent (`--session-id`); `codex` mints its own and reports it as
 *    `thread.started{thread_id}`. So the first turn runs `codex exec` and every
 *    later turn `codex exec resume <thread_id>` — which is why the conversation
 *    row's `provider_session_id` is written from the FIRST turn's event rather
 *    than assumed up front.
 *  - **No `--append-system-prompt`.** Codex reads `AGENTS.md` from its working
 *    directory, so the profile prompt and the volatile session context are
 *    rendered there and REWRITTEN before every turn. Same two halves, same
 *    order, same content as the CLI/API providers put in a system prompt.
 *  - **MCP without a config file.** `codex exec` takes `-c` overrides in dotted
 *    TOML, and an HTTP MCP server needs exactly two keys:
 *    `mcp_servers.<name>.url` and `mcp_servers.<name>.bearer_token_env_var`.
 *    The token is named by an ENV VAR rather than passed inline, which is the
 *    whole reason to prefer it: a `-c ...http_headers.Authorization="Bearer …"`
 *    would put a live token in the process's argv, visible to every `ps`
 *    on the box. The workspace `.mcp.json` the actor already writes is the
 *    source for both values, so there is one place a session's MCP surface is
 *    decided.
 *  - **`resume` has no `--cd` and no `-s`.** The working directory comes from
 *    the process instead, and the sandbox from `-c sandbox_mode=…`, so a
 *    resumed turn is sandboxed exactly like a first one.
 *
 * Sandbox per profile, mirroring the Claude adapter's permission split: a chat
 * profile runs `read-only` (it has no business touching the disk), a profile
 * that authors files gets the bypass, which is only safe because those run in a
 * container.
 *
 * Cancel is `Process.destroy()`; the thread id survives, so the next turn
 * resumes.
 */
class CodexCliProvider(
    cfg: CodexCliConfig
)(implicit logger: Logger)
    extends AgentProvider {

  override def id: String = CodexCliProvider.Id

  override def capabilities: ProviderCapabilities = ProviderCapabilities(
    caps = Set(
      Capability.FileSystem,
      Capability.Shell,
      Capability.McpNative,
      Capability.Reasoning
    ),
    resumeKind = ResumeKind.ProviderSessionId
  )

  override val models: Seq[ModelInfo] =
    cfg.models.map(m => ModelInfo(m, m, isDefault = m == cfg.model))

  override def defaultModel: String = cfg.model

  override def open(spec: SessionSpec): Future[ProviderSession] =
    Future.successful(new CodexCliSession(cfg, spec))
}

object CodexCliProvider {

  val Id: String = "codex-cli"

  /**
   * The env var a server's bearer token is passed in. Codex reads it by NAME
   * from config (`bearer_token_env_var`), so the token never appears in argv.
   */
  def tokenEnvVar(server: String): String =
    "AGENT_MCP_TOKEN_" + server.toUpperCase.map(c => if (c.isLetterOrDigit) c else '_')

  /** Tool results and command output are truncated to this before they ride on
    * an event — a big query result is not a transcript. */
  val MaxResultChars: Int = 8000

}

/**
 * Everything the Codex adapter reads from config.
 *
 * `model` is deliberately allowed to be empty: unset means "whatever the box's
 * `codex` is configured for", which is the right default for a CLI that is
 * logged in with an account whose model list we do not own. `apiKey` is only
 * a fallback — the normal server credential is the `CODEX_HOME` login.
 */
final case class CodexCliConfig(
    bin:         String,
    model:       String,
    models:      List[String],
    /** Sandbox for profiles that do NOT ask for FileSystem/Shell. */
    sandboxChat: String,
    /** The server's `CODEX_HOME` (its login), passed through to the process. */
    codexHome:   Option[String],
    /** An OpenAI key for a box authenticated by key rather than by `codex
      * login`. Passed to the child as `OPENAI_API_KEY`. */
    apiKey:      Option[String],
    prices:      Map[String, ModelPrice] = Map.empty
) {
  override def toString: String =
    s"CodexCliConfig($bin, $model, $models, $sandboxChat, $codexHome, key=${apiKey.map(CredentialSource.mask).getOrElse("none")})"
}

/**
 * One conversation with `codex`. Not thread-safe for concurrent turns by
 * design: a turn is a subprocess, and the layer above serialises them.
 */
final class CodexCliSession(cfg: CodexCliConfig, spec: SessionSpec)(implicit logger: Logger)
    extends ProviderSession {

  import CodexCliProvider._

  override def providerId: String = CodexCliProvider.Id

  private val workspace: Path =
    spec.workspace.getOrElse(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))

  /** Codex's own thread id. Unknown until the first `thread.started`. */
  @volatile private var threadId: Option[String] = spec.resume.flatMap(_.sessionId)
  @volatile private var current:  Option[Process] = None
  @volatile private var killed:   Boolean         = false
  @volatile private var context:  Option[SessionContext] = spec.context
  // The system prompt is recomposed per turn from the editable prompt blocks,
  // so the profile this session SENDS is a holder, not the captured spec.
  @volatile private var profile: AgentProfile = spec.profile

  /**
   * The MCP servers (name, url, optional bearer) from the `.mcp.json` the actor
   * wrote. Reading it back keeps ONE definition of a session's MCP surface —
   * the file the Claude adapter passes to `--mcp-config` is the one parsed here.
   */
  private def mcpEndpoints: List[(String, String, Option[String])] =
    spec.tools.mcpConfigPath.filter(Files.exists(_)).toList.flatMap { p =>
      try {
        val json = parseJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).getOrElse(Json.Null)
        val servers = json.hcursor.downField("mcpServers")
        servers.keys.toList.flatten.flatMap { name =>
          val srv = servers.downField(name)
          srv.get[String]("url").toOption.filter(_.trim.nonEmpty).map { url =>
            val token = srv.downField("headers").get[String]("Authorization").toOption
              .map(_.trim.stripPrefix("Bearer ").trim).filter(_.nonEmpty)
            (name, url, token)
          }
        }
      } catch {
        case NonFatal(t) =>
          logger.warning(s"[codex-cli] could not read ${p}: ${t.getMessage}; running without MCP")
          Nil
      }
    }

  override def resumeCursor: ResumeCursor =
    ResumeCursor(ResumeKind.ProviderSessionId, threadId)

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

  // ── the instructions actually sent ────────────────────────────────────────

  /**
   * Codex has no `--append-system-prompt`; it reads `AGENTS.md` from its
   * working directory. Rewritten before EVERY turn so a refreshed session
   * context (who, tools) reaches the model the same turn it changes —
   * the same guarantee the other providers get from re-rendering their system
   * block.
   */
  private def writeInstructions(): Unit = {
    val body = context.map(SessionContextRenderer.systemBlock(_)).filter(_.trim.nonEmpty) match {
      case Some(block) => profile.systemPrompt + "\n\n" + block
      case None        => profile.systemPrompt
    }
    if (body.trim.nonEmpty)
      try { Files.write(workspace.resolve("AGENTS.md"), body.getBytes(StandardCharsets.UTF_8)); () }
      catch { case NonFatal(t) => logger.warning(s"[codex-cli] AGENTS.md write failed: ${t.getMessage}") }
  }

  /** A profile that authors files / runs commands keeps the bypass; chat does not. */
  private def needsWorkspaceTools: Boolean =
    spec.profile.needsWorkspaceTools

  private def buildCommand(userMessage: String): java.util.List[String] = {
    val args = mutable.ArrayBuffer[String](cfg.bin, "exec")

    // `resume` is a SUBCOMMAND, so the thread id comes before the flags.
    threadId.foreach { id => args += "resume"; args += id }

    args += "--json"
    args += "--skip-git-repo-check"

    // `codex exec resume` has no `--cd`; the process's working directory is the
    // only thing both forms agree on, so it is what we set (see `start`). The
    // first turn still passes `--cd` explicitly, because that is the flag whose
    // absence would silently mean "wherever the JVM was launched".
    if (threadId.isEmpty) { args += "--cd"; args += workspace.toString }

    if (cfg.model.trim.nonEmpty) { args += "--model"; args += cfg.model.trim }
    else if (spec.model.trim.nonEmpty && spec.model.trim != "default") {
      args += "--model"; args += spec.model.trim
    }

    // Sandbox. `resume` has no `-s`, so it always goes on as a config override
    // — one code path, and a resumed turn is confined exactly like a first one.
    if (needsWorkspaceTools) args += "--dangerously-bypass-approvals-and-sandbox"
    else { args += "-c"; args += s"""sandbox_mode="${cfg.sandboxChat}"""" }

    mcpEndpoints.foreach { case (name, url, token) =>
      args += "-c"; args += s"""mcp_servers.$name.url="$url""""
      // By NAME, not by value: the token is read from the child's environment,
      // so it never lands in argv where `ps` would show it.
      if (token.isDefined) { args += "-c"; args += s"""mcp_servers.$name.bearer_token_env_var="${tokenEnvVar(name)}"""" }
    }

    args += "--"
    args += userMessage
    java.util.Arrays.asList(args.toArray: _*)
  }

  /**
   * Runs one `codex` turn, yielding events in order: `SessionStarted` on
   * `thread.started`, then reasoning/text/tool items as they stream, then
   * `FileUpdated` for changed canonical files, then exactly one terminal
   * `TurnCompleted` (or `RuntimeError` if the process could not start).
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
    private var initialized = false

    private val mapper =
      new CodexEventMapper(turnId, () => context.map(_.view).getOrElse(SessionContextView.empty), effectiveModel)

    private def emit(e: AgentEvent, raw: Option[Json] = None): Unit =
      pending.enqueue(ProviderEvent(e, raw))

    private def start(): Unit = {
      writeInstructions()
      val pb = new ProcessBuilder(buildCommand(userMessage))
      pb.directory(workspace.toFile)
      pb.redirectErrorStream(false)
      val env = pb.environment()
      cfg.codexHome.foreach(h => env.put("CODEX_HOME", h))
      // A box authenticated by key rather than by `codex login`.
      cfg.apiKey.filter(_.trim.nonEmpty).foreach(k => env.put("OPENAI_API_KEY", k))
      mcpEndpoints.foreach { case (name, _, token) => token.foreach(t => env.put(tokenEnvVar(name), t)) }
      t0   = System.currentTimeMillis()
      proc = pb.start()
      current = Some(proc)
      killed  = false
      // Codex appends piped stdin to the prompt as a `<stdin>` block; closing it
      // immediately is what makes `-- <prompt>` the WHOLE question.
      try proc.getOutputStream.close() catch { case _: Throwable => () }
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
            logger.warning(s"[codex-cli] failed to start ${cfg.bin}: ${t.getMessage}")
            emit(
              AgentEvent.RuntimeError(
                Some(turnId), "not_configured", s"could not start the Codex CLI: ${t.getMessage}", retryable = false
              )
            )
            emit(AgentEvent.TurnCompleted(turnId, TurnOutcome.Failed, Usage.empty, 0L))
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
      if (trimmed.nonEmpty) parseJson(trimmed) match {
        case Left(_)     => ()
        case Right(json) =>
          mapper.onEvent(json).foreach { e =>
            logMapped(e)
            emit(e, Some(json))
          }
          // The thread id is Codex's, learned here and nowhere else; the actor
          // persists it from `SessionStarted` and hands it back as the resume
          // cursor on the next turn.
          mapper.threadId.foreach(id => threadId = Some(id))
      }
    }

    private def finalizePhase(): Unit = {
      AgentSourceFiles.list(workspace, spec.profile.sourceExtensions).foreach { case (name, content) =>
        if (content.nonEmpty && content != before.getOrElse(name, "")) {
          logLine(s"FILE: $name (${content.length} chars)")
          emit(AgentEvent.FileUpdated(turnId, name, content))
        }
      }
      val code = try proc.waitFor() catch { case _: Throwable => -1 }
      val dur  = System.currentTimeMillis() - t0
      val wasKilled = killed

      // Anything the model left open when the process ended is closed here, so
      // the UI never keeps a spinner running on a card that will never finish.
      mapper.closeOpenItems().foreach(e => emit(e))

      val outcome =
        if (wasKilled) TurnOutcome.Interrupted
        else if (mapper.failure.isDefined || (code != 0 && !mapper.sawTurnEnd)) TurnOutcome.Failed
        else TurnOutcome.Completed

      mapper.failure.foreach { case (kind, msg) =>
        logLine(s"FAILED [$kind]: $msg")
        emit(AgentEvent.RuntimeError(Some(turnId), kind, msg, retryable = kind == "rate_limited"))
      }
      if (mapper.failure.isEmpty && code != 0 && !wasKilled && !mapper.sawTurnEnd) {
        logger.warning(s"[codex-cli] exited with code $code")
        emit(
          AgentEvent.RuntimeError(Some(turnId), "internal", s"codex exited with code $code", retryable = true)
        )
      }

      val usage = mapper.usage.copy(costUsd = cfg.prices.get(effectiveModel).map(_.cost(mapper.usage)))
      logLine(s"DONE outcome=${outcome.ident} in=${usage.inputTokens} out=${usage.outputTokens} elapsedMs=$dur")
      emit(AgentEvent.TurnCompleted(turnId, outcome, usage, dur))

      cleanup()
      phase = 2
    }

    private def effectiveModel: String =
      if (cfg.model.trim.nonEmpty) cfg.model.trim else spec.model.trim

    private def cleanup(): Unit = {
      try reader.close() catch { case _: Throwable => () }
      try if (log != null) log.close() catch { case _: Throwable => () }
      current = None
    }

    private def logMapped(e: AgentEvent): Unit = e match {
      case d: AgentEvent.ContentDelta if d.kind == StreamKind.AssistantText => logRaw(d.delta)
      case s: AgentEvent.ItemStarted if s.toolName.isDefined                =>
        logLine(s"TOOL: ${s.title} (${ToolPresentation.bareName(s.toolName.get)})")
      case _ => ()
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
