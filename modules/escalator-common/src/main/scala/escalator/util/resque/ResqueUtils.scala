package escalator.util.resque

// import akka.actor._

object ResqueUtils {

  val namespace = "resque"

  def key(parts: List[String]) = {
    namespace + ":" + parts.mkString(":")
  }

  def resqueWorkerName(actorPath: String) = {
    // sender.path.toString
    val className = {
      val classNameWithNumber = (actorPath.split("/")).last
      // removeNumber
      classNameWithNumber.split("-").head
    }
    className
  }

  def resqueWorkerIndex(actorPath: String) = {
    try {
      (actorPath.split("-")).last.toInt
    } catch {
      case e: Exception =>  -1
    }

  }

  def localhost = {
    java.net.InetAddress.getLocalHost.getHostName
  }

  def resqueWorkerId(actorPath: String) = {
    // (actorPath.split("-")).last.toInt
    localhost + ":" + resqueWorkerIndex(actorPath) + ":" + resqueWorkerName(actorPath)
  }

}