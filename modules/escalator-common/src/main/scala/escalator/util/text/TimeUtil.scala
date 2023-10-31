package escalator.util

// import org.joda.time.{ DateTimeZone, DateTime }
// import org.joda.time.format.DateTimeFormat

import java.text._

import java.time.{Instant, LocalDateTime, ZoneOffset, ZonedDateTime}
import java.time.Clock

import escalator.models.Timestamp

object TimeUtil {

  def nowMs: Long = {
    // new DateTime().getMillis
    System.currentTimeMillis
  }

  def nowDate(): java.util.Date = {
    new java.util.Date();
  }

  def nowString = {
    // val fmt = DateTimeFormat.forPattern("dd-MM-yy HH:mm:ss:SSS").withZone(DateTimeZone.UTC)
    // return format.format(d);
    // fmt.print(nowMs)

    val format = new SimpleDateFormat("dd-MM-yy HH:mm:ss:SSS")
    format.format(nowDate())

  }

  // def toNano() = {
  //   // Instant

  //   val instant = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant();
  //   long epochNanos = TimeUnit.NANOSECONDS.convert(instant.getEpochSecond(), TimeUnit.SECONDS);
  //   epochNanos += instant.getNano();

  // }

  // def localDateTimeToEpochMilli(localDateTime: LocalDateTime): Long = {
  //   localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli
  // }  

  def nowTimestamp(): Timestamp = {
    val now = Clock.systemUTC().millis() * 1000000L
    Timestamp(now)
  }  

}