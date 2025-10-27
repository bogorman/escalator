package escalator.db.generators

import scala.collection.mutable.{Map => MMap}

object DefnBuilder {
	
	var tablesLookup: MMap[String, Table] = MMap.empty
	
	def setTablesLookup(tables: MMap[String, Table]): Unit = {
		tablesLookup = tables
	}

	def extractClassName(fullType: String): String = {
		if (fullType.contains(".")) {
			fullType.split("\\.").last
		} else {
			fullType
		}
	}

	def buildUpsertDefn(table: Table, key: String, modelClass: String): String = {
		// val namingStrategy = GeneratorNamingStrategy

		// val initial = modelClass.take(1).toLowerCase

		// val keyCols = List(key)		

		// val functionName = keyCols.map( c => namingStrategy.table(c) ).mkString("")

		// s"""
		//   def upsert${functionName}(${initial}: ${modelClass}): Future[_]
		// """

		""
	}

	def buildUpsertOnDefn(table: Table, key: UniqueKey, modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		s"""
		  def upsertOn${functionName}(${initial}: ${modelClass}): Future[${modelClass}]
		"""
	}

	def buildMergeOnDefn(table: Table, key: UniqueKey, modelClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		s"""
		  def mergeOn${functionName}(${initial}: ${modelClass}): Future[${modelClass}]
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
		// val namingStrategy = GeneratorNamingStrategy

		// val primaryKeyClass: Option[String] = table.primaryKeyClass	// s"${modelClass}Id"
		// if (primaryKeyClass.isEmpty){
		// 	return ""
		// }

		// val initial = modelClass.take(1).toLowerCase
		// val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")

		// s"""
		//   def update${functionName}ById(${initial}: ${modelClass}): Future[${modelClass}]
		// """

		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass: Option[String] = table.primaryKeyClass	// s"${modelClass}Id"
		if (primaryKeyClass.isEmpty){
			return ""
		}

		val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// make function param list from columns

		val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		// modelClass
		s"""
		  def update${functionName}ById(id: ${primaryKeyClass.get}, ${functionArgs}): Future[${modelClass}]
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
			// println("table.inheritedFromTable.isDefined:" + tableName)

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

	def buildMergesDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		// val includeMergeOn = table.hasUniqueKeysExcludingPrimaryKey()
		// val merge1 = if (includeMergeOn) {
		// 	val uKeys = table.uniqueKeysExcludingPrimaryKey
		// 	uKeys.map { uk => buildMergeOnDefn(table, uk, modelClass) }.mkString("\n")
		// } else {
		// 	""
		// }

		// val merge2 = if (table.inheritedFromTable.isDefined){
		// 	val it = table.inheritedFromTable.get
		// 	if (it.hasUniqueKeysExcludingPrimaryKey()){
		// 		val uKeys2 = it.uniqueKeysExcludingPrimaryKey
		// 		uKeys2.map { uk => buildMergeOnDefn(table, uk, modelClass) }.mkString("\n")
		// 	} else {
		// 		""
		// 	}
		// } else {
		// 	""
		// }

		// merge1 + merge2

		""
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
		// ""
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
		// ""
	}


	def buildGettersByUniqueKeysDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeGettersByKey = table.hasUniqueKeysExcludingPrimaryKey()

		val getters = if (includeGettersByKey) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val nonKeyColumns = table.nonKeyColumns

			// println("buildGettersByUniqueKeysDefn: " + table.name)
			println(uKeys)

			uKeys.map { uk =>
				buildGetterDefnByUniqueKey(table, uk, modelClass)
			}.mkString("\n")
		} else {
			""
		}
		getters
	}
	
	def buildGettersByForeignKeysDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy
		// println("buildGettersByForeignKeysDefn " + table.name)

		// Find columns that are foreign keys (reference other tables)
		val foreignKeyColumns = table.tableColumns.filter(col => col.references.isDefined)
		
		if (foreignKeyColumns.isEmpty) {
			""
		} else {
			foreignKeyColumns.map { col =>
				val foreignTable = col.references.get.tableName

				val foreignKeyType = if (col.columnName.endsWith("_id")) {
					// This is a foreign key column, use the proper ID type
					namingStrategy.table(foreignTable) + "Id"
				} else {
					// This column references another table but isn't a typical _id column
					// Look up the referenced table and get the actual column type
					val referencedColumnName = col.references.get.columnName
					tablesLookup.get(foreignTable) match {
						case Some(referencedTable) =>
							val referencedColumn = referencedTable.findColumn(referencedColumnName)
							if (referencedTable.name == "attributes") {
								col.toDefn(col.tableName, true)
							} else {							
								// println("foreignTable found. " + referencedTable.name)
								// println("foreignTable referencedColumnName:" + referencedColumnName)
								// println("foreignTable referencedColumn. " + referencedColumn.scalaType)

								// println("foreignTable referencedColumn. " + referencedColumn.toDefn(referencedColumn.tableName, true)) 
								// println("foreignTable referencedColumn. " + referencedColumn.toArg(namingStrategy,referencedColumn.tableName,true,true)) 

								// extractClassName(referencedColumn.scalaType)
								referencedColumn.toDefn(referencedColumn.tableName, true)
							}
						case None =>
							println("foreignTable fallback")
							// Fallback to the original approach if table not found
							extractClassName(col.scalaType)
					}
				}


				val methodName = s"getListBy${namingStrategy.table(col.columnName).capitalize}"
				val bulkMethodName = s"getListBy${namingStrategy.table(col.columnName).capitalize}s"
				val paramName = namingStrategy.column(col.columnName)

				val c = table.findColumn(col.columnName)
				// println("XXXXXXXX " + col.columnName + " -> " + foreignTable)
				// println(foreignKeyType)
				// println(col.scalaType)
				// println(paramName)
				// println(s"${c.toArg(namingStrategy,table.name,true,true)}")
				
				// val keyArgs = key.cols.map{ sc => 
				// 	val c = table.findColumn(sc.columnName)
				// 	s"${c.toArg(namingStrategy,table.name,true,true)}"
				// }.mkString(",")
				
				// For getBy methods, we don't wrap nullable columns in Option - the method handles nulls internally
				val singleMethod = s"  def ${methodName}(${paramName}: ${foreignKeyType}): Future[List[${modelClass}]]"
				
				val bulkMethod = s"  def ${bulkMethodName}(${paramName}s: List[${foreignKeyType}]): Future[List[${modelClass}]]"
				
				singleMethod + "\n\n" + bulkMethod
			}.mkString("\n")
		}
	}


}