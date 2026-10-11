package escalator.agent

import scala.concurrent.Await
import scala.concurrent.duration._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.profiles.PlainAssistantProfile

class PromptStoreSpec extends AnyFunSuite with Matchers {

  private val profiles = AgentProfiles.default

  test("with no overrides the composed prompt is exactly the profile's own text") {
    profiles.build(PlainAssistantProfile.Id).map(_.systemPrompt) shouldBe Some(PlainAssistantProfile.ProfileText)
  }

  test("an override replaces the profile block; the shared block appends to every profile") {
    val store = PromptStore.inMemory(profiles)
    Await.result(store.set("profile.assistant", "Be terse.", Some(1L)), 5.seconds).isRight shouldBe true
    Await.result(store.set(PromptBlocks.SharedGuidelines, "Use UK spelling.", None), 5.seconds).isRight shouldBe true
    profiles.build("assistant", store).get.systemPrompt shouldBe "Be terse.\n\nUse UK spelling.\n"
  }

  test("validation, history and reset") {
    val store = PromptStore.inMemory(profiles)
    Await.result(store.set("profile.assistant", "  ", None), 5.seconds).left.toOption.get should include("empty")
    Await.result(store.set("profile.nope", "x", None), 5.seconds).left.toOption.get should include("unknown")
    Await.result(store.set("profile.assistant", "v1", None), 5.seconds)
    val d = Await.result(store.set("profile.assistant", "v2", None), 5.seconds).toOption.get
    d.body shouldBe "v2"
    d.history.map(_.body) shouldBe List("v1", PlainAssistantProfile.ProfileText)
    Await.result(store.reset("profile.assistant", None), 5.seconds).get.isOverride shouldBe false
    store.resolve("profile.assistant") shouldBe PlainAssistantProfile.ProfileText
  }

  test("the registry holds one block per profile plus the shared ones, no domain-specific keys") {
    PromptBlocks.registry(profiles).map(_.key) shouldBe Seq("profile.assistant", PromptBlocks.SharedGuidelines)
  }
}
