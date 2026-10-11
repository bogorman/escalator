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
    // A column called `type` (or any Scala keyword) becomes a backticked field; Quill still maps it to the column.
    if (GeneratorNamingStrategy.ScalaKeywords(c)) s"`$c`" else c
  }

  override def table(s: String) = {
    val t = snakeToUpperCamel(singularize(s)) 
    // println(s"NS table: ${t} -> ${s}")
    t
  }
}
object GeneratorNamingStrategy extends GeneratorNamingStrategy {
  val ScalaKeywords: Set[String] = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false", "final", "finally", "for",
    "forSome", "if", "implicit", "import", "lazy", "macro", "match", "new", "null", "object", "override",
    "package", "private", "protected", "return", "sealed", "super", "then", "this", "throw", "trait", "true",
    "try", "type", "val", "var", "while", "with", "yield", "enum", "export", "given")
}
