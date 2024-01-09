package escalator.util.akka

import org.apache.pekko.actor.ActorSystem

import escalator.util.logging.Logger

object AkkaActorSystem {
  def create(name: String)(implicit logger: Logger): ActorSystem = {
    implicit val system = ActorSystem(name)
    WartLogger.create()
    system
  }
}
