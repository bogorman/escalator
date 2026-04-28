package escalator.util.logging

import org.apache.pekko.actor.Actor
import org.slf4j.MDC

/**
 * Mix into a pekko Actor to push the actor's path into SLF4J MDC under key
 * "actorPath" while a message is being handled. Logback's SiftingAppender
 * can then split log output into per-actor files using ${actorPath}.
 *
 * Usage in an actor:
 *
 *   class MyActor extends Actor with DynamicActorLogging with ActorPathMdc {
 *     def receive: Receive = withActorPathMdc(myHandler orElse logReceive)
 *     ...
 *   }
 *
 * Why a wrapper rather than overriding aroundReceive: pekko's Actor
 * declares aroundReceive as protected[pekko], so a trait outside the
 * pekko package can't override it. The wrapper achieves the same
 * outcome (set MDC during message handling, clear after) with no
 * pekko-internal access.
 *
 * Sanitization: full pekko path "pekko://betbot/user/exchange-user-manager/user-4-2"
 * becomes "exchange-user-manager-user-4-2" — strips the address prefix
 * and the leading "/user/", then replaces remaining slashes with dashes
 * so the value is safe to use directly in a filename.
 *
 * Caveat: MDC is thread-local. Lines logged synchronously from the
 * receive body will carry actorPath; lines logged from async callbacks
 * (Future.onComplete) run on dispatcher threads where MDC is empty and
 * fall back to the SiftingAppender's default discriminator value (_root).
 */
trait ActorPathMdc { this: Actor =>

  private val actorPathMdcKey = "actorPath"

  private lazy val actorPathMdcValue: String =
    self.path.toStringWithoutAddress
      .stripPrefix("/user/")
      .replace('/', '-')

  protected def withActorPathMdc(receive: Receive): Receive = new Receive {
    def isDefinedAt(msg: Any): Boolean = receive.isDefinedAt(msg)
    def apply(msg: Any): Unit = {
      MDC.put(actorPathMdcKey, actorPathMdcValue)
      try receive.apply(msg)
      finally MDC.remove(actorPathMdcKey)
    }
  }
}
