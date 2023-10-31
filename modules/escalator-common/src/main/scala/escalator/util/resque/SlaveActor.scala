package escalator.util.resque

import akka.actor.{ Actor, ActorLogging } //Status, 
// import com.redis.RedisClient
// import redis.clients.jedis._

// import spray.json._
// import DefaultJsonProtocol._

import concurrent.Future
import scala.util.Success
import scala.util.Failure

// import akka.event.Logging

// atomic unit of work

trait SlaveActor extends Actor with ActorLogging {
  import ResqueProtocol._

  val classname: String

  val queue: String

  implicit val ec = context.dispatcher

  // var processing: List[String] = null

  // val log = Logging(context.system, this)

  override def preStart() = {
    // log.debug("Starting Actor")
    // log.debug(sender.toString)
    // sender ! WorkerStarted(self)
    context.system.eventStream.publish(WorkerStarted(self.path.toString, queue))
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    // LoggerUtils.error(self.path + "\n" + TraceUtils.stackTraceToString(reason))
    // log.debug("preRestart")
    // log.debug(TraceUtils.stackTraceToString(reason))
    // log.debug(message.getOrElse("No message"))
    // message.map()

    // postStop
    // ActorUtils.logActorRestart(self, reason.getMessage)
    super.preRestart(reason, message)
  }

  override def postStop() = {
    // log.debug("Stopping Actor")
    // log.debug(sender.toString)
    // sender ! WorkerStopped
    context.system.eventStream.publish(WorkerStopped(self.path.toString, queue))
    // ActorUtils.logActorStop(self)
  }

  def doOperation(msg: List[String]): Unit

  def receive = {
    case Task(payload) =>  {
      log.debug("Running Task - Start")
      log.debug(sender.toString)
      val processing = payload.args.map(_.toString)
      val contoller = sender
      // log.debug(context.self + ": working with " + processing)
      // try {
      // TaskStarted(queue, payload)
      contoller ! TaskStarted(queue, payload)

      val f = Future {
        try {
          doOperation(processing)
        } catch {
          case e: Exception =>  {
            // println("doOperation FAILED:  \n" + TraceUtils.stackTraceToString(e) + "\nARGS:" + processing.toString)
            // LoggerUtils.error("doOperation FAILED:  \n" + TraceUtils.stackTraceToString(e) + "\nARGS:" + processing.toString)
            throw e
          }
        }
      }
      f.onComplete {
        case Success(x) =>  {
          contoller ! TaskSuccess("OK", queue, payload)
        }
        case Failure(e) =>  {
          // LoggerUtils.error(self.path + "\n" + TraceUtils.stackTraceToString(e))
          contoller ! TaskFailure(e, queue, payload)
        }
      }

      // } catch {
      //   case e: Exception =>
      //     e.printStackTrace
      //     log.error("exception occurred: " + e)
      //     // Resque.enqueueFailed(classname, processing)

      //     // TaskFailure(exception, queue, payload)
      //     contoller ! TaskFailure(e, queue, payload)
      //   // sender ! Status.Failure(e)
      //   // sender ! Done("ERROR")
      // }
      log.debug(contoller.toString)
      log.debug("Running Task - End")
      // sender ! Done("OK")
    }

    // case a: Any =>  {
    //   log.debug("a unknown message ")
    //   log.debug(a.toString)
    //   // println(_)
    // }

    case _ =>  {
      log.debug("unknown message ")
      // log.debug(_.toString)
      // println(_)
    }

  }
}

