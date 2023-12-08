package escalator.db.generators

trait CustomGenerator {
	def setup(): Boolean

	def processFileData(fileData: String): String

	def customMappers(tableClass: String): String

	def customMappings(): Map[String,String]

	def useDefaultValue(tableName: String,columnName: String): Boolean
}