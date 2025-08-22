package escalator.util.postgres

import scala.concurrent.{ExecutionContext, Future}

import escalator.util.logging.Logger
import escalator.util.monitoring.Monitoring

class MonitoredPostgresOperation(
    operationName: String,
    tableName: String
  )(implicit
    executionContext: ExecutionContext,
    logger: Logger,
    monitoring: Monitoring
  ) {

  private val counter = monitoring.counter(s"postgres_${tableName}_$operationName")
  private val failsCounter = monitoring.counter(s"failed_postgres_${tableName}_$operationName")
  private val histogram = monitoring.histogram(s"postgres_${tableName}_$operationName")

  def apply[T](action: Future[T]): Future[T] = {
    counter.increment()
    val result = histogram.recordF(action)
    result.failed.foreach { e =>
      failsCounter.increment()
      logger.error(e, "Error on persistence operation", "table" -> tableName, "operation" -> operationName)
    }
    result
  }
}

object MonitoredPostgresOperation {
  def apply(
    operationName: String,
    tableName: String
  )(implicit
    executionContext: ExecutionContext,
    logger: Logger,
    monitoring: Monitoring
  ): MonitoredPostgresOperation =
    new MonitoredPostgresOperation(operationName, tableName)
}
