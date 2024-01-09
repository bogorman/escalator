package escalator.util.logging

import org.apache.pekko.actor._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.collection.mutable.{ LinkedHashMap ⇒ MLinkedMap }
import scala.collection.mutable.{ ArrayBuffer => MList }
import scala.io.BufferedSource

case class ScheduleListAllActorsReply(actorRef: ActorRef, correlationId: Int)
case class ListAllActorsReplayData(actors: List[ActorRef])
case object ListAllActors
case object PersistActorLoggingLevelsToFile

class ActorLoggingManager(pathsToManage: List[String]) extends Actor with ActorLogging {
  import ActorLoggingProtocol._
  import ActorLoggingManager._

  override def preStart(): Unit = {
    setActorLoggingManagerRef(self)
    import context.dispatcher
    context.system.scheduler.schedule(5.seconds, 5.seconds, self, ActorLogBroadcast)
    context.system.scheduler.schedule(10.seconds, 120.seconds, self, ListAllActors)

    logLevels += ("user/*" -> defaultLogLevel)
    broadcastLogLevels

    loadLogLevels
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    setActorLoggingManagerRef(self)
    // LoggerUtils.error(TraceUtils.stackTraceToString(reason))
    // ActorUtils.logActorRestart(self, TraceUtils.stackTraceToString(reason))
    super.preRestart(reason, message)
  }

  override def postStop() = {

  }

  def receive = {
    case ActorLogBroadcast => {
      loadLogLevels
      broadcastLogLevels
    }

    case ListAllActors =>
      log.debug("ListAllActors message received")
      pathsToManage.foreach { path =>
        context.actorSelection(path) ! Identify(None)
      }
    // self ! ActorPath.fromString("/user")
    // context.system.eventStream.publish(WorkerStopped(self.path.toString, queue))

    // case path: ActorPath =>
    //   log.info("ActorPath - " + path.toString + "/*")
    // context.actorSelection(path / "*") ! Identify(None)

    // sent by each actor after receiving the Identify message
    case ActorIdentity(id, Some(actor)) =>
      log.debug("ActorIdentity:" + id + " " + actor.path.name + " " + actor.path.toString)
      // val actorFullName = "user/" + actor.path.name
      // if (actorFullName.contains("user/mgs-")) {
      //   addActorName(actorFullName.replace("user/mgs-", "user/MarketManager/mgs-"))
      // } else {
      //   addActorName(actorFullName)
      // }
      addActorName(actor.path.toString)

    case PersistActorLoggingLevelsToFile => {
      persistActorLoggingToFile()
    }

    case message => {
      log.error("Unknown message received by ActorLoggingManager: " + message)
    }
  }

  def broadcastLogLevels = {
    logLevels.foreach { keyVal =>
      org.apache.pekko.event.Logging.levelFor(keyVal._2).foreach { logLevel =>
        // context.system.eventStream.publish(SetActorLogLevel(keyVal._1, logLevel))
        log.debug("actor:" + keyVal._1)
        log.debug("loglevel:" + keyVal._2)
        val actorRef = context.system.actorSelection(keyVal._1)
        actorRef ! SetActorLogLevel(keyVal._1, logLevel)
      }
    }
  }

  def loadLogLevels = {
    val previousLogLevels = logLevels.clone()
    logLevels.clear()
    var source: Option[BufferedSource] = None
    try {
      source = Some(scala.io.Source.fromFile(actorLoggingFile))
      for (line <- source.get.getLines()) {
        try {
          if (line.trim.charAt(0) != '#') {
            val parts = line.trim.split("=")
            logLevels += (parts(0) -> parts(1))
          }
        } catch {
          case ex: Exception => {

          }
        }
      }

      val needResetting = previousLogLevels.keySet.diff(logLevels.keySet).filter(previousLogLevels(_) != defaultLogLevel)
      logLevels ++= needResetting.map((_, defaultLogLevel))

      logLevels = sortedByActorName(logLevels)

    } catch {
      case ex: Exception => {
        println(Console.RED + ex.printStackTrace + Console.RESET)
      }
    } finally {
      source match {
        case Some(s) => s.close()
        case None =>
      }
    }
  }

  def persistActorLoggingToFile() = {
    // TODO : fix
    // val str = getActorLoggingLevels(false).map(a => a._1 + "=" + a._2).mkString("\r\n")

    // try {
    //   while (FileUtils.fileExists(actorLoggingFile)) {
    //     FileUtils.deleteFile(actorLoggingFile)
    //     Thread.sleep(10)
    //   }

    //   FileUtils.appendFile(actorLoggingFile, str)
    // } catch {
    //   case ex: Exception => {
    //     log.error("Error persisting Actor Logging to file")
    //     log.error(ex.getMessage)
    //   }
    // }
  }

}

object ActorLoggingManager {
  val actorLoggingFile = "actor_logging.properties"
  // val bmProps = BMConfig.getProperties
  // val defaultLogLevel = bmProps.get("defaultLogLevel", "Info")

  var defaultLogLevel = "Debug" //

  var logLevels = MLinkedMap.empty[String, String] // using LinkedHashMap here because it retains the sort order of its elements
  val actorNames = MList.empty[String]

  var loggingManagerRef: ActorRef = null

  def getActorLoggingLevels(includeDefaults: Boolean): MLinkedMap[String, String] = {
    if (includeDefaults) {
      val allActors = MLinkedMap.empty[String, String]
      allActors ++= actorNames.map(_ -> defaultLogLevel)
      logLevels.foreach(l => allActors(l._1) = l._2)

      sortedByActorName(allActors)
    } else {
      logLevels
    }
  }

  def getInitialActorLoggingLevel(actorPath: String): String = {
    // defaultLogLevel

    var source: Option[BufferedSource] = None
    try {
      source = Some(scala.io.Source.fromFile(actorLoggingFile))
      for (line <- source.get.getLines()) {
        if (line.trim.charAt(0) != '#') {
          val parts = line.trim.split("=")
          // logLevels += (parts(0) -> parts(1))
          if (parts(0) == actorPath) {
            return parts(1)
          }
        }
      }
    } catch {
      case ex: Exception => {
        println(Console.RED + ex.printStackTrace + Console.RESET)
      }
    } finally {
      source match {
        case Some(s) => s.close()
        case None =>
      }
    }
    defaultLogLevel
  }

  def updateActorLogLevel(actorName: String, logLevel: String, updateFile: Boolean) = {
    if (logLevels.contains(actorName) && defaultLogLevel.equalsIgnoreCase(logLevel)) {
      // logLevels.remove(actorName)
      //TODO: remove it and force an update in the actor
      logLevels(actorName) = logLevel
      logLevels = sortedByActorName(logLevels)
      persistToFile()
    } else if (!defaultLogLevel.equalsIgnoreCase(logLevel)) {
      logLevels(actorName) = logLevel
      logLevels = sortedByActorName(logLevels)
      persistToFile()
    }
  }

  private def addActorName(actorName: String) = {
    if (!actorNames.contains(actorName)) {
      actorNames += actorName
    }
  }

  // default Log Level needs to be processed before specific ones, so we sort the map.
  private def sortedByActorName(map: MLinkedMap[String, String]): MLinkedMap[String, String] = {
    val ret = MLinkedMap.empty[String, String]
    ret ++= map.toSeq.sortBy(_._1)
    ret
  }

  private def setActorLoggingManagerRef(actor: ActorRef) = {
    loggingManagerRef = actor
  }

  private def persistToFile() = {
    loggingManagerRef ! PersistActorLoggingLevelsToFile
  }

}