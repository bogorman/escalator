package escalator.agent.cli

import java.nio.file.{Files, Paths}

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.providers.ClaudeCliConfig

/** The version manager hands turns the switchable binary; with no admin-selected version it is the bundled one. */
class ClaudeEngineSpec extends AnyFunSuite with Matchers {

  private val cli = ClaudeCliConfig(bin = "/opt/bundled/claude", configDir = Paths.get("/x/cfg"), parentEnv = Map.empty)

  test("without a selected version, turns run the bundled binary") {
    val engine = new ClaudeEngine(cli, Files.createTempDirectory("claude-engine-spec"))
    engine.current() shouldBe None
    ClaudeEngine.resolving(cli, engine).currentBin shouldBe "/opt/bundled/claude"
  }

  test("currentBin is resolved on every call, so a switch applies from the next turn") {
    var bin   = "/v1/claude"
    val moved = cli.copy(binResolver = Some(() => bin))
    moved.currentBin shouldBe "/v1/claude"
    bin = "/v2/claude"
    moved.currentBin shouldBe "/v2/claude"
    cli.currentBin shouldBe "/opt/bundled/claude"
  }
}
