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
    file: Option[String] = None,
    // Event generation options
    generateEvents: Boolean = true,
    generateAppRepositories: Boolean = true,
    repositoriesFolder: String = "", // e.g. "modules/core/src/main/scala/fun/slashfun/core/repositories/postgres"
    
    // Aggregate generation options
    generateAggregates: Boolean = false,
    aggregatesFolder: String = "", // e.g. "modules/core/src/main/scala/aggregates"
    aggregateRootTables: List[String] = List.empty, // e.g. List("users", "orders", "products")
    maxAggregateDepth: Int = 3,
    generatePekkoActors: Boolean = false,
    aggregateBoundaryHints: Map[String, Boolean] = Map.empty
)

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

case class Table(customGen: CustomGenerator,options: CodegenOptions,name: String, tableColumns: Seq[Column], allUniqueKeys: Set[UniqueKey], isAbstract: Boolean, inheritedFromTable: Option[Table], db: Connection) {
  import TextUtil._
  val namingStrategy = GeneratorNamingStrategy

  def uniqueKeys = {
    allUniqueKeys.filter(_.partial == false)
  }

  def columnCaseClasses(): List[String] = {
    val caseClasses = tableColumns.filter(_.shouldDefineType).map { col =>
      println("shouldDefineType:" + name + ":" + col.columnName + " " + col.toDefn(name, true))
      val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
      println(cc)
      cc
    }
    caseClasses.toList
  }
  

  def hasColumn(columnName: String): Boolean = {
    tableColumns.find(c => c.columnName == columnName).isDefined
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
        println("scol: " + scol.columnName)

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

    val caseClass = s"case class $scalaName($args) extends Persisted"
    caseClass
  }

  def defaultConstructorValue(col: Column): String = {
    if (col.scalaType == "java.util.UUID") {
      "escalator.util.RichUUID.BLANK_UUID"
    } else {
      "0L"
    }
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

    val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(${defaultConstructorValue(primaryKeyColumnOpt.get)}),").getOrElse("")

    val autoInsertedAtCreator = autoColumns.find(c => c.columnName == "created_at").map(c => s"escalator.util.Timestamp(0L),").getOrElse("")
    val autoUpdatedAtCreator = autoColumns.find(c => c.columnName == "updated_at").map(c => s"escalator.util.Timestamp(0L),").getOrElse("")

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

      val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(${defaultConstructorValue(primaryKeyColumnOpt.get)}),").getOrElse("")

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

  def findColumn(colName: String): Column = {
    println("colName: " + colName)
    println("columns: " + columns.map(_.columnName))

    val cOpt = columns.filter(_.columnName == colName).headOption

    if (cOpt.isEmpty){
      throw new Exception(s"Missing column ${colName} on Table ${name}(${columns.map(_.columnName).mkString(",")})")
    }

    cOpt.get
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

  def primaryKeyCol(): Option[Column] = {
    val primaryKeyColumns = tableColumns.filter { c => c.isPrimaryKey && !c.isExtraColumn }
    primaryKeyColumns.headOption
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
    } else if (shouldUseAttributeType()) {
      // Use generated AttributeType class
      getAttributeTypeName()
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
    } else if (shouldUseAttributeType()) {
      // Use generated AttributeType class
      s"${fix(mutate,namingStrategy.column(columnName))}: ${getAttributeTypeName()}"
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
  
  def shouldUseAttributeType(): Boolean = {
    // Check if this column should use a generated AttributeType
    // This includes:
    // 1. Columns with attribute_type schema type
    // 2. Columns that reference the attributes table attr_type column
    
    println(s"shouldUseAttributeType ${tableName}.${columnName} ${scalaType} references=${references.map(r => r.tableName + "." + r.columnName)}")
    
    val isAttributeTypeColumn = (scalaType == "AttributeType" || scalaType.endsWith(".AttributeType"))
    val referencesAttributeTable = references.isDefined && references.get.tableName == "attributes" && references.get.columnName == "attr_type"
    
    isAttributeTypeColumn || referencesAttributeTable
  }
  
  def getAttributeTypeName(): String = {
    println(s"getAttributeTypeName ${tableName}.${columnName} ${scalaType} references=${references.map(r => r.tableName + "." + r.columnName)}")
    
    // Case 1: This is the attr_type column in attributes table
    if (columnName == "attr_type") {
      return "AttributeType"
    }
    
    // Case 2: This column references attributes.attr_type
    if (references.isDefined && references.get.tableName == "attributes" && references.get.columnName == "attr_type") {
      // Check for explicit mapping from CustomGenerator
      val tableColumnKey = s"${tableName}.${columnName}"
      val customMappings = customGen.columnAttributeTypeMappings()
      
      customMappings.get(tableColumnKey) match {
        case Some(attributeTypeName) =>
          println(s"Found custom attribute type mapping: $tableColumnKey -> $attributeTypeName")
          return attributeTypeName
        case None =>
          println(s"No custom mapping for $tableColumnKey")
      }
      
      // Try to infer from column name patterns
      val inferredAttrType = if (columnName.endsWith("_type")) {
        columnName.replace("_type", "").toUpperCase.replace("_", "_") + "_TYPE"
      } else {
        // For columns like "source", we can't easily infer the type
        // Would need constraint analysis or explicit mapping
        columnName.toUpperCase.replace("_", "_") + "_TYPE" 
      }
      
      // Check if we have a mapping for this inferred attribute type
      // attributeTypeMapping.get(inferredAttrType) match {
      //   case Some(className) => 
      //     println(s"Found inferred attribute type mapping: $inferredAttrType -> $className")
      //     return className
      //   case None =>
      //     println(s"No attribute type mapping found for $inferredAttrType, using generic AttributeType")
      //     return "AttributeType"
      // }
    }
    
    // Case 3: This is a direct attribute_type schema column
    val baseName = columnName.replace("_type", "").split("_").map(_.capitalize).mkString("") + "Type"
    println(s"getAttributeTypeName result: ${baseName}")
    baseName
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

  case class Reference(fromTableName: String, fromColName: String, toTableName: String, toColumnName: String, referneceName: String)

  def getReferences(db: Connection, tableName: String, idCol: String): List[Reference] = {
    // val tbl = TextUtil.pluralize(tableName).toLowerCase
    val tbl = tableName.toLowerCase

    val sql = s"""
      SELECT
          conname AS constraint_name,
          conrelid::regclass AS table_name,
          a.attname AS column_name,
          confrelid::regclass AS referenced_table_name,
          af.attname AS referenced_column_name
      FROM pg_constraint
      JOIN pg_attribute a ON a.attnum = ANY(pg_constraint.conkey) AND a.attrelid = pg_constraint.conrelid
      JOIN pg_attribute af ON af.attnum = ANY(pg_constraint.confkey) AND af.attrelid = pg_constraint.confrelid
      where confrelid::regclass = '${tbl}'::regclass  
      and conrelid::regclass != '${tbl}'::regclass    
      """

      val stmt = db.createStatement()
      val rs = stmt.executeQuery(sql)

      val l = MList.empty[Reference]
      while (rs.next()) {
        val constraintName = rs.getString(1)
        val tableName = rs.getString(2)
        val columnName = rs.getString(3)
        val referencedTableName = rs.getString(4)
        val referencedColumnName = rs.getString(5)

        l += Reference(tableName,columnName,referencedTableName,referencedColumnName,constraintName)
      }
      l.toList
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
      options.aggregateRootTables.foreach { rootTable =>
        generateAggregateRoot(rootTable, "id", options.maxAggregateDepth)
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

  def generateAggregateRoot(rootTableName: String, uniqueId: String = "id", maxDepth: Int = 3): Unit = {
    val startTime = System.currentTimeMillis()
    Class.forName(options.jdbcDriver)
    val db: Connection = DriverManager.getConnection(options.url, options.user, options.password)
    
    try {
      println(s"Generating aggregate root for table: $rootTableName")
      
      // 1. Build reference tree starting from root table
      val referenceTree = buildReferenceTree(db, rootTableName, maxDepth)
      println(s"Reference tree: $referenceTree")
      
      // 2. Generate aggregate state class
      val aggregateName = namingStrategy.table(rootTableName)
      val baseStateCode = generateBaseAggregateState(rootTableName, referenceTree)
      
      // 3. Generate event handlers
      val eventHandlerCode = generateAggregateEventHandler(rootTableName, referenceTree)
      
      // 4. Generate state repository
      val stateRepositoryCode = generateAggregateStateRepository(rootTableName, referenceTree)
      
      // 5. Write files for this aggregate
      writeAggregateFiles(aggregateName, baseStateCode, eventHandlerCode, stateRepositoryCode)
      
      // 6. Optionally generate Pekko Persistence actor
      if (options.generatePekkoActors) {
        val pekkoActorCode = generatePekkoAggregateActor(rootTableName, referenceTree)
        writeAggregateActor(aggregateName, pekkoActorCode)
      }
      
      val endTime = System.currentTimeMillis()
      println(s"Generated aggregate $aggregateName in ${endTime - startTime}ms")
      
    } finally {
      db.close()
    }
  }
  
  def buildReferenceTree(db: Connection, rootTableName: String, maxDepth: Int): ReferenceTree = {
    
    def isLikelyAggregateRoot(tableName: String): Boolean = {
      val rootPatterns = options.aggregateRootTables
      rootPatterns.contains(tableName.toLowerCase) ||
      options.aggregateBoundaryHints.getOrElse(tableName, false)
    }
    
    def traverse(tableName: String, depth: Int, visited: Set[String]): List[ReferenceNode] = {
      if (depth >= maxDepth || visited.contains(tableName)) {
        return List.empty
      }
      
      val refs = ConnectionUtils.getReferences(db, tableName, "id")
      
      refs.flatMap { ref =>
        // Stop traversal at other aggregate boundaries (unless it's depth 0, i.e., the root)
        if (depth > 0 && isLikelyAggregateRoot(ref.fromTableName)) {
          // Just store the ID reference, don't traverse deeper
          Some(ReferenceNode(
            table = ref.fromTableName,
            foreignKeyColumn = ref.fromColName,
            children = List.empty,
            isWeakReference = true,
            depth = depth
          ))
        } else {
          // Continue traversing within this aggregate
          Some(ReferenceNode(
            table = ref.fromTableName,
            foreignKeyColumn = ref.fromColName,
            children = traverse(ref.fromTableName, depth + 1, visited + tableName),
            isWeakReference = false,
            depth = depth
          ))
        }
      }
    }
    
    ReferenceTree(rootTableName, traverse(rootTableName, 0, Set.empty))
  }
  
  def generateBaseAggregateState(rootTableName: String, tree: ReferenceTree): String = {
    val aggregateName = namingStrategy.table(rootTableName)
    val rootEntityFieldName = singularize(rootTableName.toLowerCase)
    
    // Direct children (store as List[EntityId])
    val directRefs = tree.nodes.filter(node => node.depth == 0)
    val directIdFields = directRefs.map { ref =>
      if (ref.isWeakReference) {
        // Weak reference to another aggregate - just store the IDs
        // ref.table is already plural, so don't pluralize again
        s"  ${ref.table}Ids: List[${namingStrategy.table(singularize(ref.table))}Id] = List.empty"
      } else {
        // Strong reference within this aggregate
        // ref.table is already plural, so don't pluralize again
        s"  ${ref.table}Ids: List[${namingStrategy.table(singularize(ref.table))}Id] = List.empty"
      }
    }
    
    // Nested Maps for children of children (e.g., postComments: Map[PostId, List[CommentId]])
    val nestedMaps = tree.nodes.flatMap { parentNode =>
      if (!parentNode.isWeakReference) {
        parentNode.children.filter(child => !child.isWeakReference).map { childNode =>
          val parentId = s"${namingStrategy.table(singularize(parentNode.table))}Id"
          val childId = s"${namingStrategy.table(singularize(childNode.table))}Id"
          // Fix: Use singular parent table name + plural child table name (already plural)
          s"  ${singularize(parentNode.table)}${namingStrategy.table(childNode.table)}: Map[$parentId, List[$childId]] = Map.empty"
        }
      } else {
        List.empty
      }
    }
    
    val allFields = directIdFields ++ nestedMaps
    val fieldsString = {
      val metadataComment = ",\n  \n  // Event sourcing metadata"
      if (allFields.nonEmpty) {
        val childrenSection = ",\n  \n  // Direct children\n" + allFields.mkString(",\n")
        childrenSection + metadataComment
      } else {
        metadataComment
      }
    }
    
    s"""package ${options.packageName}.aggregates.${rootEntityFieldName}

${GeneratorTemplates.autoGeneratedComment}

//import scala.concurrent.Future
//import monix.eval.Task
import escalator.ddd.Event
import escalator.models.CorrelationId
import escalator.util.Timestamp
import ${options.packageName}.models._
import ${options.packageName}.models.events._

/**
 * Base aggregate state for ${aggregateName}
 * Contains the root entity plus IDs of all related entities
 */
case class Base${aggregateName}State(
  ${rootEntityFieldName}: Option[${aggregateName}] = None${fieldsString}
  version: Long = 0L,
  lastUpdated: Timestamp = Timestamp(0L)
) {
  /**
   * Apply an event to update the aggregate state
   */
  def applyEvent(event: Event): Base${aggregateName}State = event match {
    case e: ${aggregateName}Event => ${aggregateName}EventHandler.apply(this, e)${generateEventMatchCases(tree, aggregateName)}
    case _ => this  // Ignore unrelated events
  }
}

object Base${aggregateName}State {
  def empty(${rootEntityFieldName}Id: ${aggregateName}Id): Base${aggregateName}State = {
    Base${aggregateName}State(
      ${rootEntityFieldName} = None,
      version = 0L,
      lastUpdated = Timestamp(0L)
    )
  }
}
"""
  }
  
  def generateEventMatchCases(tree: ReferenceTree, aggregateName: String): String = {
    val eventCases = tree.nodes.filter(node => !node.isWeakReference).map { node =>
      val eventName = namingStrategy.table(singularize(node.table))
      s"""
    case e: ${eventName}Event => ${aggregateName}EventHandler.apply(this, e)"""
    }
    eventCases.mkString
  }
  
  def generateAggregateEventHandler(rootTableName: String, tree: ReferenceTree): String = {
    val aggregateName = namingStrategy.table(rootTableName)
    val rootEntityFieldName = singularize(rootTableName.toLowerCase)
    
    // Generate handler methods for each event type
    val rootEventHandler = generateRootEventHandler(rootTableName, aggregateName)
    val childEventHandlers = tree.nodes.filter(node => !node.isWeakReference).map { node =>
      generateChildEventHandler(rootTableName, node, tree, aggregateName)
    }.mkString("\n")
    
    s"""package ${options.packageName}.aggregates.${rootEntityFieldName}

${GeneratorTemplates.autoGeneratedComment}

import escalator.ddd.Event
import escalator.models.CorrelationId
import escalator.util.Timestamp
import ${options.packageName}.models._
import ${options.packageName}.models.events._

/**
 * Pure event handlers for ${aggregateName} aggregate
 * These are side-effect free functions for applying events to state
 */
object ${aggregateName}EventHandler {
  
$rootEventHandler
  
$childEventHandlers
}
"""
  }
  
  def generateRootEventHandler(rootTableName: String, aggregateName: String): String = {
    val rootEntityFieldName = singularize(rootTableName.toLowerCase)
    s"""  /**
   * Handle events for the root ${aggregateName} entity
   */
  def apply(state: Base${aggregateName}State, event: ${aggregateName}Event): Base${aggregateName}State = {
    event match {
      case ${aggregateName}Created($rootEntityFieldName, _, correlationId, timestamp) =>
        state.copy(
          $rootEntityFieldName = Some($rootEntityFieldName),
          version = state.version + 1,
          lastUpdated = timestamp
        )
        
      case ${aggregateName}Updated($rootEntityFieldName, previous${aggregateName}, _, correlationId, timestamp) =>
        state.copy(
          $rootEntityFieldName = Some($rootEntityFieldName),
          version = state.version + 1,
          lastUpdated = timestamp
        )
        
      case ${aggregateName}Deleted($rootEntityFieldName, _, correlationId, timestamp) =>
        // Mark as deleted by setting to None
        state.copy(
          $rootEntityFieldName = None,
          version = state.version + 1,
          lastUpdated = timestamp
        )
    }
  }"""
  }
  
  def generateChildEventHandler(rootTableName: String, node: ReferenceNode, tree: ReferenceTree, aggregateName: String): String = {
    val childName = namingStrategy.table(singularize(node.table))
    val childNameLower = node.table.toLowerCase
    val foreignKeyCheck = generateForeignKeyCheck(node, rootTableName)
    val nestedUpdates = generateNestedUpdates(node, tree)
    val depthDescription = if (node.depth == 0) "direct children" else s"depth ${node.depth}"
    
    s"""  /**
   * Handle events for ${childName} entities ($depthDescription)
   */
  def apply(state: Base${aggregateName}State, event: ${childName}Event): Base${aggregateName}State = {
    event match {
      case ${childName}Created(${childNameLower}, _, correlationId, timestamp) if $foreignKeyCheck =>
        state.copy(
          ${node.table}Ids = state.${node.table}Ids :+ ${childNameLower}.id,
          version = state.version + 1,
          lastUpdated = timestamp
        )
        
      case ${childName}Updated(${childNameLower}, previous${childName}, _, correlationId, timestamp) if $foreignKeyCheck =>
        // No ID changes needed for updates
        state.copy(
          version = state.version + 1,
          lastUpdated = timestamp
        )
        
      case ${childName}Deleted(${childNameLower}, _, correlationId, timestamp) if $foreignKeyCheck =>
        state.copy(
          ${node.table}Ids = state.${node.table}Ids.filterNot(_ == ${childNameLower}.id),$nestedUpdates
          version = state.version + 1,
          lastUpdated = timestamp
        )
        
      case _ => state  // Event not relevant to this aggregate
    }
  }"""
  }
  
  def generateForeignKeyCheck(node: ReferenceNode, rootTableName: String): String = {
    if (node.depth == 0) {
      // Direct child - check against root entity ID (now Optional)
      val rootEntityFieldName = singularize(rootTableName.toLowerCase)
      // Convert database column name (user_id) to Scala property name (userId)
      val scalaPropertyName = namingStrategy.column(node.foreignKeyColumn)
      s"state.$rootEntityFieldName.exists(_.id == ${node.table.toLowerCase}.$scalaPropertyName)"
    } else {
      // Nested child - check if parent is in our collection
      val parentTable = findParentTable(node, rootTableName)
      val parentIdField = parentTable + "Ids"  // parentTable is already plural
      // Convert database column name to Scala property name
      val scalaPropertyName = namingStrategy.column(node.foreignKeyColumn)
      s"state.$parentIdField.contains(${node.table.toLowerCase}.$scalaPropertyName)"
    }
  }
  
  def generateNestedUpdates(node: ReferenceNode, tree: ReferenceTree): String = {
    if (node.children.nonEmpty) {
      val childCleanups = node.children.map { child =>
        // Use singular parent + plural child naming
        val mapField = s"${singularize(node.table)}${namingStrategy.table(child.table)}"
        s"$mapField = state.$mapField - ${node.table.toLowerCase}.id"
      }
      "\n          " + childCleanups.mkString(",\n          ") + ","
    } else {
      ""
    }
  }
  
  def findParentTable(node: ReferenceNode, rootTableName: String): String = {
    // This is a simplified approach - in a full implementation,
    // you'd need to traverse the tree to find the actual parent
    rootTableName
  }
  
  def generateAggregateStateRepository(rootTableName: String, tree: ReferenceTree): String = {
    val aggregateName = namingStrategy.table(rootTableName)
    val rootEntityFieldName = singularize(rootTableName.toLowerCase)
    
    // Generate repository dependencies (existing repositories for each table)
    val repoDependencies = (rootTableName :: tree.allTables.filter(table => table != rootTableName)).map { tableName =>
      val repoName = s"${pluralize(namingStrategy.table(tableName))}Repository"
      s"  ${tableName.toLowerCase}Repo: $repoName"
    }.mkString(",\n")
    
    // Generate loading logic for each level
    val loadingLogic = generateStateLoadingLogic(rootTableName, tree, aggregateName)
    
    s"""package ${options.packageName}.aggregates.${rootEntityFieldName}

${GeneratorTemplates.autoGeneratedComment}

import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.Logger
import escalator.ddd.Event
import escalator.models.CorrelationId
import escalator.util.{Timestamp, TimeUtil}
import ${options.packageName}.models._
import ${options.packageName}.models.events._
import ${options.packageName}.core.repositories.postgres._

import monix.eval.Task
import escalator.util.monix.TaskSyntax._


/**
 * Repository for loading and managing ${aggregateName} aggregate state
 * Supports both DB bootstrapping and event replay
 */
class ${aggregateName}StateRepository(
$repoDependencies
)(implicit ec: ExecutionContext, logger: Logger) {
  
  /**
   * Load complete aggregate state from database
   */
  def load${aggregateName}State(${rootEntityFieldName}Id: ${aggregateName}Id): Task[Option[Base${aggregateName}State]] = {
$loadingLogic
  }
  
  /**
   * Load multiple states efficiently
   */
  def load${aggregateName}States(ids: List[${aggregateName}Id]): Task[Map[${aggregateName}Id, Base${aggregateName}State]] = {
    Task.sequence(ids.map { id =>
      load${aggregateName}State(id).map(stateOpt => id -> stateOpt)
    }).map(_.collect { case (id, Some(state)) => id -> state }.toMap)
  }
  
  /**
   * Replay events to rebuild state (Pekko Persistence compatible)
   */
  def replay${aggregateName}State(${rootEntityFieldName}Id: ${aggregateName}Id, fromSequenceNr: Long = 0L): Task[Base${aggregateName}State] = {
    // This method would integrate with your event store
    // For now, return empty state as placeholder
    Task.pure(Base${aggregateName}State.empty(${rootEntityFieldName}Id))
  }
  
  /**
   * Apply events to state in sequence
   */
  def applyEvents(initialState: Base${aggregateName}State, events: List[Event]): Base${aggregateName}State = {
    events.foldLeft(initialState)(_.applyEvent(_))
  }
}

object ${aggregateName}StateRepository {
  def apply(
$repoDependencies
  )(implicit ec: ExecutionContext, logger: Logger): ${aggregateName}StateRepository = 
    new ${aggregateName}StateRepository(${(rootTableName :: tree.allTables.filter(table => table != rootTableName)).map(t => s"${t.toLowerCase}Repo").mkString(", ")})
}
"""
  }
  
  def generateStateLoadingLogic(rootTableName: String, tree: ReferenceTree, aggregateName: String): String = {
    val rootEntityFieldName = singularize(rootTableName.toLowerCase)
    s"""    for {
      // Load root entity
      ${rootEntityFieldName}Opt <- Task.fromFuture(${rootTableName.toLowerCase}Repo.getById(${rootEntityFieldName}Id))
      
      state <- ${rootEntityFieldName}Opt match {
        case None => Task.pure(None)
        case Some(${rootEntityFieldName}) =>
          for {
${generateDirectChildrenLoading(tree)}
${generateNestedChildrenLoading(tree)}
            
          } yield Some(Base${aggregateName}State(
            ${rootEntityFieldName} = Some(${rootEntityFieldName}),
${generateStateConstruction(tree)}
            version = 0L,
            lastUpdated = TimeUtil.nowTimestamp()
          ))
      }
    } yield state"""
  }
  
  def generateDirectChildrenLoading(tree: ReferenceTree): String = {
    val directChildren = tree.nodes.filter(node => node.depth == 0 && !node.isWeakReference)
    if (directChildren.nonEmpty) {
      val loadingTasks = directChildren.map { node =>
        // node.table is already plural, don't pluralize again
        val tableName = node.table
        val foreignKeyMethodName = s"getBy${namingStrategy.table(node.foreignKeyColumn).capitalize}"
        s"Task.fromFuture(${tableName.toLowerCase}Repo.${foreignKeyMethodName}(${singularize(tree.rootTable.toLowerCase)}Id))"
      }
      
      if (directChildren.size == 1) {
        // Single task - no need for parMap
        s"""            // Load direct children
            ${directChildren.head.table} <- ${loadingTasks.head}"""
      } else {
        // Multiple tasks - use parMapN (up to 10) or parSequence
        val taskCount = directChildren.size
        if (taskCount >= 2 && taskCount <= 10) {
          val paramNames = directChildren.zipWithIndex.map { case (_, i) => s"p$i" }
          val combiningFunction = s"((${paramNames.mkString(", ")}) => (${paramNames.mkString(", ")}))"
          s"""            // Load direct children in parallel
            (${directChildren.map(_.table).mkString(", ")}) <- Task.parMap$taskCount(
              ${loadingTasks.mkString(",\n              ")}
            )$combiningFunction"""
        } else {
          s"""            // Load direct children in parallel - many children (${taskCount})
            allChildren <- Task.parSequence(List(
              ${loadingTasks.mkString(",\n              ")}
            ))
            (${directChildren.map(_.table).mkString(", ")}) = (${directChildren.zipWithIndex.map { case (_, i) => s"allChildren($i)" }.mkString(", ")})"""
        }
      }
    } else {
      "            // No direct children to load"
    }
  }
  
  def generateNestedChildrenLoading(tree: ReferenceTree): String = {
    val nestedNodes = tree.nodes.filter(node => node.children.nonEmpty)
    if (nestedNodes.nonEmpty) {
      val loadingLogic = nestedNodes.map { parentNode =>
        parentNode.children.map { childNode =>
          // Both parentNode.table and childNode.table are already plural
          s"""            // Load ${childNode.table} for each ${parentNode.table}
            ${parentNode.table}${namingStrategy.table(childNode.table)}Map <- if (${parentNode.table}.nonEmpty) {
              Task.fromFuture(${childNode.table.toLowerCase}Repo.getBy${namingStrategy.table(childNode.foreignKeyColumn).capitalize}s(${parentNode.table}.map(_.id)))
                .map(_.groupBy(_.${namingStrategy.column(childNode.foreignKeyColumn)}).map { case (k, v) => k -> v.map(_.id) }.asInstanceOf[Map[${namingStrategy.table(childNode.foreignKeyColumn.replace("_id", "").split("_").map(_.capitalize).mkString)}Id, List[${namingStrategy.table(childNode.table.replace("s$", "")).capitalize}Id]]])
            } else {
              Task.pure(Map.empty[${namingStrategy.table(childNode.foreignKeyColumn.replace("_id", "").split("_").map(_.capitalize).mkString)}Id, List[${namingStrategy.table(childNode.table.replace("s$", "")).capitalize}Id]])
            }"""
        }.mkString("\n")
      }.mkString("\n")
      
      s"""            // Load nested children
$loadingLogic"""
    } else {
      ""
    }
  }
  
  def generateStateConstruction(tree: ReferenceTree): String = {
    val directChildIds = tree.nodes.filter(node => node.depth == 0 && !node.isWeakReference).map { node =>
      // node.table is already plural
      s"            ${node.table}Ids = ${node.table}.map(_.id),"
    }
    
    val nestedMaps = tree.nodes.filter(node => node.children.nonEmpty).flatMap { parentNode =>
      parentNode.children.map { childNode =>
        // Both tables are already plural, use singular parent + plural child naming
        s"            ${singularize(parentNode.table)}${namingStrategy.table(childNode.table)} = ${parentNode.table}${namingStrategy.table(childNode.table)}Map,"
      }
    }
    
    (directChildIds ++ nestedMaps).mkString("\n")
  }
  
  def writeAggregateFiles(aggregateName: String, baseStateCode: String, eventHandlerCode: String, stateRepositoryCode: String): Unit = {
    val aggregateFolder = if (options.aggregatesFolder.nonEmpty) {
      s"${options.aggregatesFolder}/${aggregateName.toLowerCase}"
    } else {
      s"${options.persistenceBaseFolder}/aggregates/${aggregateName.toLowerCase}"
    }
    
    FileUtil.createDirectoriesForFolder(aggregateFolder)
    FileUtil.createDirectoriesForFolder(s"$aggregateFolder/custom")
    
    // Write generated files
    writeIfDoesNotExist(s"$aggregateFolder/Base${aggregateName}State.scala", formatCode(baseStateCode))
    writeIfDoesNotExist(s"$aggregateFolder/${aggregateName}EventHandler.scala", formatCode(eventHandlerCode))
    writeIfDoesNotExist(s"$aggregateFolder/${aggregateName}StateRepository.scala", formatCode(stateRepositoryCode))
    
    // Write custom extension files (only if they don't exist)
    val customStateCode = generateCustomStateTemplate(aggregateName)
    writeIfDoesNotExist(s"$aggregateFolder/custom/${aggregateName}State.scala", formatCode(customStateCode))
    
    val customLogicCode = generateCustomLogicTemplate(aggregateName)
    writeIfDoesNotExist(s"$aggregateFolder/custom/${aggregateName}BusinessLogic.scala", formatCode(customLogicCode))
  }
  
  def generateCustomStateTemplate(aggregateName: String): String = {
    s"""package ${options.packageName}.aggregates.${aggregateName.toLowerCase}.custom

// This file is never overwritten by the generator
// Add your custom state extensions and business logic here

import ${options.packageName}.aggregates.${aggregateName.toLowerCase}.Base${aggregateName}State
import ${options.packageName}.models._

/**
 * Extended ${aggregateName} state with custom business logic
 * Extend Base${aggregateName}State and add your domain-specific methods
 */
case class ${aggregateName}State(base: Base${aggregateName}State) {
  
  // Delegate to base state
  def ${aggregateName.toLowerCase} = base.${aggregateName.toLowerCase}
  def version = base.version
  def lastUpdated = base.lastUpdated
  
  // Add your custom computed properties here
  // Example:
  // def totalPosts: Int = base.postsIds.size
  // def isActive: Boolean = base.user.status == "active"
  
  // Add your custom business logic methods here
  // Example:
  // def canCreatePost: Boolean = isActive && totalPosts < 100
}

object ${aggregateName}State {
  def fromBase(baseState: Base${aggregateName}State): ${aggregateName}State = 
    ${aggregateName}State(baseState)
}
"""
  }
  
  def generateCustomLogicTemplate(aggregateName: String): String = {
    s"""package ${options.packageName}.aggregates.${aggregateName.toLowerCase}.custom

// This file is never overwritten by the generator
// Add your custom business logic and domain services here

import scala.concurrent.Future
import monix.eval.Task
import escalator.ddd.Event
import ${options.packageName}.aggregates.${aggregateName.toLowerCase}.Base${aggregateName}State
import ${options.packageName}.models._
import ${options.packageName}.models.events._

/**
 * Business logic for ${aggregateName} aggregate
 * Add your domain-specific operations here
 */
class ${aggregateName}BusinessLogic {
  
  /**
   * Example business rule validation
   */
  def validate${aggregateName}Creation(data: ${aggregateName}): Task[Either[String, ${aggregateName}]] = {
    // Add your validation logic here
    Task.pure(Right(data))
  }
  
  /**
   * Example business operation
   */
  def process${aggregateName}Command(state: Base${aggregateName}State, command: Any): Task[List[Event]] = {
    // Add your command processing logic here
    Task.pure(List.empty)
  }
  
  // Add more business logic methods as needed
}

object ${aggregateName}BusinessLogic {
  def apply(): ${aggregateName}BusinessLogic = new ${aggregateName}BusinessLogic()
}
"""
  }
  
  def writeAggregateActor(aggregateName: String, pekkoActorCode: String): Unit = {
    val aggregateFolder = if (options.aggregatesFolder.nonEmpty) {
      s"${options.aggregatesFolder}/${aggregateName.toLowerCase}"
    } else {
      s"${options.persistenceBaseFolder}/aggregates/${aggregateName.toLowerCase}"
    }
    
    writeIfDoesNotExist(s"$aggregateFolder/${aggregateName}Aggregate.scala", formatCode(pekkoActorCode))
  }
  
  def generatePekkoAggregateActor(rootTableName: String, tree: ReferenceTree): String = {
    val aggregateName = namingStrategy.table(rootTableName)
    
    s"""package ${options.packageName}.aggregates.${rootTableName.toLowerCase}

${GeneratorTemplates.autoGeneratedComment}

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import escalator.ddd.{Command, Event}
import escalator.models.CorrelationId
import escalator.util.{Timestamp, TimeUtil}
import ${options.packageName}.models._
import ${options.packageName}.models.events._

/**
 * Pekko Persistence EventSourced actor for ${aggregateName} aggregate
 */
object ${aggregateName}Aggregate {
  
  // Commands
  sealed trait ${aggregateName}Command extends Command
  
  case class Create${aggregateName}(
    data: ${aggregateName},
    correlationId: CorrelationId,
    replyTo: ActorRef[${aggregateName}Reply]
  ) extends ${aggregateName}Command
  
  case class Update${aggregateName}(
    data: ${aggregateName},
    correlationId: CorrelationId,
    replyTo: ActorRef[${aggregateName}Reply]
  ) extends ${aggregateName}Command
  
  case class Get${aggregateName}State(
    replyTo: ActorRef[${aggregateName}StateReply]
  ) extends ${aggregateName}Command
  
  // Replies
  sealed trait ${aggregateName}Reply
  case class ${aggregateName}Created(${rootTableName.toLowerCase}: ${aggregateName}) extends ${aggregateName}Reply
  case class ${aggregateName}Updated(${rootTableName.toLowerCase}: ${aggregateName}) extends ${aggregateName}Reply
  case class ${aggregateName}Error(message: String) extends ${aggregateName}Reply
  
  case class ${aggregateName}StateReply(state: Base${aggregateName}State)
  
  def apply(${rootTableName.toLowerCase}Id: ${aggregateName}Id): Behavior[${aggregateName}Command] = {
    EventSourcedBehavior[${aggregateName}Command, Event, Base${aggregateName}State](
      persistenceId = PersistenceId.ofUniqueId(s"${aggregateName}-$${${rootTableName.toLowerCase}Id.value}"),
      emptyState = Base${aggregateName}State.empty(${rootTableName.toLowerCase}Id),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  }
  
  private def commandHandler: (Base${aggregateName}State, ${aggregateName}Command) => ReplyEffect[Event, Base${aggregateName}State] = {
    (state, command) => command match {
      case Create${aggregateName}(data, correlationId, replyTo) =>
        if (state.version == 0L) {
          Effect.persist(${aggregateName}Created(data, data.id, correlationId, TimeUtil.nowTimestamp()))
            .thenReply(replyTo)(_ => ${aggregateName}Created(data))
        } else {
          Effect.reply(replyTo)(${aggregateName}Error("${aggregateName} already exists"))
        }
        
      case Update${aggregateName}(data, correlationId, replyTo) =>
        if (state.version > 0L) {
          Effect.persist(${aggregateName}Updated(data, Some(state.${rootTableName.toLowerCase}), data.id, correlationId, TimeUtil.nowTimestamp()))
            .thenReply(replyTo)(_ => ${aggregateName}Updated(data))
        } else {
          Effect.reply(replyTo)(${aggregateName}Error("${aggregateName} does not exist"))
        }
        
      case Get${aggregateName}State(replyTo) =>
        Effect.reply(replyTo)(${aggregateName}StateReply(state))
    }
  }
  
  private def eventHandler: (Base${aggregateName}State, Event) => Base${aggregateName}State = {
    (state, event) => state.applyEvent(event)
  }
}
"""
  }

  def mergeOnFullWord(s1: String, s2: String): String = {
    val words1 = s1.split("_")
    val words2 = s2.split("_")

    // Find the longest overlap based on full words
    val maxOverlap = (1 to words1.length).findLast { i =>
      words2.startsWith(words1.takeRight(i))
    }.getOrElse(0)

    // Merge by removing the overlapping words from s2
    val mergedWords = words1 ++ words2.drop(maxOverlap)
    mergedWords.mkString("_")
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

  def rootGen(codegenOptions: CodegenOptions,customGen: CustomGenerator,rootTable: String, uniqueId: String): Unit = {
    customGen.setup()

    val namingStrategy = GeneratorNamingStrategy
    val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)

    codegen.generateAggregateRoot(rootTable,uniqueId)
  }
  
  /**
   * Generate a single aggregate root from a specific table
   */
  def generateAggregate(codegenOptions: CodegenOptions, customGen: CustomGenerator, rootTableName: String, maxDepth: Int = 3): Unit = {
    customGen.setup()
    
    val namingStrategy = GeneratorNamingStrategy
    val codegen = CodeGenerator(codegenOptions, namingStrategy, customGen)
    
    codegen.generateAggregateRoot(rootTableName, "id", maxDepth)
  }
  
  /**
   * Generate all configured aggregate roots
   */
  def generateAggregates(codegenOptions: CodegenOptions, customGen: CustomGenerator): Unit = {
    if (!codegenOptions.generateAggregates || codegenOptions.aggregateRootTables.isEmpty) {
      println("Aggregate generation disabled or no root tables configured")
      return
    }
    
    println(s"Generating ${codegenOptions.aggregateRootTables.size} aggregate roots: ${codegenOptions.aggregateRootTables.mkString(", ")}")
    
    codegenOptions.aggregateRootTables.foreach { rootTable =>
      generateAggregate(codegenOptions, customGen, rootTable, codegenOptions.maxAggregateDepth)
    }
  }
  
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
