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
      // Wrap in Option if the column is nullable
      val baseType = getAttributeTypeName()
      if (nullable) s"Option[$baseType]" else baseType
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
      // Wrap in Option if the column is nullable
      val baseType = getAttributeTypeName()
      val finalType = if (nullable) s"Option[$baseType]" else baseType
      s"${fix(mutate,namingStrategy.column(columnName))}: $finalType"
    } else if (includeRef && references.isDefined){
      s"${fix(mutate,namingStrategy.column(columnName))}: ${makeOption(references.get.toType)}"
    } else if (includeRef && (shouldTypeifyColumn() || incomingReferences.size > 0)){
      s"${fix(mutate,namingStrategy.column(columnName))}: ${makeOption(SimpleColumn(tableName,columnName).toType)}"
    } else {
      s"${fix(mutate,namingStrategy.column(columnName))}: ${scalaOptionType}"
    }
  }

  def fix(mutate: Boolean, arg: String): String = {
    // if (mutate && arg == "quote"){
      // "quote_arg"
    // } else {
      arg  
    // }
    
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
    
    // println(s"shouldUseAttributeType ${tableName}.${columnName} ${scalaType} references=${references.map(r => r.tableName + "." + r.columnName)}")
    
    val isAttributeTypeColumn = (scalaType == "AttributeType" || scalaType.endsWith(".AttributeType"))
    val referencesAttributeTable = references.isDefined && references.get.tableName == "attributes" && references.get.columnName == "attr_type"
    
    isAttributeTypeColumn || referencesAttributeTable
  }
  
  def getAttributeTypeName(): String = {
    // println(s"getAttributeTypeName ${tableName}.${columnName} ${scalaType} references=${references.map(r => r.tableName + "." + r.columnName)}")
    
    // Case 1: This is the attr_type column in attributes table
    if (columnName == "attr_type") {
      return "AttributeType"
    }
    
    // Case 2: This column references attributes.attr_type
    if (references.isDefined && references.get.tableName == "attributes" && references.get.columnName == "attr_type") {
      val tableColumnKey = s"${tableName}.${columnName}"
      
      // Check for mapping from combined custom and auto-generated mappings
      val allMappings = customGen.getAllColumnAttributeTypeMappings()
      // println("allMappings:"+allMappings)
      // println("tableColumnKey:"+tableColumnKey)
      allMappings.get(tableColumnKey) match {
        case Some(attributeTypeName) =>
          // println(s"Found attribute type mapping: $tableColumnKey -> $attributeTypeName")
          return attributeTypeName
        case None =>
          println(s"No mapping found for $tableColumnKey (checked both custom and auto-generated)")
      }
      
      // Try to infer from column name patterns
      // val inferredAttrType = if (columnName.endsWith("_type")) {
      //   columnName.replace("_type", "").toUpperCase.replace("_", "_") + "_TYPE"
      // } else {
      //   // For columns like "source", we can't easily infer the type
      //   // Would need constraint analysis or explicit mapping
      //   columnName.toUpperCase.replace("_", "_") + "_TYPE" 
      // }
      
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
    // println(s"getAttributeTypeName result: ${baseName}")
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
