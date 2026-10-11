package escalator.agent

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.profiles.PlainAssistantProfile
import escalator.agent.providers.{ClaudeCliConfig, ClaudeCliProvider}

/** The argv and environment a `claude` child runs with: no foreign tools, CLAUDE_CONFIG_DIR always set. */
class ClaudeCliCommandSpec extends AnyFunSuite with Matchers {

  private def cmd(profile: AgentProfile, model: String = "claude-opus-5-5", resume: Boolean = false) =
    ClaudeCliProvider.command(
      bin          = "claude",
      profile      = profile,
      model        = model,
      systemPrompt = "SYSTEM",
      mcpConfig    = None,
      resume       = resume,
      sessionId    = "sess-1",
      userMessage  = "hello",
      disableHooks = true
    )

  private def after(args: Seq[String], flag: String): Option[String] =
    args.sliding(2).collectFirst { case Seq(`flag`, v) => v }

  test("the plain assistant gets no tools at all and never the permission bypass") {
    val args = cmd(PlainAssistantProfile)
    after(args, "--tools") shouldBe Some("")
    args should not contain "--allowedTools"
    args should not contain "--dangerously-skip-permissions"
    after(args, "--permission-mode") shouldBe Some("dontAsk")
    after(args, "--disallowedTools").get.split(' ') should contain allOf ("Bash", "Write", "Edit", "WebFetch")
  }

  test("no betbot (or any other app's) MCP tools leak into a command") {
    val args = cmd(PlainAssistantProfile).mkString(" ")
    args should not include "mcp__"
    args should not include "betbot"
  }

  test("a profile's own allowed tools are the only ones allowed; built-ins go to --tools") {
    val p    = AgentProfile.Simple("lookup", "x", allowedTools = Seq("mcp__app__*", "Read"))
    val args = cmd(p)
    after(args, "--tools") shouldBe Some("Read")
    after(args, "--allowedTools") shouldBe Some("mcp__app__* Read")
    after(args, "--disallowedTools").get.split(' ') should not contain "Read"
  }

  test("a workspace profile keeps the bypass") {
    val p    = AgentProfile.Simple("builder", "x", requires = Set(Capability.FileSystem, Capability.Shell))
    val args = cmd(p)
    args should contain("--dangerously-skip-permissions")
    args should not contain "--tools"
  }

  test("model, hooks, session id vs resume, and the message after --") {
    val first = cmd(PlainAssistantProfile)
    after(first, "--model") shouldBe Some("claude-opus-5-5")
    after(first, "--settings") shouldBe Some("""{"disableAllHooks":true}""")
    after(first, "--session-id") shouldBe Some("sess-1")
    first.takeRight(2) shouldBe Seq("--", "hello")
    after(cmd(PlainAssistantProfile, resume = true), "--resume") shouldBe Some("sess-1")
    cmd(PlainAssistantProfile, model = "default") should not contain "--model"
  }

  test("the child env always sets CLAUDE_CONFIG_DIR and drops inherited Anthropic/Claude Code variables") {
    val parent = Map(
      "PATH"                  -> "/usr/bin",
      "HOME"                  -> "/home/owner",
      "ANTHROPIC_BASE_URL"    -> "http://elsewhere",
      "ANTHROPIC_AUTH_TOKEN"  -> "stray-token",
      "ANTHROPIC_API_KEY"     -> "stray-key",
      "CLAUDECODE"            -> "1",
      "CLAUDE_CODE_ENTRYPOINT"-> "sdk",
      "CLAUDE_CONFIG_DIR"     -> "/somewhere/else"
    )
    val cfg = ClaudeCliConfig(configDir = Paths.get("/home/owner/.claude-cliproxy"), parentEnv = parent)
    val env = cfg.childEnv
    env("CLAUDE_CONFIG_DIR") shouldBe "/home/owner/.claude-cliproxy"
    env("PATH") shouldBe "/usr/bin"
    env.keySet should contain noneOf ("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT")
  }

  test("with an API key credential the child gets exactly that key") {
    val cfg = ClaudeCliConfig(
      configDir  = Paths.get("/cfg"),
      credential = Some(ProviderCredential.ApiKey("sk-ant-real")),
      parentEnv  = Map("ANTHROPIC_API_KEY" -> "stray")
    )
    cfg.childEnv("ANTHROPIC_API_KEY") shouldBe "sk-ant-real"
    cfg.childEnv("CLAUDE_CONFIG_DIR") shouldBe "/cfg"
  }

  test("settings from env: CLAUDE_CONFIG_DIR flows to the CLI config, default ~/.claude-cliproxy") {
    AgentSettings.fromEnv(env = Map.empty, home = "/home/owner").claudeCli.configDir shouldBe Paths.get("/home/owner/.claude-cliproxy")
    AgentSettings.fromEnv(env = Map("CLAUDE_CONFIG_DIR" -> "/x/y"), home = "/home/owner").claudeCli.configDir shouldBe Paths.get("/x/y")
  }
}
