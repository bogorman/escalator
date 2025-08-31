package escalator.db.generators

trait CustomGenerator {
	def setup(): Boolean

	def processFileData(fileData: String): String

	def customTypes(): List[String]

	def customMappers(tableClass: String): String

	def customMappings(): Map[String,String]

	def useDefaultValue(tableName: String,columnName: String): Boolean

	// Event generation customization
	def shouldGenerateEvents(tableName: String): Boolean = true

	def customEventMetadata(tableName: String): List[String] = List.empty
	
	// AttributeType column mappings: Map[tableName.columnName -> AttributeTypeName]
	def columnAttributeTypeMappings(): Map[String, String] // = Map.empty
}