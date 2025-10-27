package escalator.db.generators

object TypeMapper {

  val defaultMappings = Map(
    "text" -> "String",
    "_text" -> "List[String]",

    "float8" -> "Double",
    "numeric" -> "BigDecimal",
    "int4" -> "Int",
    "_int4" -> "List[Int]",
    "int8" -> "Long",
    "_int8" -> "List[Long]",
    "bool" -> "Boolean",
    "varchar" -> "String",
    "serial" -> "Int",
    "bigserial" -> "Long",
    // "timestamp" -> "java.util.Date",
    "bytea" -> "Array[Byte]", // PostgreSQL
    "uuid" -> "java.util.UUID", // H2, PostgreSQL
    // "json" -> "io.circe.Json", // PostgreSQL
    // handle json properly!! use circe
    "json" -> "io.circe.Json", 
    "jsonb" -> "io.circe.Json", 
    //
    "_varchar" -> "List[String]", //?????
    "Vector(_varchar)" -> "List[String]",
    "timestamp" -> "escalator.util.Timestamp",
    "date" -> "escalator.util.Timestamp",

    //
    "inet" -> "String",

    // pgvector extension types
    "vector" -> "Array[Float]"  // Vector embeddings stored as text: "[0.1,0.2,0.3]" 
  )

  // val customMappings = Map()

  def mappings(customGen: CustomGenerator): Map[String,String] = {
    defaultMappings ++ customGen.customMappings
  }

}

