package escalator.util.akka.streams

import scala.annotation.tailrec

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Graph, SinkShape, UniformFanOutShape}
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, Keep, Sink}

object SinkUtils {
  def combineMat[T, U, Mat](
    first: Sink[U, Mat],
    second: Sink[U, _],
    rest: Sink[U, _]*
  )(
    strategy: Int ⇒ Graph[UniformFanOutShape[T, U], NotUsed]
  ): Sink[T, Mat] =
    Sink.fromGraph(GraphDSL.create(first) { implicit b ⇒ f =>
      import GraphDSL.Implicits._
      val d = b.add(strategy(rest.size + 2))
      d.out(0) ~> f
      d.out(1) ~> second

      @tailrec def combineRest(idx: Int, i: Iterator[Sink[U, _]]): SinkShape[T] =
        if (i.hasNext) {
          d.out(idx) ~> i.next()
          combineRest(idx + 1, i)
        } else new SinkShape(d.in)

      combineRest(2, rest.iterator)
    })

  def listCombineNMat[In, Mat](first: Sink[In, Mat], toCombine: List[Sink[In, _]]): Sink[In, Mat] = toCombine match {
    case Nil => first
    case single :: rest => combineMat(first, single, rest: _*)(Broadcast[In](_))
  }

  def combineKeepingMats[In, Mat](
    first: Sink[In, Mat],
    others: List[Sink[In, Mat]],
    eagerCancel: Boolean
  ): Sink[In, List[Mat]] = {
    val initial = first.mapMaterializedValue(x => List(x))

    others.foldLeft(initial) { (acc: Sink[In, List[Mat]], next: Sink[In, Mat]) =>
      val shape = GraphDSL.create(next, acc)(_ :: _) { implicit b => (first, second) =>
        import GraphDSL.Implicits._
        val broadcast = b.add(Broadcast[In](2, eagerCancel))
        broadcast.out(0) ~> first
        broadcast.out(1) ~> second
        SinkShape(broadcast.in)
      }

      Sink.fromGraph(shape)
    }
  }

  def combineKeepingMats[In, Mat1, Mat2](
    first: Sink[In, Mat1],
    other: Sink[In, Mat2],
    eagerCancel: Boolean
  ): Sink[In, (Mat1, Mat2)] = {
    val shape = GraphDSL.create(first, other)(Keep.both) { implicit b => (first, second) =>
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[In](2, eagerCancel))
      broadcast.out(0) ~> first
      broadcast.out(1) ~> second
      SinkShape(broadcast.in)
    }

    Sink.fromGraph(shape)
  }
}
