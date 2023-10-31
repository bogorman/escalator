package escalator.db.generators

object TypeMapper {

  val defaultMappings = Map(
    "text" -> "String",
    "float8" -> "Double",
    "numeric" -> "BigDecimal",
    "int4" -> "Int",
    "int8" -> "Long",
    "bool" -> "Boolean",
    "varchar" -> "String",
    "serial" -> "Int",
    "bigserial" -> "Long",
    // "timestamp" -> "java.util.Date",
    "bytea" -> "Array[Byte]", // PostgreSQL
    "uuid" -> "java.util.UUID", // H2, PostgreSQL
    // "json" -> "io.circe.Json", // PostgreSQL
    "json" -> "String", 
    "_varchar" -> "List[String]", //?????
    "Vector(_varchar)" -> "List[String]",
    "timestamp" -> "escalator.models.Timestamp" 
  )

  // val customMappings = Map()

  def mappings(customGen: CustomGenerator): Map[String,String] = {
    defaultMappings ++ customGen.customMappings
  }

}

