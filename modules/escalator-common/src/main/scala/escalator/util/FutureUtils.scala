package escalator.util

import scala.concurrent.{ ExecutionContext, Future }

import escalator.util.logging.Logger

/** Bounded fan-out helpers shared across services that must respect the
  *  project's "never `Future.sequence`/`traverse` unbounded over DB or HTTP"
  *  rule. Hoisted out of the per-service private copies (Polymarket/Kalshi
  *  discovery, DataFields ingest) so there is a single implementation.
  */
object FutureUtils {

  /** Run `f` over `items` with at most `parallelism` calls in flight at once.
    *  Walks the list as a queue: when one call completes, the next dequeues.
    *  Per-item failures are logged (prefixed with `label`) and swallowed so one
    *  bad item can't sink the rest. `label` identifies the caller in the log.
    */
  def processBounded[A](items: List[A], parallelism: Int, label: String)(
      f: A => Future[Unit]
  )(implicit ec: ExecutionContext, logger: Logger): Future[Unit] = {
    if (items.isEmpty) return Future.successful(())

    val queue = new java.util.concurrent.ConcurrentLinkedQueue[A]()
    items.foreach(queue.offer)
    val totalP = math.max(1, math.min(parallelism, items.size))

    def worker(): Future[Unit] = {
      val next = queue.poll()
      if (next == null) Future.successful(())
      else {
        val done = f(next).recover {
          case ex =>
            logger.warning(s"$label processBounded item failed: ${ex.getMessage}")
            ()
        }
        done.flatMap(_ => worker())
      }
    }

    Future.sequence(List.fill(totalP)(worker())).map(_ => ())
  }

  /** Bounded result-collecting variant of `processBounded` — runs `f` over
    *  every item with at most `parallelism` calls in flight, returning all
    *  results in input order. Failures inside `f` propagate; callers should
    *  `.recover` per item if they want to swallow per-item errors.
    */
  def boundedTraverse[A, B](items: List[A], parallelism: Int)(
      f: A => Future[B]
  )(implicit ec: ExecutionContext): Future[List[B]] = {
    if (items.isEmpty) return Future.successful(Nil)
    val indexed = items.zipWithIndex
    val queue = new java.util.concurrent.ConcurrentLinkedQueue[(A, Int)]()
    indexed.foreach(queue.offer)
    val results = new java.util.concurrent.ConcurrentHashMap[Int, B]()
    val totalP = math.max(1, math.min(parallelism, items.size))

    def worker(): Future[Unit] = {
      val next = queue.poll()
      if (next == null) Future.successful(())
      else {
        val (item, idx) = next
        f(item).flatMap { b =>
          results.put(idx, b)
          worker()
        }
      }
    }

    Future.sequence(List.fill(totalP)(worker())).map { _ =>
      indexed.map { case (_, idx) => results.get(idx) }
    }
  }

}
