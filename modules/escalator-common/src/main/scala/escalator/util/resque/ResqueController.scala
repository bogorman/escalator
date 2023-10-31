package escalator.util.resque

import akka.actor._
// import com.redis.RedisClient
import redis.clients.jedis._
import scala.collection.mutable.ArrayBuffer
// import akka.event.Logging

import java.util.Date
import java.text.SimpleDateFormat

// import scala.jdk.CollectionConverters._
import scala.jdk.CollectionConverters._

import spray.json._
// import DefaultJsonProtocol._
import escalator.util._

trait ResqueController extends Actor with ActorLogging {
  sealed abstract class WorkerStatus
  case object Busy extends WorkerStatus
  case object Available extends WorkerStatus

  import ResqueProtocol._
  import ResqueConstants._
  import ResqueUtils._
  import FailureMessageJsonProtocol._
  import WorkerStatusJsonProtocol._
  import PayloadJsonProtocol._
  // import StringHelper._

  // number of jobs to finish
  var mustbe: Int = 0

  // name of SlaveActors
  val workerNames: List[String]

  // var workerNamesLower: Vector[String] = Vector[String]() // a copy of workerNames in lower case ,
  // used to deal with akka path

  // needed to cancel the queue when finished
  val controllerName: String

  // package name of SlaveActors
  val packageName: String

  // number of childs workers
  var nbWorkers = ArrayBuffer[Int]()

  // list of child workers
  var workersActors: Vector[Vector[ActorRef]] = Vector[Vector[ActorRef]]()

  // status of workers
  var workersStatus: Vector[ArrayBuffer[WorkerStatus]] = Vector[ArrayBuffer[WorkerStatus]]()

  def nowAsString = {
    new SimpleDateFormat(DATE_FORMAT_RUBY_V1).format(new Date())
  }

  def init() = {
    context.system.eventStream.subscribe(context.self, classOf[WorkerStarted])
    context.system.eventStream.subscribe(context.self, classOf[WorkerStopped])

    // init workersName
    // for (x <- 0 until workerNames.size) {
    //   workerNamesLower = workerNamesLower :+ workerNames(x).toLowerCase
    // }

    // read nb of workers from config
    if (context.system.settings.config.hasPath("controllers." + controllerName.toLowerCase)) {
      val controllerConf = context.system.settings.config.getConfig("controllers." + controllerName.toLowerCase)
      for (x <- 0 until workerNames.size) {
        // get value from config
        val nbW = if (controllerConf.hasPath("nb-" + workerNames(x).toLowerCase)) {
          controllerConf.getInt("nb-" + workerNames(x).toLowerCase)
        } else {
          1 // default value
        }
        println("nbWorkers append " + nbW)
        nbWorkers.append(nbW)
      }
    } else {
      // default value, 1 worker by worker type
      for (x <- 0 until workerNames.size) {
        println("nbWorkers append X");
        nbWorkers.append(1)
      }
    }

    // init workers and their status (Available at starting)
    for (x <- 0 until workerNames.size) {
      // for each worker type

      // create coll for actorsReferences and actorsStatus
      var wRefs = Vector[ActorRef]()
      var wStatus = ArrayBuffer[WorkerStatus]()

      for (y <- 0 until nbWorkers(x)) {
        // create the workers actors with suffix -index
        // for example
        // /user/queue/workerType-0, /user/queue/workerType-1, /user/queue/workerType-2
        logDebug("Starting Actor " + workerNames(x).toLowerCase + "-" + y.toString)
        wRefs = wRefs :+ context.system.actorOf(Props(Class.forName(packageName + workerNames(x)).asInstanceOf[Class[Actor]]), workerNames(x).toLowerCase + "-" + y.toString)
        // at starting, all workers are available
        wStatus.append(Available)
      }
      workersActors = workersActors :+ wRefs
      workersStatus = workersStatus :+ wStatus
    }
  }

