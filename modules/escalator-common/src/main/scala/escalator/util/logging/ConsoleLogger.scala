package escalator.util.logging

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ConsoleLogger(name: String) extends Logger {
  private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

  private def format(level: String, msg: String, mdc: Seq[(String, Any)]): String = {
    val timestamp = LocalDateTime.now().format(timestampFormat)
    val mdcStr = if (mdc.nonEmpty) mdc.map { case (k, v) => s"$k=$v" }.mkString(" [", ", ", "]") else ""
    s"$timestamp [$level] [$name] - $msg$mdcStr"
  }

  override def debug(msg: String, mdc: (String, Any)*): Unit =
    println(format("DEBUG", msg, mdc))

  override def debug(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit = {
    println(format("DEBUG", msg, mdc))
    throwable.printStackTrace()
  }

  override def info(msg: String, mdc: (String, Any)*): Unit =
    println(format("INFO", msg, mdc))

  override def info(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit = {
    println(format("INFO", msg, mdc))
    throwable.printStackTrace()
  }

  override def warning(msg: String, mdc: (String, Any)*): Unit =
    println(format("WARN", msg, mdc))

  override def warning(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit = {
    println(format("WARN", msg, mdc))
    throwable.printStackTrace()
  }

  override def error(msg: String, mdc: (String, Any)*): Unit =
    println(format("ERROR", msg, mdc))

  override def error(throwable: Throwable, msg: String, mdc: (String, Any)*): Unit = {
    println(format("ERROR", msg, mdc))
    throwable.printStackTrace()
  }
}
