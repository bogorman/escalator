package escalator.util

import scala.concurrent.duration._

/**
 * A Timestamp is internally nanoseconds since epoch.
 *
 * DO NOT call `Timestamp(...)` directly — the apply/constructor is locked down
 * to prevent the common bug of passing a value in the wrong unit (millis
 * treated as nanos, seconds treated as millis, …). Use one of the named
 * helpers which make the input unit explicit:
 *
 *   - [[Timestamp.fromNanos]]   — input is already nanoseconds
 *   - [[Timestamp.fromMillis]]  — input is milliseconds since epoch
 *   - [[Timestamp.fromSeconds]] — input is Unix seconds
 *   - [[Timestamp.now]]         — wall-clock now
 */
case class Timestamp private (nanos: Long) extends AnyVal {
  def isAfter(other: Timestamp): Boolean = this > other

  def after(other: Timestamp): Boolean = this > other

  def <(other: Timestamp): Boolean = nanos < other.nanos
  def <=(other: Timestamp): Boolean = nanos <= other.nanos
  def >(other: Timestamp): Boolean = nanos > other.nanos
  def >=(other: Timestamp): Boolean = nanos >= other.nanos

  def +(duration: FiniteDuration): Timestamp = Timestamp.fromNanos(nanos + duration.toNanos)

  def -(duration: FiniteDuration): Timestamp = Timestamp.fromNanos(nanos - duration.toNanos)

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

  /**
   * Private factory — not callable from outside this file. This shadows the
   * case class's auto-generated `apply` so external code must go through one
   * of the named helpers below, forcing the unit to be explicit.
   */
  private def apply(nanos: Long): Timestamp = new Timestamp(nanos)

  /** Build a Timestamp from a value already in nanoseconds. */
  def fromNanos(nanos: Long): Timestamp = apply(nanos)

  /** Build a Timestamp from milliseconds since epoch. */
  def fromMillis(ms: Long): Timestamp = apply(ms * 1000000L)

  /** Build a Timestamp from Unix seconds (common API format). */
  def fromSeconds(seconds: Long): Timestamp = apply(seconds * 1000000000L)

  /** Wall-clock now. */
  def now(): Timestamp = fromMillis(System.currentTimeMillis())

  // Circe codec lives in the companion so any code deriving codecs for case
  // classes containing a Timestamp field picks it up automatically — without
  // this, `io.circe.generic.auto._` would try to derive a codec against the
  // private constructor and fail. Same JSON shape as the old derived codec
  // ({"nanos": <long>}) so the wire format is unchanged.
  implicit val circeCodec: io.circe.Codec.AsObject[Timestamp] = io.circe.Codec.AsObject.from(
    io.circe.Decoder.forProduct1("nanos")(Timestamp.fromNanos _),
    io.circe.Encoder.forProduct1[Timestamp, Long]("nanos")(_.nanos)
  )
}
