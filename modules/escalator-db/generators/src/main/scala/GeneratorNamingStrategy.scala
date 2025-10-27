package escalator.db.generators

import io.getquill._
import escalator.util._

trait GeneratorNamingStrategy extends NamingStrategy {
  import TextUtil._

  override def default(s: String) = {
    val d = SnakeCase.default(s)
    // println(s"NS default: ${s} -> ${d}")
    d
  }

  override def column(s: String): String = {
    val c = snakeToLowerCamel(s)
    // println(s"NS column: ${s} -> ${c}")
    c    
  }  

  override def table(s: String) = {
    val t = snakeToUpperCamel(singularize(s)) 
    // println(s"NS table: ${t} -> ${s}")
    t
  }
}
object GeneratorNamingStrategy extends GeneratorNamingStrategy
