package escalator.util

import scala.collection.mutable.{ Map => MMap }

object TextUtil {
  //add a mapping here of common english words
  // or use https://github.com/atteo/evo-inflector
  // https://stackoverflow.com/questions/16961774/scala-library-for-finding-plural-of-string-and-possible-singulars-of-a-plural

  val CUSTOM_PLURAL_MAPPINGS = MMap.empty[String,String]

  // Initialize with common English irregular/tricky plurals
  initDefaultMappings()

  private def initDefaultMappings(): Unit = {
    // Irregular plurals
    addDefaultPair("person", "people")
    addDefaultPair("child", "children")
    addDefaultPair("man", "men")
    addDefaultPair("woman", "women")
    addDefaultPair("datum", "data")
    addDefaultPair("medium", "media")
    addDefaultPair("criterion", "criteria")
    addDefaultPair("index", "indices")
    addDefaultPair("matrix", "matrices")
    addDefaultPair("vertex", "vertices")
    addDefaultPair("appendix", "appendices")

    // Words ending in "us" - singular already ends in "s"
    addDefaultPair("status", "statuses")
    addDefaultPair("bus", "buses")
    addDefaultPair("campus", "campuses")
    addDefaultPair("census", "censuses")
    addDefaultPair("consensus", "consensuses")
    addDefaultPair("bonus", "bonuses")
    addDefaultPair("focus", "focuses")
    addDefaultPair("nexus", "nexuses")
    addDefaultPair("radius", "radiuses")
    addDefaultPair("virus", "viruses")
    addDefaultPair("apparatus", "apparatuses")

    // Words ending in "ss" - singular already ends in "s"
    addDefaultPair("address", "addresses")
    addDefaultPair("process", "processes")
    addDefaultPair("access", "accesses")
    addDefaultPair("business", "businesses")
    addDefaultPair("progress", "progresses")
    addDefaultPair("success", "successes")

    // Words ending in "is" - singular already ends in "s"
    addDefaultPair("analysis", "analyses")
    addDefaultPair("basis", "bases")
    addDefaultPair("crisis", "crises")
    addDefaultPair("axis", "axes")
    addDefaultPair("synopsis", "synopses")
    addDefaultPair("thesis", "theses")
    addDefaultPair("diagnosis", "diagnoses")

    // Words ending in "s" that are uncountable or same plural
    addDefaultPair("series", "series")
    addDefaultPair("species", "species")
    addDefaultPair("news", "news")

    // Words ending in "s" that need "es"
    addDefaultPair("alias", "aliases")
    addDefaultPair("canvas", "canvases")
    addDefaultPair("lens", "lenses")
    addDefaultPair("atlas", "atlases")

    // Words ending in "f/fe" -> "ves"
    addDefaultPair("leaf", "leaves")
    addDefaultPair("life", "lives")
    addDefaultPair("shelf", "shelves")
    addDefaultPair("half", "halves")
    addDefaultPair("knife", "knives")
    addDefaultPair("wife", "wives")
    addDefaultPair("wolf", "wolves")
    addDefaultPair("calf", "calves")
    addDefaultPair("loaf", "loaves")
    addDefaultPair("thief", "thieves")

    // Words ending in "y" -> "ies" (common DB table names)
    addDefaultPair("entity", "entities")
    addDefaultPair("currency", "currencies")
    addDefaultPair("frequency", "frequencies")
    addDefaultPair("policy", "policies")
    addDefaultPair("agency", "agencies")
    addDefaultPair("dependency", "dependencies")
    addDefaultPair("emergency", "emergencies")
    addDefaultPair("category", "categories")
    addDefaultPair("entry", "entries")
    addDefaultPair("activity", "activities")
    addDefaultPair("facility", "facilities")
    addDefaultPair("history", "histories")
    addDefaultPair("inventory", "inventories")
    addDefaultPair("strategy", "strategies")
    addDefaultPair("company", "companies")
    addDefaultPair("country", "countries")
    addDefaultPair("city", "cities")
    addDefaultPair("body", "bodies")
    addDefaultPair("copy", "copies")
    addDefaultPair("query", "queries")
    addDefaultPair("reply", "replies")
    addDefaultPair("summary", "summaries")
    addDefaultPair("library", "libraries")
    addDefaultPair("factory", "factories")
    addDefaultPair("delivery", "deliveries")
    addDefaultPair("discovery", "discoveries")
    addDefaultPair("property", "properties")
    addDefaultPair("ability", "abilities")
    addDefaultPair("quality", "qualities")
    addDefaultPair("quantity", "quantities")
    addDefaultPair("identity", "identities")
    addDefaultPair("priority", "priorities")
    addDefaultPair("security", "securities")
    addDefaultPair("utility", "utilities")
    addDefaultPair("salary", "salaries")

    // Words ending in "x", "ch", "sh" -> "es"
    addDefaultPair("tax", "taxes")
    addDefaultPair("box", "boxes")
    addDefaultPair("match", "matches")
    addDefaultPair("batch", "batches")
    addDefaultPair("watch", "watches")
    addDefaultPair("switch", "switches")
    addDefaultPair("patch", "patches")
    addDefaultPair("dispatch", "dispatches")
    addDefaultPair("push", "pushes")
    addDefaultPair("flash", "flashes")
    addDefaultPair("crash", "crashes")
    addDefaultPair("wish", "wishes")
    addDefaultPair("search", "searches")
    addDefaultPair("branch", "branches")
    addDefaultPair("fix", "fixes")
    addDefaultPair("mix", "mixes")
    addDefaultPair("prefix", "prefixes")
    addDefaultPair("suffix", "suffixes")
  }

