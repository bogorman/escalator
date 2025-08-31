package escalator.db.generators

object CodeBuilder {

	def extractClassName(fullType: String): String = {
		if (fullType.contains(".")) {
			fullType.split("\\.").last
		} else {
			fullType
		}
	}

	def buildUpsertCode(table: Table, key: String, modelClass: String): String = {
		// This is for upsertId which is handled differently in the tableDaoTemplate
		// as the main upsert() method, so we just return empty string
		""
	}	

	def buildUpsertOnCode(table: Table, key: UniqueKey, modelClass: String): String = {
		// table.uniqueKeysExcludingPrimaryKey
		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass: Option[String] = table.primaryKeyClass //s"${modelClass}Id"
		val initial = modelClass.take(1).toLowerCase

		val uniqueKeyCols = key.cols.map { scol =>
		  namingStrategy.column(scol.columnName)
		}.toList

		val tableCols = table.columns.map { col =>
		  namingStrategy.column(col.columnName)
		}.toList

		val conflictCols = tableCols diff uniqueKeyCols diff  List("id", "createdAt")

		val tableCols2 = table.columns.map { col =>
		  col.columnName
		}.toList		

		println("tableCols2")
		println(tableCols2)

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

		val insertUpdateTimeTracking = if (primaryKeyClass.isEmpty){
			"${initial}"
		} else {
			s"${initial}.copy(createdAt = ts, updatedAt = ts)"
		}
		
		val pkFieldForCreated = if (primaryKeyClass.isDefined) {
			val pkField = table.primaryKeyCol.get.toArg(GeneratorNamingStrategy, table.name, true).split(":")(0).trim
			s"${pkField} = cur.${pkField},"
		} else {
			""
		}

		// val returning = tableCols2.map { c =>
		// 	s"r.${c}"
		// }.mkString(",")

		// val returned = tableCols.map { c =>
		// 	s"${c}"
		// }.mkString(",")		

		// .returning(r => (r.id,r.createdAt,infix"xmax = 0".as[Boolean]))

		// s"""
		//   override def upsertOn${functionName}(${initial}: ${modelClass}): Future[${modelClass}] = monitored("upsert-on-${monitorKey}") {
		//     val ts = TimeUtil.nowTimestamp()
		//     val toUpsert = ${insertUpdateTimeTracking}
		    
		//     // Using xmax = 0 to determine if this was an insert (true) or update (false)
		//     ctx.run(
		//       query[${modelClass}]
		//         .insert(lift(toUpsert))
		//         .onConflictUpdate(${conflictArgs})(
		//           ${updateArgs}
		//         )
		//         .returningGenerated(_.id)
		//     ).runToFuture
		//     .flatMap { case (id,createdAt,wasInserted) =>
		//       val result = toUpsert.copy(id = id,createdAt = createdAt)
		//       println("upsert-on-${monitorKey}:" + wasInserted)
		//       println("upsert-on-${monitorKey}:" + result)
		//       if (wasInserted) {
		//         // xmax = 0 means this was an insert
		//         writeWithTimestamp(result, ts)(Future.successful(()))
		//           .publishingCreated((cur, cid, time) => ${modelClass}Created(cur, ${pkFieldForCreated} cid, time))
		//       } else {
		//         // xmax != 0 means this was an update  
		//         writeWithTimestamp(result, ts)(Future.successful(()))
		//           .publishingUpdated((cur, prev, cid, time) => ${modelClass}Updated(cur, prev, ${pkFieldForCreated} cid, time))
		//       }
		//     }
		//   }
		// """


		s"""
		  override def upsertOn${functionName}(${initial}: ${modelClass}): Future[${modelClass}] = monitored("upsert-on-${monitorKey}") {
		    val ts = TimeUtil.nowTimestamp()
		    val toUpsert = ${insertUpdateTimeTracking}
		    
			ctx.transaction {
			 for {
			  // Step 1: perform upsert, return only the generated ID
			  id <- ctx.run(
			  	query[${modelClass}]
			        .insert(lift(toUpsert))
			        .onConflictUpdate(${conflictArgs})(
			          ${updateArgs}
			        )
			        .returningGenerated(_.id)
		    	)

 				tuples: List[(Timestamp, Timestamp)] <- ctx.run(
		          query[${modelClass}]
		            .filter(_.id == lift(id))
		            .map(r => (r.createdAt, r.updatedAt))
		        )

				 _ <- if (tuples.isEmpty) { Task.raiseError(new NoSuchElementException("No row returned after upsert")) }  else Task.unit

		        createdAt = tuples.head._1
		        updatedAt = tuples.head._2
		        wasInserted = createdAt == updatedAt

				result = toUpsert.copy(id = id, createdAt = createdAt, updatedAt = updatedAt)

				_ <- Task.deferFuture {
		               if (wasInserted)
		                 writeWithTimestamp(result, ts)(Future.successful(()))
		                   .publishingCreated((cur, cid, time) => ${modelClass}Created(cur, ${pkFieldForCreated} cid, time))
		               else
		                 writeWithTimestamp(result, ts)(Future.successful(()))
		                   .publishingUpdated((cur, prev, cid, time) => ${modelClass}Updated(cur, prev, ${pkFieldForCreated} cid, time))
		             }
			      } yield result				
		      
		    }.runToFuture
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
		    read {
		      ctx.run(
		        query[${modelClass}]
		          .filter( row =>
		            ${filterArgs}
		          )
		          .nonEmpty
		      ).runToFuture
		    }
		  }
		"""
	}	

