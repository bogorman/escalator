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

import org.scalafmt.interfaces.Scalafmt
import scala.meta._
import scala.reflect.classTag

import scala.collection.mutable.{ ArrayBuffer => MList }
import scala.collection.mutable.{ Map => MMap }

case class CodegenOptions(
    packageName: String,
    appName: String,
    appFolder: String, 
    modelsBaseFolder: String, 
    persistenceBaseFolder: String, 
    databaseFolder: String, 
    postgresFolder: String,
    //
    user: String, //= "postgres",
    password: String, //= "postgres",
    url: String, // = "jdbc:postgresql:postgres",
    schema: String = "public",
    jdbcDriver: String = "org.postgresql.Driver",
    imports: String = """import io.getquill.WrappedValue""",
    `package`: String = "tables",
    excludedTables: List[String] = List("schema_version","flyway_schema_history"),
    file: Option[String] = None
)

case class Error(msg: String) extends Exception(msg)

case class UniqueKey(keyName: String, tableName: String, cols: List[SimpleColumn]) {
  def containsColumn(name: String): Boolean = {
    // false
    cols.filter { c => c.columnName == name }.size == 1
  } 
}


case class IndexKey(name: String, col: SimpleColumn)
case class SimpleCaseClass(tableName: String,mainCaseClass: String, mainObjectClass: String, columnCaseClasses: List[String], uniqueKeyCaseClasses: List[String], extraCaseClasses: String)
case class ForeignKey(from: SimpleColumn, to: SimpleColumn)


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

