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

  def getColumnAttributeTypeMappingsFromConstraints(db: Connection): Map[String, String] = {
    println("Building column attribute type mappings from database CHECK constraints...")

    val sql = """
      WITH checks AS (
        SELECT
          tc.table_schema,
          tc.table_name,
          cc.check_clause
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND cc.constraint_name  = tc.constraint_name
        WHERE tc.constraint_type = 'CHECK'
          AND tc.table_schema    = 'public'
      ),
      -- Pull *all* (identifier, token) pairs from each CHECK
      pairs AS (
        SELECT
          c.table_schema,
          c.table_name,
          COALESCE(m[4], m[3], m[2], m[1]) AS ident,   -- rightmost ident if qualified
          m[5] AS token
        FROM checks c
        CROSS JOIN LATERAL regexp_matches(
          c.check_clause,
          $$\(\s*\(\s*\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z_0-9]*))(?:\s*\.\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z_0-9]*)))?\s*\)\s*\.attr\)\s*(?:::text)?\s*=\s*'([^']+)'\s*(?:::text)?$$,
          'g'
        ) AS m
      ),
      rels AS (
        SELECT c.oid AS relid, n.nspname AS table_schema, c.relname AS table_name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
      )
      SELECT DISTINCT ON (r.table_name, a.attname)
        r.table_name  AS table_name,
        a.attname     AS column_name,
        p.token
      FROM pairs p
      JOIN rels r
        ON r.table_schema = p.table_schema
       AND r.table_name   = p.table_name
      JOIN pg_attribute a
        ON a.attrelid = r.relid
       AND a.attnum > 0 AND NOT a.attisdropped
      LEFT JOIN pg_type t            -- for type-name fallback
        ON lower(t.typname) = lower(p.ident)
      -- Optional: keep only USER-DEFINED columns. Uncomment if you want this filter.
      -- JOIN information_schema.columns ic
      --   ON ic.table_schema = r.table_schema AND ic.table_name = r.table_name AND ic.column_name = a.attname
      --  AND ic.data_type = 'USER-DEFINED'
      WHERE
        -- assign a priority to each candidate column for this (table, ident)
        CASE
          WHEN lower(a.attname) = lower(p.ident) THEN 1                     -- direct column name match
          WHEN t.oid IS NOT NULL AND a.atttypid = t.oid THEN 2              -- same type as ident
          WHEN a.attname ~* ('(^|_)' || regexp_replace(p.ident,'\W','','g') || '(_|$)') THEN 3  -- name-like fallback
          ELSE NULL
        END IS NOT NULL
      ORDER BY
        r.table_name, a.attname,
        CASE
          WHEN lower(a.attname) = lower(p.ident) THEN 1
          WHEN t.oid IS NOT NULL AND a.atttypid = t.oid THEN 2
          WHEN a.attname ~* ('(^|_)' || regexp_replace(p.ident,'\W','','g') || '(_|$)') THEN 3
        END;
    """

    val stmt = db.prepareStatement(sql)
    // stmt.setString(1, options.schema)
    val rs = stmt.executeQuery()
    
    val mappings = scala.collection.mutable.Map[String, String]()
    
    while (rs.next()) {
      val tableName = rs.getString("table_name")
      val columnName = rs.getString("column_name")  
      // val checkClause = rs.getString("check_clause")
      val token = rs.getString("token")
      
      // Extract attribute type from check clause like: ((entity_type).attr = 'ENTITY_TYPE'::text)
      // or: check ((entity_type).attr = 'ENTITY_TYPE')
      // val pattern = """.*attr\s*=\s*'([^']+)'.*""".r
      // val pattern = """.*attr\s*=\s*'([^']+)'(?:::text)?.*""".r
      // val pattern = """.*\)\.attr\)?(?:::text)?\s*=\s*'([^']+)'(?:::text)?.*""".r

      // checkClause match {
        // case pattern(attrType) =>
          // Convert ENTITY_TYPE to EntityType
          val attrType = token

          val className = attrType.toLowerCase.replace("_type", "").split("_").map(_.capitalize).mkString("") + "Type"

          // val className = attrType.split("_").map(_.toLowerCase.capitalize).mkString("")
          val key = s"$tableName.$columnName"
          mappings(key) = className
          // println(s"  Found mapping: $key -> $className")
        // case _ =>
          // println(s"  Could not parse constraint for $tableName.$columnName: $checkClause")
      // }
    }
    
    stmt.close()

    println(mappings)

    mappings.toMap
  }
  


}
