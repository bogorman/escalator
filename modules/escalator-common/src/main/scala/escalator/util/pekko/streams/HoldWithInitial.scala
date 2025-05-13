package escalator.util.akka.streams

import org.apache.pekko.stream._
import org.apache.pekko.stream.stage._

final class HoldWithInitial[T](initial: T) extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("HoldWithInitial.in")
  val out: Outlet[T] = Outlet[T]("HoldWithInitial.out")

  override val shape: FlowShape[T, T] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var currentValue: T = initial

    setHandlers(
      in,
      out,
      new InHandler with OutHandler {
        override def onPush(): Unit = {
          currentValue = grab(in)
          pull(in)
        }

        override def onPull(): Unit = {
          push(out, currentValue)
        }
      }
    )

    override def preStart(): Unit = {
      pull(in)
    }
  }

}
