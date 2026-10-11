package escalator.agent

import java.sql.{Connection, PreparedStatement, ResultSet, Types}

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Using
import scala.util.control.NonFatal

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import escalator.util.logging.Logger

import escalator.agent.protocol.{AgentEvent, AgentEventEnvelope, AgentSeq, Usage}

/** Where the agent stores get a JDBC connection. Backend adapts its `Db` pool to this. */
trait AgentDb {
  def withConn[A](f: Connection => A): A
}

/**
 * Wire names ↔ attribute idents. The DB keeps enum families as `attribute_type`
 * with UPPER_SNAKE idents (codegen turns idents into Scala identifiers); the
 * runtime speaks kebab/lower case (`content-delta`, `claude-cli`, `idle`).
 */
object AgentAttrs {
  val Provider       = "AGENT_PROVIDER"
  val Status         = "AGENT_CONVERSATION_STATUS"
  val Credential     = "AGENT_CREDENTIAL_SOURCE"
  val Role           = "AGENT_MESSAGE_ROLE"
  val EventKind      = "AGENT_EVENT_KIND"

  def toIdent(wire: String): String   = wire.trim.replace('-', '_').toUpperCase
  def fromIdent(ident: String): String = ident.trim.toLowerCase.replace('_', '-')
}

/**
 * ==The Postgres-backed [[AgentEventStore]]==
 *
 * Plain JDBC over the V020 tables rather than the generated repositories: the
 * two operations that matter need SQL the generated CRUD does not offer —
 * an atomic `UPDATE … SET last_seq = last_seq + n RETURNING last_seq` and a
 * multi-row INSERT per coalescing window. (The generated repositories are on
 * the generated repositories for simple per-table access.)
 *
 * An event whose stored payload no longer decodes is DROPPED from a replay with
 * a warning rather than failing the stream.
 */
class JdbcAgentEventStore(db: AgentDb)(implicit ec: ExecutionContext, logger: Logger) extends AgentEventStore {

  import AgentAttrs._
  import JdbcAgentEventStore._

  private def io[A](f: Connection => A): Future[A] = Future(blocking(db.withConn(f)))

  private val Columns =
    """id, user_id, profile, (provider).ident AS provider, model, title, (status).ident AS status,
      |(credential_source).ident AS credential_source, provider_session_id, workspace_dir, last_seq, turn_count,
      |input_tokens, output_tokens, cost_usd, updated_at""".stripMargin

  override def create(
      userId:            Option[Long],
      profile:           String,
      provider:          String,
      model:             String,
      title:             String,
      providerSessionId: Option[String],
      workspaceDir:      Option[String]
  ): Future[ConversationRow] = io { c =>
    val id = one(
      c,
      s"""INSERT INTO agent_conversations
         |  (user_id, profile, provider, model, title, status, credential_source, provider_session_id, workspace_dir)
         |VALUES (?, ?, ROW('$Provider', ?)::attribute_type, ?, ?, ROW('$Status', 'IDLE')::attribute_type,
         |        ROW('$Credential', 'NONE')::attribute_type, ?, ?)
         |RETURNING id""".stripMargin,
      userId, profile, toIdent(provider), model, title.take(255), providerSessionId, workspaceDir
    )(_.getLong("id")).get
    getSync(c, id).get
  }

  override def get(conversationId: Long): Future[Option[ConversationRow]] = io(getSync(_, conversationId))

  private def getSync(c: Connection, id: Long): Option[ConversationRow] =
    one(c, s"SELECT $Columns FROM agent_conversations WHERE id = ?", id)(toRow)

  override def list(profile: Option[String], limit: Int): Future[Seq[ConversationRow]] = io { c =>
    query(
      c,
      s"""SELECT $Columns FROM agent_conversations
         |WHERE (status).ident <> 'ARCHIVED' AND (?::varchar IS NULL OR profile = ?)
         |ORDER BY updated_at DESC LIMIT ?""".stripMargin,
      profile, profile, limit
    )(toRow)
  }

  override def setTitle(conversationId: Long, title: String): Future[Unit] =
    io(update(_, "UPDATE agent_conversations SET title = ?, updated_at = NOW() WHERE id = ?", title.take(255), conversationId)).map(_ => ())

  override def delete(conversationId: Long): Future[Unit] =
    io(update(_, "DELETE FROM agent_conversations WHERE id = ?", conversationId)).map(_ => ())

