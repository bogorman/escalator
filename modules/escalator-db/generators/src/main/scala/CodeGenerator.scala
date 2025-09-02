package escalator.db.generators

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

import com.typesafe.scalalogging.Logger
import io.getquill.NamingStrategy

import escalator.util.disk._
import escalator.util._

// import db.generators._
import escalator.db.generators.AttributeTypeData

import org.scalafmt.interfaces.Scalafmt
import scala.meta._
import scala.reflect.classTag

import scala.collection.mutable.{ ArrayBuffer => MList }
import scala.collection.mutable.{ Map => MMap }

case class Error(msg: String) extends Exception(msg)

case class UniqueKey(keyName: String, tableName: String, cols: List[SimpleColumn], partial: Boolean) {
  def containsColumn(name: String): Boolean = {
    // false
    cols.filter { c => c.columnName == name }.size == 1
  } 
}

case class IndexKey(name: String, col: SimpleColumn, partial: Boolean)
case class SimpleCaseClass(tableName: String,mainCaseClass: String, mainObjectClass: String, columnCaseClasses: List[String], uniqueKeyCaseClasses: List[String], extraCaseClasses: String)
case class ForeignKey(from: SimpleColumn, to: SimpleColumn)

// Aggregate generation data structures
case class ReferenceNode(
  table: String,
  foreignKeyColumn: String,
  children: List[ReferenceNode] = List.empty,
  isWeakReference: Boolean = false,  // Reference to another aggregate
  depth: Int = 0
)

case class ParentChildPair(parentNode: ReferenceNode, childNode: ReferenceNode)

case class ReferenceTree(rootTable: String, nodes: List[ReferenceNode]) {
  def allTables: List[String] = {
    def collectTables(nodes: List[ReferenceNode]): List[String] = {
      nodes.flatMap { node =>
        node.table :: collectTables(node.children)
      }
    }
    rootTable :: collectTables(nodes)
  }
  
  def flatten: List[ReferenceNode] = {
    def collectNodes(nodes: List[ReferenceNode]): List[ReferenceNode] = {
      nodes.flatMap { node =>
        node :: collectNodes(node.children)
      }
    }
    collectNodes(nodes)
  }
}

case class SimpleColumn(tableName: String, columnName: String) {
  import TextUtil._
  val namingStrategy = GeneratorNamingStrategy

  // def toType = {
  //   s"${namingStrategy.table(tableName)}.${namingStrategy.table(columnName)}"
  // }

  // want something like CurrentPositionId
  def toType = {
    println("     SimpleColumn tableName:" + tableName + " " + namingStrategy.table(tableName).toLowerCase)
    println("     SimpleColumn columnName:" + columnName + " " + camelify(columnName).toLowerCase)

    val t = if (namingStrategy.table(tableName).toLowerCase == camelify(columnName).toLowerCase) {
      s"${camelify(columnName)}Type"
    } else if (camelify(columnName).toLowerCase.startsWith(namingStrategy.table(tableName).toLowerCase)){
      s"${camelify(columnName)}"
    } else {
      s"${namingStrategy.table(tableName)}${camelify(columnName)}"
    }
    println("   SimpleColumn t:" + t)
    t
  }
}