case class Table(customGen: CustomGenerator,options: CodegenOptions,name: String, tableColumns: Seq[Column], uniqueKeys: Set[UniqueKey], isAbstract: Boolean, inheritedFromTable: Option[Table], db: Connection) {
  import TextUtil._
  val namingStrategy = GeneratorNamingStrategy

  def columnCaseClasses(): List[String] = {
    val caseClasses = tableColumns.filter(_.shouldDefineType).map { col =>
      println("shouldDefineType:" + name + ":" + col.columnName + " " + col.toDefn(name, true))
      val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
      println(cc)
      cc
    }
    caseClasses.toList
  }
  

  // def showColumnCaseClasses(): List[String] = {
  //   val caseClasses = tableColumns.filter(_.shouldDefineType).map { col =>
  //     // println("shouldDefineType:" + name + ":" + col.columnName + " " + col.toDefn(name, true))
  //     // val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
  //     // println(cc)
  //     // cc
  //   }
  //   caseClasses.toList
  // }  


  // def includesColumn(colName: String): Boolean = {
  //   columns.filter(_.cols != List(SimpleColumn(name,colName))).size > 0
  // }

  def hasUniqueKeysExcludingPrimaryKey() = {
    uniqueKeysExcludingPrimaryKey().size > 0
  }

  def hasIdColumn(): Boolean = {
    columns.filter { col => col.columnName == "id" }.size == 1
  }

  def uniqueKeysExcludingPrimaryKey() = {
    //assuming primary keu is ID
    uniqueKeys.filter(_.cols != List(SimpleColumn(name,"id")))
  }

  def nonKeyColumns() = {
    columns.filter { col => !col.isPrimaryKey && !col.hasUniqueKey && !col.isAutoColumn}
  }

  def uniqueKeyCaseClasses(): List[String] = {
    val ukKeys = uniqueKeys.filter(_.cols.size > 1)

    // var index = 0
    val caseClasses = ukKeys.map { uk =>
      println("uniqueKeyCaseClasses:" + uk)

      // val suffix = if (index > 0){
      //   s"${index}"
      // } else {
      //   ""
      // }
      // index = index + 1

      // val scalaTableName = namingStrategy.table(name)
      // val caseClassName = s"${scalaTableName}UniqueKey${suffix}"

      val caseClassName = makeUniqueKeyClassName(uk)

      val args = uk.cols.map { scol =>
        val col = findColumn(scol.columnName)
        col.toArg(namingStrategy, name, true)
      }.mkString(", ")           

      // val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
      // println(cc)
      // cc
      val caseClass = s"case class $caseClassName($args)"

      println(caseClass)

      caseClass      
    }
    caseClasses.toList
  }

  def makeUniqueKeyClassName(uk: UniqueKey) = {
    val suffix = uk.cols.map( c => namingStrategy.table(c.columnName) ).mkString("")

    val scalaTableName = namingStrategy.table(name)
    val caseClassName = s"${scalaTableName}UniqueKey${suffix}"

    caseClassName
  }

  def mainCaseClass(): String = {
    val scalaName = namingStrategy.table(name)

    println("toCaseClass scalaName:" + scalaName)

    val autoColumn = tableColumns.filter { c => c.isAutoColumn && !c.isExtraColumn}
    val noAutoColumns = tableColumns.filter { c => !c.isAutoColumn && !c.isExtraColumn}
    val extraDefaultColumns = tableColumns.filter { c => c.isExtraColumn }

    val primaryArgs = (noAutoColumns ++ autoColumn).map { col =>
      col.toArg(namingStrategy, name, true)
    }

    val extraArgs = (extraDefaultColumns).map { col =>
      col.toArg(namingStrategy, name, true) + " = None"
    }

    val args = (primaryArgs ++ extraArgs).mkString(", ")

    val caseClass = s"case class $scalaName($args)"
    caseClass
  }

  def mainObjectClass(): String = {
    val scalaName = namingStrategy.table(name)

    val primaryKeyColumns = tableColumns.filter { c => c.isPrimaryKey && !c.isExtraColumn }
    val requiredColumns = tableColumns.filter { c => !c.isPrimaryKey && !c.isAutoColumn  && !c.isExtraColumn }

    val autoColumns = tableColumns.filter { c => c.isAutoColumn && !c.isExtraColumn }

    val extraDefaultColumns = tableColumns.filter { c => c.isExtraColumn }

    val primaryKeyColumnOpt: Option[Column] = primaryKeyColumns.headOption

    val primaryKeyColArgOpt: Option[String] = primaryKeyColumnOpt.map { col => col.toDefn(col.tableName, true) }

    val requiredObjectArgs = requiredColumns.map { col =>
      col.toArg(namingStrategy, name, true)
    }

    val extraObjectArgs = extraDefaultColumns.map { col =>
      col.toArg(namingStrategy, name, true)
    }

    val objectArgs = (requiredObjectArgs ++ extraObjectArgs).mkString(", ")     

    val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(0L),").getOrElse("")
    val autoInsertedAtCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"escalator.models.Timestamp(0L),").getOrElse("")
    val autoUpdatedAtCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"escalator.models.Timestamp(0L),").getOrElse("")

    val objectClass = if (inheritedFromTable.isDefined){
      val inheritedRequiredColumns = requiredColumns.filter { c => c.inheritedFromColumn.isDefined }

      val objClassInheritedArgsWithDefaults = inheritedRequiredColumns.map { col =>
        namingStrategy.column(col.columnName)
      }.mkString(", ")  


      val objClassExtraColsArgsWithDefaults = extraDefaultColumns.map { col =>
        namingStrategy.column(col.columnName)
      }.mkString(", ")         

      val directRequiredColumns = requiredColumns.filter { c => !c.inheritedFromColumn.isDefined }

      val objClassDirectArgsWithDefaults = directRequiredColumns.map { col =>
        namingStrategy.column(col.columnName)
      }.mkString(", ")        

      s"""object $scalaName {

        def apply(${objectArgs}): ${scalaName} = {
          ${scalaName}(
            ${primaryColCreator}
            ${objClassInheritedArgsWithDefaults},
            ${autoInsertedAtCreator}
            ${autoUpdatedAtCreator}
            ${objClassExtraColsArgsWithDefaults}
            ${objClassDirectArgsWithDefaults}
          )
        }
      }
      """
    } else {
      val objClassArgsWithDefaults = requiredColumns.map { col =>
        namingStrategy.column(col.columnName)
      }.mkString(", ")  

      val objClassExtraColsArgsWithDefaults = extraDefaultColumns.map { col =>
        namingStrategy.column(col.columnName)
      }.mkString(", ")        

      val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(0L),").getOrElse("")

      s"""object $scalaName {

        def apply(${objectArgs}): ${scalaName} = {
          ${scalaName}(
            ${primaryColCreator}
            ${objClassArgsWithDefaults},
            ${autoInsertedAtCreator}
            ${autoUpdatedAtCreator} 
            ${objClassExtraColsArgsWithDefaults}
          )
        }
      }
      """
    }
    objectClass
  }

  def formatConst(str: String) = {
    str.replace("@","_")
  }

  def extraObjects(): String = {
    val uks = uniqueKeys.filter { k => k.keyName.contains("_enum") }

    val objs = uks.map { uk =>
      val tn = uk.tableName
      val cn = uk.cols.head.columnName

      val list = ConnectionUtils.getStringList(db,s"select ${cn} from ${tn}")

      val col = findColumn(cn)

      val caseClass = col.toDefn(tn, true)

      val typeDefinitions = list.map { i =>
        s"""val ${formatConst(i)} = ${caseClass}("${i}")""" 
      }.mkString("\n")

      s"""
      import cats.Show
      object ${caseClass} {
        implicit val defaultShow: Show[${caseClass}] = _.${cn.toLowerCase}

        implicit def strTo${caseClass}(str: String): ${caseClass} = {
          ${caseClass}(str)
        }        
        ${typeDefinitions}
      }
      """
    }.toList

    objs.mkString("\n")
  }

  def toCaseClass(): SimpleCaseClass = {
    println("toCaseClass name:" + name)
    SimpleCaseClass(name,mainCaseClass(),mainObjectClass(),columnCaseClasses(),uniqueKeyCaseClasses(),extraObjects()) 
  }

  def findColumn(name: String): Column = {
    println("name: " + name)
    println("columns: " + columns.map(_.columnName))

    val c = columns.filter(_.columnName == name).headOption.get
    c
  }

  def findColumnOpt(name: String): Option[Column] = {
    columns.filter(_.columnName == name).headOption
  }


  def columns(): Seq[Column] = {
    tableColumns.map { c => 
      if (c.inheritedFromColumn.isDefined){
        c.inheritedFromColumn.get
      } else {
        c
      }
    }
  }

  def primaryKeyClass(): Option[String] = {
    val primaryKeyColumns = tableColumns.filter { c => c.isPrimaryKey }    
    if (primaryKeyColumns.headOption.isEmpty){
      None
    } else {
      val primaryKeyColumn = primaryKeyColumns(0)
      val primaryKeyColArg = primaryKeyColumn.toDefn(primaryKeyColumn.tableName, true)
      Some(primaryKeyColArg)
    }
  }

}


