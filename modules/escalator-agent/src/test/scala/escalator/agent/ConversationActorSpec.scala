package escalator.agent

import java.nio.file.{Files, Path}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.util.logging.{Logger, Slf4jLogger}

import escalator.agent.profiles.PlainAssistantProfile
import escalator.agent.protocol._

/** Manager + conversation actor + store, with a scripted provider (no process, no network). */
class ConversationActorSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("agent-spec")
  private implicit val logger: Logger      = new Slf4jLogger("test")
  private implicit val timeout: Timeout    = Timeout(10.seconds)
  import system.dispatcher

  override def afterAll(): Unit = { Await.ready(system.terminate(), 10.seconds); () }

  /** Answers every turn with "ok", and records what it was asked. */
  private final class ScriptedProvider extends AgentProvider {
    @volatile var asked: List[String] = Nil
    override def id: String = "anthropic-api"
    override def capabilities: ProviderCapabilities =
      ProviderCapabilities(Set(Capability.InProcessTools), ResumeKind.StoredMessages)
    override def models: Seq[ModelInfo] = Seq(ModelInfo("m1", "M1", isDefault = true))
    override def open(spec: SessionSpec): Future[ProviderSession] = Future.successful(new ProviderSession {
      override def providerId: String = id
      override def sendTurnRaw(turnId: TurnId, message: String, attachments: Seq[Path]): Source[ProviderEvent, UniqueKillSwitch] = {
        asked = asked :+ message
        val item = ItemId(s"${turnId.value}-text")
        ProviderSession.withKillSwitch(
          Source(List(
            ProviderEvent(AgentEvent.ItemStarted(turnId, item, ItemType.AssistantMessage, "")),
            ProviderEvent(AgentEvent.ContentDelta(turnId, item, StreamKind.AssistantText, "ok")),
            ProviderEvent(AgentEvent.ItemCompleted(turnId, item, ItemStatus.Completed)),
            ProviderEvent(AgentEvent.TurnCompleted(turnId, TurnOutcome.Completed, Usage(3, 1), 5L))
          )),
          () => ()
        )
      }
      override def interrupt(): Unit     = ()
      override def resumeCursor: ResumeCursor = ResumeCursor.none
      override def close(): Unit         = ()
    })
  }

  test("a turn runs through the manager, streams, and lands in the store with the transcript") {
    val store    = new InMemoryAgentEventStore
    val provider = new ScriptedProvider
    val router   = new ProviderRouter(Seq(provider), Set.empty, "anthropic-api", "claude-cli")
    val ws       = Files.createTempDirectory("agent-ws-")
    val manager  = system.actorOf(AgentConversationManager.props(AgentConfig(ws), store, router, 2, 4, 1.minute))

    val row = Await.result(store.create(None, PlainAssistantProfile.Id, "anthropic-api", "m1", "", Some("s1"), None), 5.seconds)
    val accepted = Await.result(manager ? AgentConversationManager.Deliver(row.id, AgentConversationActor.Send("Reply with ok")), 10.seconds)
    accepted shouldBe a[AgentConversationActor.Accepted]

    eventually {
      Await.result(store.get(row.id), 5.seconds).map(_.turnCount) shouldBe Some(1)
    }
    val events = Await.result(store.events(row.id, 0, 100), 5.seconds).map(_.event)
    events.head shouldBe a[AgentEvent.SessionStarted]
    events.collect { case AgentEvent.ContentDelta(_, _, _, d) => d }.mkString shouldBe "ok"
    events.last shouldBe a[AgentEvent.TurnCompleted]

    val msgs = Await.result(store.messages(row.id, 10), 5.seconds)
    msgs.map(_.role) shouldBe Seq("user", "assistant")
    Await.result(store.get(row.id), 5.seconds).get.title shouldBe "Reply with ok"
    provider.asked shouldBe List("Reply with ok")
  }

  private def eventually(check: => Unit): Unit = {
    val deadline = System.currentTimeMillis() + 10000
    var last: Throwable = null
    while (System.currentTimeMillis() < deadline) {
      try { check; return }
      catch { case t: Throwable => last = t; Thread.sleep(50) }
    }
    throw last
  }
}
