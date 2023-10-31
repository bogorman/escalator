package escalator.util.logging

abstract class Logger {
  def debug(msg: String, mdc: (String, Any)*): Unit
  def debug(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit

  def info(msg: String, mdc: (String, Any)*): Unit
  def info(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit

  def warning(msg: String, mdc: (String, Any)*): Unit
  def warning(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit

  def error(msg: String, mdc: (String, Any)*): Unit
  def error(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit
}