case class CodeGenerator(options: CodegenOptions, namingStrategy: NamingStrategy, customGen: CustomGenerator) {
  // import CodeGenerator._
  import TextUtil._

  val TABLE_NAME = "TABLE_NAME"
  val COLUMN_NAME = "COLUMN_NAME"
  val TYPE_NAME = "TYPE_NAME"
  val NULLABLE = "NULLABLE"
  val PK_NAME = "pk_name"
  val FK_TABLE_NAME = "fktable_name"
  val FK_COLUMN_NAME = "fkcolumn_name"
  val PK_TABLE_NAME = "pktable_name"
  val PK_COLUMN_NAME = "pkcolumn_name"

  val excludedTables = options.excludedTables.toList

  // val columnType2scalaType = options.typeMap.pairs.toMap

  def results(resultSet: ResultSet): Iterator[ResultSet] = {
    new Iterator[ResultSet] {
      def hasNext = resultSet.next()
      def next() = resultSet
    }
  }

  def getForeignKeys(db: Connection): Set[ForeignKey] = {
    val foreignKeys = db.getMetaData.getExportedKeys(null, options.schema, null)
    val dbForeignKeys = results(foreignKeys).map { row =>
      ForeignKey(
        from = SimpleColumn(
          tableName = row.getString(FK_TABLE_NAME),
          columnName = row.getString(FK_COLUMN_NAME)
        ),
        to = SimpleColumn(
          tableName = row.getString(PK_TABLE_NAME),
          columnName = row.getString(PK_COLUMN_NAME)
        )
      )
    }.toSet


    // ?????????
    val customForeignKeys = Set(
       ForeignKey(SimpleColumn("instruments","base"),SimpleColumn("assets","symbol")),
       ForeignKey(SimpleColumn("instruments","quote"),SimpleColumn("assets","symbol")),
       ForeignKey(SimpleColumn("instruments","settled_in"),SimpleColumn("assets","symbol"))
    )

    (dbForeignKeys ++ customForeignKeys).toSet
  }

  // : Set[ForeignKey]
  // def getCrossReferenceKeys(db: Connection) = {
    // https://github.com/scala-bones/scatonic-ideal/blob/483d3364d5c70b6e72ca5c11275568b49eac10e9/jdbc/src/main/scala/com/bones/si/jdbc/load/LoadCrossReference.scala
  //   println("getCrossReferenceKeys")
  //   val foreignKeys = db.getMetaData.getCrossReference(null, options.schema, null, null, options.schema, null )
  //   println(foreignKeys)


  //   // results(foreignKeys).map { row =>
  //   //   ForeignKey(
  //   //     from = SimpleColumn(
  //   //       tableName = row.getString(FK_TABLE_NAME),
  //   //       columnName = row.getString(FK_COLUMN_NAME)
  //   //     ),
  //   //     to = SimpleColumn(
  //   //       tableName = row.getString(PK_TABLE_NAME),
  //   //       columnName = row.getString(PK_COLUMN_NAME)
  //   //     )
  //   //   )
  //   // }.toSet
  // }

  def warn(msg: String): Unit = {
    System.err.println(s"[${Console.YELLOW}warn${Console.RESET}] $msg")
  }

  def getTables(db: Connection, foreignKeys: Set[ForeignKey]): Seq[Table] = {
    val abstractTables = ConnectionUtils.getAbstractTables(db)
    val inheritedTables = ConnectionUtils.getInheritedTables(db)    
    val inheritedTablesMappings = ConnectionUtils.getInheritedTablesMappings(db)

    println("abstractTables:" + abstractTables)
    println("inheritedTables:" + inheritedTables)
    println("inheritedTablesMappings:" + inheritedTablesMappings)

    val rs: ResultSet = db.getMetaData.getTables(null, options.schema, "%", Array("TABLE"))

    val metaTables = MList.empty[String]
    val tablesLookup = MMap.empty[String, Table]

    results(rs).foreach { row =>
      val name = row.getString(TABLE_NAME)
      println("META name:" + name)
      metaTables += name
    }

    val tables = ((abstractTables ++ inheritedTables ++ metaTables).distinct diff excludedTables).toList

    println("tables:" + tables)

    val result = tables.flatMap { name => 
      // val name = row.getString(TABLE_NAME)
      println("Table name: " + name)

      if (!excludedTables.contains(name)) {
        val uniqueKeys = getUniqueKeys(db, name)
        println("uniqueKeys:" + uniqueKeys)

        val abstractTable: Boolean = abstractTables.contains(name)
        val inheritedFromTable: Option[Table] = if (inheritedTablesMappings.contains(name)){
          val parentTable = inheritedTablesMappings.get(name).get
          // println("parentTable:" + parentTable)

          if (tablesLookup.contains(parentTable)){
            Some(tablesLookup(parentTable))
          } else {
            throw new Exception("Missing parent table:" + parentTable)
          }
        } else {
          None
        }

        val columns = getColumns(db, name, foreignKeys, uniqueKeys, inheritedFromTable)
        val mappedColumns = columns.filter(_.isRight).map(_.right.get)
        val unmappedColumns = columns.filter(_.isLeft).map(_.left.get)

        if (unmappedColumns.nonEmpty) {
          warn(s"The following columns from table $name need a mapping: $unmappedColumns")
        }        

        val t = Table(
          customGen,
          options,
          name,
          mappedColumns,
          uniqueKeys,
          abstractTable,
          inheritedFromTable,
          db
        )

        tablesLookup += (name -> t)

        Some(t)
      } else {
        None
      }
    }.toVector
    
    // Set the tables lookup in DefnBuilder for foreign key type resolution
    DefnBuilder.setTablesLookup(tablesLookup)
    
    result
  }

  def getColumns(db: Connection,
                 tableName: String,
                 foreignKeys: Set[ForeignKey],
                 uniqueKeys: Set[UniqueKey], 
                 inheritedFromTable: Option[Table] 
               ): Seq[Either[String, Column]] = {
    val primaryKeys = getPrimaryKeys(db, tableName)

    println("primaryKeys:" + primaryKeys)
    
    val cols = db.getMetaData.getColumns(null, options.schema, tableName, null)
    
    results(cols).map { row =>
      val colName = cols.getString(COLUMN_NAME)
      val simpleColumn = SimpleColumn(tableName, colName)

      val ref = foreignKeys.find(_.from == simpleColumn).map(_.to)
      val incomingRefs = foreignKeys.filter(_.to == simpleColumn).map(_.from).toList

      val hasUniqueKey = uniqueKeys.filter(_.cols == List(simpleColumn)).headOption.isDefined

      // val inheritedFromTable: Option[Table] = None
      val inheritedFromColumn: Option[Column] = if (inheritedFromTable.isDefined) {
        // None
        inheritedFromTable.flatMap { t =>
          t.findColumnOpt(colName)
        }
      } else {
        None
      }

      val typ = cols.getString(TYPE_NAME)

      if (!TypeMapper.mappings(customGen).contains(typ)) {
        println(s"Missing type mapping for: '${typ}'")
      }

      TypeMapper.mappings(customGen).get(typ).map { scalaType =>
        Right(Column(
          customGen,
          tableName,
          colName,
          scalaType,
          cols.getBoolean(NULLABLE),
          primaryKeys contains cols.getString(COLUMN_NAME),
          hasUniqueKey,
          ref,
          incomingRefs,
          inheritedFromTable,
          inheritedFromColumn
        ))
      }.getOrElse(Left(typ))
    }.toVector
  }

  def getPrimaryKeys(db: Connection, tableName: String): Set[String] = {
    val sb = Set.newBuilder[String]
    val primaryKeys = db.getMetaData.getPrimaryKeys(null, null, tableName)
    while (primaryKeys.next()) {
      sb += primaryKeys.getString(COLUMN_NAME)
    }
    sb.result()
  }

  def getUniqueKeys(db: Connection, tableName: String): Set[UniqueKey] = {
    val uniqueKeys = db.getMetaData.getIndexInfo(null, null, tableName, true, false)

    println("getUniqueKeys: " + tableName)
    val indices = results(uniqueKeys).map { row =>
      val indexName = row.getString("INDEX_NAME")
      val columnName = row.getString("COLUMN_NAME")

      val nonUnique = row.getBoolean("NON_UNIQUE")
      val typeCode = row.getShort("TYPE")
      val ordinalPosition = row.getInt("ORDINAL_POSITION")

      val filter = row.getString("FILTER_CONDITION")
      val partial = filter != null

      println("[UK] indexName:" + indexName)
      println("[UK] columnName:" + columnName)

      println("[UK] nonUnique:" + nonUnique)
      println("[UK] typeCode:" + typeCode)
      println("[UK] ordinalPosition:" + ordinalPosition)

      println("[UK] partial:" + partial)

      // rs.getString("INDEX_NAME") to extract index name
      // rs.getBoolean("NON_UNIQUE") to extract unique information
      // rs.getShort("TYPE") to extract index type
      // rs.getInt("ORDINAL_POSITION") to extract ordinal position

      //HACK FIX!
      val fixedColName: String = if (columnName.contains("attribute_type_to_string")) {
        columnName.replace("attribute_type_to_string(","").stripSuffix(")")
      } else {
        columnName
      }

      IndexKey(indexName, SimpleColumn(tableName,fixedColName), partial)
    }.toList

    val validIndices = indices.filter{ i => !i.name.startsWith("inherited_") && !i.name.startsWith("ignore_") }

    val groupedIndixies: Map[String, Seq[IndexKey]] = validIndices.groupBy(_.name)
    // validIndices.groupBy(_.name)
    groupedIndixies.map { case (key,values) => 
      // values
      // values.map { indexCols =>
        val partial = values.map(_.partial).headOption.getOrElse(false)

        UniqueKey(key,tableName,values.map(_.col).toList,partial)
      // }
    }.toSet
  }

  /////////////////////////////////////////////////////

  private def packageName = options.packageName
  private def appName = options.appName
  private def appFolder = options.appFolder
  private def modelsBaseFolder = options.modelsBaseFolder
  private def persistenceBaseFolder = options.persistenceBaseFolder
  private def databaseFolder = options.databaseFolder
  private def postgresFolder = options.postgresFolder


  def generateCaseCasses(tables: Seq[Table]) = {
    tables.map(_.toCaseClass)
  }

  private def generateDatabaseDao(tables: Seq[Table]) = {
    val simpleCaseClasses = generateCaseCasses(tables).toList
    val caseClasses = simpleCaseClasses.map(_.mainCaseClass).toList

    FileUtil.createDirectoriesForFolder(databaseFolder+"/tables")
    FileUtil.createDirectoriesForFolder(postgresFolder+"/tables")

    val tableCaseClasses = caseClasses.map { caseClass =>
      println("generateDaos: " + caseClass)

      val caseClassStat = caseClass.parse[Stat].get
      println(caseClassStat)

      val caseClassName = caseClassStat.collect {
        case q"case class $tname (...$paramss) extends Persisted" => tname.value
      }.head

      caseClassName
    }

    val genericDatabaseSource = GeneratorTemplates.genericDatabaseTemplate(packageName, appName, tableCaseClasses)
    val actualDatabaseSource = GeneratorTemplates.postgresDatabaseTemplate(packageName, appName, tableCaseClasses)

    val formattedGenericDatabaseSource = formatCode(genericDatabaseSource)
    val formattedActualDatabaseSource = formatCode(actualDatabaseSource)

    writeIfDoesNotExist(databaseFolder + s"/${appName}Database.scala" , formattedGenericDatabaseSource)
    writeIfDoesNotExist(postgresFolder + s"/Postgres${appName}Database.scala" , formattedActualDatabaseSource)    
  }

  private def generateTableDaos(tables: Seq[Table]) = {
    // val simpleCaseClasses = generateCaseCasses(tables).toList
    // val caseClasses = simpleCaseClasses.map(_.mainCaseClass).toList

    tables.foreach { table =>
      val simpleCaseClass = table.toCaseClass
      val caseClass = simpleCaseClass.mainCaseClass

      println("generateDaos: " + caseClass)

      val caseClassStat = caseClass.parse[Stat].get
      println(caseClassStat)

      val caseClassName = caseClassStat.collect {
        case q"case class $tname (...$paramss) extends Persisted" => tname.value
      }.head

      println("caseClassName:" + caseClassName)

      val tableClass = pluralize(caseClassName)
      println("tableClass:" + tableClass)

      val tableClassName = tableClass+"Table"
      println("tableClassName:" + tableClassName)

      val traitSource = GeneratorTemplates.tableTraitTemplate(table, packageName, caseClassName, tableClass, tableClassName)
      val daoSource = GeneratorTemplates.tableDaoTemplate(customGen, table, packageName, caseClassName, tableClass, tableClassName)

      val formattedTraitSource = formatCode(traitSource)
      val formattedDaoSource = formatCode(daoSource)

      writeIfDoesNotExist(databaseFolder + s"/tables/${tableClassName}.scala" , formattedTraitSource)
      writeIfDoesNotExist(postgresFolder + s"/tables/Postgres${tableClassName}.scala" , formattedDaoSource)
    }
  }

  private def generateAppRepositories(tables: Seq[Table]) = {
    // Use repositoriesFolder if provided, otherwise fall back to persistenceBaseFolder
    val targetFolder = if (options.repositoriesFolder.nonEmpty) {
      options.repositoriesFolder
    } else {
      persistenceBaseFolder
    }

    // Create the repositories directory if it doesn't exist
    FileUtil.createDirectoriesForFolder(targetFolder)

    tables.foreach { table =>
      val simpleCaseClass = table.toCaseClass
      val caseClass = simpleCaseClass.mainCaseClass

      println("generateAppRepositories: " + caseClass)

      val caseClassStat = caseClass.parse[Stat].get
      val caseClassName = caseClassStat.collect {
        case q"case class $tname (...$paramss) extends Persisted" => tname.value
      }.head

      val tableClass = pluralize(caseClassName)
      val tableClassName = tableClass+"Table"

      val repositorySource = GeneratorTemplates.appRepositoryTemplate(packageName, caseClassName, tableClass, tableClassName, options.repositoriesFolder)
      val formattedRepositorySource = formatCode(repositorySource)

      writeIfDoesNotExist(targetFolder + s"/${tableClass}Repository.scala" , formattedRepositorySource)
    }
  }

  private def generateModelEvents(tables: Seq[Table]) = {
    // Create the events directory if it doesn't exist
    FileUtil.createDirectoriesForFolder(modelsBaseFolder + "/events")

    tables.foreach { table =>
      if (customGen.shouldGenerateEvents(table.name)) {
        val simpleCaseClass = table.toCaseClass
        val caseClass = simpleCaseClass.mainCaseClass

        println("generateModelEvents: " + caseClass)

        val caseClassStat = caseClass.parse[Stat].get
        val caseClassName = caseClassStat.collect {
          case q"case class $tname (...$paramss) extends Persisted" => tname.value
        }.head

        val eventSource = GeneratorTemplates.modelEventTemplate(packageName, caseClassName, table)
        val formattedEventSource = formatCode(eventSource)

        writeIfDoesNotExist(modelsBaseFolder + s"/events/${caseClassName}Event.scala", formattedEventSource)
      }
    }
  }

  def cleanupExistingGeneratedFiles() = {
    println("cleanupExistingGeneratedFiles")
    cleanupExistingGeneratedFilesInFolder(modelsBaseFolder)
    cleanupExistingGeneratedFilesInFolder(modelsBaseFolder+"/events")
    cleanupExistingGeneratedFilesInFolder(persistenceBaseFolder)
    
    // Clean repositories folder if specified
    if (options.repositoriesFolder.nonEmpty) {
      cleanupExistingGeneratedFilesInFolder(options.repositoriesFolder)
    }
    
    // Clean aggregates folder if specified
    println("aggregatesFolder:" + options.aggregatesFolder)

    if (options.aggregatesFolder.nonEmpty) {
      cleanupExistingGeneratedFilesInFolder(options.aggregatesFolder,true)
    }

    // FileUtil.createDirectoriesForFolder(aggregateFolder)
    // FileUtil.createDirectoriesForFolder(s"$aggregateFolder/custom")

    // }
    
    cleanupExistingGeneratedFilesInFolder(databaseFolder)
    cleanupExistingGeneratedFilesInFolder(databaseFolder+"/tables")
    cleanupExistingGeneratedFilesInFolder(postgresFolder)
    cleanupExistingGeneratedFilesInFolder(postgresFolder+"/tables")

    val persustanceDbTypePath = s"${options.persistenceBaseFolder}/common/src/main/scala/${packageName.replace('.', '/')}/persistence/postgres/"
    cleanupExistingGeneratedFilesInFolder(persustanceDbTypePath)
    
  }

  //   def cleanupExistingGeneratedFilesInFolder(folder: String) = {
  //   val f = new File(folder)
  //   val scalaFiles = FileUtil.getListOfFiles(f, List("scala"))

  //   scalaFiles.foreach { scalaFile =>
  //     val scalaFilePath = scalaFile.getPath
  //     val content = FileUtil.read(scalaFilePath)
  //     if (content.contains(GeneratorTemplates.autoGeneratedCommentTracker)){
  //       println("DELETE FILE:" + scalaFilePath)        
  //       FileUtil.delete(scalaFilePath)
  //     } else {
  //       println("SKIPPING FILE:" + scalaFilePath + ".")
  //     }
  //   }
  //   // println("scalaFiles")
  // }

  def cleanupExistingGeneratedFilesInFolder(folder: String, recursive: Boolean = false) = {
    val f = new File(folder)
    
    if (recursive) {
      cleanupExistingGeneratedFilesRecursive(f)
    } else {
      val scalaFiles = FileUtil.getListOfFiles(f, List("scala"))
      
      println("cleanupExistingGeneratedFilesInFolder:" + folder + " " + scalaFiles.size)
      scalaFiles.foreach { scalaFile =>
        val scalaFilePath = scalaFile.getPath
        val content = FileUtil.read(scalaFilePath)
        if (content.contains(GeneratorTemplates.autoGeneratedCommentTracker)){
          println("DELETE FILE:" + scalaFilePath)        
          FileUtil.delete(scalaFilePath)
        } else {
          println("SKIPPING FILE:" + scalaFilePath + ".")
        }
      }
    }
  }
  
  def cleanupExistingGeneratedFilesRecursive(folder: File): Unit = {
    if (!folder.exists() || !folder.isDirectory) return
    
    // Process files in current directory
    val scalaFiles = FileUtil.getListOfFiles(folder, List("scala"))
    scalaFiles.foreach { scalaFile =>
      val scalaFilePath = scalaFile.getPath
      val content = FileUtil.read(scalaFilePath)
      if (content.contains(GeneratorTemplates.autoGeneratedCommentTracker)){
        println("DELETE FILE:" + scalaFilePath)        
        FileUtil.delete(scalaFilePath)
      } else {
        println("SKIPPING FILE:" + scalaFilePath + ".")
      }
    }
    
    // Recursively process subdirectories
    folder.listFiles().foreach { subFile =>
      if (subFile.isDirectory) {
        cleanupExistingGeneratedFilesRecursive(subFile)
      }
    }
  }

  def formatCode(code: String) = {
    Formatter.format(code)
    // code
  }


  def debugPrintColumnLabels(rs: ResultSet): Unit = {
    (1 to rs.getMetaData.getColumnCount).foreach { i =>
      println(i -> rs.getMetaData.getColumnLabel(i))
    }
  }


  def extractClassName(classDefinition: String): String = {
    //not good code. will break. use regex.
    val cc = classDefinition.replace("case class","")
    
    val parts = cc.split("\\(")

    if (parts.size > 1){
      parts(0).trim
    } else {
      cc.trim
    }

  }

  // AttributeType generation from database
  var generatedAttributeTypes: List[String] = List.empty
  var attributeTypeMapping: Map[String, String] = Map.empty  // Maps attr_type -> ClassName
  
  def queryAttributeTypesFromDatabase(db: Connection): Map[String, List[AttributeTypeData]] = {
    val sql = "SELECT (attr_type).attr, (attr_type).ident, description FROM attributes ORDER BY ordering"
    val stmt = db.createStatement()
    val rs = stmt.executeQuery(sql)
    
    val attributeData = results(rs).map { row =>
      AttributeTypeData(
        attr = row.getString(1),
        ident = row.getString(2),
        description = row.getString(3)
      )
    }.toList
    
    stmt.close()
    
    // Group by attr category (e.g., ENTITY_TYPE -> List of idents)
    attributeData.groupBy(_.attr)
  }
  
  // def buildColumnAttributeTypeMappingsFromConstraints(db: Connection): Map[String, String] = {
  //   println("Building column attribute type mappings from database CHECK constraints...")

  //   val sql = """
  //     WITH checks AS (
  //       SELECT
  //         tc.table_schema,
  //         tc.table_name,
  //         cc.check_clause
  //       FROM information_schema.table_constraints tc
  //       JOIN information_schema.check_constraints cc
  //         ON cc.constraint_schema = tc.constraint_schema
  //        AND cc.constraint_name  = tc.constraint_name
  //       WHERE tc.constraint_type = 'CHECK'
  //         AND tc.table_schema    = 'public'
  //     ),
  //     -- Pull *all* (identifier, token) pairs from each CHECK
  //     pairs AS (
  //       SELECT
  //         c.table_schema,
  //         c.table_name,
  //         COALESCE(m[4], m[3], m[2], m[1]) AS ident,   -- rightmost ident if qualified
  //         m[5] AS token
  //       FROM checks c
  //       CROSS JOIN LATERAL regexp_matches(
  //         c.check_clause,
  //         $$\(\s*\(\s*\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z_0-9]*))(?:\s*\.\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z_0-9]*)))?\s*\)\s*\.attr\)\s*(?:::text)?\s*=\s*'([^']+)'\s*(?:::text)?$$,
  //         'g'
  //       ) AS m
  //     ),
  //     rels AS (
  //       SELECT c.oid AS relid, n.nspname AS table_schema, c.relname AS table_name
  //       FROM pg_class c
  //       JOIN pg_namespace n ON n.oid = c.relnamespace
  //       WHERE n.nspname = 'public'
  //     )
  //     SELECT DISTINCT ON (r.table_name, a.attname)
  //       r.table_name  AS table_name,
  //       a.attname     AS column_name,
  //       p.token
  //     FROM pairs p
  //     JOIN rels r
  //       ON r.table_schema = p.table_schema
  //      AND r.table_name   = p.table_name
  //     JOIN pg_attribute a
  //       ON a.attrelid = r.relid
  //      AND a.attnum > 0 AND NOT a.attisdropped
  //     LEFT JOIN pg_type t            -- for type-name fallback
  //       ON lower(t.typname) = lower(p.ident)
  //     -- Optional: keep only USER-DEFINED columns. Uncomment if you want this filter.
  //     -- JOIN information_schema.columns ic
  //     --   ON ic.table_schema = r.table_schema AND ic.table_name = r.table_name AND ic.column_name = a.attname
  //     --  AND ic.data_type = 'USER-DEFINED'
  //     WHERE
  //       -- assign a priority to each candidate column for this (table, ident)
  //       CASE
  //         WHEN lower(a.attname) = lower(p.ident) THEN 1                     -- direct column name match
  //         WHEN t.oid IS NOT NULL AND a.atttypid = t.oid THEN 2              -- same type as ident
  //         WHEN a.attname ~* ('(^|_)' || regexp_replace(p.ident,'\W','','g') || '(_|$)') THEN 3  -- name-like fallback
  //         ELSE NULL
  //       END IS NOT NULL
  //     ORDER BY
  //       r.table_name, a.attname,
  //       CASE
  //         WHEN lower(a.attname) = lower(p.ident) THEN 1
  //         WHEN t.oid IS NOT NULL AND a.atttypid = t.oid THEN 2
  //         WHEN a.attname ~* ('(^|_)' || regexp_replace(p.ident,'\W','','g') || '(_|$)') THEN 3
  //       END;
  //   """

  //   val stmt = db.prepareStatement(sql)
  //   // stmt.setString(1, options.schema)
  //   val rs = stmt.executeQuery()
    
  //   val mappings = scala.collection.mutable.Map[String, String]()
    
  //   while (rs.next()) {
  //     val tableName = rs.getString("table_name")
  //     val columnName = rs.getString("column_name")  
  //     // val checkClause = rs.getString("check_clause")
  //     val token = rs.getString("token")
      
  //     // Extract attribute type from check clause like: ((entity_type).attr = 'ENTITY_TYPE'::text)
  //     // or: check ((entity_type).attr = 'ENTITY_TYPE')
  //     // val pattern = """.*attr\s*=\s*'([^']+)'.*""".r
  //     // val pattern = """.*attr\s*=\s*'([^']+)'(?:::text)?.*""".r
  //     // val pattern = """.*\)\.attr\)?(?:::text)?\s*=\s*'([^']+)'(?:::text)?.*""".r

  //     // checkClause match {
  //       // case pattern(attrType) =>
  //         // Convert ENTITY_TYPE to EntityType
  //         val attrType = token

  //         val className = attrType.toLowerCase.replace("_type", "").split("_").map(_.capitalize).mkString("") + "Type"

  //         // val className = attrType.split("_").map(_.toLowerCase.capitalize).mkString("")
  //         val key = s"$tableName.$columnName"
  //         mappings(key) = className
  //         println(s"  Found mapping: $key -> $className")
  //       // case _ =>
  //         // println(s"  Could not parse constraint for $tableName.$columnName: $checkClause")
  //     // }
  //   }
    
  //   stmt.close()

  //   println(mappings)

  //   mappings.toMap
  // }
  
  def generateAttributeTypes(db: Connection): Unit = {
    println("Generating AttributeTypes from database...")
    
    val attributeTypeMap = queryAttributeTypesFromDatabase(db)
    
    // Build lists of all classes and objects
    val allTypeClasses = scala.collection.mutable.ListBuffer[String]()
    val allTypeObjects = scala.collection.mutable.ListBuffer[String]()
    
    attributeTypeMap.foreach { case (attrCategory, values) =>
      val baseName = TextUtil.snakeToUpperCamel(attrCategory.toLowerCase.replace("_", "_"))
      val className = if (baseName.endsWith("Type")) baseName else baseName + "Type"
      val scalaClassName = className.capitalize
      
      generatedAttributeTypes = generatedAttributeTypes :+ scalaClassName
      attributeTypeMapping = attributeTypeMapping + (attrCategory -> scalaClassName)
      
      // Build case class definition
      val caseClassDef = s"case class ${scalaClassName}(ident: String) extends BaseAttributeType {\n  override def attr = \"${attrCategory}\"\n}"
      allTypeClasses += caseClassDef
      
      // Build object with constants
      val constants = values.map { value =>
        val constantName = TextUtil.snakeToUpperCamel(value.ident.replace("-", "_"))
        s"  val ${constantName} = ${scalaClassName}(\"${value.ident}\")"
      }.mkString("\n")
      
      val objectDef = s"object ${scalaClassName}s {\n${constants}\n}"
      allTypeObjects += objectDef
    }

    println("attributeTypeMapping")
    println(attributeTypeMapping)
    
    // Generate combined files
    generateAllAttributeTypesFile(allTypeClasses.toList)
    generateAllAttributeObjectsFile(allTypeObjects.toList)
    generatePostgresDbTypesEncoder()
    
    println(s"Generated ${generatedAttributeTypes.size} AttributeType classes: ${generatedAttributeTypes.mkString(", ")}")
  }
  
  def generateAllAttributeTypesFile(typeDefinitions: List[String]): Unit = {
    try {
      val attributesFolder = modelsBaseFolder//.replace("/shared/","/shared-common/")
      println(s"Generating combined AttributeTypes.scala with ${typeDefinitions.size} types")
      println(s"attributesFolder is: ${attributesFolder}")
      val content = GeneratorTemplates.allAttributeTypesTemplate(packageName, typeDefinitions)
      val formattedContent = formatCode(content)
      val filePath = s"${attributesFolder}/AttributeTypes.scala"
      
      println(s"Writing AttributeTypes file to: ${filePath}")
      FileUtil.createDirectoriesForFile(filePath)
      FileUtil.write(filePath, formattedContent) // Always overwrite
      println(s"Successfully generated AttributeTypes.scala at ${filePath}")
    } catch {
      case ex: Exception =>
        println(s"Error generating AttributeTypes file: ${ex.getMessage}")
        ex.printStackTrace()
    }
  }
  
  def generateAllAttributeObjectsFile(objectDefinitions: List[String]): Unit = {
    try {
      val attributesFolder = modelsBaseFolder//.replace("/shared/","/shared-common/")
      println(s"Generating combined AttributeObjects.scala with ${objectDefinitions.size} objects")
      val content = GeneratorTemplates.allAttributeObjectsTemplate(packageName, objectDefinitions)
      val formattedContent = formatCode(content)  
      val filePath = s"${attributesFolder}/AttributeObjects.scala"
      
      println(s"Writing AttributeObjects file to: ${filePath}")
      FileUtil.createDirectoriesForFile(filePath)
      FileUtil.write(filePath, formattedContent) // Always overwrite
      println(s"Successfully generated AttributeObjects.scala")
    } catch {
      case ex: Exception =>
        println(s"Error generating AttributeObjects file: ${ex.getMessage}")
        ex.printStackTrace()
    }
  }
  
  def generatePostgresDbTypesEncoder(): Unit = {
    val content = GeneratorTemplates.postgresCustomEncoderTemplate(packageName, generatedAttributeTypes)
    val formattedContent = formatCode(content)
    val filePath = s"${options.persistenceBaseFolder}/common/src/main/scala/${packageName.replace('.', '/')}/persistence/postgres/PostgresDbTypesEncoder.scala"
    
    FileUtil.createDirectoriesForFile(filePath)
    FileUtil.write(filePath, formattedContent) // Always overwrite
  }

  def internalTypes(): List[String] = {
    generatedAttributeTypes ++ List(
      // "CorrelationId"
    )
  }

  def generateSerializer(sharedModelTypes: List[String],sharedModels: List[String]) = {
    val scalaFilePath = modelsBaseFolder+s"/ModelSerializers.scala"

    val modelTypeSerializers = sharedModelTypes.map { mt =>
      s"implicit val codec${mt}: Codec.AsObject[${mt}] = deriveCodec[${mt}]"
    }

    val customModelTypes = internalTypes() ++ customGen.customTypes
    val customModelTypeSerializers = customModelTypes.map { m =>
      s"implicit val codec${m}: Codec.AsObject[${m}] = deriveCodec[${m}]"
    }

    val modelSerializers = sharedModels.map { m =>
      s"implicit val codec${m}: Codec.AsObject[${m}] = deriveCodec[${m}]"
    }

    val packageSpace = packageName

     val classSource = s"""package ${packageSpace}.models
        |
        |${GeneratorTemplates.autoGeneratedComment}        
        |
        |import io.circe.Codec
        |import io.circe.generic.semiauto.deriveCodec
        |
        |object ModelSerializers {
        |
        |  implicit val codecTimestamp: Codec.AsObject[escalator.util.Timestamp] = deriveCodec[escalator.util.Timestamp]
        |  implicit val codecCorrelationId: Codec.AsObject[escalator.models.CorrelationId] = deriveCodec[escalator.models.CorrelationId]
        |
        |  ${modelTypeSerializers.mkString("\n|  ")}
        |
        |  ${customModelTypeSerializers.mkString("\n|  ")}
        |        
        |  ${modelSerializers.mkString("\n|  ")}
        |
        |}
      """.stripMargin    

      writeIfDoesNotExist(scalaFilePath, classSource)
  }

  def applyFixes(fileData: String) = {
    // TODO: fix multi key foreign key
    customGen.processFileData(fileData)
  }

  def writeIfDoesNotExist(filePath: String, fileData: String) = {
    val fixedContent = applyFixes(fileData)

    FileUtil.writeIfDoesNotExist(filePath, fixedContent)
  }

  def run() = {
    val codegenOptions = options

    // codegenOptions.file.foreach { x =>
    //   outstream.println("Starting...")
    // }

    val startTime = System.currentTimeMillis()
    Class.forName(codegenOptions.jdbcDriver)
    val db: Connection =
      DriverManager.getConnection(codegenOptions.url,
                                  codegenOptions.user,
                                  codegenOptions.password)

    // Initialize auto-generated column attribute type mappings from database constraints
    val autoMappings = ConnectionUtils.getColumnAttributeTypeMappingsFromConstraints(db)
    customGen.setAutoGeneratedColumnMappings(autoMappings)

    // val namingStrategy = GeneratorNamingStrategy
    // val codegen = CodeGenerator(codegenOptions, namingStrategy)

    cleanupExistingGeneratedFiles()

    val plainForeignKeys = getForeignKeys(db)

    println("plainForeignKeys:" + plainForeignKeys.size)
    // println(plainForeignKeys)

    // val crossKeys = 
      // codegen.getCrossReferenceKeys(db)
    // println("crossKeys:" + crossKeys.size)    

    val foreignKeys = plainForeignKeys.map { fk =>
      def resolve(col: SimpleColumn): SimpleColumn = {
        plainForeignKeys.find(f => f.from == col).map(_.to).getOrElse(col)
      }

      ForeignKey(fk.from, resolve(fk.to))
    }

    println(foreignKeys)

    // Generate AttributeTypes FIRST - this must run before other generation
    generateAttributeTypes(db)

    val allTables = getTables(db, foreignKeys)

    val tables = allTables.filter { _.name != "attributes" }.toList

    val simpleCaseClasses = generateCaseCasses(tables).toList

    println(simpleCaseClasses)

    val sharedModelTypes = MList.empty[String]
    val sharedModels = MList.empty[String]

    /////////////// add the cats stuff here

    val packageSpace = codegenOptions.packageName


    simpleCaseClasses.foreach { scc =>
      val classSource = s"""
        |package ${packageSpace}.models
        |
        |${GeneratorTemplates.autoGeneratedComment()}  
        |
        |${scc.uniqueKeyCaseClasses.mkString("\n|")}  
        |
        |${scc.columnCaseClasses.mkString("\n|")}
        |
        |${scc.mainCaseClass}
        |
        |${scc.mainObjectClass}
        |
        |${scc.extraCaseClasses}
        |
      """.stripMargin

      sharedModelTypes ++= scc.uniqueKeyCaseClasses.map(extractClassName(_))
      sharedModelTypes ++= scc.columnCaseClasses.map(extractClassName(_))

      val scalaFilePath = modelsBaseFolder+s"/${namingStrategy.table(scc.tableName)}.scala"

      sharedModels += namingStrategy.table(scc.tableName)

      val formattedCaseClass = formatCode(classSource)

      FileUtil.createDirectoriesForFile(scalaFilePath)

      writeIfDoesNotExist(scalaFilePath, formattedCaseClass)
    }


    // val simpleCaseClasses = codegen.generateCaseCasses(tables).toList

    // val caseClasses = simpleCaseClasses.map(_.mainCaseClass).toList

    generateSerializer(sharedModelTypes.toList,sharedModels.toList)

    generateDatabaseDao(tables)

    generateTableDaos(tables)

    println("generateAppRepositories:" + options.generateAppRepositories)

    if (options.generateAppRepositories) {
      generateAppRepositories(tables)
    }

    println("generateEvents:" + options.generateEvents)
    if (options.generateEvents) {
      generateModelEvents(tables)
    }

    println("generateAggregates:" + options.generateAggregates)
    if (options.generateAggregates && options.aggregateRootTables.nonEmpty) {
      println(s"Generating ${options.aggregateRootTables.size} aggregate roots: ${options.aggregateRootTables.mkString(", ")}")

      val aggGen = new AggregateGenerator(allTables.toList,namingStrategy,options,customGen)

      options.aggregateRootTables.foreach { rootTable =>
        aggGen.generateAggregateRoot(rootTable, "id", options.maxAggregateDepth)
      }
    }


    // val generatedCode = codegen.tables2code(tables, CustomNamingStrategy, codegenOptions)

    // println("generatedCode:" + generatedCode)

    // val codeStyle = ScalafmtStyle.defaultWithAlign.copy(maxColumn = 120)
    // val code = Scalafmt.format(generatedCode, style = codeStyle) match {
    //   case FormatResult.Success(x) => x
    //   case _ => generatedCode
    // }
    // codegenOptions.file match {
    //   case Some(uri) =>
    //     Files.write(Paths.get(new File(uri).toURI), code.getBytes)
    //     println(
    //       s"Done! Wrote to $uri (${System.currentTimeMillis() - startTime}ms)")
    //   case _ =>
    //     outstream.println(code)
    // }

    db.close()    
  }

//   import org.apache.commons.csv._
// import java.io.FileReader
// import java.util.UUID
// import java.time.Instant

// object EntityGenerator {
  
//   case class Reference(tableName: String, columnName: String)

//   def main(args: Array[String]): Unit = {
//     val csvFilePath = "Result 2025-01-29 15-11-37.csv"  // Change to your actual file path

//     // Read CSV file and extract references to "entities"
//     val references = extractReferences(csvFilePath, "entities")

//     // Generate Scala case class
//     val caseClassCode = generateEntityCaseClass(references)

//     // Print the generated code
//     println(caseClassCode)
//   }

//   /** Reads the CSV and extracts references to a given table */
//   def extractReferences(csvFilePath: String, referencedTable: String): List[Reference] = {
//     val reader = new FileReader(csvFilePath)
//     val csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader())

//     val references = csvParser.getRecords
//       .toArray
//       .map(_.asInstanceOf[CSVRecord])
//       .filter(record => record.get("referenced_table_name") == referencedTable)
//       .map(record => Reference(record.get("table_name"), record.get("column_name")))
//       .toList

//     csvParser.close()
//     reader.close()
//     references
//   }

//   /** Generates a Scala case class for Entity based on references */
//   def generateEntityCaseClass(references: List[Reference]): String = {
//     val baseClass =
//       """import java.util.UUID
//         |import java.time.Instant
//         |
//         |// Aggregate Root: Entity
//         |case class Entity(
//         |    id: UUID,
//         |    name: String,  // Assuming 'entities' table has a 'name' column
//         |    createdAt: Instant,""".stripMargin

//     val referenceClasses = references.map { ref =>
//       val className = ref.tableName.split("_").map(_.capitalize).mkString
//       s"case class $className(entityId: UUID, someData: String) // Example field"
//     }.mkString("\n")

//     val referenceFields = references.map { ref =>
//       val className = ref.tableName.split("_").map(_.capitalize).mkString
//       val fieldName = ref.tableName.split("_").map(_.toLowerCase).mkString
//       s"    ${fieldName}: List[$className] = List.empty,"
//     }.mkString("\n")

//     val caseClassEnd = ")"

//     s"$referenceClasses\n\n$baseClass\n$referenceFields\n$caseClassEnd"
//   }
// }

  // case class Reference(tableName: String, columnName: String)

  
  /**
   * Clean aggregate files
   */
  // def resetAggregates(codegenOptions: CodegenOptions, customGen: CustomGenerator): Unit = {
  //   if (codegenOptions.aggregatesFolder.nonEmpty) {
  //     println(s"Cleaning aggregate files in ${codegenOptions.aggregatesFolder}")
  //     cleanupAggregateFiles(codegenOptions.aggregatesFolder)
  //   } else {
  //     println("No aggregates folder configured")
  //   }
  // }
  
  // def cleanupAggregateFiles(aggregatesFolder: String): Unit = {
  //   import java.io.File
    
  //   val folder = new File(aggregatesFolder)
  //   if (folder.exists() && folder.isDirectory) {
  //     folder.listFiles().foreach { subFolder =>
  //       if (subFolder.isDirectory) {
  //         val scalaFiles = FileUtil.getListOfFiles(subFolder, List("scala"))
  //         scalaFiles.foreach { scalaFile =>
  //           val content = FileUtil.read(scalaFile.getPath)
  //           if (content.contains(GeneratorTemplates.autoGeneratedCommentTracker)) {
  //             println(s"Deleting generated aggregate file: ${scalaFile.getPath}")
  //             FileUtil.delete(scalaFile.getPath)
  //           }
  //         }
  //       }
  //     }
  //   }
  // }

  //////////////////////////

}


