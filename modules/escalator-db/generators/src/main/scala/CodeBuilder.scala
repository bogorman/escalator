package escalator.db.generators

object CodeBuilder {

	def buildUpsertCode(table: Table, key: UniqueKey, modelClass: String): String = {
		// table.uniqueKeysExcludingPrimaryKey
		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass = table.primaryKeyClass //s"${modelClass}Id"
		val initial = modelClass.take(1).toLowerCase

		val uniqueKeyCols = key.cols.map { scol =>
		  namingStrategy.column(scol.columnName)
		}.toList

		val tableCols = table.columns.map { col =>
		  namingStrategy.column(col.columnName)
		}.toList

		val conflictCols = tableCols diff uniqueKeyCols diff  List("id", "createdAt")

		println("uniqueKeyCols")
		println(uniqueKeyCols)

		println("tableCols")
		println(tableCols)

		println("conflictCols")
		println(conflictCols)

		val monitorKey = key.cols.map( _.columnName.toLowerCase ).mkString("-")
		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		val conflictArgs = key.cols.map( c => "_."+namingStrategy.column(c.columnName) ).mkString(",")

		val updateArgs = conflictCols.map( c => s"_.${c} -> _.${c}" ).mkString(",\n")

		// val includeCreatedAt = 
		// val includeUpdatedAt = 	

		s"""
		  override def upsertOn${functionName}(${initial}: ${modelClass}): Future[${primaryKeyClass}] = monitored("upsert-${monitorKey}") {
		    val ts = TimeUtil.nowTimestamp()
		    ctx.run(
		      query[${modelClass}]
		        .insert(lift(${initial}.copy(createdAt = ts, updatedAt = ts) ))
		        .onConflictUpdate(${conflictArgs})(
		        	${updateArgs}
		        )
		        .returningGenerated(_.id)
		    ).runToFuture
		  }
		"""
	}

	def buildUniqueExistanceCode(table: Table, key: UniqueKey, modelClass: String): String = {
		// table.uniqueKeysExcludingPrimaryKey
		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass = table.primaryKeyClass //s"${modelClass}Id"
		val initial = modelClass.take(1).toLowerCase

		val uniqueKeyCols = key.cols.map { scol =>
		  namingStrategy.column(scol.columnName)
		}.toList

		val tableCols = table.columns.map { col =>
		  namingStrategy.column(col.columnName)
		}.toList

		val conflictCols = tableCols diff uniqueKeyCols diff  List("id", "createdAt")

		// println("uniqueKeyCols")
		// println(uniqueKeyCols)

		// println("tableCols")
		// println(tableCols)

		// println("conflictCols")
		// println(conflictCols)

		val monitorKey = key.cols.map( _.columnName.toLowerCase ).mkString("-")
		val functionName = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

		// val conflictArgs = key.cols.map( c => "_."+namingStrategy.column(c.columnName) ).mkString(",")

		val filterArgs = key.cols.map { sc => 
			val c = table.findColumn(sc.columnName)
			// val attrName = camelify(c.columnName) 
			val attrName = namingStrategy.column(c.columnName)
			val fc = c.fix(true,attrName)
			s"row.${attrName} == lift(${initial}.${fc})"
		}.mkString(" &&\n")		

		// val updateArgs = conflictCols.map( c => s"_.${c} -> _.${c}" ).mkString(",\n")

		// val includeCreatedAt = 
		// val includeUpdatedAt = 	

		// ${initial} =>

		s"""
		  override def existsOn${functionName}(${initial}: ${modelClass}): Future[Boolean] = monitored("exists-${monitorKey}") {
		    val ts = TimeUtil.nowTimestamp()
		    ctx.run(
		      query[${modelClass}]
				.filter( row =>
					${filterArgs}
				)
		      	.nonEmpty
		    ).runToFuture
		  }
		"""
	}	

	def buildUpdateCodeById(table: Table, columns: List[Column], modelClass: String) = {
		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass = table.primaryKeyClass //s"${modelClass}Id"
		val initial = modelClass.take(1).toLowerCase

		// val caseCol = namingStrategy.column(col.columnName)

		val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// make function param list from columns

		val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		val updateArgs = columns.map { col => 
			val c = namingStrategy.column(col.columnName)
			val fc = col.fix(true,c)
			s"_.${c} -> lift(${fc})"
		}.mkString(",\n")

		s"""
		  override def update${functionName}ById(${initial}: ${primaryKeyClass}, ${functionArgs}): Future[${primaryKeyClass}] = monitored("update-${monitorKey}-by-id") {
		    val ts = TimeUtil.nowTimestamp()
		    ctx.run(
		      query[${modelClass}]
		  				.filter(_.id == lift(${initial}))
		  				.update(
		  					${updateArgs},
		  					_.updatedAt -> lift(ts)
		  				)    
		  				.returning(_.id)
		    ).runToFuture
		  }
		"""
	}

	def buildUpdateCodeByUniqueKey(table: Table, key: UniqueKey, columns: List[Column], modelClass: String) = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val keyNames = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val keyArgs = key.cols.map{ sc => 
			val c = table.findColumn(sc.columnName)
			s"${c.toArg(namingStrategy,table.name,true,true)}"
		}.mkString(",")
	
		val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		val functionMonitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")
		val byMonitorKey = key.cols.map( _.columnName.toLowerCase ).mkString("-")

		val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		//
		val filterArgs = key.cols.map { sc => 
			val c = table.findColumn(sc.columnName)
			// val attrName = camelify(c.columnName) 
			val attrName = namingStrategy.column(c.columnName)
			val fc = c.fix(true,attrName)
			s"${initial}.${attrName} == lift(${fc})"
		}.mkString(" &&\n")

