package escalator.util.monitoring

import scala.concurrent.{ExecutionContext, Future}

import escalator.util.monitoring.Monitoring.{Counter, Histogram, MinMaxCounter} // Gauge, 

abstract class Monitoring {
  def counter(name: String): Counter
  // def gauge(name: String)(value: => Long): Gauge
  def histogram(name: String): Histogram
  def minMaxCounter(name: String): MinMaxCounter
}

object Monitoring {
  abstract class Counter {
    def increment(): Unit
    def add(amount: Long): Unit
  }

  abstract class MinMaxCounter {
    def increment(): Unit
    def decrement(): Unit
  }

  abstract class Histogram {
    def record(value: Long): Unit

    def record[T](block: => T): T = {
      val start = System.nanoTime()
      val result = block
      val end = System.nanoTime()
      record(end - start)
      result
    }

    def recordF[T](future: => Future[T])(implicit executionContext: ExecutionContext): Future[T] = {
      val start = System.nanoTime()
      val result = future
      result.foreach { _ =>
        val end = System.nanoTime()
        record(end - start)
      }
      result
    }
  }

  // abstract class Gauge
}
