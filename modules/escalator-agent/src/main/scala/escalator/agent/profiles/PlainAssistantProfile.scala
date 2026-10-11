package escalator.agent.profiles

import escalator.agent.AgentProfile

/**
 * The smallest useful profile: a plain chat assistant with NO tools and NO
 * filesystem. On the Claude CLI it runs with `--tools ""`, `--permission-mode
 * dontAsk` and a deny list; on the Codex CLI in a read-only sandbox; on the API
 * engines with an empty tool list. Proves the engine end to end.
 */
object PlainAssistantProfile extends AgentProfile {

  val Id: String = "assistant"

  override val id: String = Id

  val ProfileText: String =
    """You are a concise, helpful assistant inside this app.
      |Answer directly and briefly. You have no tools and no file access:
      |if a question needs data you do not have, say so in one line instead of guessing.""".stripMargin

  override def systemPrompt: String = ProfileText
}
