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


case class Table(customGen: CustomGenerator,options: CodegenOptions,name: String, tableColumns: Seq[Column], allUniqueKeys: Set[UniqueKey], isAbstract: Boolean, inheritedFromTable: Option[Table], db: Connection) {
  import TextUtil._
  val namingStrategy = GeneratorNamingStrategy

  def uniqueKeys = {
    allUniqueKeys.filter(_.partial == false)
  }

  def columnCaseClasses(): List[String] = {
    val caseClasses = tableColumns.filter(_.shouldDefineType).map { col =>
      // println("shouldDefineType:" + name + ":" + col.columnName + " " + col.toDefn(name, true))
      val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
      // println(cc)
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
      // println("uniqueKeyCaseClasses:" + uk)

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
        // println("scol: " + scol.columnName)

        val col = findColumn(scol.columnName)
        col.toArg(namingStrategy, name, true)
      }.mkString(", ")           

      // val cc = s"""case class ${col.toDefn(name, true)}(${col.toArg(namingStrategy, name, false)}) extends AnyVal"""
      // println(cc)
      // cc
      val caseClass = s"case class $caseClassName($args)"

      // println(caseClass)

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

    // println("toCaseClass scalaName:" + scalaName)

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

  def appendIfNotEmpty(code: String, toAppend: String): String = {
    if (code == ""){
      ""
    } else {
      code + toAppend
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

    val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(${defaultConstructorValue(primaryKeyColumnOpt.get)})").getOrElse("")

    val autoInsertedAtCreator = autoColumns.find(c => c.columnName == "created_at").map(c => s"escalator.util.Timestamp(0L)").getOrElse("")
    val autoUpdatedAtCreator = autoColumns.find(c => c.columnName == "updated_at").map(c => s"escalator.util.Timestamp(0L)").getOrElse("")

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
            ${appendIfNotEmpty(primaryColCreator,",")}
            ${appendIfNotEmpty(objClassInheritedArgsWithDefaults,",")}
            ${appendIfNotEmpty(objClassExtraColsArgsWithDefaults,",")}
            ${appendIfNotEmpty(objClassDirectArgsWithDefaults,",")}
            ${appendIfNotEmpty(autoInsertedAtCreator,",")}
            ${appendIfNotEmpty(autoUpdatedAtCreator,",")}
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

      val primaryColCreator = primaryKeyColArgOpt.map(primaryKeyColArg =>  s"${primaryKeyColArg}(${defaultConstructorValue(primaryKeyColumnOpt.get)})").getOrElse("")

      s"""object $scalaName {

        def apply(${objectArgs}): ${scalaName} = {
          ${scalaName}(
            ${appendIfNotEmpty(primaryColCreator,",")}
            ${appendIfNotEmpty(objClassArgsWithDefaults,",")}
            ${appendIfNotEmpty(objClassExtraColsArgsWithDefaults,",")}
            ${appendIfNotEmpty(autoInsertedAtCreator,",")}
            ${appendIfNotEmpty(autoUpdatedAtCreator,",")} 
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
    // println("toCaseClass name:" + name)
    SimpleCaseClass(name,mainCaseClass(),mainObjectClass(),columnCaseClasses(),uniqueKeyCaseClasses(),extraObjects()) 
  }

  def findColumn(colName: String): Column = {
    // println("colName: " + colName)
    // println("columns: " + columns.map(_.columnName))

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