case class Column(customGen: CustomGenerator,
                  tableName: String,
                  columnName: String,
                  scalaType: String,
                  nullable: Boolean,
                  primaryKey: Boolean,
                  hasUniqueKey: Boolean,
                  references: Option[SimpleColumn],
                  incomingReferences: List[SimpleColumn],
                  inheritedFromTable: Option[Table] = None,
                  inheritedFromColumn: Option[Column] = None,                  
                ) {
  import TextUtil._
  val namingStrategy = GeneratorNamingStrategy

  def scalaOptionType = {
    makeOption(scalaType)
  }

  def isPrimaryKey(): Boolean = {
    if (columnName == "id"){
      true
    } else {
      primaryKey
    }
  }

  def makeOption(typ: String): String = {
    if (nullable) {
      s"Option[$typ]"
    } else {
      typ
    }
  }

  def toDefn(tableName: String, includeRef: Boolean = false): String = {
    if (inheritedFromColumn.isDefined){
      val ic = inheritedFromColumn.get
      ic.toDefn(ic.tableName, includeRef)
    } else if (includeRef && references.isDefined){
      // println("HERE1")
      s"${references.get.toType}"
    } else if (includeRef && (shouldTypeifyColumn() || incomingReferences.size > 0)){
      // println("HERE2")
      s"${SimpleColumn(tableName,columnName).toType}"
    } else {
      // println("HERE3")
      s"${scalaOptionType}"
    }
  }

  def toArg(namingStrategy: NamingStrategy, tableName: String, includeRef: Boolean = false, mutate: Boolean = false): String = {
    if (inheritedFromColumn.isDefined){
      val ic = inheritedFromColumn.get
      ic.toArg(namingStrategy,ic.tableName,includeRef,mutate)
    } else if (includeRef && references.isDefined){
      s"${fix(mutate,namingStrategy.column(columnName))}: ${makeOption(references.get.toType)}"
    } else if (includeRef && (shouldTypeifyColumn() || incomingReferences.size > 0)){
      s"${fix(mutate,namingStrategy.column(columnName))}: ${makeOption(SimpleColumn(tableName,columnName).toType)}"
    } else {
      s"${fix(mutate,namingStrategy.column(columnName))}: ${scalaOptionType}"
    }
  }

  def fix(mutate: Boolean, arg: String): String = {
    if (mutate && arg == "quote"){
      "quote_arg"
    } else {
      arg  
    }
    
  }

  def shouldTypeifyColumn() = {
    // columnName == "id"
    (isPrimaryKey || hasUniqueKey) && references.isEmpty
  }

  def hasSpecificType() = {
    shouldTypeifyColumn() || references.isDefined || incomingReferences.size > 0
  }

  def shouldDefineType(): Boolean = {
    if (inheritedFromColumn.isDefined){
      false
    } else {
      shouldTypeifyColumn() || incomingReferences.size > 0
    }
  }

  def isAutoColumn(): Boolean = {
    columnName == "created_at" || columnName == "updated_at"
  }

  def isExtraColumn(): Boolean = {
    //make generic! and move into app a way to override
    if (customGen == null){
      false
    } else {
      customGen.useDefaultValue(tableName,columnName)
    }
    // tableName == "candidate_references" && (columnName == "email_result_message" || columnName == "interaction_id")
  }


  // def toSimple = {
  //   references.getOrElse(SimpleColumn(tableName, columnName))
  // }

  // def toType: String = {
  //   this.toSimple.toType
  // }

  // def toCaseClass: String = {
    // s"case class ${namingStrategy.table(columnName)}(value: $scalaType) extends AnyVal with WrappedValue[$scalaType]"
  
    // case class ${namingStrategy.table(columnName)}(value: $scalaType) 
    // s"case class ${namingStrategy.table(columnName)}(value: $scalaType)"
  // }
}


