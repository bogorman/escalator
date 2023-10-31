package escalator.util.resque

import java.io.StringWriter
import java.io.PrintWriter
import akka.actor._
// import java.io.File
import java.io.FileWriter
// import java.io.IOException

import escalator.common.util.debug.StackTrace

object LogUtils {

  def logActorRestart(actor: ActorRef, message: String) = {
    appendToFile("resqueActorRestart.log", actor.path + ":" + message)
  }

  def stackTraceToString(e: Throwable): String = {
    // val sw = new StringWriter
    // e.printStackTrace(new PrintWriter(sw))
    // sw.toString
    StackTrace.stackTraceToString(e)
  }

  def appendToFile(fileName: String, text: String) = {
    try {
      val fw = new FileWriter(fileName, true)
      fw.write(text)
      fw.close()
    } catch {
      case ex: Exception => {
        ex.printStackTrace
      }
    }
  }

}