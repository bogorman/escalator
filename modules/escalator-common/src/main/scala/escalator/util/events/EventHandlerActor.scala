package escalator.util.events

import scala.concurrent.Future
import scala.util.{Failure, Success}

import org.apache.pekko.actor.{Actor, ActorLogging, Props}

import escalator.ddd.Event
import escalator.util.logging.Logger

/**
 * Actor that handles events from the Pekko EventStream.
 * This actor receives events and delegates processing to the provided handler function.
 */
class EventHandlerActor[E <: Event](
  handler: E => Future[Unit],
  logger: Logger
) extends Actor with ActorLogging {
  
  import context.dispatcher

  override def receive: Receive = {
    case event: Event =>
      try {
        val eventTyped = event.asInstanceOf[E]
        
        handler(eventTyped).onComplete {
          case Success(_) =>
            log.debug(s"Successfully processed event: ${event.getClass.getSimpleName}")
          
          case Failure(ex) =>
            logger.error(ex, s"Failed to process event: ${event.getClass.getSimpleName}")
        }
        
      } catch {
        case ex: Exception =>
          logger.error(ex, s"Error handling event: ${event.getClass.getSimpleName}")
      }
      
    case unexpected =>
      log.warning(s"EventHandlerActor received unexpected message: $unexpected")
  }
}

object EventHandlerActor {
  def props[E <: Event](
    handler: E => Future[Unit],
    logger: Logger
  ): Props = Props(new EventHandlerActor(handler, logger))
}