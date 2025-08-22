package escalator.util.postgres

import io.getquill._
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import escalator.util._

trait CustomNamingStrategy extends NamingStrategy {
  import TextUtil._

  override def default(s: String) = {
    SnakeCase.default(s).toLowerCase
  }

  override def table(s: String) = {
    val t = camelToSnake(s)

    SnakeCase.default(pluralize(t)).toUpperCase
  }
}
object CustomNamingStrategy extends CustomNamingStrategy

abstract class PostgresDatabase(ctx: PostgresMonixJdbcContext[CustomNamingStrategy]) {

  def drop() = {
    println("IMPLEMENT THIS: drop" + ctx)
  }

  def create() = {
    println("IMPLEMENT THIS: create")
  }

  def truncate() = {
    println("IMPLEMENT THIS: truncate")
  }  

  def context() = {
    ctx
  }

}

object PostgresDatabase {

  case class PostgresDatabaseConfiguration(
    host: String,
    port: Int,
    database: String
  )

  def pgDataSource(config: PostgresDatabaseConfiguration) = {
    val dbHost = System.getenv("DB_HOST")
    val dbPort = System.getenv("DB_PORT").toInt
    val dbUser = System.getenv("DB_USER")
    val dbPass = System.getenv("DB_PASSWORD")
    val dbName = System.getenv("DB_NAME")

    val ds = new org.postgresql.ds.PGSimpleDataSource()
    ds.setUser(dbUser)
    ds.setDatabaseName(dbName)
    ds.setPassword(dbPass) // needs password here
    ds.setServerName(dbHost)
    ds.setPortNumber(dbPort)
    
    val config = new HikariConfig()
    config.setDataSource(ds)

    new HikariDataSource(config)
  }



}