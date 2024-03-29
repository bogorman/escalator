package escalator.util

import scala.collection.mutable.{ Map => MMap }

object TextUtil {
  //add a mapping here of common english words
  // or use https://github.com/atteo/evo-inflector
  // https://stackoverflow.com/questions/16961774/scala-library-for-finding-plural-of-string-and-possible-singulars-of-a-plural

  val CUSTOM_PLURAL_MAPPINGS = MMap.empty[String,String]
  // var CUSTOM_MAPPINGS: Map[String,String] = CustomNameMapper.CUSTOM_MAPPINGS

  def reverseCustomPluralMappings() = {
    for ((k,v) <- CUSTOM_PLURAL_MAPPINGS) yield (v, k)
  }

  def singularize(str: String): String = {
    val cm = reverseCustomPluralMappings
    val s = if (cm.contains(str)){
      cm.get(str).get
    } else {
      if (str.endsWith("classes")) {
        str.stripSuffix("es")   
      } else {
        str.stripSuffix("s") 
      }
    }

    println("singularize:" + str + " -> " + s)

    s
  }

  def pluralize(str: String): String = {
    val cm = CUSTOM_PLURAL_MAPPINGS
    val p = if (cm.contains(str)){
      cm.get(str).get
    // } else if (cm.contains(str.downcase))
    //   cm.get(str.downcase).get
    } else {
      val s = str.toLowerCase
      if (s.endsWith("class")){
        str + "es"
      } else {
        str + "s"
      }
    }

    println("pluralize:" + str + " -> " + p)

    p
  }

  def camelToSnake(name: String): String = {
    // @tailrec
    def go(accDone: List[Char], acc: List[Char]): List[Char] = acc match {
      case Nil => accDone
      case a::b::c::tail if a.isUpper && b.isUpper && c.isLower => go(accDone ++ List(a, '_', b, c), tail)
      case a::b::tail if a.isLower && b.isUpper => go(accDone ++ List(a, '_', b), tail)
      case a::tail => go(accDone :+ a, tail)
    }
    go(Nil, name.toList).mkString.toLowerCase
  }  

  def snakeify(s: String) = {
    // println("CustomSnakeCase default " + s)
    (s.toList match {
      case c :: tail => c.toLower +: snakeCase(tail)
      case Nil       => Nil
    }).mkString.toUpperCase
  }

  private def snakeCase(s: List[Char]): List[Char] = {
    s match {
      case c :: tail if c.isUpper => List('_', c.toLower) ++ snakeCase(tail)
      case c :: tail              => c +: snakeCase(tail)
      case Nil                    => Nil
    }
  }  

  def snakeToUpperCamel(str: String) = str.split("_").map(_.toLowerCase).map(_.capitalize).mkString
  def snakeToLowerCamel(str: String) = uncapitalize(str.split("_").map(_.toLowerCase).map(_.capitalize).mkString)
  def lowerCamelToSnake(str: String) = str.split("(?=[A-Z])").mkString("_").toLowerCase 

  def uncapitalize(str: String) = {
    new String(
      (str.toList match {
        case head :: tail => head.toLower :: tail
        case Nil          => Nil
      }).toArray
    )  
  }

  def camelify(s: String) = {
    snakeToUpperCamel(s)
  }

  def blank_?(s: String): Boolean = {
    (s == null || s.size == 0)
  }

  def extractInitials(s: String) = {
    s.take(3).toUpperCase()
  }

}

