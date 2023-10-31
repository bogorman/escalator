package escalator.util.akka.streams

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

class RepeatLast[T] extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("RepeatLast.in")
  val out: Outlet[T] = Outlet[T]("RepeatLast.out")

  override val shape: FlowShape[T, T] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var lastElement: Option[T] = None
    private var finished = false

    setHandlers(
      in,
      out,
      new InHandler with OutHandler {
        override def onPush(): Unit = {
          lastElement = Some(grab(in))
          push(out, lastElement.get)
        }

        override def onPull(): Unit = {
          if (finished) push(out, lastElement.get)
          else pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          if (lastElement.isEmpty) completeStage()
          else {
            finished = true
            if (isAvailable(out)) push(out, lastElement.get)
          }
        }
      }
    )
  }

  override def toString: String = "RepeatLast"
}