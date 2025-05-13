package escalator.util

// import scala.scalajs.js.Date

// import escalator.util.Timestamp

import escalator.util.logging.GlobalLogger
import java.text.SimpleDateFormat

object DateUtil {
  // implicit class RichDate(date: Date) {
  //   def toTimestamp: Timestamp = Timestamp(date.getTime().toLong * 1000000L)
  // }

  // def fromTimestamp(timestamp: Timestamp): java.util.Date = {
  //   new java.util.Date((timestamp.nanos / 1000000L).toLong)
  // }

  def parseDate(date: String): java.util.Date = {
    try {
      // "15/05/2022"
      val formatter = if (date.contains("/")) {
        new SimpleDateFormat("dd/MM/yyyy")
      } else {
        new SimpleDateFormat("yyyy-MM-dd")
      }
      formatter.parse(date)
    } catch {
      case ex: java.text.ParseException =>
        GlobalLogger.error(ex)
        throw ex
    }
  }

}
