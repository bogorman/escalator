package escalator.util.logging

import org.slf4j.{LoggerFactory, MDC}

class Slf4jLogger(name: String) extends Logger {
  private val logger = LoggerFactory.getLogger(name)

  private def withMDC(enabled: Boolean, mdc: Seq[(String, Any)])(block: => Unit) = {
    if (enabled) {
      mdc.foreach { case (key, value) => MDC.put(key, value.toString) }
      block
      MDC.clear()
    }
  }

  private def mdcWithMessageReplica(msg: String, mdc: Seq[(String, Any)]): Seq[(String, Any)] =
    ("message_replica" -> msg) :: mdc.toList

  override def debug(msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isDebugEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.debug(msg)
    }

  override def debug(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isDebugEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.debug(msg, throwable)
    }

  override def info(msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isInfoEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.info(msg)
    }

  override def info(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isInfoEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.info(msg, throwable)
    }

  override def warning(msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isWarnEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.warn(msg)
    }

  override def warning(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isWarnEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.warn(msg, throwable)
    }

  override def error(msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isErrorEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.error(msg)
    }

  override def error(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit =
    withMDC(logger.isErrorEnabled(), mdcWithMessageReplica(msg, mdc)) {
      logger.error(msg, throwable)
    }
}