object ConnectionUtils {

  def getAbstractTables(db: Connection): List[String] = {
    val sql = """
      WITH inherited AS (
      SELECT
          nmsp_parent.nspname AS parent_schema,
          parent.relname      AS parent,
          nmsp_child.nspname  AS child_schema,
          child.relname       AS child
      FROM pg_inherits
          JOIN pg_class parent            ON pg_inherits.inhparent = parent.oid
          JOIN pg_class child             ON pg_inherits.inhrelid   = child.oid
          JOIN pg_namespace nmsp_parent   ON nmsp_parent.oid  = parent.relnamespace
          JOIN pg_namespace nmsp_child    ON nmsp_child.oid   = child.relnamespace
    )
    select distinct(parent) from inherited
    """

    getStringList(db,sql)
  }

  def getInheritedTables(db: Connection): List[String] = {
    val sql = """
    WITH inherited AS (
      SELECT
          nmsp_parent.nspname AS parent_schema,
          parent.relname      AS parent,
          nmsp_child.nspname  AS child_schema,
          child.relname       AS child
      FROM pg_inherits
          JOIN pg_class parent            ON pg_inherits.inhparent = parent.oid
          JOIN pg_class child             ON pg_inherits.inhrelid   = child.oid
          JOIN pg_namespace nmsp_parent   ON nmsp_parent.oid  = parent.relnamespace
          JOIN pg_namespace nmsp_child    ON nmsp_child.oid   = child.relnamespace
    )
    select distinct(child) from inherited
    """

    getStringList(db,sql)
  }

