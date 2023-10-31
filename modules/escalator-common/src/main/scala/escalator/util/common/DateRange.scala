package escalator.util

import java.time._
import java.time.temporal._

// https://users.scala-lang.org/t/how-to-create-date-ranges-in-scala/6566/6

case class DateRange[T <: Temporal](from:T, to:T) {
      def every(i:Int, chronoUnit:ChronoUnit)(implicit ord:Ordering[T]):LazyList[T] =
           LazyList.iterate(from)(t => t.plus(i, chronoUnit).asInstanceOf[T]).takeWhile(ord.lteq(_, to))
}