		//
		val updateArgs = columns.map { col => 
			val c = namingStrategy.column(col.columnName)		
			val fc = col.fix(true,c)
			s"_.${c} -> lift(${fc})"
		}.mkString(",\n")

		s"""
			override def update${functionName}By${keyNames}(${keyArgs}, ${functionArgs}): Future[_] = monitored("update-${functionMonitorKey}-by-${byMonitorKey}") {
		    val ts = TimeUtil.nowTimestamp()
		   	ctx.run(
		   	  query[${modelClass}]
		  					.filter( ${initial} =>
		  						${filterArgs}
		  					)
		  					.update(
		  						${updateArgs},
		  						_.updatedAt -> lift(ts)
		  					)    
		   	).runToFuture
		  }
		"""
	}

	def buildGetterCodeByUniqueKey(table: Table, key: UniqueKey, modelClass: String) = {
		val namingStrategy = GeneratorNamingStrategy

		val initial = modelClass.take(1).toLowerCase

		val keyNames = key.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")
		val keyArgs = key.cols.map{ sc => 
			val c = table.findColumn(sc.columnName)
			s"${c.toArg(namingStrategy,table.name,true,true)}"
		}.mkString(",")
	
		// val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		// val functionMonitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")
		val byMonitorKey = key.cols.map( _.columnName.toLowerCase ).mkString("-")

		// val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		//
		val filterArgs = key.cols.map { sc => 
			val c = table.findColumn(sc.columnName)
			// val attrName = camelify(c.columnName) 
			val attrName = namingStrategy.column(c.columnName)
			val fc = c.fix(true,attrName)
			s"${initial}.${attrName} == lift(${fc})"
		}.mkString(" &&\n")

		// val primaryKeyClass = table.primaryKeyClass

		//
		// val updateArgs = columns.map { col => 
		// 	val c = namingStrategy.column(col.columnName)		
		// 	val fc = col.fix(true,c)
		// 	s"_.${c} -> lift(${fc})"
		// }.mkString(",\n")

		// def getBy${keyNames}(${keyArgs}): Future[_]
		s"""
			override def getBy${keyNames}(${keyArgs}): Future[Option[${modelClass}]] = monitored("get-by-${byMonitorKey}") {
		    val ts = TimeUtil.nowTimestamp()
		   	ctx.run(
		   	  query[${modelClass}]
					.filter( ${initial} =>
						${filterArgs}
					)
                  .take(1)
            	).runToFuture.map(_.headOption)
		  	}
		"""
	}

	def buildStoreCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		""
		// s"""
		//   override def store(): Future[${primaryKeyClass}] = monitored("store") {
		//   	val ts = TimeUtil.nowTimestamp()

		//   	val ${initial} = 

		//     ctx.run(
		//       query[${modelClass}]
		//         .insert(lift(${initial}.copy(createdAt = ts, updatedAt = ts)))
		//         .returningGenerated(_.id)
		//     ).runToFuture
		//   }

		// """				
	}

	def buildUpsertsCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeUpsert = table.hasUniqueKeysExcludingPrimaryKey()
		val upsert1 = if (includeUpsert) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val ukCodeStr = uKeys.map { uk => buildUpsertCode(table, uk, modelClass) }.mkString("\n")

			ukCodeStr
		} else {
			""
		}

		val upsert2 = if (table.inheritedFromTable.isDefined){
			println("table.inheritedFromTable.isDefined:" + tableName)

			val it = table.inheritedFromTable.get
			if (it.hasUniqueKeysExcludingPrimaryKey()){
				val uKeys2 = it.uniqueKeysExcludingPrimaryKey
				uKeys2.map { uk => buildUpsertCode(table, uk, modelClass) }.mkString("\n")	
			} else {
				""
			}

		} else {
			""
		}	
		// val upsert2 = ""	

		upsert1 + upsert2
	}


	def buildUniqueCheckDefn(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		// ""
		// if (table.name != "users"){
		// 	return ""
		// }


		val includeUpsert = table.hasUniqueKeysExcludingPrimaryKey()
		val check1 = if (includeUpsert) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val ukCodeStr = uKeys.map { uk => buildUniqueExistanceCode(table, uk, modelClass) }.mkString("\n")

			ukCodeStr
		} else {
			""
		}		
		check1
	}	

	def buildUpdatesByIdCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeUpdatesById = table.hasIdColumn()
		
		val updates = if (includeUpdatesById) {
			val nonKeyColumns = table.nonKeyColumns
			nonKeyColumns.map { col => buildUpdateCodeById(table, List(col), modelClass) }.mkString("\n")
		} else {
			""
		}
		updates		
	}

	def buildUpdatesByUniqueKeysCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeUpdatesByKey = table.hasUniqueKeysExcludingPrimaryKey()

		val updates = if (includeUpdatesByKey) {
			// ""
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val nonKeyColumns = table.nonKeyColumns

			uKeys.map { uk =>
				nonKeyColumns.map { col =>
					if (!uk.containsColumn(col.columnName)) {
						buildUpdateCodeByUniqueKey(table, uk, List(col), modelClass)
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

	def buildGettersByUniqueKeysCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val includeGettersByKey = table.hasUniqueKeysExcludingPrimaryKey()

		val getters = if (includeGettersByKey) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val nonKeyColumns = table.nonKeyColumns

			uKeys.map { uk =>
				buildGetterCodeByUniqueKey(table, uk, modelClass)
			}.mkString("\n")
		} else {
			""
		}
		getters
	}	


}