object CodeGenerator {
  import TextUtil._
 
  def cliRun(codegenOptions: CodegenOptions,customGen: CustomGenerator): Unit = {
    try {
      run(codegenOptions,customGen)
    } catch {
      case Error(msg) =>
        System.err.println(msg)
        System.exit(1)
    }
  }

  def reset(codegenOptions: CodegenOptions,customGen: CustomGenerator): Unit = {
    println("running reset")

    customGen.setup()

    val namingStrategy = GeneratorNamingStrategy
    val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)

    codegen.cleanupExistingGeneratedFiles()
  }

  def run(codegenOptions: CodegenOptions,customGen: CustomGenerator): Unit = {
    customGen.setup()

    val namingStrategy = GeneratorNamingStrategy
    val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)

    // println("dbgen.reset :" + System.getProperty("dbgen.reset"))
    // println("dbgenreset :" + System.getProperty("dbgenreset"))

    val shouldReset = (System.getProperty("dbgen.reset") == "true" || System.getProperty("dbgenreset") == "true")

    if (shouldReset){
      reset(codegenOptions,customGen)
      return;
    }

    codegen.run()
  }

  // def rootGen(codegenOptions: CodegenOptions,customGen: CustomGenerator,rootTable: String, uniqueId: String): Unit = {
  //   customGen.setup()

  //   val namingStrategy = GeneratorNamingStrategy
  //   val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)

  //   codegen.generateAggregateRoot(rootTable,uniqueId)
  // }
  
  // /**
  //  * Generate a single aggregate root from a specific table
  //  */
  // def generateAggregate(codegenOptions: CodegenOptions, customGen: CustomGenerator, rootTableName: String, maxDepth: Int = 3): Unit = {
  //   customGen.setup()
    
  //   val namingStrategy = GeneratorNamingStrategy
  //   val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)
    
  //   codegen.generateAggregateRoot(rootTableName, "id", maxDepth)
  // }
  
  /**
   * Generate all configured aggregate roots
   */
  // def generateAggregates(codegenOptions: CodegenOptions, customGen: CustomGenerator): Unit = {
  //   if (!codegenOptions.generateAggregates || codegenOptions.aggregateRootTables.isEmpty) {
  //     println("Aggregate generation disabled or no root tables configured")
  //     return
  //   }
    
  //   println(s"Generating ${codegenOptions.aggregateRootTables.size} aggregate roots: ${codegenOptions.aggregateRootTables.mkString(", ")}")
    
  //   codegenOptions.aggregateRootTables.foreach { rootTable =>
  //     generateAggregate(codegenOptions, customGen, rootTable, codegenOptions.maxAggregateDepth)
  //   }
  // }
}