  def receive = {
    // log.debug("receive called")
    case Queue(name) =>  {
      logDebug("Queue name - START")
      Resque.clients.withClient {
        client =>  
          {
            val jobs = new ResqueList(name, client)
            logDebug(controllerName + " queue size : " + jobs.size)
            logAvailabilityInfo

            try {
              val workerNamesLower = workerNames.map(_.toLowerCase)
              var waitAvailable = false
              while (jobs.size > 0 && waitAvailable == false) {
                logDebug("jobs.size:" + jobs.size)
                val nextJob = jobs.getNext

                if (!nextJob.isEmpty) {
                  val payload = nextJob.get

                  val indexWorkerType = workerNamesLower.indexOf(payload.className.toLowerCase)
                  if (indexWorkerType == -1) {
                    throw new Exception("Could not find " + payload.className.toLowerCase + " in workers list. " + workerNamesLower.toString)
                  }

                  // find the first available worker
                  findFirstAvailableWorker(indexWorkerType) match {
                    case Some(iFirstAvailable) =>
                      // send the job to the worker
                      workersStatus(indexWorkerType)(iFirstAvailable) = Busy
                      workersActors(indexWorkerType)(iFirstAvailable) ! Task(payload)
                      // remove job from queue
                      jobs.popNext
                    case None =>
                      waitAvailable = true
                  }
                }

              }
            } catch {
              case e: Exception =>  {
                log.error(e.getMessage)
                e.printStackTrace
              }
            }

            housekeepQueue(client, name)
          }
      }
      logDebug("Queue name - END")
    }

    case WorkerStarted(workerPath, queue) =>  {
      // log.debug("WorkerStarted here:" + workerPath)
      val workerId = resqueWorkerId(workerPath)
      // log.debug("WorkerStarted " + workerId)

      // log.debug("calling recoverInflight")
      recoverInflight(queue, workerId)
      // log.debug("done calling recoverInflight")

      Resque.clients.withClient {
        client =>  
          {
            client.sadd(key(List(WORKERS)), workerId)
            client.set(key(List(WORKER, workerId, STARTED)), nowAsString)
          }
      }
    }

    case WorkerStopped(workerPath, queue) => {
      // log.debug("WorkerStopped here:" + workerPath)
      val workerId = resqueWorkerId(workerPath)
      stopWorker(workerId)
    }

    case TaskStarted(queue, payload) => {
      // this.jedis.set(key(WORKER, this.name), statusMsg(curQueue, job));
      val workerId = resqueWorkerId(sender.path.toString)
      Resque.clients.withClient {
        client =>  
          {
            client.set(key(List(WORKER, workerId)), statusMsg(queue, payload))
            client.set(key(List(INFLIGHT, workerId, queue)), CompactPrinter(payload.toJson))
          }
      }
    }

    case TaskSuccess(msg, queue, payload) =>  {
      val workerId = resqueWorkerId(sender.path.toString)
      Resque.clients.withClient {
        client =>  
          {
            client.incr(key(List(STAT, PROCESSED)))
            client.incr(key(List(STAT, PROCESSED, workerId)))
            client.del(key(List(WORKER, workerId)))

            client.del(key(List(INFLIGHT, workerId, queue)))

            if (!TextUtil.blank_?(payload.trackingid)) {
              client.decr("resque:tracking:" + payload.trackingid)
            }
          }
      }

      val wName = resqueWorkerName(sender.path.toString)
      // log.debug("TaskSuccess setWorkerAvailable " + wName)
      setWorkerAvailable(wName)
    }

    case TaskFailure(throwable, queue, payload) =>  {
      val workerId = resqueWorkerId(sender.path.toString)
      Resque.clients.withClient {
        client =>  
          {
            client.incr(key(List(STAT, FAILED)))
            client.incr(key(List(STAT, FAILED, workerId)))

            client.rpush(key(List(FAILED)), failMsg(throwable, queue, payload, workerId))
            client.del(key(List(WORKER, workerId)))

            client.del(key(List(INFLIGHT, workerId, queue)))

            if (!TextUtil.blank_?(payload.trackingid)) {
              client.decr("resque:tracking:" + payload.trackingid)
            }
          }
      }
      val wName = resqueWorkerName(sender.path.toString)
      // log.debug("TaskFailure setWorkerAvailable " + wName)
      setWorkerAvailable(wName)
    }

    case _ =>  {
      logDebug("ResqueController unknown message ")
    }
  }

  def statusMsg(queue: String, payload: Payload) = {
    CompactPrinter(WorkerStatus(nowAsString, queue, payload, false).toJson)
  }

  def failMsg(throwable: Throwable, queue: String, payload: Payload, worker: String) = {
    logDebug("throwable:" + throwable.getMessage)
    logDebug("queue:" + queue)
    logDebug("worker:" + worker)
    logDebug("nowAsString:" + nowAsString)
    throwable.getStackTrace.toList.map { line =>
      logDebug(line.toString)
    }
    // val errorMessage = exception.getMessage
    val errorMessage = if (throwable.getMessage != null) {
      throwable.getMessage
    } else {
      "No Error Message"
    }
    CompactPrinter(FailureMessage(nowAsString, errorMessage, throwable.getStackTrace.toList.map(_.toString), payload, queue, worker).toJson)
  }

