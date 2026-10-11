package escalator.util

/**
 * A PostgreSQL `tsvector`, held in its text form (`'blade':1A 'runner':2A`).
 *
 * Reading a row and writing it back keeps the vector exactly as it was. Build new
 * vectors in SQL (`to_tsvector('english', ...)`), not by hand: the lexemes depend on
 * the database's text search configuration.
 *
 * Deliberately not an AnyVal: Quill would map a value class to its underlying
 * String and send it as varchar, which PostgreSQL won't put in a tsvector column.
 */
case class TsVector(value: String)

object TsVector {
  val empty: TsVector = TsVector("")

  // In the companion, like Timestamp's, so generated model codecs find it. A plain JSON string.
  implicit val circeCodec: io.circe.Codec[TsVector] =
    io.circe.Codec.from(io.circe.Decoder.decodeString.map(TsVector(_)), io.circe.Encoder.encodeString.contramap(_.value))
}
