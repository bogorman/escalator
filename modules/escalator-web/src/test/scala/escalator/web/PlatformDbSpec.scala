package escalator.web

import scala.collection.mutable
import scala.util.Try

import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.util.email.{EscalatorMailResult, EscalatorMailer}
import escalator.web.auth.{Crypto, Sessions}
import escalator.web.db.Db
import escalator.web.email.Outbox

/** Db, Sessions and Outbox against a throwaway database with the platform migrations
  * (src/main/resources/escalator/web/sql) applied. Runs only when ESCALATOR_TEST_DB names that database — never
  * DB_NAME, which in an app's source_local.sh is its dev database. Connection from DB_HOST / DB_PORT / DB_USER. */
class PlatformDbSpec extends AnyFunSuite with Matchers {

  private lazy val db: Option[Db] =
    for {
      name <- sys.env.get("ESCALATOR_TEST_DB")
      host  = sys.env.getOrElse("DB_HOST", "localhost"); port = sys.env.getOrElse("DB_PORT", "5432"); user = sys.env.getOrElse("DB_USER", "postgres")
      d    <- Try(new Db(s"jdbc:postgresql://$host:$port/$name", user, sys.env.getOrElse("DB_PASSWORD", ""), "escalator-web-test", 2)).toOption
      if Try(d.one("SELECT 1 AS one FROM interactions LIMIT 1")(_.int("one"))).isSuccess
    } yield d

  private def withDb(name: String)(f: Db => Unit): Unit =
    db match {
      case Some(d) => test(name)(f(d))
      case None    => test(s"$name (skipped: set ESCALATOR_TEST_DB)")(cancel("no database"))
    }

  private val crypto = new Crypto("pepper", "secret")

  private def newUser(d: Db, role: String = "USER"): Long =
    d.insertId(
      "INSERT INTO users(email, role, status) VALUES (?, ?, ?)",
      s"u${System.nanoTime()}@example.com", Db.Attr("USER_ROLE", role), Db.Attr("USER_STATUS", "ACTIVE"))

  withDb("Db binds jsonb, attribute_type and text[] and reads them back") { d =>
    val row = d.one("SELECT ?::jsonb AS j, ?::attribute_type AS a, ?::text[] AS t",
      Db.JsonB(Json.obj("k" -> Json.fromInt(1))), Db.Attr("USER_ROLE", "ADMIN"), Db.TextArray(Seq("x", "y"))) { r =>
      (r.json("j"), r.attr("a"), r.strs("t"))
    }
    row shouldBe Some((Json.obj("k" -> Json.fromInt(1)), "ADMIN", List("x", "y")))
  }

  withDb("tx rolls back on failure") { d =>
    val id = newUser(d)
    an[IllegalStateException] should be thrownBy d.tx { c =>
      Db.update(c, "UPDATE users SET full_name = 'changed' WHERE id = ?", id)
      throw new IllegalStateException("boom")
    }
    d.one("SELECT full_name FROM users WHERE id = ?", id)(_.strO("full_name")) shouldBe Some(None)
  }

  withDb("sessions: only the keyed hash is stored; lookup, admin role and destroy") { d =>
    val sessions = new Sessions(d, crypto)
    val id       = newUser(d, "ADMIN")
    val raw      = sessions.create(id, Some("1.2.3.4"), Some("spec"))
    d.one("SELECT count(*) AS n FROM user_sessions WHERE session_id = ?", raw)(_.long("n")) shouldBe Some(0L)
    val u = sessions.lookup(raw).get
    (u.userId, u.isAdmin) shouldBe ((id, true))
    sessions.destroy(raw)
    sessions.lookup(raw) shouldBe None
  }

  withDb("outbox: enqueue is idempotent per (model, id, reason) and the sender marks rows done") { d =>
    val sent = mutable.Buffer.empty[(String, String)]
    val mailer = new EscalatorMailer {
      def sendEmail(to: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult = {
        sent += (to -> subject); EscalatorMailResult(true, Some("id-1"))
      }
    }
    val outbox = new Outbox(d, mailer)
    val id     = newUser(d)
    outbox.enqueue("User", id, "email_code", "Someone@Example.com", "first", "<p>1</p>")
    outbox.enqueue("User", id, "email_code", "someone@example.com", "second", "<p>2</p>")
    outbox.sendPending()
    sent.toList.filter(_._1 == "someone@example.com") shouldBe List("someone@example.com" -> "second")
    d.one("SELECT success FROM interactions WHERE source_model_id = ? AND (reason).ident = 'email_code'", id)(_.bool("success")) shouldBe Some(true)
  }
}
