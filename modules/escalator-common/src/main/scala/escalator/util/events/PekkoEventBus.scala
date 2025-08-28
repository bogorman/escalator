package escalator.util.events

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.{EventBus => PekkoEventBusImpl, LookupClassification}

import escalator.ddd.Event
import escalator.util.logging.Logger

/**
 * Pekko-based EventBus implementation that publishes events to the Pekko EventStream.
 * Events can be subscribed to by type using Pekko's built-in event bus system.
 */
class PekkoEventBus(
  config: EventBusConfig
)(implicit 
  system: ActorSystem,
  ec: ExecutionContext,
  logger: Logger
) extends EventBus {

  private val eventStream = system.eventStream

  override def publish[E <: Event](event: E): Future[Unit] = {
    if (config.enableEvents) {
      if (config.logEvents) {
        logger.info(s"Publishing event: ${event.getClass.getSimpleName}")
      }
      
      if (config.asyncProcessing) {
        Future {
          eventStream.publish(event)
        }
      } else {
        eventStream.publish(event)
        Future.successful(())
      }
    } else {
      Future.successful(())
    }
  }

  override def publishBatch[E <: Event](events: List[E]): Future[Unit] = {
    if (config.enableEvents && events.nonEmpty) {
      if (config.logEvents) {
        logger.info(s"Publishing ${events.length} events in batch")
      }
      
      if (config.asyncProcessing) {
        Future {
          events.foreach(eventStream.publish)
        }
      } else {
        events.foreach(eventStream.publish)
        Future.successful(())
      }
    } else {
      Future.successful(())
    }
  }

  override def subscribe[E <: Event](handler: E => Future[Unit])(implicit ct: ClassTag[E]): Unit = {
    val eventClass = ct.runtimeClass.asInstanceOf[Class[E]]
    
    // Create an actor to handle the events
    val handlerActor = system.actorOf(EventHandlerActor.props(handler, logger))
    eventStream.subscribe(handlerActor, eventClass)
    
    logger.info(s"Subscribed to events of type: ${eventClass.getSimpleName}")
  }

  override def subscribeWithErrorHandling[E <: Event](
    handler: E => Future[Unit],
    errorHandler: (E, Throwable) => Unit
  )(implicit ct: ClassTag[E]): Unit = {
    val eventClass = ct.runtimeClass.asInstanceOf[Class[E]]
    
    val wrappedHandler: E => Future[Unit] = { event =>
      handler(event).recoverWith { case ex =>
        try {
          errorHandler(event, ex)
          Future.successful(())
        } catch {
          case errorEx: Exception =>
            logger.error(errorEx, s"Error in error handler for event: ${event.getClass.getSimpleName}")
            Future.failed(errorEx)
        }
      }
    }
    
    val handlerActor = system.actorOf(EventHandlerActor.props(wrappedHandler, logger))
    eventStream.subscribe(handlerActor, eventClass)
    
    logger.info(s"Subscribed to events of type: ${eventClass.getSimpleName} with error handling")
  }
}

object PekkoEventBus {
  def apply(
    config: EventBusConfig = EventBusConfig()
  )(implicit 
    system: ActorSystem,
    ec: ExecutionContext,
    logger: Logger
  ): PekkoEventBus = new PekkoEventBus(config)
}