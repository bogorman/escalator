package escalator.db.generators

trait CustomGenerator {
	def processFileData(fileData: String): String

	def customMappers(tableClass: String): String

	def customMappings(): Map[String,String]
}