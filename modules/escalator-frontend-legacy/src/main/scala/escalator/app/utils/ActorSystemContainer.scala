package escalator.frontend.app.utils

import akka.actor.ActorSystem
// import akka.actor.ActorMaterializer

import scala.concurrent.ExecutionContext

final class ActorSystemContainer {

  implicit val actorSystem: ActorSystem = ActorSystem()
  // implicit val actorMaterializer: ActorMaterializer = new ActorMaterializer()

  implicit def ec: ExecutionContext = actorSystem.dispatcher

}

object ActorSystemContainer {

  implicit lazy final val default: ActorSystemContainer = new ActorSystemContainer

}
