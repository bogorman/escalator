package escalator.util.resque

// import com.redis.RedisClient
import redis.clients.jedis._
// import scala.util.parsing.json._
// import scala.util.parsing.json.JSON
import scala.jdk.CollectionConverters._

class CC[T] { def unapply(a: Any): Option[T] = Some(a.asInstanceOf[T]) }

object M extends CC[Map[String, Any]]

object L extends CC[List[Any]]
object S extends CC[String]
object D extends CC[Double]
object B extends CC[Boolean]

sealed trait ResqueDataStructure {
  val client: Jedis
  val key: String
  // def psize(fun: String =>  Option[Long]): Int = {
  //   fun(key) match {
  //     case Some(num) =>  num.toInt
  //     case None      =>  0
  //   }
  // }
}

class ResqueList(name: String, redis: Jedis) extends ResqueDataStructure {
  // import ResqueProtocol._

  val client = redis
  val key = name

  def size: Int = client.llen(name).toInt

  // def printListJobs = for (x <- 0 until size) log.debug(client.lindex(key, x))

  def popNext(): Option[Payload] = Option(client.lpop(key)) match {
    // case Some(t) =>  parsePayload(t).head.asInstanceOf[(String, List[Any])]
    // case None    =>  ("Error", List("None"))
    case Some(t) =>  Some(parsePayload(t).head)
    case None =>  None
  }

  def getNext(): Option[Payload] = Option(client.lindex(key, 0)) match {
    // case Some(t) =>  parsePayload(t).head.asInstanceOf[(String, List[Any])]
    // case None    =>  ("Error", List("None"))
    case Some(t) =>  Some(parsePayload(t).head)
    case None =>  None
  }

  // to reimplement recursively
  // @deprecated def jobsInQueue: List[List[String]] = {
  //   var acc = List[Any]()
  //   for (x <- 0 until size) {
  //     val one = Option(client.lindex(key, x)) match {
  //       case Some(t) =>  parsePayload(t)
  //       case None    =>  List("None")
  //     }
  //     acc :::= one
  //   }
  //   acc.asInstanceOf[List[List[String]]]
  // }

  def parsePayload(jsonString: String) = {
    try {
      for {
        Some(M(map)) <- List(scala.util.parsing.json.JSON.parseFull(jsonString))
        S(name) = map("class")
        L(args) = map("args")
        S(trackingid) = map("trackingid")
      } yield {
        Payload(name, args.map(_.toString), trackingid)
      }
    } catch {
      case e: Exception => {
        println("parsePayload FAILED.")
        e.printStackTrace
        for {
          Some(M(map)) <- List(scala.util.parsing.json.JSON.parseFull(jsonString))
          S(name) = map("class")
          L(args) = map("args")
        } yield {
          Payload(name, args.map(_.toString), "")
        }
      }
    }
  }
}

class ResqueSet(name: String, redis: Jedis) extends ResqueDataStructure {
  val client = redis
  val key = name
  def size: Int = client.scard(name).toInt
  def members: List[String] = {
    Option(client.smembers(key)) match {
      case Some(set) =>  set.asScala.toList //map({ f: Option[String] =>  f.get })
      case None =>  List("")
    }
  }
  // for "resque:queues"
  def memberNames: List[String] = members.map(key.dropRight(1) + ":" + _)

  def operations: List[String] = memberNames.map(_.split(":").last)
}
