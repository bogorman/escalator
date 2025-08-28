package escalator.util.events

import scala.concurrent.Future
import scala.reflect.ClassTag

import escalator.ddd.Event

/**
 * Event bus for publishing and subscribing to domain events.
 * Designed to be compatible with Event Sourcing systems.
 */
trait EventBus {
  
  /**
   * Publish an event to the bus.
   * @param event The event to publish
   * @return Future indicating success/failure of publishing
   */
  def publish[E <: Event](event: E): Future[Unit]
  
  /**
   * Publish multiple events as a batch.
   * @param events The events to publish
   * @return Future indicating success/failure of publishing
   */
  def publishBatch[E <: Event](events: List[E]): Future[Unit] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future.traverse(events)(publish).map(_ => ())
  }
  
  /**
   * Subscribe to events of a specific type.
   * @param handler Function to handle the event
   * @param ct ClassTag for type erasure
   */
  def subscribe[E <: Event](handler: E => Future[Unit])(implicit ct: ClassTag[E]): Unit
  
  /**
   * Subscribe to events of a specific type with error handling.
   * @param handler Function to handle the event
   * @param errorHandler Function to handle errors during event processing
   * @param ct ClassTag for type erasure
   */
  def subscribeWithErrorHandling[E <: Event](
    handler: E => Future[Unit],
    errorHandler: (E, Throwable) => Unit
  )(implicit ct: ClassTag[E]): Unit
}

/**
 * Configuration for EventBus implementations
 */
case class EventBusConfig(
  enableEvents: Boolean = true,
  bufferSize: Int = 1000,
  asyncProcessing: Boolean = true,
  logEvents: Boolean = false
)