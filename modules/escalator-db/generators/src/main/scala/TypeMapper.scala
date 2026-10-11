package escalator.db.generators

object TypeMapper {

  val UuidList = "List[java.util.UUID]"
  val TsVector = "escalator.util.TsVector"

  /** Scala types Quill can't encode on its own; the generated PostgresDbTypesEncoder adds codecs for those in use. */
  val needsCodec = Set(UuidList, TsVector)

  val defaultMappings = Map(
    "text" -> "String",
    "_text" -> "List[String]",

    "float8" -> "Double",
    "_float8" -> "List[Double]",
    "float4" -> "Float",
    "_float4" -> "List[Float]",
    "numeric" -> "BigDecimal",
    "_numeric" -> "List[BigDecimal]",
    "int2" -> "Short",
    "_int2" -> "List[Short]",
    "int4" -> "Int",
    "_int4" -> "List[Int]",
    "int8" -> "Long",
    "_int8" -> "List[Long]",
    "bool" -> "Boolean",
    "_bool" -> "List[Boolean]",
    "varchar" -> "String",
    "serial" -> "Int",
    "bigserial" -> "Long",
    // "timestamp" -> "java.util.Date",
    "bytea" -> "Array[Byte]", // PostgreSQL
    "uuid" -> "java.util.UUID", // H2, PostgreSQL
    "_uuid" -> UuidList, // Quill has no uuid[] codec: PostgresDbTypesEncoder gets one
    // "json" -> "io.circe.Json", // PostgreSQL
    // handle json properly!! use circe
    "json" -> "io.circe.Json", 
    "jsonb" -> "io.circe.Json", 
    //
    "_varchar" -> "List[String]", //?????
    "Vector(_varchar)" -> "List[String]",
    "timestamp" -> "escalator.util.Timestamp",
    "timestamptz" -> "escalator.util.Timestamp",
    "date" -> "escalator.util.Timestamp",

    //
    "inet" -> "String",

    // full text search: kept as text, codec in PostgresDbTypesEncoder
    "tsvector" -> TsVector,

    // pgvector extension types
    "vector" -> "Array[Float]"  // Vector embeddings stored as text: "[0.1,0.2,0.3]"
  )


  // val customMappings = Map()

  def mappings(customGen: CustomGenerator): Map[String,String] = {
    defaultMappings ++ customGen.customMappings
  }

}
