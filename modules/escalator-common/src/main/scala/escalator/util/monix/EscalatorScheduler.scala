package escalator.util.monix

import monix.execution.Scheduler
import monix.execution.schedulers.TracingScheduler

/**
 * Shared, project-wide Monix [[Scheduler]] used wherever code wants
 * `Local`-aware Task execution.
 *
 * Backed by a [[TracingScheduler]] over [[Scheduler.global]] so values held
 * in `monix.execution.misc.Local` propagate **per-Task** instead of via a
 * per-thread `ThreadLocal`. Without `TracingScheduler`, the default
 * `Scheduler.global` runs on a Fork/Join pool whose threads are shared
 * across concurrent Tasks — any `Local.bind` set inside one Task would be
 * visible to peer Tasks scheduled on the same thread.
 *
 * Concrete bug this prevents: Quill 3.12.x stores the in-flight transaction
 * connection in `Local[Option[Connection]]`. With `Scheduler.global`
 * (non-tracing) the binding is essentially a `ThreadLocal`, so two
 * concurrent transactions on the same thread leak `currentConnection` to
 * each other:
 *
 *   1. Task A enters `ctx.transaction { ... }` → binds Local on thread T
 *   2. Scheduler yields T to Task B before A commits
 *   3. Task B's `ctx.run { ... }` reads Local — finds A's connection
 *   4. A commits, returning that connection to the pool
 *   5. B's later use of that connection → `SQLException: Connection is closed`
 *
 * Use this everywhere the global Monix scheduler would otherwise be picked
 * up — most importantly in auto-generated repositories, which embed an
 * `import escalator.util.monix.EscalatorScheduler.scheduler` so all DB
 * Tasks materialise via per-Task local context.
 */
object EscalatorScheduler {
  implicit lazy val scheduler: Scheduler = TracingScheduler(Scheduler.global)
}
