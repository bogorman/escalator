package escalator.util.resque

import akka.actor.{ Props, ActorSystem }
import redis.clients.jedis._
import scala.concurrent._
import duration._
import escalator.util.redis._
import spray.json._
// import DefaultJsonProtocol._

// SUGGEST : Finish Resque INFLIGHT options.

case class Payload(className: String, args: List[String], trackingid: String)

object PayloadJsonProtocol extends DefaultJsonProtocol {
  implicit val PayloadFormat = jsonFormat(Payload, "class", "args", "trackingid")
}

import PayloadJsonProtocol._

case class FailureMessage(failedAt: String, errorMessage: String, backtrace: List[String], payload: Payload, queue: String, worker: String)

object FailureMessageJsonProtocol extends DefaultJsonProtocol {
  implicit val FailureMessageFormat = jsonFormat(FailureMessage, "failed_at", "error", "backtrace", "payload", "queue", "worker")
}

// import FailureMessageJsonProtocol._

case class WorkerStatus(runAt: String, queue: String, payload: Payload, paused: Boolean)

object WorkerStatusJsonProtocol extends DefaultJsonProtocol {
  implicit val WorkerStatusFormat = jsonFormat(WorkerStatus, "run_at", "queue", "payload", "paused")
}

/**
 *   EntryPoint of Akka2Resque
 *   initResque must be call first to init resque and define th
 */
object Resque {
  import ResqueProtocol._

  var system: ActorSystem = null
  lazy val config = system.settings.config.getConfig("resque")
  lazy val resqueCheckInterval = if (config.hasPath("checkInterval")) config.getInt("checkInterval") else 1000

  lazy val clients = {
    if (system == null) {
      throw new Exception("Resque Actor System needs to be initialized")
    }
    val host = if (config.hasPath("redisHost")) config.getString("redisHost") else "localhost"
    val port = if (config.hasPath("redisPort")) config.getInt("redisPort") else 6379
    val password = if (config.hasPath("redisPassword")) config.getString("redisPassword").trim else ""
    val timeout = if (config.hasPath("redisTimeout")) config.getInt("redisTimeout") else 30000

    println("Resque Host:" + host)
    println("Resque Port:" + port)
    println("Resque Password:" + password)
    println("Resque timeout:" + timeout)

    if (password.isEmpty) {
      // new RedisClientPool(host, port)
      new Pool(new JedisPool(new JedisPoolConfig(), host, port, timeout))
    } else {
      // new RedisClientPool(host, port, 8, 0, Some(password))
      val config = new JedisPoolConfig()
      // config.setMaxA(32)
      // println("jedis max :" + config.getMaxTotal)
      val jpool = new JedisPool(config, host, port, timeout, password)
      new Pool(jpool)
    }

  }

  def initSystem(aSystem: ActorSystem = null) {
    if (aSystem == null) {
      system = ActorSystem("resque")
    } else {
      system = aSystem
    }
  }

  /**
   *  Init Resque
   * Require a List[String] of used Queue in the project
   * @param queuesList The full name including package must be provided
   */
  def initResque(queuesList: List[String], aSystem: ActorSystem = null) {
    initSystem(aSystem)

    clients.withClient {
      client =>
        {
          println("initResque")

          // var interval = 1000L

          // create the ResqueSet (contains the controllers name)
          val resques = new ResqueSet("resque:queues", client)

          // get the controllers name by removing the package path part
          val controllerNames = queuesList.map(x =>  (x.split('.')).last)
          // register controllers in resque:queues

          println("controllerNames:" + controllerNames)

          register(client, "resque:queues", controllerNames)
          // create the master actor which manage ResqueControllers
          val master = system.actorOf(Props(classOf[ResqueMaster], client, resques, queuesList), "resqueMaster")

          master ! JobsAvailable
          // Scheduling
          // import system.dispatcher
          import scala.concurrent.ExecutionContext.Implicits.global
          system.scheduler.schedule(Duration(0, "seconds"), Duration(resqueCheckInterval, "ms"), master, JobsAvailable)
        }
    }
  }

  def extractTrackingId(args: List[String], trackingArgs: List[Int]): String = {
    trackingArgs.map { tid =>
      args(tid)
    }.toList.mkString(":")
  }

  /**
   * Enqueue a task using a specific SlaveActor
   * @param queue // Queue name
   * @param classname // SlaveActor name
   * @param args // List[String] of args passed to the actor
   */
  def enqueue(queue: String, classname: String, args: List[String]): Unit = {
    enqueue(queue, classname, args, List())
  }

  def enqueue(queue: String, classname: String, args: List[String], trackingArgs: List[Int]): Unit = {
    val tid = queue + ":" + extractTrackingId(args, trackingArgs)

    val format = CompactPrinter(Payload(classname, args, tid).toJson)
    //    log.debug("enqueuing.. " + format)
    clients.withClient {
      client =>  
        {
          println("enqueue. " + queue + " : " + format)
          client.rpush("resque:queue:" + queue, format)
          client.sadd("resque:queues", queue)
          client.incr("resque:tracking:" + tid)
        }
    }
  }

  // def enqueueFailed(classname: String, args: List[String]) {
  //   //    log.error("enqueueFailed")
  //   val format = CompactPrinter(Payload(classname, args).toJson)
  //   //    log.error("enqueuing fail.. " + format)
  //   clients.withClient {
  //     client =>  
  //       {
  //         // println("enqueueFailed")
  //         client.rpush("resque:stat:failed", format)
  //         println(format)
  //       }
  //   }
  // }

  /**
   * Register a resque controller
   * @param queue , name of redis set (which contains the list of controllerNames
   * @param controllerNames // Set of controller Name
   */
  def register(redisClient: Jedis, queue: String, controllerNames: List[String]) {
    println("register start queue:" + queue + " controllerNames:" + controllerNames.mkString(","))
    if (redisClient == null) {
      clients.withClient {
        client =>  
          {
            for (controller <- controllerNames) {
              println("queue" + queue)
              println("controller" + controller)
              client.sadd(queue, controller)
            }
          }
      }
    } else {
      for (controller <- controllerNames) {
        redisClient.sadd(queue, controller)
      }
    }
    println("register end")
  }

  def registerQueues(queuesList: List[String]) {
    clients.withClient {
      client =>  
        {
          println("registerQueues")

          // get the controllers name by removing the package path part
          val controllerNames = queuesList.map(x =>  (x.split('.')).last)
          // register controllers in resque:queues
          register(client, "resque:queues", controllerNames)

        }
    }
  }

  def clearQueues(queuesList: List[String], aSystem: ActorSystem = null) {
    initSystem(aSystem)

    clients.withClient {
      client =>  
        {
          println("clearQueues")

          // var interval = 5L

          // create the ResqueSet (contains the controllers name)
          // val resques = new ResqueSet("resque:queues", client)

          // get the controllers name by removing the package path part
          val controllerNames = queuesList.map(x =>  (x.split('.')).last)
          // register controllers in resque:queues

          for (controller <- controllerNames) {
            println("controller:" + controller + " has " + client.llen("resque:queue:" + controller) + " items...clearing")
            client.del("resque:queue:" + controller)
          }

        }
    }

  }

  def shutdown {
    //TODO: shutdown fix this
    println("shutdown the workers cleanly")
    // system.shutdown
    // println("waiting for full termination")
    // system.awaitTermination
    // println("waiting terminated")
  }

}
