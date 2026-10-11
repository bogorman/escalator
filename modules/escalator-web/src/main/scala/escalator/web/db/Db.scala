package escalator.web.db

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp => SqlTs, Types}
import java.time.Instant

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Using

/** Small JDBC helper for domain services that need joins/locks. Generated Quill repositories remain available
  * for simple per-table access; both share the same database. */
final class Db(url: String, user: String, password: String, poolName: String = "app", poolSize: Int = 10) {
  private val ds: HikariDataSource = {
    val c = new HikariConfig()
    c.setJdbcUrl(url); c.setUsername(user); c.setPassword(password)
    c.setMaximumPoolSize(poolSize); c.setPoolName(poolName)
    new HikariDataSource(c)
  }

  def close(): Unit = ds.close()

  def withConn[A](f: Connection => A): A = Using.resource(ds.getConnection)(f)

  /** Runs `f` in a transaction; rolls back on any exception. */
  def tx[A](f: Connection => A): A = withConn { c =>
    c.setAutoCommit(false)
    try { val r = f(c); c.commit(); r }
    catch { case t: Throwable => c.rollback(); throw t }
    finally c.setAutoCommit(true)
  }

  def async[A](f: Db => A)(implicit ec: ExecutionContext): Future[A] = Future(blocking(f(this)))

  def query[A](sql: String, params: Any*)(row: Row => A): List[A] = withConn(Db.query(_, sql, params: _*)(row))
  def one[A](sql: String, params: Any*)(row: Row => A): Option[A] = query(sql, params: _*)(row).headOption
  def update(sql: String, params: Any*): Int = withConn(Db.update(_, sql, params: _*))
  def insertId(sql: String, params: Any*): Long = withConn(Db.insertId(_, sql, params: _*))
}

object Db {
  /** A JSON value bound as jsonb. */
  final case class JsonB(json: Json)
  /** An attribute_type value (family, ident). */
  final case class Attr(family: String, ident: String)
  /** A text[] value. */
  final case class TextArray(values: Seq[String])

  def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (p, i0) =>
      val i = i0 + 1
      p match {
        case null | None       => ps.setNull(i, Types.NULL)
        case Some(v)           => bind1(ps, i, v)
        case v                 => bind1(ps, i, v)
      }
    }

  private def bind1(ps: PreparedStatement, i: Int, v: Any): Unit = v match {
    case s: String       => ps.setString(i, s)
    case n: Int          => ps.setInt(i, n)
    case n: Long         => ps.setLong(i, n)
    case d: Double       => ps.setDouble(i, d)
    case b: Boolean      => ps.setBoolean(i, b)
    case d: BigDecimal   => ps.setBigDecimal(i, d.bigDecimal)
    case t: Instant      => ps.setTimestamp(i, SqlTs.from(t))
    case JsonB(j)        => val o = new org.postgresql.util.PGobject(); o.setType("jsonb"); o.setValue(j.noSpaces); ps.setObject(i, o)
    case Attr(f, id)     => val o = new org.postgresql.util.PGobject(); o.setType("attribute_type"); o.setValue(s"(\"$f\",\"$id\")"); ps.setObject(i, o)
    case TextArray(vs)   => ps.setArray(i, ps.getConnection.createArrayOf("text", vs.map(_.asInstanceOf[AnyRef]).toArray))
    case other           => ps.setObject(i, other)
  }

  def query[A](c: Connection, sql: String, params: Any*)(row: Row => A): List[A] =
    Using.resource(c.prepareStatement(sql)) { ps =>
      bind(ps, params)
      Using.resource(ps.executeQuery()) { rs =>
        val b = List.newBuilder[A]; val r = new Row(rs)
        while (rs.next()) b += row(r)
        b.result()
      }
    }

  def one[A](c: Connection, sql: String, params: Any*)(row: Row => A): Option[A] = query(c, sql, params: _*)(row).headOption

  def update(c: Connection, sql: String, params: Any*): Int =
    Using.resource(c.prepareStatement(sql)) { ps => bind(ps, params); ps.executeUpdate() }

  /** INSERT … RETURNING id */
  def insertId(c: Connection, sql: String, params: Any*): Long =
    one(c, if (sql.toLowerCase.contains("returning")) sql else sql + " RETURNING id", params: _*)(_.long("id"))
      .getOrElse(throw new IllegalStateException("insert returned no id"))
}

/** Typed accessors over a ResultSet row. */
final class Row(val rs: ResultSet) {
  def str(c: String): String = rs.getString(c)
  def strO(c: String): Option[String] = Option(rs.getString(c))
  def long(c: String): Long = rs.getLong(c)
  def longO(c: String): Option[Long] = { val v = rs.getLong(c); if (rs.wasNull()) None else Some(v) }
  def int(c: String): Int = rs.getInt(c)
  def intO(c: String): Option[Int] = { val v = rs.getInt(c); if (rs.wasNull()) None else Some(v) }
  def bool(c: String): Boolean = rs.getBoolean(c)
  def dec(c: String): BigDecimal = BigDecimal(rs.getBigDecimal(c))
  def decO(c: String): Option[BigDecimal] = Option(rs.getBigDecimal(c)).map(BigDecimal(_))
  def ts(c: String): Instant = rs.getTimestamp(c).toInstant
  def tsO(c: String): Option[Instant] = Option(rs.getTimestamp(c)).map(_.toInstant)
  def json(c: String): Json = Option(rs.getString(c)).flatMap(s => parse(s).toOption).getOrElse(Json.Null)
  def strs(c: String): List[String] = Option(rs.getArray(c)).map(_.getArray.asInstanceOf[Array[AnyRef]].toList.map(_.toString)).getOrElse(Nil)
  /** ident part of an attribute_type column, e.g. ("REVIEW_STATUS","OFFERED") → "OFFERED". */
  def attrO(c: String): Option[String] = Option(attr(c))
  def attr(c: String): String = Option(rs.getString(c)).map(_.stripPrefix("(").stripSuffix(")").split(",", 2)).map(a => a.last.replace("\"", "")).orNull
}
