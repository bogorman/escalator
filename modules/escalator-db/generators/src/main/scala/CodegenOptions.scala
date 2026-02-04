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
    generatePekkoActors: Boolean = false, // When true with generateAggregates=false, generates standalone actors
    aggregateBoundaryHints: Map[String, Boolean] = Map.empty
)