  def getInheritedTablesMappings(db: Connection): Map[String,String] = {
    val sql = """
      SELECT
          parent.relname      AS parent,
          child.relname       AS child
      FROM pg_inherits
          JOIN pg_class parent            ON pg_inherits.inhparent = parent.oid
          JOIN pg_class child             ON pg_inherits.inhrelid   = child.oid
          JOIN pg_namespace nmsp_parent   ON nmsp_parent.oid  = parent.relnamespace
          JOIN pg_namespace nmsp_child    ON nmsp_child.oid   = child.relnamespace
    """

    val stmt = db.createStatement()
    val rs = stmt.executeQuery(sql)

    val r = MMap.empty[String,String]
    while (rs.next()) {
      val parent = rs.getString(1)
      val child = rs.getString(2)

      r += (child -> parent)
    }
    r.toMap
  }

  def getStringList(db: Connection, sql: String): List[String] = {
    // List()
    // db.get
    val stmt = db.createStatement()
    val rs = stmt.executeQuery(sql)

    val r = MList.empty[String]
    while (rs.next()) {
      r += rs.getString(1)
    }
    r.toList
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

    tables.flatMap { name => 
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

      println("indexName:" + indexName)
      println("columnName:" + columnName)

      println("nonUnique:" + nonUnique)
      println("typeCode:" + typeCode)
      println("ordinalPosition:" + ordinalPosition)


      // rs.getString("INDEX_NAME") to extract index name
      // rs.getBoolean("NON_UNIQUE") to extract unique information
      // rs.getShort("TYPE") to extract index type
      // rs.getInt("ORDINAL_POSITION") to extract ordinal position

      IndexKey(indexName, SimpleColumn(tableName,columnName))
    }.toList

    val validIndices = indices.filter{ i => !i.name.startsWith("inherited_") && !i.name.startsWith("ignore_") }

    val groupedIndixies: Map[String, Seq[IndexKey]] = validIndices.groupBy(_.name)
    // validIndices.groupBy(_.name)
    groupedIndixies.map { case (key,values) => 
      // values
      // values.map { indexCols =>
        UniqueKey(key,tableName,values.map(_.col).toList)
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
        case q"case class $tname (...$paramss)" => tname.value
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
        case q"case class $tname (...$paramss)" => tname.value
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

  def cleanupExistingGeneratedFiles() = {
    cleanupExistingGeneratedFilesInFolder(modelsBaseFolder)
    cleanupExistingGeneratedFilesInFolder(databaseFolder)
    cleanupExistingGeneratedFilesInFolder(databaseFolder+"/tables")
    cleanupExistingGeneratedFilesInFolder(postgresFolder)
    cleanupExistingGeneratedFilesInFolder(postgresFolder+"/tables")
  }

  def cleanupExistingGeneratedFilesInFolder(folder: String) = {
    val f = new File(folder)
    val scalaFiles = FileUtil.getListOfFiles(f, List("scala"))

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
    // println("scalaFiles")
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

  def generateSerializer(sharedModelTypes: List[String],sharedModels: List[String]) = {
    val scalaFilePath = modelsBaseFolder+s"/ModelSerializers.scala"

    val modelTypeSerializers = sharedModelTypes.map { mt =>
      s"implicit val codec${mt}: Codec.AsObject[${mt}] = deriveCodec[${mt}]"
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
        |  implicit val codecTimestamp: Codec.AsObject[escalator.models.Timestamp] = deriveCodec[escalator.models.Timestamp]
        |
        |  ${modelTypeSerializers.mkString("\n|  ")}
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

    // val namingStrategy = GeneratorNamingStrategy
    // val codegen = CodeGenerator(codegenOptions, namingStrategy)


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

    val tables = getTables(db, foreignKeys)

    val simpleCaseClasses = generateCaseCasses(tables).toList

    println(simpleCaseClasses)


    cleanupExistingGeneratedFiles()

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

  //////////////////////////

}
