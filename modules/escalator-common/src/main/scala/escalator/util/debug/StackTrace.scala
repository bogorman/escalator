package escalator.common.util.debug

import java.io.StringWriter
import java.io.PrintWriter

object StackTrace {

  def currentStack() = {
    stackTraceToString(new Exception(""));
  }

  def stackTraceToString(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

}