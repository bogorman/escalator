package escalator.agent

import scala.concurrent.Future

import io.circe.Json

/**
 * One tool as a direct-API provider must declare it: a name, a description the
 * model reads to decide when to call it, and a JSON Schema for its arguments —
 * the same three fields MCP publishes.
 */
final case class ToolDef(name: String, description: String, inputSchema: Json)

/** WHERE a tool call is happening: the turn and the `tool_use` id inside it. */
final case class ToolCallCtx(turnId: Option[String] = None, itemId: Option[String] = None)

object ToolCallCtx {
  val empty: ToolCallCtx = ToolCallCtx()
}

/**
 * ==Tools, executed by us==
 *
 * A CLI provider dials its MCP servers itself and we never see the call. A
 * direct-API provider cannot: the model answers with a `tool_use` block and
 * someone has to run it. That someone is an implementation of this trait,
 * supplied per session by `AgentConfig.toolExecutorFor`.
 *
 * `tools` is already filtered to what the profile allows: a tool that may not
 * be called is never offered, so the model cannot promise it.
 */
trait AgentToolExecutor {

  /** Tool definitions, in a stable order (prompt-cache safe). */
  def tools: Seq[ToolDef]

  /**
   * Run one tool call. `Right` is the tool's JSON result; `Left` is an error
   * message that goes back to the model as a `tool_result` with `is_error`.
   * Never fails the Future — a thrown tool is a `Left`.
   */
  def execute(name: String, input: Json): Future[AgentToolExecutor.ToolOutcome]

  /** The same call, with the turn it belongs to. Default drops the context. */
  def execute(name: String, input: Json, ctx: ToolCallCtx): Future[AgentToolExecutor.ToolOutcome] =
    execute(name, input)

  /** True once the tools have done what the turn was for (a job's result was accepted): the provider ends the turn
    * after this round of tool results instead of asking the model for a closing message. False by default. */
  def finished: Boolean = false

  /** A base64 PNG to attach to the tool_result (so the model sees a picture it produced). None by default. */
  def imagePng(payload: Json): Future[Option[String]] = Future.successful(None)
}

object AgentToolExecutor {

  /** `Right(result)` | `Left(error text)`. */
  type ToolOutcome = Either[String, Json]

  /** No tools at all. */
  val empty: AgentToolExecutor = new AgentToolExecutor {
    override def tools: Seq[ToolDef] = Seq.empty
    override def execute(name: String, input: Json): Future[ToolOutcome] =
      Future.successful(Left(s"tool '$name' is not available in this session"))
  }
}
