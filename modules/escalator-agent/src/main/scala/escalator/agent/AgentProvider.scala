package escalator.agent

import java.nio.file.Path

import scala.concurrent.Future

import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.scaladsl.{Keep, Source}

import io.circe.Json

import escalator.agent.protocol.{AgentEvent, TurnId}

/**
 * ==What an agent engine can do==
 *
 * A capability is the question the router asks, not a feature list: a profile
 * that authors Java in a workspace needs [[Capability.FileSystem]] and
 * [[Capability.Shell]], so it can only be routed to a CLI provider; a chat
 * profile needs only tools, so it can also be routed to a direct API provider
 * that has no workspace at all.
 */
sealed abstract class Capability(val ident: String)
object Capability {

  /** Reads/writes files in a workspace directory. */
  case object FileSystem extends Capability("file-system")

  /** Runs shell commands. */
  case object Shell extends Capability("shell")

  /** Speaks MCP natively (the engine connects to the MCP servers in `.mcp.json` itself). */
  case object McpNative extends Capability("mcp-native")

  /** Tools are executed in-process by us and fed back as tool results. */
  case object InProcessTools extends Capability("in-process-tools")

  /** A message can be injected into a RUNNING turn. */
  case object Steering extends Capability("steering")

  /** Can pause for a human approval decision mid-turn. */
  case object Approvals extends Capability("approvals")

  /** Emits reasoning/thinking content. */
  case object Reasoning extends Capability("reasoning")

  val all: List[Capability] =
    List(FileSystem, Shell, McpNative, InProcessTools, Steering, Approvals, Reasoning)

  def fromIdent(s: String): Option[Capability] = all.find(_.ident == s)
}

/** How a provider picks a conversation back up. */
sealed abstract class ResumeKind(val ident: String)
object ResumeKind {
  /** The engine owns the history; we hand back its session id (CLI providers). */
  case object ProviderSessionId extends ResumeKind("provider-session-id")
  /** WE own the history; we replay stored messages (direct API providers). */
  case object StoredMessages extends ResumeKind("stored-messages")
}

/** The resume handle for one conversation, in whichever form its provider uses. */
final case class ResumeCursor(kind: ResumeKind, sessionId: Option[String] = None, messageCount: Int = 0)
object ResumeCursor {
  def forSession(id: String): ResumeCursor = ResumeCursor(ResumeKind.ProviderSessionId, Some(id))
  val none: ResumeCursor = ResumeCursor(ResumeKind.StoredMessages, None, 0)
}

final case class ProviderCapabilities(caps: Set[Capability], resumeKind: ResumeKind) {
  def has(c: Capability): Boolean = caps.contains(c)

  /**
   * Can this engine do what a profile needs?
   *
   * Every capability is matched literally EXCEPT the two that are the same
   * requirement wearing different clothes: a profile that asks for
   * [[Capability.McpNative]] is asking for TOOLS, and an engine whose tools we
   * execute ourselves ([[Capability.InProcessTools]]) answers that just as
   * well — the model cannot tell, and neither can the user. Keeping them
   * distinct as a REQUIREMENT would mean every chat profile had to name both,
   * and the one that forgot would silently lose the faster engine.
   *
   * A profile that needs a workspace and a shell is unaffected: those have no
   * equivalent, which is exactly why codegen stays on the CLI.
   */
  def satisfies(req: Set[Capability]): Boolean = req.forall {
    case Capability.McpNative | Capability.InProcessTools =>
      has(Capability.McpNative) || has(Capability.InProcessTools)
    case c => has(c)
  }
}

/** One model a provider can be asked for. */
final case class ModelInfo(id: String, label: String, isDefault: Boolean = false)

/**
 * The tool surface handed to a session. A CLI provider gets an MCP config file
 * to load; a direct-API provider gets the tool definitions to send inline.
 * Both describe the SAME tool set — that is the point of the type.
 */
final case class ToolSurface(
    /** Absolute path of a workspace `.mcp.json` (CLI providers). */
    mcpConfigPath: Option[Path] = None,
    /** Tool names the profile allows (CLI `--allowedTools`; also listed in the prompt). */
    allowedTools: Seq[String] = Seq.empty,
    /** JSON tool definitions for a direct-API provider (empty for CLI). */
    definitions: Seq[Json] = Seq.empty,
    /**
     * Who runs the tools when the ENGINE cannot dial them itself. Present only
     * for [[Capability.InProcessTools]] providers; a CLI provider ignores it and
     * uses `mcpConfigPath` instead.
     */
    executor: Option[AgentToolExecutor] = None
)
object ToolSurface {
  val empty: ToolSurface = ToolSurface()
}

/**
 * One user turn's worth of session setup. Everything the provider needs and
 * nothing it does not: it never sees the repository, the HTTP session, or the
 * workspace MCP config (that is already baked into `tools`).
 */
