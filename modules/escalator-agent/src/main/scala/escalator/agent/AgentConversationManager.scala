package escalator.agent

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

import org.apache.pekko.actor.{Actor, ActorRef, Props, Terminated}

import escalator.util.logging.Logger

/**
 * What the conversation layer needs besides the store and the router.
 *
 * @param workspaceRoot      where CLI workspaces are created (`<root>/<profile>/<sessionId>`)
 * @param profiles           the registered profiles
 * @param prompts            override-or-default prompt blocks
 * @param toolExecutorFor    in-process tools for a direct-API engine, per (profile, context). Default: none
 * @param sessionContextFor  who the thread is for (by owning user id). Default: nobody in particular
 */
final case class AgentConfig(
    workspaceRoot:     java.nio.file.Path,
    profiles:          AgentProfiles = AgentProfiles.default,
    prompts:           PromptStore   = PromptStore.defaults(AgentProfiles.default),
    toolExecutorFor:   (AgentProfile, SessionContext) => Option[AgentToolExecutor] = (_, _) => None,
    sessionContextFor: Option[Long] => Future[Option[SessionContext]] = _ => Future.successful(None)
)

/**
 * ==Conversation directory + the concurrency budget==
 *
 * Routes a message to the actor for one conversation, spawning it (and loading
 * its row) on demand, and owns the two caps that used to be HTTP errors:
 *
 *  - per user: how many of their turns may run at once (`max-running-per-user`);
 *  - per host: how many turns may run at all (`max-running-total`).
 *
 * Over either cap the turn is not refused, it WAITS — the caller gets a
 * position and a `TurnQueued` event, and is granted a slot in arrival order as
 * others finish. That is the difference between "the assistant is busy, try
 * again" and a chat that simply answers a moment later.
 *
 * Slots are held by the conversation actor for exactly the length of a turn;
 * an actor that dies mid-turn releases its slot through `Terminated`, so a
 * crash cannot leak the budget.
 */
object AgentConversationManager {

  def props(
      config:            AgentConfig,
      store:             AgentEventStore,
      router:            ProviderRouter,
      maxRunningPerUser: Int,
      maxRunningTotal:   Int,
      idleTimeout:       FiniteDuration
  )(implicit logger: Logger): Props =
    Props(new AgentConversationManager(config, store, router, maxRunningPerUser, maxRunningTotal, idleTimeout))

  /** Send `msg` to the actor for `conversationId`, spawning it if need be. */
  final case class Deliver(conversationId: Long, msg: Any)

  /** Stop a conversation's actor (delete / explicit close). Always acked. */
  final case class Evict(conversationId: Long)

  /** The conversation row is gone (or was never there). */
  final case class Unknown(conversationId: Long)

  case object Ack

  // ── slot protocol (conversation actor ⇄ manager) ──────────────────────────

  private[agent] final case class AcquireSlot(userId: Long)
  private[agent] final case class ReleaseSlot(userId: Long)
  private[agent] case object SlotGranted
  private[agent] final case class SlotQueued(position: Int)

  private final case class Loaded(conversationId: Long, row: Option[ConversationRow])
}