  /**
    * Add a singular/plural pair (both lowercase and capitalized forms).
    */
  private def addDefaultPair(singular: String, plural: String): Unit = {
    CUSTOM_PLURAL_MAPPINGS += (singular -> plural)
    CUSTOM_PLURAL_MAPPINGS += (singular.capitalize -> plural.capitalize)
  }

  /**
    * Remove a plural mapping (if a default is incorrect for your domain).
    * Removes both lowercase and capitalized forms.
    */
  def removePluralMapping(singular: String): Unit = {
    CUSTOM_PLURAL_MAPPINGS -= singular
    CUSTOM_PLURAL_MAPPINGS -= singular.capitalize
  }

  def reverseCustomPluralMappings() = {
    for ((k,v) <- CUSTOM_PLURAL_MAPPINGS) yield (v, k)
  }

  def singularize(str: String): String = {
    val cm = reverseCustomPluralMappings
    val s = if (cm.contains(str)){
      cm.get(str).get
    } else if (str.contains("_")) {
      // For compound snake_case names, singularize the last segment
      val parts = str.split("_")
      val lastPart = parts.last
      val singularLast = singularize(lastPart)
      (parts.init :+ singularLast).mkString("_")
    } else {
      if (str.endsWith("classes")) {
        str.stripSuffix("es")
      } else if (str.endsWith("ities")) {
        str.stripSuffix("ities") + "ity"
      } else if (str.endsWith("sses") || str.endsWith("shes") || str.endsWith("ches") || str.endsWith("xes") || str.endsWith("zes")) {
        str.stripSuffix("es")
      } else if (str.endsWith("ies") && str.length > 3) {
        str.stripSuffix("ies") + "y"
      } else if (str.endsWith("ves")) {
        str.stripSuffix("ves") + "f"
      } else if (str.endsWith("us") || str.endsWith("ss") || str.endsWith("is")) {
        // Words like "status", "address", "analysis" - already singular
        str
      } else {
        str.stripSuffix("s")
      }
    }

    // println("singularize:" + str + " -> " + s)

    s
  }

  def pluralize(str: String,count: Int): String = {
    if (count <= 1){
      str
    } else {
      pluralize(str)
    }
  }

  def pluralize(str: String): String = {
    val cm = CUSTOM_PLURAL_MAPPINGS
    val p = if (cm.contains(str)){
      cm.get(str).get
    } else if (str.contains("_")) {
      // For compound snake_case names, pluralize the last segment
      val parts = str.split("_")
      val lastPart = parts.last
      val pluralLast = pluralize(lastPart)
      (parts.init :+ pluralLast).mkString("_")
    } else {
      val s = str.toLowerCase
      if (s.endsWith("ss") || s.endsWith("sh") || s.endsWith("ch") || s.endsWith("x") || s.endsWith("z")) {
        str + "es"
      } else if (s.endsWith("us")) {
        str + "es"
      } else if (s.endsWith("is")) {
        str.stripSuffix("is") + "es"
      } else if (s.endsWith("f")) {
        str.stripSuffix("f") + "ves"
      } else if (s.endsWith("fe")) {
        str.stripSuffix("fe") + "ves"
      } else if (s.endsWith("y") && !s.endsWith("ey") && !s.endsWith("ay") && !s.endsWith("oy") && !s.endsWith("uy")) {
        str.stripSuffix("y") + "ies"
      } else {
        str + "s"
      }
    }

    // println("pluralize:" + str + " -> " + p)

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
