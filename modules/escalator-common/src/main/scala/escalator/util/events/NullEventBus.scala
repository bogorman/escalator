package escalator.util.events

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import escalator.ddd.Event

/**
 * A no-op EventBus implementation that discards all events.
 * Useful for testing or when event processing is not needed.
 */
class NullEventBus(implicit ec: ExecutionContext) extends EventBus {
  
  /**
   * Publishes an event (actually just discards it)
   * @param event The event to publish (will be discarded)
   * @return A successful Future[Unit]
   */
  def publish[E <: Event](event: E): Future[Unit] = {
    // Simply return a successful future, discarding the event
    Future.successful(())
  }
  
  /**
   * Subscribe to events of a specific type (no-op)
   * @param handler The event handler (will never be called)
   * @param ct ClassTag for type erasure
   */
  def subscribe[E <: Event](handler: E => Future[Unit])(implicit ct: ClassTag[E]): Unit = {
    // Do nothing - events are discarded so handlers will never be called
  }
  
  /**
   * Subscribe to events with error handling (no-op)
   * @param handler The event handler (will never be called)
   * @param errorHandler The error handler (will never be called)
   * @param ct ClassTag for type erasure
   */
  def subscribeWithErrorHandling[E <: Event](
    handler: E => Future[Unit],
    errorHandler: (E, Throwable) => Unit
  )(implicit ct: ClassTag[E]): Unit = {
    // Do nothing - events are discarded so handlers will never be called
  }
}

object NullEventBus {
  /**
   * Creates a new NullEventBus instance
   * @param ec The execution context for futures
   * @return A new NullEventBus
   */
  def apply()(implicit ec: ExecutionContext): NullEventBus = new NullEventBus()
  
  /**
   * Creates a new NullEventBus with the global execution context
   * @return A new NullEventBus using the global execution context
   */
  def create(): NullEventBus = {
    import scala.concurrent.ExecutionContext.Implicits.global
    new NullEventBus()
  }
}