class AgentConversationManager(
    config:            AgentConfig,
    store:             AgentEventStore,
    router:            ProviderRouter,
    maxRunningPerUser: Int,
    maxRunningTotal:   Int,
    idleTimeout:       FiniteDuration
)(implicit logger: Logger)
    extends Actor {

  import AgentConversationManager._

  private implicit val ec: scala.concurrent.ExecutionContext = context.dispatcher

  private val actors  = mutable.Map.empty[Long, ActorRef]
  private val idOf    = mutable.Map.empty[ActorRef, Long]
  private val loading = mutable.Map.empty[Long, Vector[(Any, ActorRef)]]

  // Running turns, and who is waiting for one.
  private val runningByUser = mutable.Map.empty[Long, Int]
  private var runningTotal  = 0
  private val waiting       = mutable.Queue.empty[(ActorRef, Long)]

  override def receive: Receive = {

    case Deliver(conversationId, msg) =>
      actors.get(conversationId) match {
        case Some(ref) => ref.forward(msg)
        case None =>
          val replyTo = sender()
          loading.get(conversationId) match {
            case Some(buffered) =>
              loading.update(conversationId, buffered :+ ((msg, replyTo)))
            case None =>
              loading.update(conversationId, Vector((msg, replyTo)))
              store.get(conversationId).onComplete {
                case Success(row) => self ! Loaded(conversationId, row)
                case Failure(t) =>
                  logger.warning(s"[agent] conversation load failed id=$conversationId: ${t.getMessage}")
                  self ! Loaded(conversationId, None)
              }
          }
      }

    case Loaded(conversationId, rowOpt) =>
      val buffered = loading.remove(conversationId).getOrElse(Vector.empty)
      rowOpt.flatMap(spawn) match {
        case Some(ref) => buffered.foreach { case (msg, replyTo) => ref.tell(msg, replyTo) }
        case None      => buffered.foreach { case (_, replyTo) => replyTo ! Unknown(conversationId) }
      }

    case Evict(conversationId) =>
      actors.get(conversationId).foreach(_ ! AgentConversationActor.Close)
      sender() ! Ack

    case AcquireSlot(userId) =>
      val who = sender()
      if (hasRoom(userId)) {
        take(userId)
        who ! SlotGranted
      } else {
        waiting.enqueue((who, userId))
        who ! SlotQueued(waiting.size)
      }

    case ReleaseSlot(userId) =>
      release(userId)
      grantNext()

    case Terminated(ref) =>
      idOf.remove(ref).foreach { id =>
        if (actors.get(id).contains(ref)) actors.remove(id)
      }
      // A conversation that died holding a slot must not keep it.
      val stillWaiting = waiting.dequeueAll(_._1 == ref)
      if (stillWaiting.nonEmpty) grantNext()
  }

  // ── budget ────────────────────────────────────────────────────────────────

  private def hasRoom(userId: Long): Boolean =
    runningTotal < maxRunningTotal && runningByUser.getOrElse(userId, 0) < maxRunningPerUser

  private def take(userId: Long): Unit = {
    runningByUser.update(userId, runningByUser.getOrElse(userId, 0) + 1)
    runningTotal += 1
  }

  private def release(userId: Long): Unit = {
    val left = runningByUser.getOrElse(userId, 0) - 1
    if (left <= 0) runningByUser.remove(userId) else runningByUser.update(userId, left)
    runningTotal = math.max(0, runningTotal - 1)
  }

  /**
   * Grant to the first waiter the caps allow. Deliberately not strict FIFO
   * across users: a user who is at their OWN cap is skipped rather than
   * blocking everyone behind them.
   */
  private def grantNext(): Unit = {
    val skipped = mutable.ListBuffer.empty[(ActorRef, Long)]
    var granted = false
    while (!granted && waiting.nonEmpty) {
      val entry = waiting.dequeue()
      if (hasRoom(entry._2)) {
        take(entry._2)
        entry._1 ! SlotGranted
        granted = true
      } else skipped += entry
    }
    if (skipped.nonEmpty) {
      // Skipped waiters keep their place at the FRONT: they were only passed
      // over because of their own user's cap, not because they arrived late.
      val rest = waiting.dequeueAll(_ => true)
      waiting ++= skipped
      waiting ++= rest
    }
  }

  // ── spawning ──────────────────────────────────────────────────────────────

  private def spawn(row: ConversationRow): Option[ActorRef] =
    config.profiles
      .base(row.profile)
      .flatMap(profile => router.byId(row.provider).orElse(router.pick(profile, Some(row.provider))))
      .map { provider =>
        val ref = context.actorOf(
          AgentConversationActor.props(row, config, store, provider, self, idleTimeout)(logger),
          name = s"agent-conv-${row.id}-${java.util.UUID.randomUUID().toString.take(6)}"
        )
        context.watch(ref)
        actors.update(row.id, ref)
        idOf.update(ref, row.id)
        ref
      }
      .orElse {
        logger.warning(
          s"[agent] cannot start conversation ${row.id}: no provider for profile=${row.profile} provider=${row.provider}"
        )
        None
      }
}
