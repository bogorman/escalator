package escalator.util.akka.streams

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}

import escalator.util.logging.Logger

object AkkaStreamsMaterializer {
  def apply()(implicit logger: Logger, system: ActorSystem): ActorMaterializer = {
    val decider: Supervision.Decider = { e =>
      logger.error(e, "Unhandled exception in stream")
      Supervision.Stop
    }

    val materializerSettings =
      ActorMaterializerSettings(system)
        .withSupervisionStrategy(decider)
        .withInputBuffer(1024, 1024)

    ActorMaterializer(materializerSettings)
  }
}
