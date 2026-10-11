package escalator.agent

import java.nio.file.Paths

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.util.logging.{Logger, Slf4jLogger}

import escalator.agent.profiles.PlainAssistantProfile

/** Router decisions over the real providers, built from settings (no process, no network). */
class ProviderRoutingSpec extends AnyFunSuite with Matchers {

  private implicit val logger: Logger = new Slf4jLogger("test")

  private val proxyHome = Files.tempHome(
    ".claude-cliproxy/settings.json" ->
      """{"env":{"ANTHROPIC_BASE_URL":"http://proxy.local:8317","ANTHROPIC_AUTH_TOKEN":"tok"}}"""
  )

  private def router(env: Map[String, String], home: String): (AgentSettings, ProviderRouter) = {
    val s  = AgentSettings.fromEnv(env = env, home = home)
    val ps = s.providers
    (s, ProviderRouter(ps, s, ModelCatalog.fromProviders(ps)))
  }

  test("proxy only: chat goes to the API engine, on the proxy credential") {
    val (_, r) = router(Map.empty, proxyHome)
    val p      = r.pick(PlainAssistantProfile, None).get
    p.id shouldBe "anthropic-api"
    p.credentialLabel shouldBe CredentialSource.Proxy
  }

  test("an env API key: chat goes to the API engine on the key") {
    val (_, r) = router(Map("ANTHROPIC_API_KEY" -> "sk-ant-x"), "/nonexistent-home")
    r.pick(PlainAssistantProfile, None).map(_.credentialLabel) shouldBe Some(CredentialSource.ApiKey)
  }

  test("no credential at all: the API engine is skipped and the CLI answers") {
    val (_, r) = router(Map.empty, "/nonexistent-home")
    r.pick(PlainAssistantProfile, None).map(_.id) shouldBe Some("claude-cli")
    r.availability.find(_.id == "anthropic-api").map(_.available) shouldBe Some(false)
  }

  test("an explicit request wins when usable, and is ignored when not") {
    val (_, r) = router(Map.empty, proxyHome)
    r.pick(PlainAssistantProfile, Some("claude-cli")).map(_.id) shouldBe Some("claude-cli")
    // openai-api has no model and no key here, so the request falls back to the default.
    r.pick(PlainAssistantProfile, Some("openai-api")).map(_.id) shouldBe Some("anthropic-api")
  }

  test("a workspace profile is never routed to an API engine") {
    val (_, r) = router(Map.empty, proxyHome)
    val builder = AgentProfile.Simple("builder", "x", requires = Set(Capability.FileSystem, Capability.Shell))
    r.pick(builder, Some("anthropic-api")).map(_.id) shouldBe Some("claude-cli")
  }

  test("APP_AGENT_PROVIDERS limits the enabled engines") {
    val (_, r) = router(Map("APP_AGENT_PROVIDERS" -> "claude-cli"), proxyHome)
    r.enabled.map(_.id) shouldBe Seq("claude-cli")
    r.pick(PlainAssistantProfile, None).map(_.id) shouldBe Some("claude-cli")
  }

  test("default model: claude-opus-5-5, overridable by APP_AGENT_MODEL; Haiku only by its dated id") {
    val (s, r) = router(Map.empty, proxyHome)
    val api    = r.byId("anthropic-api").get
    r.defaultModelFor(api) shouldBe "claude-opus-5-5"
    r.modelsFor(api).map(_.id) should contain("claude-haiku-4-5-20251001")
    r.modelsFor(api).map(_.id) should not contain "claude-haiku-4-5"
    s.claudeCli.models.find(_.isDefault).map(_.id) shouldBe Some("claude-opus-5-5")

    val (s2, r2) = router(Map("APP_AGENT_MODEL" -> "claude-sonnet-5-5"), proxyHome)
    r2.defaultModelFor(r2.byId("anthropic-api").get) shouldBe "claude-sonnet-5-5"
    s2.claudeCli.models.find(_.isDefault).map(_.id) shouldBe Some("claude-sonnet-5-5")
  }

  test("an unknown explicit model is refused with the valid ids; a stale candidate falls back") {
    val (_, r) = router(Map.empty, proxyHome)
    val api    = r.byId("anthropic-api").get
    r.validateModel(api, Some("claude-sonnet-5-5")) shouldBe Right(Some("claude-sonnet-5-5"))
    r.validateModel(api, Some("gpt-x")).left.toOption.get should include("claude-opus-5-5")
    r.pickModel(api, Seq(Some("gone-model"), None)) shouldBe "claude-opus-5-5"
  }

  test("the workspace root follows APP_DATA_DIR, then APP_DATA") {
    AgentSettings.fromEnv(env = Map("APP_DATA" -> "/srv/cp"), home = "/h").workspaceRoot shouldBe Paths.get("/srv/cp/agent")
    AgentSettings.fromEnv(env = Map("APP_DATA_DIR" -> "/srv/a", "APP_DATA" -> "/srv/b"), home = "/h").workspaceRoot shouldBe
      Paths.get("/srv/a/agent")
  }

  test("an app prefix scopes the app-owned variables, not the provider-owned ones") {
    val env = Map("CATCHPOLL_AGENT_MODEL" -> "claude-sonnet-5-5", "APP_AGENT_MODEL" -> "ignored", "CATCHPOLL_CLAUDE_BIN" -> "/opt/claude")
    val s   = AgentSettings.fromEnv("CATCHPOLL", env, "/nonexistent-home")
    s.anthropic.model shouldBe "claude-sonnet-5-5"
    s.claudeCli.bin shouldBe "/opt/claude"
  }
}

/** A throwaway home directory with the given files. */
private object Files {
  def tempHome(files: (String, String)*): String = {
    val dir = java.nio.file.Files.createTempDirectory("agent-home-")
    files.foreach { case (rel, body) =>
      val p = dir.resolve(rel)
      java.nio.file.Files.createDirectories(p.getParent)
      java.nio.file.Files.write(p, body.getBytes("UTF-8"))
    }
    dir.toFile.deleteOnExit()
    dir.toString
  }
}
