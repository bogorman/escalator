package escalator.util.logging

import org.apache.pekko.event.LoggingAdapter

class PekkoActorLogger(log: org.apache.pekko.event.LoggingAdapter) extends Logger {

  private def fmt(msg: String, mdc: Seq[(String, Any)]): String =
    if (mdc == null || mdc.isEmpty) msg
    else {
      val ctx = mdc.map { case (k, v) => s"$k=${Option(v).map(_.toString).getOrElse("null")}" }.mkString(", ")
      s"$msg | mdc: {$ctx}"
    }

  private def fmtWithCause(msg: String, cause: Throwable, mdc: Seq[(String, Any)]): String = {
    val base = fmt(msg, mdc)
    val causeMsg = Option(cause.getMessage).getOrElse("")
    s"$base | cause=${cause.getClass.getName}: $causeMsg"
  }

  // ---- debug ----
  def debug(msg: String, mdc: (String, Any)*): Unit =
    log.debug(fmt(msg, mdc))

  def debug(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    // LoggingAdapter may not have debug(cause, msg) in all versions, so include cause in text.
    log.debug(fmtWithCause(msg, throwable, mdc))

  // ---- info ----
  def info(msg: String, mdc: (String, Any)*): Unit =
    log.info(fmt(msg, mdc))

  def info(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    log.info(fmtWithCause(msg, throwable, mdc))

  // ---- warning ----
  def warning(msg: String, mdc: (String, Any)*): Unit =
    log.warning(fmt(msg, mdc))

  def warning(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    log.warning(fmtWithCause(msg, throwable, mdc))

  // ---- error ----
  def error(msg: String, mdc: (String, Any)*): Unit =
    log.error(fmt(msg, mdc))

  def error(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    // LoggingAdapter does have error(cause, msg); use it so stack traces are preserved.
    log.error(throwable, fmt(msg, mdc))
}