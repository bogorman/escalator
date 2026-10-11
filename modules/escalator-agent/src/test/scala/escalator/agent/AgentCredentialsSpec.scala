package escalator.agent

import java.nio.file.{Path, Paths}

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Credential order: ANTHROPIC_API_KEY env → cliproxy settings.json in $CLAUDE_CONFIG_DIR (default ~/.claude-cliproxy). */
class AgentCredentialsSpec extends AnyFunSuite with Matchers {

  private val home = "/home/owner"

  private val proxySettings =
    """{"env":{"ANTHROPIC_BASE_URL":"http://proxy.local:8317","ANTHROPIC_AUTH_TOKEN":"tok-secret-1234"},"hooks":{}}"""

  private def files(entries: (String, String)*): Path => Option[String] = {
    val m = entries.toMap
    p => m.get(p.toString)
  }

  test("an ANTHROPIC_API_KEY in the environment wins over the cliproxy") {
    val cred = AnthropicCredentials.resolve(
      Map("ANTHROPIC_API_KEY" -> "sk-ant-key-9876"),
      home,
      files(s"$home/.claude-cliproxy/settings.json" -> proxySettings)
    )
    cred shouldBe Some(ProviderCredential.ApiKey("sk-ant-key-9876", None))
    cred.get.source shouldBe CredentialSource.ApiKey
  }

  test("a blank ANTHROPIC_API_KEY is ignored") {
    val cred = AnthropicCredentials.resolve(
      Map("ANTHROPIC_API_KEY" -> "  "),
      home,
      files(s"$home/.claude-cliproxy/settings.json" -> proxySettings)
    )
    cred.map(_.source) shouldBe Some(CredentialSource.Proxy)
  }

  test("no key: the cliproxy settings in the default config home (~/.claude-cliproxy)") {
    val cred = AnthropicCredentials.resolve(Map.empty, home, files(s"$home/.claude-cliproxy/settings.json" -> proxySettings))
    cred shouldBe Some(ProviderCredential.Proxy("http://proxy.local:8317", "tok-secret-1234", Paths.get(home, ".claude-cliproxy")))
  }

  test("CLAUDE_CONFIG_DIR moves the config home, with ~ expanded") {
    val env  = Map("CLAUDE_CONFIG_DIR" -> "~/other-proxy")
    AnthropicCredentials.configDir(env, home) shouldBe Paths.get(s"$home/other-proxy")
    val cred = AnthropicCredentials.resolve(env, home, files(s"$home/other-proxy/settings.json" -> proxySettings))
    cred.map(_.source) shouldBe Some(CredentialSource.Proxy)
    // ...and the default home is NOT consulted when the variable is set.
    AnthropicCredentials.resolve(env, home, files(s"$home/.claude-cliproxy/settings.json" -> proxySettings)) shouldBe None
  }

  test("a settings.json missing either value, or unreadable, is no credential") {
    val partial = """{"env":{"ANTHROPIC_BASE_URL":"http://proxy.local:8317"}}"""
    AnthropicCredentials.resolve(Map.empty, home, files(s"$home/.claude-cliproxy/settings.json" -> partial)) shouldBe None
    AnthropicCredentials.resolve(Map.empty, home, files(s"$home/.claude-cliproxy/settings.json" -> "not json")) shouldBe None
    AnthropicCredentials.resolve(Map.empty, home, files()) shouldBe None
  }

  test("credentials never print their secret") {
    val key   = ProviderCredential.ApiKey("sk-ant-key-9876")
    val proxy = ProviderCredential.Proxy("http://proxy.local:8317", "tok-secret-1234", Paths.get("/x"))
    key.toString should not include "sk-ant-key"
    key.toString should include("9876")
    proxy.toString should not include "tok-secret"
  }

  test("settings: nothing configured means no Anthropic credential, and only a real key reaches the CLI config") {
    AgentSettings.fromEnv(env = Map("CLAUDE_CONFIG_DIR" -> "/nonexistent-dir"), home = home).anthropic.credential shouldBe None
    val keyed = AgentSettings.fromEnv(env = Map("ANTHROPIC_API_KEY" -> "sk-ant-key-9876"), home = "/nonexistent-home")
    keyed.anthropic.credential.map(_.source) shouldBe Some(CredentialSource.ApiKey)
    keyed.claudeCli.credential.map(_.source) shouldBe Some(CredentialSource.ApiKey)
  }
}
