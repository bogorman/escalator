package escalator.agent

/**
 * An MCP server a profile wants its CLI engine to dial. Written into the
 * workspace `.mcp.json` (owner-only permissions). `bearer` is optional and
 * resolved when the session opens; it is never logged and never put in argv.
 */
final case class McpServer(name: String, url: String, bearer: () => Option[String] = () => None)

/**
 * ==Everything that differs between agent use-cases==
 *
 * The transport, actor and provider layers are generic; a profile supplies the
 * specialisation:
 *
 *  - `systemPrompt`     — appended to the engine's system prompt;
 *  - `requires`         — what it needs FROM AN ENGINE (the router only offers
 *                         a provider that covers this set). A profile that does
 *                         not ask for FileSystem/Shell runs the CLI engines
 *                         locked down (allowlist + deny list, read-only sandbox);
 *  - `allowedTools`     — the exact tools a locked-down CLI session may use
 *                         (e.g. `mcp__app__*`). Empty ⇒ no tools at all;
 *  - `mcpServers`       — MCP servers a CLI engine connects to (none by default);
 *  - `skillFiles`       — relative path → contents written into a new workspace;
 *  - `sourceExtensions` — files read back after a turn and surfaced as `FileUpdated`.
 *
 * Add a use-case by implementing this trait and registering it in
 * [[AgentProfiles]] — nothing in the actor, router or providers changes.
 */
trait AgentProfile {
  def id: String
  def systemPrompt: String
  def requires: Set[Capability]          = Set.empty
  def allowedTools: Seq[String]          = Seq.empty
  def mcpServers: Seq[McpServer]         = Seq.empty
  def skillFiles: Map[String, String]    = Map.empty
  def sourceExtensions: Seq[String]      = Seq.empty

  /** True when the engine may touch the workspace / run commands. */
  def needsWorkspaceTools: Boolean =
    requires.contains(Capability.FileSystem) || requires.contains(Capability.Shell)

  /** True when this profile hands the engine any tools at all. */
  def toolsEnabled: Boolean = allowedTools.nonEmpty || mcpServers.nonEmpty

  /** The same profile with a different (recomposed) system prompt. */
  def withSystemPrompt(prompt: String): AgentProfile = AgentProfile.Composed(this, prompt)
}

object AgentProfile {

  /** A profile whose prompt was recomposed from the prompt store; everything else is the base profile's. */
  final case class Composed(base: AgentProfile, systemPrompt: String) extends AgentProfile {
    override def id: String                       = base.id
    override def requires: Set[Capability]        = base.requires
    override def allowedTools: Seq[String]        = base.allowedTools
    override def mcpServers: Seq[McpServer]       = base.mcpServers
    override def skillFiles: Map[String, String]  = base.skillFiles
    override def sourceExtensions: Seq[String]    = base.sourceExtensions
  }

  /** An ad-hoc profile, for tests and one-off tools. */
  final case class Simple(
      id:                        String,
      systemPrompt:              String,
      override val requires:     Set[Capability] = Set.empty,
      override val allowedTools: Seq[String]     = Seq.empty,
      override val mcpServers:   Seq[McpServer]  = Seq.empty
  ) extends AgentProfile
}

/**
 * The profiles this app knows, by id. The system prompt is COMPOSED per turn
 * from [[PromptBlocks]] through the [[PromptStore]] (override-or-default), so
 * an owner's edit lands on the next turn.
 */
class AgentProfiles(base: Seq[AgentProfile]) {

  private val byId: Map[String, AgentProfile] = base.map(p => p.id -> p).toMap

  def ids: Seq[String] = base.map(_.id)

  def exists(id: String): Boolean = byId.contains(id)

  /** The profile with its prompt composed from `prompts`. None for an unknown id. */
  def build(id: String, prompts: PromptStore = PromptStore.defaults(this)): Option[AgentProfile] =
    byId.get(id).map(p => p.withSystemPrompt(PromptBlocks.compose(p, prompts)))

  /** The shipped profile, uncomposed (its code-default prompt). */
  def base(id: String): Option[AgentProfile] = byId.get(id)

  def all: Seq[AgentProfile] = base
}

object AgentProfiles {

  /** A generic assistant. */
  val default: AgentProfiles = new AgentProfiles(Seq(escalator.agent.profiles.PlainAssistantProfile))
}
