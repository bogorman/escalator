package escalator.util.monix

import scala.util.control.NonFatal

import monix.eval.Task
import monix.eval.Task.raiseError

import escalator.syntax.WithUnit

object TaskSyntax {
  implicit class RichTask[A](task: Task[A]) {
    def filter(p: A => Boolean): Task[A] =
      task.flatMap(a =>
        try
            if (p(a)) Task.now(a)
            else Task.raiseError(new NoSuchElementException("Future.filter predicate is not satisfied"))
        catch {
          case NonFatal(ex) => raiseError(ex)
        }
      )

    def withFilter(p: A => Boolean): Task[A] = filter(p)
  }

  def foldLeftTasks[T, U](tasks: List[Task[T]], fold: (U, Task[T]) => Task[U], unit: U): Task[U] = {

    @scala.annotation.tailrec
    def accumulator(remainingTasks: List[Task[T]], acc: Task[U]): Task[U] = {
      if (remainingTasks.isEmpty) acc
      else accumulator(remainingTasks.tail, acc.flatMap(fold(_, remainingTasks.head)))
    }

    accumulator(tasks, Task.pure(unit))

  }

  def foldLeftTasks[T, U](tasks: List[Task[T]], fold: (U, Task[T]) => Task[U])(implicit unit: WithUnit[U]): Task[U] =
    foldLeftTasks(tasks, fold, unit.unit)

  def exists[T](tasks: List[Task[T]], predicate: T => Task[Boolean]): Task[Boolean] =
    foldLeftTasks[T, Boolean](
      tasks,
      (left: Boolean, right: Task[T]) => if (left) Task.pure(left) else right.flatMap(predicate),
      false
    )

}
