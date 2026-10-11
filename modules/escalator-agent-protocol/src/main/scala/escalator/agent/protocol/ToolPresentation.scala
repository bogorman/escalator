package escalator.agent.protocol

/**
 * ==How a tool call reads to a human==
 *
 * Pure, shared, and the ONLY place a tool name is turned into words. A tool
 * card says "Reading a file" while it runs and "Read a file" once it has; both
 * the live stream and the stored transcript must agree on that wording.
 *
 * Generic on purpose: the built-in workspace tools of the CLI engines have
 * fixed wording, every MCP tool (`mcp__<server>__<tool>`) reads as a humanised
 * form of its own name, and an app that wants nicer wording for its own tools
 * passes them to [[register]] at startup.
 */
object ToolPresentation {

  /** Coarse family, for icon + colour. `Mcp` carries the server name. */
  sealed trait Group
  object Group {
    case object Read                     extends Group
    case object Search                   extends Group
    case object Command                  extends Group
    case object Edit                     extends Group
    case object Web                      extends Group
    final case class Mcp(server: String) extends Group
    case object Other                    extends Group
  }

  /** The three ways one tool is named: while it runs, once it is done, and a compact noun. */
  final case class Verbs(present: String, past: String, noun: String)

  /** Strip the MCP server prefix: `mcp__app__list_domains` → `list_domains`. */
  def bareName(toolName: String): String = {
    val n = toolName.trim
    val i = n.lastIndexOf("__")
    if (n.startsWith("mcp__") && i > 0) n.substring(i + 2) else n
  }

  /** `mcp__app__list_domains` → `app`; None for a non-MCP tool. */
  def mcpServer(toolName: String): Option[String] = {
    val n = toolName.trim
    if (!n.startsWith("mcp__")) None
    else {
      val rest = n.stripPrefix("mcp__")
      val i    = rest.indexOf("__")
      if (i > 0) Some(rest.substring(0, i)) else None
    }
  }

  def isMcpTool(toolName: String): Boolean = toolName.startsWith("mcp__")

  /** Which family a tool belongs to. */
  def group(toolName: String): Group =
    mcpServer(toolName) match {
      case Some(server) => Group.Mcp(server)
      case None =>
        toolName match {
          case "Read" | "NotebookRead"                         => Group.Read
          case "Grep" | "Glob" | "LS"                          => Group.Search
          case "Bash" | "BashOutput" | "KillShell"             => Group.Command
          case "Write" | "Edit" | "MultiEdit" | "NotebookEdit" => Group.Edit
          case "WebSearch" | "WebFetch"                        => Group.Web
          case _                                               => Group.Other
        }
    }

  /** The item type a tool call should be rendered as. An in-process tool (no prefix) is an MCP-style call too. */
  def itemType(toolName: String): ItemType = group(toolName) match {
    case Group.Mcp(_)  => ItemType.McpToolCall
    case Group.Command => ItemType.CommandExecution
    case Group.Edit    => ItemType.FileChange
    case Group.Web     => ItemType.WebSearch
    case Group.Other if !workspaceVerbs.contains(toolName) => ItemType.McpToolCall
    case _             => ItemType.Unknown
  }

  @volatile private var custom: Map[String, Verbs] = Map.empty

  /** Register wording for an app's own tools, by bare name. Later calls add to / override earlier ones. */
  def register(verbs: Map[String, Verbs]): Unit = synchronized { custom = custom ++ verbs }

  def verbs(toolName: String): Verbs = {
    val bare = bareName(toolName)
    custom.get(bare).orElse(workspaceVerbs.get(toolName)).getOrElse(fallback(bare))
  }

  def presentLabel(toolName: String): String = verbs(toolName).present

  def pastLabel(toolName: String): String = verbs(toolName).past

  /** The collapsed tool-card row: "Read a file · notes.md" (details appended). */
  def cardTitle(toolName: String, details: List[String]): String = {
    val head = pastLabel(toolName)
    if (details.isEmpty) head else (head :: details.filter(_.nonEmpty)).mkString(" · ")
  }

  private def fallback(bare: String): Verbs = {
    val words = bare.replace('_', ' ').trim
    Verbs(s"Running $words", s"Ran $words", if (words.isEmpty) "tool" else words)
  }

  /** Built-in workspace tools of the CLI engines. */
  private val workspaceVerbs: Map[String, Verbs] = Map(
    "Read"         -> Verbs("Reading a file", "Read a file", "file"),
    "Write"        -> Verbs("Writing a file", "Wrote a file", "file"),
    "Edit"         -> Verbs("Editing a file", "Edited a file", "file"),
    "MultiEdit"    -> Verbs("Editing files", "Edited files", "files"),
    "NotebookEdit" -> Verbs("Editing a notebook", "Edited a notebook", "notebook"),
    "Bash"         -> Verbs("Running a command", "Ran a command", "command"),
    "BashOutput"   -> Verbs("Reading command output", "Read command output", "output"),
    "KillShell"    -> Verbs("Stopping a command", "Stopped a command", "command"),
    "Grep"         -> Verbs("Searching", "Searched", "search"),
    "Glob"         -> Verbs("Finding files", "Found files", "files"),
    "LS"           -> Verbs("Listing files", "Listed files", "files"),
    "TodoWrite"    -> Verbs("Planning", "Planned", "plan"),
    "Task"         -> Verbs("Delegating a task", "Delegated a task", "task"),
    "WebSearch"    -> Verbs("Searching the web", "Searched the web", "web search"),
    "WebFetch"     -> Verbs("Fetching a page", "Fetched a page", "page")
  )
}
