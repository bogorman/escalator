package escalator.agent

import scala.concurrent.{ExecutionContext, Future}

import io.circe.Json

import escalator.agent.protocol.{AgentEvent, AgentEventEnvelope, AgentSeq, Usage}

/**
 * One agent thread, as the runtime sees it. A flattened view of the
 * `agent_conversations` row: no typed ids, no `Timestamp`, nothing that would
 * make the conversation actor care which database it came from.
 */
final case class ConversationRow(
    id:                Long,
    userId:            Option[Long],
    profile:           String,
    provider:          String,
    model:             String,
    title:             String,
    status:            String,
    providerSessionId: Option[String],
    workspaceDir:      Option[String],
    lastSeq:           Long,
    turnCount:         Int,
    usage:             Usage,
    updatedAt:         Long,
    /** Which credential the engine session ran on ("api-key" | "proxy" |
      * "cli-config" | "none"). Secret-free; set when the session opens. */
    credentialSource:  String = CredentialSource.NoCredential
)

/** One stored message: provider-neutral content blocks, in order. */
final case class StoredMessage(turnId: String, role: String, content: Json, at: Long)

object ConversationStatus {
  val Idle: String     = "idle"
  val Running: String  = "running"
  val Archived: String = "archived"
}

/**
 * ==The durable side of a conversation==
 *
 * Everything a conversation needs to survive a dropped connection, a refresh or
 * a restart: the thread row, its ordered event log, and its message history.
 *
 * It is a trait so the conversation actor does not depend on a database shape
 * ([[JdbcAgentEventStore]] is the Postgres implementation,
 * [[InMemoryAgentEventStore]] the test/DB-less one).
 *
 * The one subtle contract is [[allocateSeqBlock]]: it must be ATOMIC across
 * processes: if two writers could both mint seq 41, a client resuming with
 * `Last-Event-ID` would silently skip an event.
 */
trait AgentEventStore {

  def create(
      userId:            Option[Long],
      profile:           String,
      provider:          String,
      model:             String,
      title:             String,
      providerSessionId: Option[String],
      workspaceDir:      Option[String]
  ): Future[ConversationRow]

  def get(conversationId: Long): Future[Option[ConversationRow]]

  /** Newest first, archived excluded. `profile = None` ⇒ every profile. */
  def list(profile: Option[String], limit: Int): Future[Seq[ConversationRow]]

  def setTitle(conversationId: Long, title: String): Future[Unit]

  def delete(conversationId: Long): Future[Unit]

  def setStatus(conversationId: Long, status: String): Future[Unit]

  def setProviderSession(
      conversationId:    Long,
      providerSessionId: Option[String],
      workspaceDir:      Option[String]
  ): Future[Unit]

  /** Record which credential the engine session was opened with (see [[CredentialSource]]). */
  def setCredentialSource(conversationId: Long, source: String): Future[Unit]

  /** Turn count + usage rollup + status, and the title while it is still empty. */
  def completeTurn(conversationId: Long, usage: Usage, status: String, titleIfEmpty: String): Future[Unit]

  /**
   * Reserve `count` sequence numbers; returns the END of the reserved block, so
   * the range is `[end - count + 1, end]`.
   */
  def allocateSeqBlock(conversationId: Long, count: Int): Future[Long]

  /** Append already-sequenced envelopes. One round trip for the whole batch. */
  def append(envelopes: Seq[AgentEventEnvelope]): Future[Int]

  /** Ordered replay of everything after `afterSeq`, bounded by `limit`. */
  def events(conversationId: Long, afterSeq: Long, limit: Int): Future[Seq[AgentEventEnvelope]]

  /** `(events, bytes)` a replay from `afterSeq` would cost. */
  def replayBudget(conversationId: Long, afterSeq: Long): Future[(Long, Long)]

  /** The highest seq actually written — the resume point a snapshot carries. */
  def maxSeq(conversationId: Long): Future[Long]

  def appendMessage(conversationId: Long, turnId: String, role: String, content: Json): Future[Unit]

  def messages(conversationId: Long, limit: Int): Future[Seq[StoredMessage]]
}

object AgentEventStore {

  /** How many seqs a conversation reserves at a time. See [[SeqAllocator]]. */
  val DefaultSeqBlock: Int = 256

  /** Content blocks for a plain user message. */
  def textContent(text: String): Json =
    Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text)))

  /** The `text` a transcript row shows, pulled back out of the content blocks. */
  def contentText(content: Json): String =
    content.asArray match {
      case Some(blocks) =>
        blocks.iterator
          .flatMap(_.hcursor.get[String]("text").toOption)
          .mkString
      case None => content.hcursor.get[String]("text").toOption.getOrElse("")
    }
}

/**
 * Hands out per-conversation sequence numbers without a database round trip on
 * the hot path.
 *
 * A block is reserved up front and consumed in memory; only the block boundary
 * costs a query. An abandoned block tail leaves a GAP in the numbering, which
 * is harmless on purpose — readers ask for "everything after `seq`, ordered",
 * never for "the next number after `seq`".
 *
 * Ordering is guaranteed by chaining on the previous allocation's future, so
 * concurrent callers get strictly increasing numbers in call order.
 */
final class SeqAllocator(
    conversationId: Long,
    store:          AgentEventStore,
    blockSize:      Int = AgentEventStore.DefaultSeqBlock
)(implicit ec: ExecutionContext) {

  // (next free, block end). An empty block (next > end) forces a reservation.
  private var pending: Future[(Long, Long)] = Future.successful((1L, 0L))

  def next(): Future[Long] = synchronized {
    val allocated: Future[(Long, Long, Long)] = pending.flatMap {
      case (nxt, end) if nxt <= end => Future.successful((nxt, end, nxt))
      case _ =>
        store.allocateSeqBlock(conversationId, blockSize).map { blockEnd =>
          val start = blockEnd - blockSize + 1
          (start, blockEnd, start)
        }
    }
    // A failed reservation must not poison the allocator: reset to "empty
    // block" so the next call simply tries again.
    pending = allocated.map { case (n, e, _) => (n + 1, e) }.recover { case _ => (1L, 0L) }
    allocated.map(_._3)
  }

  /** `n` consecutive numbers, in order. */
  def nextN(n: Int): Future[Seq[Long]] =
    if (n <= 0) Future.successful(Seq.empty)
    else Future.sequence((1 to n).map(_ => next()))
}

/**
 * The envelope a provider event becomes once it has a number: seq, wall clock,
 * and the raw provider line kept for debugging (never sent to a browser).
 */
object AgentEnvelopes {
  def of(conversationId: Long, seq: Long, event: AgentEvent, raw: Option[Json] = None): AgentEventEnvelope =
    AgentEventEnvelope(conversationId, AgentSeq(seq), System.currentTimeMillis(), event, raw)
}
