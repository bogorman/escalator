package escalator.agent

import java.nio.file.Files
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.Timeout

import escalator.util.logging.Logger

import escalator.agent.protocol._

/** The outcome of one turn run outside a stored conversation. */
final case class TurnResult(
    provider: String,
    model:    String,
    text:     String,
    outcome:  TurnOutcome,
    usage:    Usage,
    errors:   List[String],
    events:   List[AgentEvent]
)

/**
 * ==The agent engine, assembled==
 *
 * Settings → providers → catalog → router, plus the stores and (on first use)
 * the conversation manager actor. Constructing this starts NOTHING: the
 * manager actor is spawned lazily the first time a stored conversation is
 * used, and a provider only spawns a process / opens a connection when a turn
 * runs.
 */
class AgentEngine(
    val settings: AgentSettings,
    val store:    AgentEventStore,
    val prompts:  PromptStore,
    val profiles: AgentProfiles = AgentProfiles.default,
    systemFor:    () => ActorSystem,
    toolExecutorFor: (AgentProfile, SessionContext) => Option[AgentToolExecutor] = (_, _) => None,
    sessionContextFor: Option[Long] => Future[Option[SessionContext]] = _ => Future.successful(None)
)(implicit ec: ExecutionContext, logger: Logger) {

  val providers: Seq[AgentProvider] = settings.providers
  val catalog: ModelCatalog         = ModelCatalog.fromProviders(providers)
  val router: ProviderRouter        = ProviderRouter(providers, settings, catalog)

  private implicit val askTimeout: Timeout = Timeout(30.seconds)

  lazy val manager: ActorRef = systemFor().actorOf(
    AgentConversationManager.props(
      AgentConfig(settings.workspaceRoot, profiles, prompts, toolExecutorFor, sessionContextFor),
      store,
      router,
      settings.maxRunningPerUser,
      settings.maxRunningTotal,
      settings.idleTimeout
    ),
    "agent-conversations"
  )

  /** Create a stored conversation on the engine the router picks (or `provider`, when usable). */
  def createConversation(
      profileId: String,
      provider:  Option[String] = None,
      model:     Option[String] = None,
      userId:    Option[Long]   = None
  ): Future[Either[String, ConversationRow]] =
    profiles.base(profileId) match {
      case None => Future.successful(Left(s"unknown agent profile '$profileId'"))
      case Some(profile) =>
        router.pick(profile, provider) match {
          case None => Future.successful(Left(s"no usable agent engine for profile '$profileId'"))
          case Some(p) =>
            router.validateModel(p, model) match {
              case Left(msg) => Future.successful(Left(msg))
              case Right(m) =>
                store
                  .create(userId, profileId, p.id, router.pickModel(p, m), "", Some(UUID.randomUUID().toString), None)
                  .map(Right(_))
            }
        }
    }

  /** Queue a user turn on a stored conversation. */
  def send(conversationId: Long, text: String): Future[Any] =
    manager ? AgentConversationManager.Deliver(conversationId, AgentConversationActor.Send(text))

  /** Attach to a conversation's live event stream (replay from the store for anything earlier). */
  def live(conversationId: Long): Future[Option[AgentConversationActor.LiveEvents]] =
    (manager ? AgentConversationManager.Deliver(conversationId, AgentConversationActor.SubscribeLive)).map {
      case l: AgentConversationActor.LiveEvents => Some(l)
      case _                                    => None
    }

  def interrupt(conversationId: Long): Future[Any] =
    manager ? AgentConversationManager.Deliver(conversationId, AgentConversationActor.Interrupt)

  /**
   * Run ONE turn directly on a provider: no stored conversation, no actor.
   * A fresh temp workspace is used for a CLI engine and removed afterwards.
   * This is what `AgentSmoke` drives, and a cheap way to use a profile from a job.
   */
  def runOnce(
      profileId: String,
      message:   String,
      provider:  Option[String] = None,
      model:     Option[String] = None,
      context:   Option[SessionContext] = None
  )(implicit mat: Materializer): Future[TurnResult] =
    profiles.build(profileId, prompts) match {
      case None => Future.failed(new IllegalArgumentException(s"unknown agent profile '$profileId'"))
      case Some(profile) =>
        val picked = provider match {
          case Some(id) => router.byId(id).filter(router.usable(profile, _))
          case None     => router.pick(profile, None)
        }
        picked match {
          case None => Future.failed(new IllegalStateException(s"no usable agent engine (requested ${provider.getOrElse("any")})"))
          case Some(p) =>
            val chosen    = router.pickModel(p, model)
            val workspace = Files.createTempDirectory("agent-")
            val spec = SessionSpec(
              conversationId = UUID.randomUUID().toString,
              profile        = profile,
              context        = context,
              model          = chosen,
              workspace      = Some(workspace),
              tools          = ToolSurface(
                allowedTools = profile.allowedTools,
                executor     = if (profile.toolsEnabled) toolExecutorFor(profile, context.getOrElse(SessionContext.anonymous)) else None
              )
            )
            val run = for {
              session <- p.open(spec)
              events  <- session.sendTurn(TurnId(UUID.randomUUID().toString), message).runWith(Sink.seq)
              _        = session.close()
            } yield {
              val text = events.collect { case AgentEvent.ContentDelta(_, _, StreamKind.AssistantText, d) => d }.mkString
              val done = events.collectFirst { case t: AgentEvent.TurnCompleted => t }
              TurnResult(
                provider = p.id,
                model    = chosen,
                text     = text,
                outcome  = done.map(_.outcome).getOrElse(TurnOutcome.Failed),
                usage    = done.map(_.usage).getOrElse(Usage.empty),
                errors   = events.collect { case e: AgentEvent.RuntimeError => s"${e.kind}: ${e.message}" }.toList,
                events   = events.toList
              )
            }
            run.andThen { case _ => deleteQuietly(workspace) }
        }
    }

  private def deleteQuietly(dir: java.nio.file.Path): Unit =
    try {
      val s = Files.walk(dir)
      try s.sorted(java.util.Comparator.reverseOrder()).forEach(p => { Files.deleteIfExists(p); () })
      finally s.close()
    } catch { case _: Throwable => () }
}

object AgentEngine {

  /** In-memory stores: for tools and tests that do not need the database. */
  def inMemory(settings: AgentSettings, system: => ActorSystem)(implicit ec: ExecutionContext, logger: Logger): AgentEngine =
    new AgentEngine(settings, new InMemoryAgentEventStore, PromptStore.inMemory(AgentProfiles.default), AgentProfiles.default, () => system)

  /** Postgres-backed stores over `db`. */
  def jdbc(settings: AgentSettings, db: AgentDb, system: => ActorSystem)(implicit ec: ExecutionContext, logger: Logger): AgentEngine = {
    val profiles = AgentProfiles.default
    new AgentEngine(
      settings,
      new JdbcAgentEventStore(db),
      new JdbcPromptStore(db, PromptBlocks.registry(profiles)),
      profiles,
      () => system
    )
  }
}
