package escalator.util.akka

import org.apache.pekko.actor.{Actor, ActorRef, ActorRefFactory, DeadLetter, Props, UnhandledMessage}
import org.apache.pekko.io.Tcp.ResumeReading

import escalator.util.logging.Logger

object WartLogger {
  def props(implicit logger: Logger): Props = Props(new WartLogger())
  val name = "wart-logger"

  def create()(implicit actorRefFactory: ActorRefFactory, logger: Logger): ActorRef =
    actorRefFactory.actorOf(props, name)
}

class WartLogger()(implicit logger: Logger) extends Actor {
  override def preStart(): Unit = {
    super.preStart()
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
    context.system.eventStream.subscribe(self, classOf[UnhandledMessage])
    ()
  }

  def shortened(maxLength: Int, message: Any): String = {
    val asString = message.toString
    if (asString.length > maxLength) s"${asString.take(maxLength)}..." else asString
  }

  override def receive: Receive = {
    case DeadLetter(message, sentBy, recipient) =>
      logger.warning(
        "DeadLetter received",
        "akka_message" -> shortened(200, message),
        "sender" -> sentBy,
        "recipient" -> recipient
      )

    case UnhandledMessage(message, sentBy, recipient) =>
      message match {
        case ResumeReading => // Ignore as it happens too often
        case _ =>
          logger.warning(
            "Unhandled message",
            "akka_message" -> shortened(200, message),
            "sender" -> sentBy,
            "recipient" -> recipient
          )
      }
  }
}
