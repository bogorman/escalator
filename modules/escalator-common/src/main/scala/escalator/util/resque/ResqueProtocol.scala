package escalator.util.resque

object ResqueProtocol {

  case object JobsAvailable
  case object Shutdown

  case class Queue(name: String)
  case class Done(which: String)

  case class Task(payload: Payload)

  case class WorkerStarted(workerPath: String, queue: String)
  case class WorkerStopped(workerPath: String, queue: String)

  case class TaskStarted(queue: String, payload: Payload)
  case class TaskSuccess(msg: String, queue: String, payload: Payload)
  case class TaskFailure(throwable: Throwable, queue: String, payload: Payload)

  case object RunJobs

}