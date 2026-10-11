package escalator.web.email

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import escalator.util.email.{EmailConf, EscalatorMailResult, EscalatorMailer}
import escalator.web.db.Db
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

/** Writes .html files instead of sending (EMAILER=file) — local dev and e2e tests read them. */
final class FileMailer(dir: Path) extends EscalatorMailer {
  def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult = {
    Files.createDirectories(dir)
    val name = s"${System.currentTimeMillis()}-${toField.replaceAll("[^a-zA-Z0-9@.]", "_")}.html"
    Files.write(dir.resolve(name), s"<!-- to: $toField -->\n<!-- subject: $subject -->\n$message".getBytes(UTF_8))
    EscalatorMailResult(true, Some(s"written $name"))
  }
}

/** Email outbox: callers enqueue rows in `interactions`; a scheduled sender delivers them (betbot/SWOT pattern). */
final class Outbox(db: Db, mailer: EscalatorMailer = Outbox.mailerFromEnv()) {

  /** Idempotent per (model, id, reason). */
  def enqueue(sourceModel: String, sourceId: Long, reason: String, to: String, subject: String, html: String): Unit =
    db.update(
      """INSERT INTO interactions(interaction_type, source_model, source_model_id, reason, address, header, content)
        |VALUES (('INTERACTION_TYPE','EMAIL')::attribute_type, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (source_model, source_model_id, reason) DO UPDATE
        |  SET header = EXCLUDED.header, content = EXCLUDED.content, address = EXCLUDED.address,
        |      complete_at = NULL, success = NULL, updated_at = now()""".stripMargin,
      Db.Attr("INTERACTION_SOURCE", sourceModel), sourceId, Db.Attr("INTERACTION_REASON", reason), to.toLowerCase, subject, html
    )

  /** Failed sends stay pending and are retried every 15 minutes for up to 2 days (e.g. while the SMTP provider is blocking). */
  def sendPending(): Int = {
    val pending = db.query(
      """SELECT id, address, header, content FROM interactions
        |WHERE complete_at IS NULL AND approved
        |  AND (success IS NULL OR (updated_at < now() - interval '15 minutes' AND created_at > now() - interval '2 days'))
        |ORDER BY id LIMIT 20""".stripMargin
    )(r => (r.long("id"), r.str("address"), r.str("header"), r.str("content")))
    pending.foreach { case (id, to, subj, html) =>
      val res = Try(mailer.sendEmail(to, subj, html, true)).fold(e => EscalatorMailResult(false, Some(e.getMessage)), identity)
      db.update("UPDATE interactions SET complete_at = ?, success = ?, result_message = ?, updated_at = now() WHERE id = ?",
        if (res.success) Some(Instant.now()) else None, res.success, res.messageId, id)
    }
    pending.size
  }

  def start(system: ActorSystem)(implicit ec: ExecutionContext): Unit = {
    system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds)(() => Try(sendPending()).failed.foreach(_.printStackTrace()))
    ()
  }
}

object Outbox {
  /** `EMAILER=file` writes to `EMAIL_OUTPUT_DIR` (default tmp/emails); otherwise escalator's SMTP/SendGrid config,
    * falling back to files when that isn't configured. */
  def mailerFromEnv(env: Map[String, String] = sys.env): EscalatorMailer = env.get("EMAILER") match {
    case Some("file") => new FileMailer(Paths.get(env.getOrElse("EMAIL_OUTPUT_DIR", "tmp/emails")))
    case _            => Try(EmailConf.defaultMailer()).getOrElse(new FileMailer(Paths.get("tmp/emails")))
  }
}
