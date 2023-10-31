package escalator.models

import scala.concurrent.duration._

case class Timestamp(nanos: Long) extends AnyVal {
  def isAfter(other: Timestamp): Boolean = this > other

  def <(other: Timestamp): Boolean = nanos < other.nanos
  def <=(other: Timestamp): Boolean = nanos <= other.nanos
  def >(other: Timestamp): Boolean = nanos > other.nanos
  def >=(other: Timestamp): Boolean = nanos >= other.nanos

  def +(duration: FiniteDuration): Timestamp = Timestamp(nanos + duration.toNanos)

  def -(duration: FiniteDuration): Timestamp = Timestamp(nanos - duration.toNanos)

  def -(other: Timestamp): FiniteDuration = (nanos - other.nanos).nanos

  def millis(): Long = {
    nanos / 1000000L
  }

  def toMillis(): Long = {
    millis()
  }  
}

object Timestamp {
  implicit val timestampOrdering: Ordering[Timestamp] = Ordering.by(_.nanos)

  def fromMillis(ms: Long) = {
    Timestamp(ms * 1000000L)
  }
}
