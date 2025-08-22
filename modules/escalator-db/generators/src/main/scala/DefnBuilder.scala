package escalator.db.generators

object DefnBuilder {

	def buildUpsertDefn(table: Table, key: String, modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val keyCols = List(key)		

		val functionName = keyCols.map( c => namingStrategy.table(c) ).mkString("")

		s"""
		  def upsert${functionName}(${initial}: ${modelClass}): Future[_]
		"""
	}

	def buildUpsertOnDefn(table: Table, key: UniqueKey, modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		s"""
		  def upsertOn${functionName}(${initial}: ${modelClass}): Future[_]
		"""
	}


	def buildUniqueExistanceDefn(table: Table, key: UniqueKey, modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		s"""
		  def existsOn${functionName}(${initial}: ${modelClass}): Future[Boolean]
		"""
	}


	def buildUpdateDefnById(table: Table, columns: List[Column], modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass: Option[String] = table.primaryKeyClass	// s"${modelClass}Id"
		if (primaryKeyClass.isEmpty){
			return ""
		}

		val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// make function param list from columns

		val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		s"""
		  def update${functionName}ById(id: ${primaryKeyClass.get}, ${functionArgs}): Future[${primaryKeyClass.get}]
		"""
	}

	def buildUpdateDefnByUniqueKey(table: Table, key: UniqueKey, columns: List[Column], modelClass: String) = {
		val namingStrategy = GeneratorNamingStrategy

		val keyNames = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val keyArgs = key.cols.map{ sc => 
			val c = table.findColumn(sc.columnName)
			s"${c.toArg(namingStrategy,table.name,true)}"
		}.mkString(",")
	
		val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		s"""
				def update${functionName}By${keyNames}(${keyArgs}, ${functionArgs}): Future[_]
		"""
	}

	def buildGetterDefnByUniqueKey(table: Table, key: UniqueKey, modelClass: String) = {
		val namingStrategy = GeneratorNamingStrategy

		val keyNames = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val keyArgs = key.cols.map{ sc => 
			val c = table.findColumn(sc.columnName)
			s"${c.toArg(namingStrategy,table.name,true)}"
		}.mkString(",")
	
		// val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")
		// , ${functionArgs}

		// val primaryKeyClass: Option[String] = table.primaryKeyClass

		s"""
				def getBy${keyNames}(${keyArgs}): Future[Option[${modelClass}]]
		"""
	}

	// def buildStoreDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
	// 	""
	// }

	def buildUpsertsDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		//if primary id is uuid then allow upsert

		val upsert1 = buildUpsertDefn(table, "id", modelClass)

		val includeUpsertOn = table.hasUniqueKeysExcludingPrimaryKey()
		val upsert2 = if (includeUpsertOn) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			uKeys.map { uk => buildUpsertOnDefn(table, uk, modelClass) }.mkString("\n")
		} else {
			""
		}

		val upsert3 = if (table.inheritedFromTable.isDefined){
			println("table.inheritedFromTable.isDefined:" + tableName)

			val it = table.inheritedFromTable.get
			if (it.hasUniqueKeysExcludingPrimaryKey()){
				val uKeys2 = it.uniqueKeysExcludingPrimaryKey
				uKeys2.map { uk => buildUpsertOnDefn(table, uk, modelClass) }.mkString("\n")	
			} else {
				""
			}

		} else {
			""
		}		
		// val upsert2 = ""

		upsert1 + upsert2 + upsert3
	}

	def buildUniqueCheckDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		// if (table.name != "users"){
		// 	return ""
		// }

		val includeUpsert = table.hasUniqueKeysExcludingPrimaryKey()
		val check1 = if (includeUpsert) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			uKeys.map { uk => buildUniqueExistanceDefn(table, uk, modelClass) }.mkString("\n")
		} else {
			""
		}

		check1
	}

	def buildUpdatesByIdDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeUpdatesById = table.hasIdColumn()
		
		val updates = if (includeUpdatesById) {
			val nonKeyColumns = table.nonKeyColumns
			nonKeyColumns.map { col => buildUpdateDefnById(table, List(col), modelClass) }.mkString("\n")
		} else {
			""
		}
		updates		
	}


	def buildUpdatesByUniqueKeysDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeUpdatesByKey = table.hasUniqueKeysExcludingPrimaryKey()

		val updates = if (includeUpdatesByKey) {
			// ""
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val nonKeyColumns = table.nonKeyColumns

			uKeys.map { uk =>
				nonKeyColumns.map { col =>
					if (!uk.containsColumn(col.columnName)) {
						buildUpdateDefnByUniqueKey(table, uk, List(col), modelClass)
					} else {
						""
					}
				}.mkString("\n")
			}.mkString("\n")
		} else {
			""
		}
		updates
	}


	def buildGettersByUniqueKeysDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeGettersByKey = table.hasUniqueKeysExcludingPrimaryKey()

		val getters = if (includeGettersByKey) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val nonKeyColumns = table.nonKeyColumns

			println("buildGettersByUniqueKeysDefn: " + table.name)
			println(uKeys)

			uKeys.map { uk =>
				buildGetterDefnByUniqueKey(table, uk, modelClass)
			}.mkString("\n")
		} else {
			""
		}
		getters
	}


}