package escalator.util.logging

object GlobalLogger {
  // Log an error with an optional throwable
  def error(message: String, throwable: Throwable = null): Unit = {
    if (throwable != null)
      System.err.println(s"ERROR: $message\n${throwable.getMessage}")
    else
      System.err.println(s"ERROR: $message")
  }

  // Log an error using a throwable (and its message)
  def error(throwable: Throwable): Unit = {
    error(throwable.getMessage, throwable)
  }

  // Log a warning message
  def warn(message: String): Unit = {
    System.err.println(s"WARNING: $message")
  }

  // Log an information message
  def info(message: String): Unit = {
    println(s"INFO: $message")
  }
  
  // Log a debug message
  def debug(message: String): Unit = {
    println(s"DEBUG: $message")
  }
}
