package escalator.util

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

import escalator.models.Timestamp

class TimestampProvider(implicit clock: Clock) {
  val lastTimestamp = new AtomicLong(0L)

  def uniqueNow(): Timestamp = {
    val now = clock.millis() * 1000000L
    val last = lastTimestamp.get()

    if (now > last && lastTimestamp.compareAndSet(last, now))
      Timestamp(now)
    else
      Timestamp(lastTimestamp.incrementAndGet())
  }
}
