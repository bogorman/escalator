package escalator.agent

import java.sql.{Connection, DriverManager}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

import io.circe.Json

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.util.logging.{Logger, Slf4jLogger}

import escalator.agent.protocol._

/**
 * The store contract, run against the in-memory store and — when DB_HOST etc.
 * are set (source_local.sh) and V020 is applied — against Postgres. The JDBC
 * run creates its own rows and deletes them afterwards.
 */
class AgentEventStoreSpec extends AnyFunSuite with Matchers {

  private implicit val logger: Logger = new Slf4jLogger("test")

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  private def contract(name: String, store: => AgentEventStore): Unit = {

    test(s"$name: create, allocate seq blocks, append and replay events in order") {
      val s   = store
      val row = await(s.create(None, "assistant", "anthropic-api", "claude-opus-5-5", "", Some("sess"), None))
      try {
        row.status shouldBe ConversationStatus.Idle
        row.provider shouldBe "anthropic-api"
        await(s.allocateSeqBlock(row.id, 10)) shouldBe 10L
        await(s.allocateSeqBlock(row.id, 10)) shouldBe 20L

        val t = TurnId("t1")
        val envs = Seq(
          AgentEnvelopes.of(row.id, 1, AgentEvent.TurnStarted(t, "hi")),
          AgentEnvelopes.of(row.id, 2, AgentEvent.ContentDelta(t, ItemId("i1"), StreamKind.AssistantText, "ok"), Some(Json.obj())),
          AgentEnvelopes.of(row.id, 3, AgentEvent.TurnCompleted(t, TurnOutcome.Completed, Usage(5, 1), 10L))
        )
        await(s.append(envs)) shouldBe 3
        await(s.events(row.id, 1, 100)).map(_.event) shouldBe envs.drop(1).map(_.event)
        await(s.maxSeq(row.id)) shouldBe 3L
        await(s.replayBudget(row.id, 0))._1 shouldBe 3L

        await(s.appendMessage(row.id, "t1", "user", AgentEventStore.textContent("hi")))
        await(s.appendMessage(row.id, "t1", "assistant", AgentEventStore.textContent("ok")))
        val msgs = await(s.messages(row.id, 10))
        msgs.map(_.role) shouldBe Seq("user", "assistant")
        msgs.map(m => AgentEventStore.contentText(m.content)) shouldBe Seq("hi", "ok")
        await(s.messages(row.id, 1)).map(_.role) shouldBe Seq("assistant")

        await(s.setCredentialSource(row.id, CredentialSource.Proxy))
        await(s.completeTurn(row.id, Usage(5, 1, costUsd = Some(BigDecimal("0.01"))), ConversationStatus.Idle, "hi"))
        val after = await(s.get(row.id)).get
        after.turnCount shouldBe 1
        after.title shouldBe "hi"
        after.usage.inputTokens shouldBe 5L
        after.credentialSource shouldBe CredentialSource.Proxy
        await(s.list(Some("assistant"), 50)).map(_.id) should contain(row.id)
      } finally await(s.delete(row.id))
      await(s.get(row.id)) shouldBe None
    }

    test(s"$name: the seq allocator hands out strictly increasing numbers across blocks") {
      val s   = store
      val row = await(s.create(None, "assistant", "claude-cli", "", "", None, None))
      try {
        val alloc = new SeqAllocator(row.id, s, blockSize = 3)
        await(alloc.nextN(7)) shouldBe (1L to 7L)
      } finally await(s.delete(row.id))
    }
  }

  contract("in-memory", new InMemoryAgentEventStore)

  private lazy val jdbcUrl: Option[(String, String, String)] =
    // ESCALATOR_TEST_DB, never DB_NAME: in an app's source_local.sh that is its dev database.
    sys.env.get("ESCALATOR_TEST_DB").map { name =>
      val host = sys.env.getOrElse("DB_HOST", "localhost"); val port = sys.env.getOrElse("DB_PORT", "5432")
      (s"jdbc:postgresql://$host:$port/$name", sys.env.getOrElse("DB_USER", "postgres"), sys.env.getOrElse("DB_PASSWORD", ""))
    }

  private lazy val jdbcDb: Option[AgentDb] = jdbcUrl.flatMap { case (url, user, pw) =>
    val db = new AgentDb {
      def withConn[A](f: Connection => A): A = {
        val c = DriverManager.getConnection(url, user, pw)
        try f(c) finally c.close()
      }
    }
    // Only when the agent tables exist (V020 applied).
    Try(db.withConn(_.createStatement().executeQuery("SELECT 1 FROM agent_conversations LIMIT 1").close())).toOption.map(_ => db)
  }

  if (jdbcDb.isDefined) contract("postgres", new JdbcAgentEventStore(jdbcDb.get))
  else test("postgres: skipped (set ESCALATOR_TEST_DB to a DB with agent_tables.sql)")(cancel("no database"))
}
