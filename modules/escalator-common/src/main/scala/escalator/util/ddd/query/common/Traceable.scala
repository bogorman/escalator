package escalator.common.ddd.query.common

import java.time.LocalDateTime

import escalator.models.CorrelationId

// import com.ingenuiq.note.common.{ CorrelationId, EventId, UserId }
// import com.ingenuiq.note.utils

// case class CorrelationId(value: String) 

trait Command //extends WithMetadata

trait Event

trait Query //extends WithMetadata

trait PersistentEvent extends Event {
  // def persistentEventMetadata: PersistentEventMetadata
}

trait Traceable {
  def correlationId: CorrelationId
}

// trait WithMetadata {
//   // def userId: UserId
// }

// case class PersistentEventMetadata(userId:        UserId,
//                                    eventId:       EventId = EventId.generateNew,
//                                    created:       LocalDateTime = utils.now(),
//                                    correlationId: CorrelationId = CorrelationId(utils.currentTraceId),
//                                    spanId:        String = utils.currentSpanId)
//     extends WithMetadata


case class PersistenceOffset(id: String, offset: Long)