final case class SessionSpec(
    conversationId: String,
    profile:        AgentProfile,
    context:        Option[SessionContext] = None,
    model:          String                 = "",
    workspace:      Option[Path]           = None,
    resume:         Option[ResumeCursor]   = None,
    tools:          ToolSurface            = ToolSurface.empty,
    /**
     * The thread so far, for a provider that owns its own history
     * ([[ResumeKind.StoredMessages]]). Read ONCE when the session opens, before
     * the turn's own user message is written, so a direct-API provider picks up
     * a conversation a CLI provider (or a previous process) left behind without
     * ever double-counting the message it is about to send.
     */
    history:        () => Future[Seq[StoredMessage]] = () => Future.successful(Seq.empty),
    /**
     * The credential a direct-API session runs on, resolved once when the
     * engine is built (owner-only: `ANTHROPIC_API_KEY`, else the cliproxy
     * settings). `None` ⇒ the provider uses its own configured credential.
     * Never logged, never written to the workspace, never carried on an event.
     */
    credential:     Option[ProviderCredential] = None
)

/**
 * A pluggable agent engine. Implementations wrap a CLI (`claude`, `codex`) or
 * a direct model API; the layers above only ever see [[AgentEvent]].
 */
trait AgentProvider {

  /** "claude-cli" | "codex-cli" | "anthropic-api" | "openai-api". */
  def id: String

  def capabilities: ProviderCapabilities

  def models: Seq[ModelInfo]

  def defaultModel: String = models.find(_.isDefault).orElse(models.headOption).map(_.id).getOrElse("")

  /**
   * Does this engine have a credential to run on? False for a direct-API
   * provider with neither an API key nor a proxy configured — the router then
   * never offers it. CLI providers are credentialed by their config home.
   */
  def credentialAvailable: Boolean = true

  /** A short, secret-free label of the credential this engine runs on (`api-key`, `proxy`, `cli-config`, `none`). */
  def credentialLabel: String = if (credentialAvailable) "configured" else "none"

  /**
   * Does this engine have everything it needs APART from a credential? The
   * OpenAI provider refuses to hard-code a model id, so an unconfigured
   * `model` leaves it with nothing to call.
   */
  def configured: Boolean = true

  /** Open a session. Cheap for CLI providers (the process is per-turn). */
  def open(spec: SessionSpec): Future[ProviderSession]
}

/**
 * A live conversation with one engine. `sendTurn` is the only way work happens,
 * and its materialized value is the cancel handle — killing the switch is how
 * the Stop button reaches a subprocess.
 */
trait ProviderSession {

  /** The provider that opened this session. */
  def providerId: String

  /**
   * Run ONE user turn. Events stream in order and the stream always terminates
   * with a `TurnCompleted` or a `RuntimeError`. The [[UniqueKillSwitch]] is the
   * cancel handle: shutting it down stops the turn.
   */
  def sendTurn(turnId: TurnId, message: String, attachments: Seq[Path] = Seq.empty): Source[AgentEvent, UniqueKillSwitch] =
    sendTurnRaw(turnId, message, attachments).map(_.event)

  /**
   * As [[sendTurn]], but each event keeps the native provider payload that
   * produced it. The event log stores it (`agent_events.raw`) so a mapping bug
   * can be diagnosed after the fact; the browser never sees it.
   */
  def sendTurnRaw(
      turnId:      TurnId,
      message:     String,
      attachments: Seq[Path] = Seq.empty
  ): Source[ProviderEvent, UniqueKillSwitch]

  /** Hard-stop whatever is running. Idempotent. */
  def interrupt(): Unit

  /** Where to pick this conversation back up. */
  def resumeCursor: ResumeCursor

  /**
   * Refresh the volatile session context (who, tools) between turns.
   * Cheap by design — the context block is re-rendered, nothing is restarted.
   */
  def updateContext(context: SessionContext): Unit = ()

  /**
   * Replace the PROFILE this session sends with each turn.
   *
   * The system prompt is recomposed per turn from the editable prompt blocks,
   * so an owner's edit reaches a thread that is already open — the CLI adapters pass it on every
   * `--append-system-prompt` and the direct-API adapters send it on every
   * request. Default no-op: a provider that captured the spec's profile keeps
   * its old behaviour.
   */
  def updateProfile(profile: AgentProfile): Unit = ()

  def close(): Unit
}

/** An [[AgentEvent]] with the raw provider line it was mapped from. */
final case class ProviderEvent(event: AgentEvent, raw: Option[Json] = None)

object ProviderSession {

  /** Attach a kill switch to a per-turn source and run `onStop` when it ends. */
  private[agent] def withKillSwitch[A](
      source: Source[A, _],
      onStop: () => Unit
  ): Source[A, UniqueKillSwitch] =
    source
      .viaMat(org.apache.pekko.stream.KillSwitches.single[A])(Keep.right)
      .watchTermination() { (ks, done) =>
        done.onComplete(_ => onStop())(scala.concurrent.ExecutionContext.parasitic)
        ks
      }
}
