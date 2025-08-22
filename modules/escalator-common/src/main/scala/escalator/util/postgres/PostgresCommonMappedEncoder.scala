package escalator.util.postgres

import escalator.util.Timestamp

import io.getquill._
import escalator.util.DateTimeConverter

import io.getquill.MappedEncoding

trait PostgresCommonMappedEncoder {

	def dateToTimestamp(dt: java.time.LocalDateTime): Timestamp = {
		Timestamp( DateTimeConverter.localDateTimeToEpochNano(dt) )
	}

	def timestampToDate(t: Timestamp): java.time.LocalDateTime = {
		DateTimeConverter.epochNanoToLocalDateTime( t.nanos )
	}

	implicit val encodeTimestamp = MappedEncoding[java.time.LocalDateTime, Timestamp]( dateToTimestamp )
	implicit val decodeTimestamp = MappedEncoding[Timestamp, java.time.LocalDateTime]( timestampToDate )

}