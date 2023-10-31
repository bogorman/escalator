package escalator.util

import scala.util.Random

object Randomizer {
  def onceEvery(n: Int)(action: => Unit): Unit =
    if (Random.nextInt(n) == 0) action else ()
}