  override def setStatus(conversationId: Long, status: String): Future[Unit] =
    io(update(_, s"UPDATE agent_conversations SET status = ROW('$Status', ?)::attribute_type, updated_at = NOW() WHERE id = ?",
      toIdent(status), conversationId)).map(_ => ())

  override def setProviderSession(conversationId: Long, providerSessionId: Option[String], workspaceDir: Option[String]): Future[Unit] =
    io(update(_,
      """UPDATE agent_conversations SET provider_session_id = ?, workspace_dir = COALESCE(?, workspace_dir), updated_at = NOW()
        |WHERE id = ?""".stripMargin, providerSessionId, workspaceDir, conversationId)).map(_ => ())

  override def setCredentialSource(conversationId: Long, source: String): Future[Unit] =
    io(update(_, s"UPDATE agent_conversations SET credential_source = ROW('$Credential', ?)::attribute_type WHERE id = ?",
      toIdent(source), conversationId)).map(_ => ())

  override def completeTurn(conversationId: Long, usage: Usage, status: String, titleIfEmpty: String): Future[Unit] =
    io(update(_,
      s"""UPDATE agent_conversations SET
         |  turn_count = turn_count + 1,
         |  input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
         |  cost_usd = CASE WHEN ?::numeric IS NULL THEN cost_usd ELSE COALESCE(cost_usd, 0) + ?::numeric END,
         |  status = ROW('$Status', ?)::attribute_type,
         |  title = CASE WHEN title = '' AND ? <> '' THEN ? ELSE title END,
         |  updated_at = NOW()
         |WHERE id = ?""".stripMargin,
      usage.inputTokens, usage.outputTokens, usage.costUsd, usage.costUsd, toIdent(status),
      titleIfEmpty, titleIfEmpty.take(255), conversationId)).map(_ => ())

  override def allocateSeqBlock(conversationId: Long, count: Int): Future[Long] = io { c =>
    one(c, "UPDATE agent_conversations SET last_seq = last_seq + ? WHERE id = ? RETURNING last_seq", count, conversationId)(_.getLong(1))
      .getOrElse(throw new NoSuchElementException(s"no agent conversation $conversationId"))
  }

  override def append(envelopes: Seq[AgentEventEnvelope]): Future[Int] =
    if (envelopes.isEmpty) Future.successful(0)
    else
      io { c =>
        val values = envelopes.map(_ => s"(?, ?, ?, ROW('$EventKind', ?)::attribute_type, ?::jsonb, ?::jsonb, ?)").mkString(", ")
        val params: Seq[Any] = envelopes.flatMap { env =>
          val payload = env.event.asJson.noSpaces
          Seq[Any](env.conversationId, env.seq.value, env.event.turnId.map(_.value), toIdent(env.kind), payload,
            env.raw.map(_.noSpaces), payload.length)
        }
        update(c, s"INSERT INTO agent_event_logs (conversation_id, seq, turn_id, kind, payload, raw, payload_bytes) VALUES $values", params: _*)
      }

  override def events(conversationId: Long, afterSeq: Long, limit: Int): Future[Seq[AgentEventEnvelope]] = io { c =>
    query(c,
      """SELECT conversation_id, seq, (kind).ident AS kind, payload, created_at FROM agent_event_logs
        |WHERE conversation_id = ? AND seq > ? ORDER BY seq LIMIT ?""".stripMargin,
      conversationId, afterSeq, limit) { rs =>
      val seq = rs.getLong("seq")
      parse(rs.getString("payload")).flatMap(AgentEvent.decoder.decodeJson) match {
        case Right(ev) => Some(AgentEventEnvelope(conversationId, AgentSeq(seq), rs.getTimestamp("created_at").getTime, ev, None))
        case Left(err) =>
          logger.warning(s"[agent] dropping undecodable event conv=$conversationId seq=$seq kind=${rs.getString("kind")}: ${err.getMessage}")
          None
      }
    }.flatten
  }

  override def replayBudget(conversationId: Long, afterSeq: Long): Future[(Long, Long)] = io { c =>
    one(c, "SELECT COUNT(*), COALESCE(SUM(payload_bytes), 0) FROM agent_event_logs WHERE conversation_id = ? AND seq > ?",
      conversationId, afterSeq)(rs => (rs.getLong(1), rs.getLong(2))).getOrElse((0L, 0L))
  }

