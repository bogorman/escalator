package escalator.common.ddd.query.common

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ Actor, PoisonPill }
import org.apache.pekko.pattern.pipe
import org.apache.pekko.persistence.postgres.query.scaladsl.PostgresReadJournal
import org.apache.pekko.persistence.query.{ EventEnvelope, PersistenceQuery }
import org.apache.pekko.stream.Supervision
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }

import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

object ViewBuilderActor {
  type Action[O] = () => Future[O]

  case class EnvelopeAndFunction(env:   PersistedEventEnvelope, action: Action[_])
  case class LatestOffsetResult(offset: Long)
}

case class PersistedEventEnvelope(offset: Long, persistenceId: String, event: PersistentEvent)

abstract class ViewBuilderActor extends Actor with LazyLogging {
  import ViewBuilderActor._
  import context.dispatcher
  import context.system

  val decider: Supervision.Decider = {
    case NonFatal(ex) =>
      logger.error(s"Got non-fatal exception in ViewBuilder $identifier flow", ex)
      Supervision.Resume
    case ex =>
      logger.error(s"Got fatal exception in ViewBuilder $identifier flow, stream will be stopped", ex)
      Supervision.Stop
  }

  // val resumableProjection = ResumableProjection(identifier, context.system)
  def resumableProjection: ResumableProjection

  val eventsFlow: Flow[EventEnvelope, Unit, NotUsed] =
    Flow[EventEnvelope]
      .collect {
        case EventEnvelope(_, persistenceId, sequenceNr, event: PersistentEvent) =>
          PersistedEventEnvelope(sequenceNr, persistenceId, event)
        case x =>
          throw new RuntimeException(s"Invalid event in the journal! $x")
      }
      .map(env => EnvelopeAndFunction(env, actionFor(env)))
      .mapAsync(parallelism = 1) { case EnvelopeAndFunction(env, f) => f.apply().map(_ => env) }
      .mapAsync(parallelism = 1)(env => resumableProjection.updateOffset(identifier, env.offset).map(_ => ()))

  val journal: PostgresReadJournal =
    PersistenceQuery(context.system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)

  self ! "start"

  def persistenceId: String

  def identifier: String

  def actionFor(env: PersistedEventEnvelope): Action[_]

  def receive: PartialFunction[Any, Unit] = {
    case "start" =>
      resumableProjection.fetchLatestOffset(identifier).map(LatestOffsetResult).pipeTo(self)
    case LatestOffsetResult(offset) =>
      logger.info(s"Starting up view builder for entity $identifier, with persistenceId $persistenceId with offset of $offset")
      val eventsSource: Source[EventEnvelope, NotUsed] =
        journal.eventsByPersistenceId(persistenceId, offset, Long.MaxValue)

      eventsSource
        .via(eventsFlow)
        .runWith(Sink.ignore)
        .onComplete {
          case Failure(err) =>
            logger.error(s"Persistence query $identifier ended with failure: ${err.getMessage}")
            self ! PoisonPill
          case Success(_) =>
            logger.info(s"Persistence query $identifier ended successfully")
            context.system.scheduler.scheduleOnce(1.second, self, "start")
        }
    case x =>
      logger.error(s"Failed to obtain offset for $identifier, got this message ${x.toString}")
      self ! PoisonPill
  }

}