	def buildUpdateCodeById(table: Table, columns: List[Column], modelClass: String): String = {
		// val namingStrategy = GeneratorNamingStrategy

		// val primaryKeyClass: Option[String] = table.primaryKeyClass //s"${modelClass}Id"
		// if (primaryKeyClass.isEmpty){
		// 	return ""
		// }

		// val initial = modelClass.take(1).toLowerCase

		// // val caseCol = namingStrategy.column(col.columnName)

		// val monitorKey = columns.map( _.columnName.toLowerCase ).mkString("-")

		// val functionName = columns.map( c => namingStrategy.table(c.columnName) ).mkString("")
		// // make function param list from columns

		// val functionArgs = columns.map( c => s"${c.toArg(namingStrategy,table.name,true,true)}" ).mkString(",")

		// val updateArgs = columns.map { col => 
		// 	val c = namingStrategy.column(col.columnName)
		// 	val fc = col.fix(true,c)
		// 	s"_.${c} -> lift(${fc})"
		// }.mkString(",\n")

		// val updateAt = if (!table.hasColumn("updated_at")) {
		// 	""
		// } else {
		// 	", _.updatedAt -> lift(ts)"
		// }
		
		// val updatedAtCopy = if (!table.hasColumn("updated_at")) {
		// 	""
		// } else {
		// 	".copy(updatedAt = ts)"
		// }
		
		// val pkFieldForCreated = if (primaryKeyClass.isDefined) {
		// 	val pkField = table.primaryKeyCol.get.toArg(GeneratorNamingStrategy, table.name, true).split(":")(0).trim
		// 	s"${pkField} = m.${pkField},"
		// } else {
		// 	""
		// }

		// s"""
		//   override def update${functionName}ById(${initial}: ${modelClass}): Future[${modelClass}] = monitored("update-${monitorKey}-by-id") {
		//     val ts = TimeUtil.nowTimestamp()
		//     val updatedModel = ${initial}${updatedAtCopy}
		    
		//     write(updatedModel) {
		//       ctx.run(
		//         query[${modelClass}]
		//           .filter(_.id == lift(${initial}.id))
		//           .update(lift(updatedModel))
		//           .returning(u => u)
		//       ).runToFuture
		//     }.publishingUpdated((m, prev, cid, time) => ${modelClass}Updated(m, prev, ${pkFieldForCreated} cid, time))
		//   }
		// """


		val namingStrategy = GeneratorNamingStrategy

		val primaryKeyClass: Option[String] = table.primaryKeyClass //s"${modelClass}Id"
		if (primaryKeyClass.isEmpty){
			return ""
		}

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

		val updateAt = if (!table.hasColumn("updated_at")) {
			""
		} else {
			", _.updatedAt -> lift(ts)"
		}

		val copyUpdates = columns.map { col => 
			val c = namingStrategy.column(col.columnName)
			val fc = col.fix(true,c)
			s"${fc} = ${fc}"
		}.mkString(",\n")
		val updatedAtCopy = "updatedAt = ts"

		val pkFieldForCreated = if (primaryKeyClass.isDefined) {
			val pkField = table.primaryKeyCol.get.toArg(GeneratorNamingStrategy, table.name, true).split(":")(0).trim
			s"${pkField} = cur.${pkField},"
		} else {
			""
		}		

		// ${initial}
		// primaryKeyClass.get
		s"""
		  override def update${functionName}ById(id: ${primaryKeyClass.get}, ${functionArgs}): Future[${modelClass}] = monitored("update-${monitorKey}-by-id") {
			val ts = TimeUtil.nowTimestamp()

		    getById(id).flatMap {
		        case Some(currentModel) =>
		          val updatedModel = currentModel.copy(${copyUpdates},${updatedAtCopy})

		          write(updatedModel) {
		            ctx.run(
		              query[${modelClass}]
		  				.filter(_.id == lift(id))
		  				.update(
		  					${updateArgs}
		  					${updateAt}
		  				)  
		            ).runToFuture
		          }.publishingUpdated((cur, prev, cid, time) => ${modelClass}Updated(cur, Some(updatedModel), ${pkFieldForCreated} cid, time))

		        case None =>
		          Future.failed(new NoSuchElementException(s"No ${modelClass} found with id $$id"))
		      }
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

		val updateAt = if (!table.hasColumn("updated_at")) {
			""
		} else {
			", _.updatedAt -> lift(ts)"
		}

		val copyUpdates = columns.map { col => 
			val c = namingStrategy.column(col.columnName)
			val fc = col.fix(true,c)
			s"${fc} = ${fc}"
		}.mkString(",\n")
		val updatedAtCopy = "updatedAt = ts"		

		// s"""
		// 	override def update${functionName}By${keyNames}(${keyArgs}, ${functionArgs}): Future[_] = monitored("update-${functionMonitorKey}-by-${byMonitorKey}") {
		//     val ts = TimeUtil.nowTimestamp()
		//    	ctx.run(
		//    	  query[${modelClass}]
		//   					.filter( ${initial} =>
		//   						${filterArgs}
		//   					)
		//   					.update(
		//   						${updateArgs}
		//   						${updateAt}
		//   					)    
		//    	).runToFuture
		//   }
		// """

		// ${functionName}
		s"""
		  override def update${functionName}By${keyNames}(${keyArgs}, ${functionArgs}): Future[${modelClass}] = monitored("update-${monitorKey}-by-id") {
			val ts = TimeUtil.nowTimestamp()

		    getBy${keyNames}(${keyArgs}).flatMap {
		        case Some(currentModel) =>
		          val updatedModel = currentModel.copy(${copyUpdates},${updatedAtCopy})

		          write(updatedModel) {
		            ctx.run(
		              query[${modelClass}]
						.filter( ${initial} =>
							${filterArgs}
						)
		  				.update(
		  					${updateArgs}
		  					${updateAt}
		  				)  
		            ).runToFuture
		          }.publishingUpdated((cur, prev, cid, time) => ${modelClass}Updated(cur, Some(updatedModel), id = cur.id, cid, time))

		        case None =>
		          Future.failed(new NoSuchElementException(s"No ${modelClass} found with ${keyArgs}"))
		      }
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
		    read {
		   	  ctx.run(
		   	    query[${modelClass}]
					  .filter( ${initial} =>
						  ${filterArgs}
					  )
                    .take(1)
            	  ).runToFuture.map(_.headOption)
		    }
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
		//if its a uuid
		val upsert1 = buildUpsertCode(table,"id",modelClass)


		val includeUpsertOn = table.hasUniqueKeysExcludingPrimaryKey()
		val upsert2= if (includeUpsertOn) {
			val uKeys = table.uniqueKeysExcludingPrimaryKey
			val ukCodeStr = uKeys.map { uk => buildUpsertOnCode(table, uk, modelClass) }.mkString("\n")

			ukCodeStr
		} else {
			""
		}

		val upsert3 = if (table.inheritedFromTable.isDefined){
			println("table.inheritedFromTable.isDefined:" + tableName)

			val it = table.inheritedFromTable.get
			if (it.hasUniqueKeysExcludingPrimaryKey()){
				val uKeys2 = it.uniqueKeysExcludingPrimaryKey
				uKeys2.map { uk => buildUpsertOnCode(table, uk, modelClass) }.mkString("\n")	
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

		// ""
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
		// ""
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

	def buildGettersByForeignKeysCode(table: Table, packageSpace: String, modelClass: String, tableName: String, tableClass: String): String = {
		val namingStrategy = GeneratorNamingStrategy
		
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
					// Use the actual column type from the model
					// extractClassName(col.scalaType)
					val referencedColumnName = col.references.get.columnName
					DefnBuilder.tablesLookup.get(foreignTable) match {
						case Some(referencedTable) =>
							val referencedColumn = referencedTable.findColumn(referencedColumnName)

							if (referencedTable.name == "attributes") {
								col.toDefn(col.tableName, true)
							} else {
								println("foreignTable found. " + referencedTable.name)
								println("foreignTable referencedColumnName:" + referencedColumnName)
								println("foreignTable referencedColumn. " + referencedColumn.scalaType)

								println("foreignTable referencedColumn. " + referencedColumn.toDefn(referencedColumn.tableName, true)) 
								println("foreignTable referencedColumn. " + referencedColumn.toArg(namingStrategy,referencedColumn.tableName,true,true)) 

								// extractClassName(referencedColumn.scalaType)
								referencedColumn.toDefn(referencedColumn.tableName, true)
							}
						case None =>
							println("foreignTable fallback")
							// Fallback to the original approach if table not found
							extractClassName(col.scalaType)
					}					
				}
				val methodName = s"getBy${namingStrategy.table(col.columnName).capitalize}"
				val bulkMethodName = s"getBy${namingStrategy.table(col.columnName).capitalize}s"
				val paramName = namingStrategy.column(col.columnName)
				val monitorKey = col.columnName.toLowerCase.replace("_", "-")
				
				// For nullable columns, we need to handle the Option comparison correctly
				val singleFilterCondition = if (col.nullable) {
					s"r.${paramName}.contains(lift(${paramName}))"
				} else {
					s"r.${paramName} == lift(${paramName})"
				}
				
				val bulkFilterCondition = if (col.nullable) {
					s"r.${paramName}.exists(v => liftQuery(${paramName}s).contains(v))"
				} else {
					s"liftQuery(${paramName}s).contains(r.${paramName})"
				}
				
				// For getBy methods, we don't wrap nullable columns in Option - the method handles nulls internally
				val singleMethod = s"""
  override def ${methodName}(${paramName}: ${foreignKeyType}): Future[List[${modelClass}]] = monitored("get-by-${monitorKey}") {
    read {
      ctx.run(
        query[${modelClass}]
          .filter(r => ${singleFilterCondition})
      ).runToFuture
    }
  }"""
				
				val bulkMethod = s"""
  override def ${bulkMethodName}(${paramName}s: List[${foreignKeyType}]): Future[List[${modelClass}]] = monitored("get-by-${monitorKey}s") {
    read {
      ctx.run(
        query[${modelClass}]
          .filter(r => ${bulkFilterCondition})
      ).runToFuture
    }
  }"""
				
				singleMethod + "\n" + bulkMethod
			}.mkString("\n")
		}
	}


}