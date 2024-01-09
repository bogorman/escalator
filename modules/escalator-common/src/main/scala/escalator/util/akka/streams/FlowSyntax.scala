package escalator.util.akka.streams

import org.apache.pekko.stream.scaladsl.Flow

import cats.data.State

object FlowSyntax {
  implicit class RichFlow[I, O, Mat](flow: Flow[I, O, Mat]) {
    def state[S, A](zero: S)(transition: O => State[S, A]): Flow[I, A, Mat] =
      flow.scan[(S, Option[A])]((zero, None)) {
        case ((s, _), i) =>
          val (nextState, output) = transition(i).run(s).value
          (nextState, Some(output))
      }
      .mapConcat(_._2.toList)
  }
}
