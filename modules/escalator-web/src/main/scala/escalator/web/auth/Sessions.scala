package escalator.web.auth

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import escalator.web.db.Db

/** The signed-in user behind a request. */
final case class SessionUser(userId: Long, email: String, role: String, fullName: Option[String], emailVerified: Boolean) {
  def isAdmin: Boolean = role == "ADMIN"
}

/** DB-backed sessions (user_sessions) with a short Caffeine cache. Session ids are random 32-byte tokens; only their
  * keyed hash is stored, so a DB read doesn't hand out live sessions. */
final class Sessions(db: Db, crypto: Crypto, ttlHours: Int = 24 * 7) {
  private val cache: Cache[String, SessionUser] =
    Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10000).build[String, SessionUser]()

  def create(userId: Long, ip: Option[String], userAgent: Option[String]): String = {
    val raw = crypto.token(32)
    val u = loadUser(userId).getOrElse(throw new IllegalStateException("no user"))
    db.update(
      """INSERT INTO user_sessions(session_id, user_id, email, role, ip_address, user_agent, expires_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin,
      crypto.keyedHash(raw), userId, u.email, Db.Attr("USER_ROLE", u.role), ip, userAgent, Instant.now().plus(ttlHours.toLong, ChronoUnit.HOURS)
    )
    raw
  }

  def lookup(raw: String): Option[SessionUser] = Option(cache.getIfPresent(raw)).orElse {
    val found = db.one(
      """SELECT s.user_id FROM user_sessions s
        |WHERE s.session_id = ? AND s.is_active AND s.expires_at > now()""".stripMargin,
      crypto.keyedHash(raw)
    )(_.long("user_id")).flatMap(loadUser)
    found.foreach(cache.put(raw, _))
    found
  }

  def destroy(raw: String): Unit = {
    cache.invalidate(raw)
    db.update("UPDATE user_sessions SET is_active = false WHERE session_id = ?", crypto.keyedHash(raw))
  }

  /** After role/verification changes, drop cached copies for this user. */
  def refreshUser(userId: Long): Unit = cache.asMap().entrySet().removeIf(e => e.getValue.userId == userId)

  def loadUser(userId: Long): Option[SessionUser] =
    db.one("SELECT id, email, role, full_name, email_verified_at FROM users WHERE id = ? AND (status).ident <> 'DISABLED'", userId) { r =>
      SessionUser(r.long("id"), r.str("email"), r.attr("role"), r.strO("full_name"), r.tsO("email_verified_at").isDefined)
    }
}