  private def findFirstAvailableWorker(indexType: Int): Option[Int] = {
    var x = 0
    var found = false
    while (x < nbWorkers(indexType) && found == false) {
      if (workersStatus(indexType)(x) == Available) found = true // found one
      else x += 1
    }
    if (found) Some(x)
    else None
  }

  /**
   * Set a worker available from this sender path
   * @param senderPath the actor akka path
   */
  private def setWorkerAvailable(workerName: String) = {
    // get the type index
    val workerNamesLower = workerNames.map(_.toLowerCase)
    val indexWorkerType = workerNamesLower.indexOf(workerName)

    // get the worker index
    // var workerIndex = (sender.path.toString.split("-")).last.toInt

    var workerIndex = resqueWorkerIndex(sender.path.toString)

    // this actor is now available for another task
    workersStatus(indexWorkerType)(workerIndex) = Available
  }

  private def logAvailabilityInfo() = {
    logDebug("logAvailabilityInfo:" + workerNames.mkString(","))

    for (x <- 0 until workerNames.size) {
      // for each worker type
      // println("logAvailabilityInfo X1")
      var nbBusy = 0
      for (y <- 0 until nbWorkers(x)) {
        // println("logAvailabilityInfo X1.x")
        if (workersStatus(x)(y) == Busy) nbBusy += 1
      }
      // println("logAvailabilityInfo X2")
      logDebug("%s - %d busy, %d available".format(workerNames(x), nbBusy, (nbWorkers(x) - nbBusy)))
    }
  }

  private def recoverInflight(queue: String, workerId: String) = {
    val inflightKey = key(List(INFLIGHT, workerId, queue))
    val workerQueueKey = "resque:queue:" + queue
    // val payloadsToRecover = Resque.clients.withClient {
    //   client =>  
    //     {
    //       client.lrange(k, 0, -1)
    //     }
    // }

    Resque.clients.withClient { client =>  
      // payloadsToRecover.foreach { p =>
      // client.lpush(key(List(INFLIGHT, workerId, queue)), CompactPrinter(payload.toJson))
      // log.debug("recovering payload for " + k)
      // log.debug(p)
      val payload = client.get(inflightKey)
      payload.foreach { p =>
        // log.debug("recovering message from " + inflightKey + " to " + workerQueueKey)
        client.lpush(workerQueueKey, p)
      }
      client.del(inflightKey)
    }

  }

  private def stopWorkerActors() = {
    logDebug("Resque Controller stopWorkerActors.")
    workersActors.map { classNames =>
      classNames.map { actor =>
        val workerId = resqueWorkerId(actor.path.toString)
        stopWorker(workerId)
        actor ! PoisonPill
      }
    }
    logDebug("Resque Controller stopWorkerActors.")
  }

  override def preStart() = {

  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    // LoggerUtils.error(self.path + "\n" + TraceUtils.stackTraceToString(reason))
    // log.debug("preRestart")
    // log.debug(TraceUtils.stackTraceToString(reason))
    // log.debug(message.getOrElse("No message"))
    // message.map()

    // postStop
    // ActorUtils.logActorRestart(self, reason.getMessage)

    LogUtils.logActorRestart(self, LogUtils.stackTraceToString(reason))
    super.preRestart(reason, message)
  }

  override def postStop() = {
    // log.debug("Stopping Actor")
    // log.debug(sender.toString)
    // sender ! WorkerStopped
    // context.system.eventStream.publish(WorkerStopped(self.path.toString, queue))
    // ActorUtils.logActorStop(self)
    stopWorkerActors()
  }

  def stopWorker(workerId: String) = {
    logDebug("stopWorker " + workerId)
    Resque.clients.withClient {
      client =>  
        {
          client.srem(key(List(WORKERS)), workerId)
          client.del(key(List(WORKER, workerId)))
          client.del(key(List(WORKER, workerId, STARTED)))
          client.del(key(List(STAT, FAILED, workerId)))
          client.del(key(List(STAT, PROCESSED, workerId)))
        }
    }
  }

  def housekeepQueue(client: Jedis, queue: String) = {
    logDebug("housekeepQueue")

    val workerQueueKey = "resque:queue:" + queue
    val size = client.llen(workerQueueKey)
    if (size == 0) {
      val trackingKeys = client.keys("resque:tracking:" + queue + "*").asScala
      trackingKeys.foreach { tk =>
        println("delete tk:" + tk)
        client.del(tk)
      }
    }
  }

  def logDebug(message: String) = {
    log.debug(message)
    // println(message)
  }

}

