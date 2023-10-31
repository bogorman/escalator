package escalator.util.monitoring

import kamon.Kamon

import escalator.util.monitoring.KamonMonitoring.{KamonCounter, KamonHistogram, KamonMinMaxCounter} //, KamonGauge, 
import escalator.util.monitoring.Monitoring.{Counter, Histogram, MinMaxCounter} //,Gauge,  

class KamonMonitoring extends Monitoring {
  // def start() = {
  //   // Kamon.start()
  // }

  // def stop() = {
  //   // Kamon.shutdown()
  // }

  override def counter(name: String): Counter = new KamonCounter(name)

  // override def gauge(name: String)(value: => Long): Gauge = new KamonGauge(name, value)

  override def histogram(name: String): Histogram = new KamonHistogram(name)

  override def minMaxCounter(name: String): MinMaxCounter = new KamonMinMaxCounter(name)
}

object KamonMonitoring {

  class KamonCounter(name: String) extends Counter {
    private val counter = Kamon.counter(name+"_count").withoutTags()

    override def increment(): Unit = counter.increment()

    override def add(amount: Long): Unit = counter.increment(amount)
  }

  class KamonMinMaxCounter(name: String) extends MinMaxCounter {
    private val counter = Kamon.rangeSampler(name).withoutTags()

    override def increment(): Unit = {
      counter.increment()
    }

    override def decrement(): Unit = {
      counter.decrement()
    }
  }

  class KamonHistogram(name: String) extends Histogram {
    private val histogram = Kamon.histogram(name+"_histogram").withoutTags()

    def record(value: Long): Unit = histogram.record(value)
  }

  // class KamonGauge(name: String, value: => Long) extends Gauge {
  //   Kamon.gauge(name).withoutTags()(value)
  // }

}
