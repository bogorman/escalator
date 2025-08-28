package escalator.util.postgres

import scala.concurrent.{ExecutionContext, Future}
import escalator.util.events.EventBus
import escalator.util.{TimeUtil, Timestamp}
import escalator.util.logging.Logger
import escalator.models.CorrelationId
import escalator.ddd.Event
import java.util.UUID

/**
 * EventRequiredResult forces event publishing for write operations.
 * There is no way to extract the Future[T] without calling one of the publishing methods.
 */
class EventRequiredResult[T] private[postgres](
  private val dbFuture: Future[_],
  private val model: T,
  private val timestamp: Timestamp
) {
  
  /**
   * Publishes a created event and returns the model
   */
  def publishingCreated(eventFactory: (T, CorrelationId, Timestamp) => Event)
    (implicit eventBus: EventBus, ec: ExecutionContext, logger: Logger): Future[T] = {
    dbFuture.flatMap { _ =>
      val correlationId = CorrelationId(UUID.randomUUID().toString)
      val event = eventFactory(model, correlationId, timestamp)
      
      eventBus.publish(event).map(_ => model).recover {
        case ex => 
          logger.error(ex, s"Failed to publish ${event.getClass.getSimpleName} event")
          model
      }
    }
  }
  
  /**
   * Publishes an updated event and returns the model
   */
  def publishingUpdated(eventFactory: (T, Option[T], CorrelationId, Timestamp) => Event, previous: Option[T] = None)
    (implicit eventBus: EventBus, ec: ExecutionContext, logger: Logger): Future[T] = {
    dbFuture.flatMap { _ =>
      val correlationId = CorrelationId(UUID.randomUUID().toString)
      val event = eventFactory(model, previous, correlationId, timestamp)
      
      eventBus.publish(event).map(_ => model).recover {
        case ex => 
          logger.error(ex, s"Failed to publish ${event.getClass.getSimpleName} event")
          model
      }
    }
  }
  
  /**
   * Publishes a deleted event and returns the model
   */
  def publishingDeleted(eventFactory: (T, CorrelationId, Timestamp) => Event)
    (implicit eventBus: EventBus, ec: ExecutionContext, logger: Logger): Future[T] = {
    dbFuture.flatMap { _ =>
      val correlationId = CorrelationId(UUID.randomUUID().toString)
      val event = eventFactory(model, correlationId, timestamp)
      
      eventBus.publish(event).map(_ => model).recover {
        case ex => 
          logger.error(ex, s"Failed to publish ${event.getClass.getSimpleName} event")
          model
      }
    }
  }
  
  /**
   * Publishes a custom event and returns the model
   */
  def publishingCustom(event: Event)
    (implicit eventBus: EventBus, ec: ExecutionContext, logger: Logger): Future[T] = {
    dbFuture.flatMap { _ =>
      eventBus.publish(event).map(_ => model).recover {
        case ex => 
          logger.error(ex, s"Failed to publish ${event.getClass.getSimpleName} event")
          model
      }
    }
  }
  
  /**
   * For operations that should not publish events (e.g., intermediate operations)
   * Use with caution - prefer the publishing methods
   */
  def withoutEvent(implicit ec: ExecutionContext): Future[T] = {
    dbFuture.map(_ => model)
  }
}

object EventRequiredResult {
  def apply[T](dbFuture: Future[_], model: T, timestamp: Timestamp): EventRequiredResult[T] =
    new EventRequiredResult(dbFuture, model, timestamp)
    
  def apply[T](dbFuture: Future[_], model: T): EventRequiredResult[T] =
    new EventRequiredResult(dbFuture, model, TimeUtil.nowTimestamp())
}

/**
 * Helper trait for repositories to distinguish between read and write operations
 */
trait RepositoryHelpers {
  
  /**
   * For read operations - just returns the Future directly
   */
  protected def read[T](dbOp: => Future[T]): Future[T] = dbOp
  
  /**
   * For write operations - returns EventRequiredResult that enforces event publishing
   */
  protected def write[T](model: T)(dbOp: => Future[_]): EventRequiredResult[T] = {
    EventRequiredResult(dbOp, model, TimeUtil.nowTimestamp())
  }
  
  /**
   * For write operations with explicit timestamp
   */
  protected def writeWithTimestamp[T](model: T, timestamp: Timestamp)(dbOp: => Future[_]): EventRequiredResult[T] = {
    EventRequiredResult(dbOp, model, timestamp)
  }
}