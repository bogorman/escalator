package escalator.util.resque

// import com.redis.RedisClient
// import redis.clients.jedis._

import org.apache.pekko.actor._
import escalator.util.redis._

class ResqueMaster(client: Dress.Wrap, queues: ResqueSet, val queuesList: List[String]) extends Actor with ActorLogging {
  import ResqueProtocol._

  /* case JobsAvailable checks the jobs available in the queues, if
   * there are, it gets the name of the queues and instance an actor
   * with their same name for each of them.
   * TODO checking to replace with transactions in redis..?
   * case Done received when the queue ended the jobs
   */
  def receive = {
    case JobsAvailable =>  {
      //      println("Checking jobs available... total resques: " + queuesList.size)
      logDebug("[ResqueMaster] Checking jobs available... total resques: " + queuesList.size)
      logDebug("[ResqueMaster] " + queuesList.mkString(","))

      queuesList.foreach { fullQueueName =>
        // get the className
        logDebug("[ResqueMaster] fullQueueName:" + fullQueueName)
        val queueName = (fullQueueName.split('.')).last // remove package path
        // process the job
        getWorker(queueName, fullQueueName) ! Queue("resque:queue:" + queueName)
      }
      logDebug("[ResqueMaster] Checking jobs available Done")
    }

    // case Done(msg) =>  {
    //   log.debug("[ResqueMaster] - Done the job! " + msg)
    // }

    // case a: Any =>  {
    //   log.debug("a unknown message ")
    //   log.debug(a.toString)
    //   // println(_)
    // }

    case _ =>  {
      logDebug("[ResqueMaster] unknown message ")
      // log.debug(_.toString)
      // println(_)
    }

  }

  /* If the worker with that name doesn't exist,
   * creates a new ActorRef for it
   * @param workerName: String name of the worker in CamelCase format
   */
  def getWorker(queueName: String, fullQueueName: String) = {
    logDebug("getWorker queueName:" + queueName + " fullQueueName:" + fullQueueName)
    val worker = if (context.child(queueName.toLowerCase) == None) {
      context.actorOf(
        // get the class from the name of the queue
        // use the Resque Map to have the package+className
        Props(Class.forName(fullQueueName).asInstanceOf[Class[Actor]]), queueName.toLowerCase)
    } else {
      context.child(queueName.toLowerCase).get
    }
    // println("worker created:" + worker.path.toString)
    worker
  }

  override def preStart() = {

  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    // LoggerUtils.error(self.path + "\n" + TraceUtils.stackTraceToString(reason))
    // log.debug(TraceUtils.stackTraceToString(reason))
    // ActorUtils.logActorRestart(self, reason.getMessage)
    super.preRestart(reason, message)
  }

  override def postStop() = {
    // ActorUtils.logActorStop(self)
  }

  def logDebug(message: String) = {
    log.debug(message)
    // println(message)
  }

}