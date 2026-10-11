package escalator.agent

import java.sql.Connection

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal

import escalator.util.logging.Logger

import escalator.agent.protocol.PromptBlocksProtocol._

/**
 * ==The Postgres-backed [[PromptStore]]==
 *
 * Overrides live in `agent_prompt_blocks` (one row per key, kept so by this
 * store), superseded bodies in `agent_prompt_histories`. Reads are served from
 * an in-memory snapshot refreshed on every write and at most every `ttlMs`; a
 * snapshot that cannot be read leaves the previous one (or the code defaults)
 * in force rather than failing a turn.
 */
class JdbcPromptStore(
    db:         AgentDb,
    val blocks: Seq[PromptBlocks.Block],
    ttlMs:      Long = 30000L
)(implicit ec: ExecutionContext, logger: Logger)
    extends PromptStore {

  import JdbcAgentEventStore._

  @volatile private var snapshot: Map[String, PromptStore.Stored] = Map.empty
  @volatile private var loadedAt: Long                             = 0L

  private def io[A](f: Connection => A): Future[A] = Future(blocking(db.withConn(f)))

  private def overrides(): Map[String, PromptStore.Stored] = {
    val now = System.currentTimeMillis()
    if (now - loadedAt > ttlMs) refreshSync()
    snapshot
  }

  /** Re-read the overrides. Failures keep the previous snapshot. */
  def refreshSync(): Unit =
    try {
      snapshot = db.withConn { c =>
        query(c, "SELECT key, body, default_sha, updated_by, updated_at FROM agent_prompt_blocks ORDER BY id") { rs =>
          val by = rs.getLong("updated_by")
          PromptStore.Stored(rs.getString("key"), rs.getString("body"), rs.getString("default_sha"),
            if (rs.wasNull()) None else Some(by), rs.getTimestamp("updated_at").getTime)
        }.map(s => s.key -> s).toMap
      }
      loadedAt = System.currentTimeMillis()
    } catch {
      case NonFatal(t) =>
        logger.warning(s"[agent] prompt overrides could not be read; using the previous snapshot: ${t.getMessage}")
        loadedAt = System.currentTimeMillis()
    }

  override def resolve(key: String): String = overrides().get(key).map(_.body).getOrElse(default(key))

  override def all(): Seq[PromptBlockSummary] = PromptStore.summaries(blocks, overrides())

  override def detail(key: String): Future[Option[PromptBlockDetail]] =
    io(c => history(c, key)).map(h => PromptStore.detailOf(blocks, key, overrides(), h))

  override def set(key: String, body: String, byUserId: Option[Long]): Future[Either[String, PromptBlockDetail]] =
    PromptBlocks.validate(blocks, key, body) match {
      case Left(msg) => Future.successful(Left(msg))
      case Right(text) =>
        val block = blocks.find(_.key == key).get
        io { c =>
          tx(c) {
            record(c, key, byUserId)
            update(c, "DELETE FROM agent_prompt_blocks WHERE key = ?", key)
            update(c, "INSERT INTO agent_prompt_blocks (key, body, default_sha, updated_by) VALUES (?, ?, ?, ?)",
              key, text, block.defaultSha, byUserId)
          }
        }.flatMap { _ =>
          refreshSync()
          detail(key).map(d => Right(d.get))
        }
    }

  override def reset(key: String, byUserId: Option[Long]): Future[Option[PromptBlockDetail]] =
    if (!blocks.exists(_.key == key)) Future.successful(None)
    else
      io { c =>
        tx(c) {
          record(c, key, byUserId)
          update(c, "DELETE FROM agent_prompt_blocks WHERE key = ?", key)
        }
      }.flatMap { _ =>
        refreshSync()
        detail(key)
      }

  /** Append the body in force to the history, then trim it to the cap. */
  private def record(c: Connection, key: String, byUserId: Option[Long]): Unit = {
    val current = snapshot.get(key).map(_.body).getOrElse(default(key))
    if (current.trim.nonEmpty) {
      update(c, "INSERT INTO agent_prompt_histories (key, body, updated_by) VALUES (?, ?, ?)", key, current, byUserId)
      update(c,
        """DELETE FROM agent_prompt_histories WHERE key = ? AND id NOT IN
          |  (SELECT id FROM agent_prompt_histories WHERE key = ? ORDER BY id DESC LIMIT ?)""".stripMargin,
        key, key, PromptBlocks.HistoryLimit)
      ()
    }
  }

  private def history(c: Connection, key: String): Seq[PromptStore.Revision] =
    query(c, "SELECT body, updated_by, created_at FROM agent_prompt_histories WHERE key = ? ORDER BY id DESC LIMIT ?",
      key, PromptBlocks.HistoryLimit) { rs =>
      val by = rs.getLong("updated_by")
      PromptStore.Revision(rs.getString("body"), if (rs.wasNull()) None else Some(by), rs.getTimestamp("created_at").getTime)
    }
}
