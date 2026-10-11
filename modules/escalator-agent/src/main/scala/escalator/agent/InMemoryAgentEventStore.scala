package escalator.agent

import scala.concurrent.Future

import io.circe.Json
import io.circe.syntax._

import escalator.agent.protocol.{AgentEventEnvelope, Usage}

/** An [[AgentEventStore]] in memory: for tests, smoke runs and a DB-less wiring. Same semantics as the JDBC one. */
class InMemoryAgentEventStore extends AgentEventStore {

  private var nextId                                   = 1L
  private var rows: Map[Long, ConversationRow]         = Map.empty
  private var log: Map[Long, Vector[AgentEventEnvelope]] = Map.empty
  private var msgs: Map[Long, Vector[StoredMessage]]   = Map.empty

  private def now: Long = System.currentTimeMillis()

  private def ok[A](a: => A): Future[A] = Future.fromTry(scala.util.Try(synchronized(a)))

  private def modify(id: Long)(f: ConversationRow => ConversationRow): Unit =
    rows.get(id).foreach(r => rows += id -> f(r).copy(updatedAt = now))

  override def create(
      userId:            Option[Long],
      profile:           String,
      provider:          String,
      model:             String,
      title:             String,
      providerSessionId: Option[String],
      workspaceDir:      Option[String]
  ): Future[ConversationRow] = ok {
    val row = ConversationRow(nextId, userId, profile, provider, model, title, ConversationStatus.Idle, providerSessionId,
      workspaceDir, 0L, 0, Usage.empty, now)
    rows += row.id -> row
    nextId += 1
    row
  }

  override def get(conversationId: Long): Future[Option[ConversationRow]] = ok(rows.get(conversationId))

  override def list(profile: Option[String], limit: Int): Future[Seq[ConversationRow]] = ok {
    rows.values.filter(r => r.status != ConversationStatus.Archived && profile.forall(_ == r.profile))
      .toSeq.sortBy(-_.updatedAt).take(limit)
  }

  override def setTitle(conversationId: Long, title: String): Future[Unit] = ok(modify(conversationId)(_.copy(title = title)))

  override def delete(conversationId: Long): Future[Unit] = ok {
    rows -= conversationId; log -= conversationId; msgs -= conversationId
  }

  override def setStatus(conversationId: Long, status: String): Future[Unit] = ok(modify(conversationId)(_.copy(status = status)))

  override def setProviderSession(conversationId: Long, providerSessionId: Option[String], workspaceDir: Option[String]): Future[Unit] =
    ok(modify(conversationId)(r => r.copy(providerSessionId = providerSessionId, workspaceDir = workspaceDir.orElse(r.workspaceDir))))

  override def setCredentialSource(conversationId: Long, source: String): Future[Unit] =
    ok(modify(conversationId)(_.copy(credentialSource = source)))

  override def completeTurn(conversationId: Long, usage: Usage, status: String, titleIfEmpty: String): Future[Unit] = ok {
    modify(conversationId) { r =>
      r.copy(
        turnCount = r.turnCount + 1,
        usage     = r.usage + usage.copy(cacheReadTokens = 0L, cacheWriteTokens = 0L),
        status    = status,
        title     = if (r.title.isEmpty && titleIfEmpty.nonEmpty) titleIfEmpty else r.title
      )
    }
  }

  override def allocateSeqBlock(conversationId: Long, count: Int): Future[Long] = ok {
    val r = rows.getOrElse(conversationId, throw new NoSuchElementException(s"no agent conversation $conversationId"))
    val end = r.lastSeq + count
    rows += conversationId -> r.copy(lastSeq = end)
    end
  }

  override def append(envelopes: Seq[AgentEventEnvelope]): Future[Int] = ok {
    envelopes.foreach(e => log += e.conversationId -> (log.getOrElse(e.conversationId, Vector.empty) :+ e.withoutRaw))
    envelopes.size
  }

  override def events(conversationId: Long, afterSeq: Long, limit: Int): Future[Seq[AgentEventEnvelope]] =
    ok(log.getOrElse(conversationId, Vector.empty).filter(_.seq.value > afterSeq).sortBy(_.seq.value).take(limit))

  override def replayBudget(conversationId: Long, afterSeq: Long): Future[(Long, Long)] = ok {
    val after = log.getOrElse(conversationId, Vector.empty).filter(_.seq.value > afterSeq)
    (after.size.toLong, after.map(_.event.asJson.noSpaces.length.toLong).sum)
  }

  override def maxSeq(conversationId: Long): Future[Long] =
    ok(log.getOrElse(conversationId, Vector.empty).map(_.seq.value).foldLeft(0L)(math.max))

  override def appendMessage(conversationId: Long, turnId: String, role: String, content: Json): Future[Unit] =
    ok(msgs += conversationId -> (msgs.getOrElse(conversationId, Vector.empty) :+ StoredMessage(turnId, role, content, now)))

  override def messages(conversationId: Long, limit: Int): Future[Seq[StoredMessage]] =
    ok(msgs.getOrElse(conversationId, Vector.empty).takeRight(limit))
}
