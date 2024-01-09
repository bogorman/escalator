package escalator.util.logging

import org.apache.pekko.actor.Actor
// import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem

import org.apache.pekko.event.LoggingAdapter

// import org.apache.pekko.event.NoLogging
// import org.apache.pekko.event.LoggingBus.logLevel

import org.apache.pekko.event.Logging

case object ActorLogBroadcast

class DynamicLoggingAdapter(actorFullPath: String, system: ActorSystem, ref: Actor) extends LoggingAdapter {

  /**
   * Java API to return the reference to NoLogging
   * @return The NoLogging instance
   */
  // def getInstance = this

  // final override def isErrorEnabled = false
  // final override def isWarningEnabled = false
  // final override def isInfoEnabled = false
  // final override def isDebugEnabled = false

  import Logging._

  private val _systemLogger = org.apache.pekko.event.Logging(system, ref)
  private var _logLevel: LogLevel = initLogLevel

  private def initLogLevel = {
    // val actorFullPath = ref.path.toString

    // val bmProps = BMConfig.getProperties
    // if (bmProps.get("defaultLogLevel", "Info") == "Info") {
    //   InfoLevel
    // } else {
    //   DebugLevel
    // }

    // if (DtActorLoggingManager.getInitialActorLoggingLevel(actorFullPath) == "Info") {
      InfoLevel
    // } else {
      // println("INIT LOGGING ON " + actorFullPath + " TO DEBUG")
      // DebugLevel
    // }
  }
  // InfoLevel //use the system LogLevel by default

  def setLogLevel(logLevel: LogLevel) = {
    _logLevel = logLevel
  }

  def isErrorEnabled = _logLevel >= ErrorLevel
  def isWarningEnabled = _logLevel >= WarningLevel
  def isInfoEnabled = _logLevel >= InfoLevel
  def isDebugEnabled = _logLevel >= DebugLevel

  final protected override def notifyError(message: String): Unit = { _systemLogger.error(message) }
  final protected override def notifyError(cause: Throwable, message: String): Unit = { _systemLogger.error(cause, message) }
  final protected override def notifyWarning(message: String): Unit = { _systemLogger.warning(message) }
  final protected override def notifyInfo(message: String): Unit = { _systemLogger.info(message) }
  final protected override def notifyDebug(message: String): Unit = { _systemLogger.info(message) } //use info here as contorlled elsewhere
}

object ActorLoggingProtocol {
  case class SetActorLogLevel(actorPath: String, logLevel: Logging.LogLevel)
}

trait DynamicActorLogging { this: Actor ⇒
  import Logging._
  import ActorLoggingProtocol._
  val selfActorFullPath = self.path.toString

  private var _log: DynamicLoggingAdapter = _

  def log: LoggingAdapter = {
    if (_log eq null) {
      _log = new DynamicLoggingAdapter(selfActorFullPath, context.system, this)
    }
    _log
  }

  def setLogLevel(logLevel: LogLevel) = {
    if (_log eq null) {
      _log = new DynamicLoggingAdapter(selfActorFullPath, context.system, this)
    }
    _log.setLogLevel(logLevel)
  }

  def logReceive: Receive = {
    // println("logReceive called")
    case SetActorLogLevel(actorPath, logLevel) => {
      println("SetActorLogLevel " + actorPath + " " + logLevel)
      if (selfActorFullPath.endsWith(actorPath) || actorPath == "*" || actorPath == "user/*") {
        println("Setting log level...")
        val dlog = this.asInstanceOf[DynamicActorLogging]
        dlog.setLogLevel(logLevel)
      }
    }
  }

  def isErrorEnabled = _log.isErrorEnabled
  def isWarningEnabled = _log.isWarningEnabled
  def isInfoEnabled = _log.isInfoEnabled
  def isDebugEnabled = _log.isDebugEnabled

}

// object ActorUtils {

//   def logActorRestart(actor: ActorRef, message: String) = {
//     GlobalLogger.appendToFile("actorRestart.log", actor.path + ":" + message)
//   }

//   def logActorStop(actor: ActorRef) = {
//     GlobalLogger.appendToFile("actorStopped.log", actor.path + ": Stopped")
//   }

// }