  override def maxSeq(conversationId: Long): Future[Long] = io { c =>
    one(c, "SELECT COALESCE(MAX(seq), 0) FROM agent_event_logs WHERE conversation_id = ?", conversationId)(_.getLong(1)).getOrElse(0L)
  }

  override def appendMessage(conversationId: Long, turnId: String, role: String, content: Json): Future[Unit] =
    io(update(_, s"INSERT INTO agent_messages (conversation_id, turn_id, role, content) VALUES (?, ?, ROW('$Role', ?)::attribute_type, ?::jsonb)",
      conversationId, turnId, toIdent(role), content.noSpaces)).map(_ => ())

  /** The newest `limit` messages, returned oldest first. */
  override def messages(conversationId: Long, limit: Int): Future[Seq[StoredMessage]] = io { c =>
    query(c,
      """SELECT * FROM (
        |  SELECT id, turn_id, (role).ident AS role, content, created_at FROM agent_messages
        |  WHERE conversation_id = ? ORDER BY id DESC LIMIT ?
        |) m ORDER BY id""".stripMargin,
      conversationId, limit) { rs =>
      StoredMessage(rs.getString("turn_id"), fromIdent(rs.getString("role")),
        parse(rs.getString("content")).getOrElse(Json.Null), rs.getTimestamp("created_at").getTime)
    }
  }

  private def toRow(rs: ResultSet): ConversationRow = ConversationRow(
    id                = rs.getLong("id"),
    userId            = Option(rs.getLong("user_id")).filterNot(_ => rs.wasNull()),
    profile           = rs.getString("profile"),
    provider          = fromIdent(rs.getString("provider")),
    model             = rs.getString("model"),
    title             = rs.getString("title"),
    status            = fromIdent(rs.getString("status")),
    providerSessionId = Option(rs.getString("provider_session_id")),
    workspaceDir      = Option(rs.getString("workspace_dir")),
    lastSeq           = rs.getLong("last_seq"),
    turnCount         = rs.getInt("turn_count"),
    usage             = Usage(
      inputTokens  = rs.getLong("input_tokens"),
      outputTokens = rs.getLong("output_tokens"),
      costUsd      = Option(rs.getBigDecimal("cost_usd")).map(BigDecimal(_))
    ),
    updatedAt         = rs.getTimestamp("updated_at").getTime,
    credentialSource  = fromIdent(rs.getString("credential_source"))
  )
}

/** Minimal JDBC helpers shared by the agent stores. */
object JdbcAgentEventStore {

  def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (p, i0) =>
      val i = i0 + 1
      p match {
        case null | None      => ps.setNull(i, Types.NULL)
        case Some(v)          => bind1(ps, i, v)
        case v                => bind1(ps, i, v)
      }
    }

  private def bind1(ps: PreparedStatement, i: Int, v: Any): Unit = v match {
    case s: String     => ps.setString(i, s)
    case n: Int        => ps.setInt(i, n)
    case n: Long       => ps.setLong(i, n)
    case b: Boolean    => ps.setBoolean(i, b)
    case d: BigDecimal => ps.setBigDecimal(i, d.bigDecimal)
    case other         => ps.setObject(i, other)
  }

  def query[A](c: Connection, sql: String, params: Any*)(row: ResultSet => A): List[A] =
    Using.resource(c.prepareStatement(sql)) { ps =>
      bind(ps, params)
      Using.resource(ps.executeQuery()) { rs =>
        val b = List.newBuilder[A]
        while (rs.next()) b += row(rs)
        b.result()
      }
    }

  def one[A](c: Connection, sql: String, params: Any*)(row: ResultSet => A): Option[A] =
    query(c, sql, params: _*)(row).headOption

  def update(c: Connection, sql: String, params: Any*): Int =
    Using.resource(c.prepareStatement(sql)) { ps => bind(ps, params); ps.executeUpdate() }

  /** Runs `f` in a transaction; rolls back on any exception. */
  def tx[A](c: Connection)(f: => A): A = {
    c.setAutoCommit(false)
    try { val r = f; c.commit(); r }
    catch { case NonFatal(t) => c.rollback(); throw t }
    finally c.setAutoCommit(true)
  }
}
