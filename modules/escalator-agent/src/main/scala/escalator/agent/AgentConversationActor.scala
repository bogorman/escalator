package escalator.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{Actor, ActorRef, Props, ReceiveTimeout}
import org.apache.pekko.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}

import io.circe.Json
import io.circe.syntax._

import escalator.util.logging.Logger

import escalator.agent.protocol._

/**
 * ==One conversation, one actor==
 *
 * Keyed by conversation id rather than by (user, profile), which is the whole
 * point: two dashboards can hold two live threads, and opening an old thread no
 * longer kills the current one.
 *
 * It owns four things: the provider session, the queue of turns waiting for it,
 * the kill switch of the turn that is running, and a live fan-out hub the SSE
 * endpoint attaches to. Everything it emits also lands in the event store with
 * a monotonic `seq`, so a browser that missed the middle of a turn replays it
 * instead of losing it.
 *
 * Back-pressure is expressed as QUEUEING, never as an error: a second message
 * during a turn is accepted and answered with `TurnQueued(position)`, and a
 * user (or a host) at their concurrency cap waits their turn instead of seeing
 * a 409 or a 503. The only thing that fails a send is a conversation that does
 * not exist.
 */
object AgentConversationActor {

  def props(
      row:         ConversationRow,
      config:      AgentConfig,
      store:       AgentEventStore,
      provider:    AgentProvider,
      manager:     ActorRef,
      idleTimeout: FiniteDuration
  )(implicit logger: Logger): Props =
    Props(new AgentConversationActor(row, config, store, provider, manager, idleTimeout))

  /** How long a built [[SessionContext]] is reused before it is rebuilt. */
  val ContextTtlMs: Long = 60000L

  /** How much of a thread a provider that replays history reads back when it
    * takes it over. Newest-last; older turns stay in the store. */
  val HistoryMessages: Int = 200

  // ── commands ──────────────────────────────────────────────────────────────

  /** Queue a user turn. Replies [[Accepted]] once its position is known. */
  final case class Send(text: String, attachments: Seq[String] = Seq.empty)

  /** Stop the running turn. Always acked — interrupting nothing is not an error. */
  case object Interrupt

  /** Attach to the live event fan-out. Replies [[LiveEvents]]. */
  case object SubscribeLive

  /** The actor's live view of the thread (the store holds the durable one). */
  case object Snapshot

  /** End the conversation: kill the turn, close the engine, stop. */
  case object Close

  // ── replies ───────────────────────────────────────────────────────────────

  final case class Accepted(turnId: String, queued: Boolean, position: Int)
  final case class LiveEvents(source: Source[AgentEventEnvelope, NotUsed])
  final case class SnapshotReply(status: String, runningTurn: Option[String], queued: Int)
  final case class Rejected(kind: String, message: String)
  case object Ack

  // ── internals ─────────────────────────────────────────────────────────────

  private final case class PendingTurn(
      turnId:      String,
      text:        String,
      attachments: Seq[String],
      var replyTo: Option[ActorRef]
  )

  private final case class TurnReady(turn: PendingTurn, session: ProviderSession, context: Option[SessionContext])
  private final case class TurnFailed(turn: PendingTurn, cause: Throwable)
  private final case class TurnFinished(turnId: String, acc: TurnAccumulator, elapsedMs: Long)
  private final case class Publish(envelope: AgentEventEnvelope)

  /**
   * What a turn produced, folded as it streams. This is what becomes the
   * `agent_messages` rows: provider-neutral content blocks that a direct-API
   * provider can replay verbatim as history (plan §2.3), rather than a
   * transcript only a human could read.
   */
  private final class TurnAccumulator {
    val text                                = new StringBuilder
    val toolUses                            = mutable.ListBuffer.empty[Json]
    val toolResults                         = mutable.ListBuffer.empty[Json]
    private val toolNames                   = mutable.Map.empty[String, String]
    var usage: Usage                        = Usage.empty
    var outcome: Option[TurnOutcome]        = None
    var error: Option[String]               = None

    def observe(ev: AgentEvent): Unit = ev match {
      case AgentEvent.ContentDelta(_, _, StreamKind.AssistantText, d) =>
        text.append(d); ()

      case AgentEvent.ItemStarted(_, itemId, itemType, _, toolName, input) if itemType != ItemType.AssistantMessage =>
        toolName.foreach(n => toolNames.put(itemId.value, n))
        if (toolName.isDefined)
          toolUses += Json.obj(
            "type"  -> Json.fromString("tool_use"),
            "id"    -> Json.fromString(itemId.value),
            "name"  -> toolName.get.asJson,
            "input" -> input.getOrElse(Json.obj())
          )
        ()

      case AgentEvent.ItemCompleted(_, itemId, _, result, isError) =>
        if (toolNames.contains(itemId.value))
          toolResults += Json.obj(
            "type"        -> Json.fromString("tool_result"),
            "tool_use_id" -> Json.fromString(itemId.value),
            "content"     -> result.getOrElse(Json.Null),
            "is_error"    -> Json.fromBoolean(isError)
          )
        ()

      case AgentEvent.TurnCompleted(_, o, u, _) =>
        outcome = Some(o); usage = usage + u; ()

      case AgentEvent.RuntimeError(_, _, m, _) =>
        error = Some(m); ()

      case _ => ()
    }

    /** `[text, tool_use…]` — empty when the turn produced neither. */
    def assistantContent: Option[Json] = {
      val blocks =
        (if (text.nonEmpty) List(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text.toString)))
         else Nil) ++ toolUses.toList
      if (blocks.isEmpty) None else Some(Json.arr(blocks: _*))
    }

    def toolContent: Option[Json] =
      if (toolResults.isEmpty) None else Some(Json.arr(toolResults.toList: _*))
  }
}

class AgentConversationActor(
    row:         ConversationRow,
    config:      AgentConfig,
    store:       AgentEventStore,
    provider:    AgentProvider,
    manager:     ActorRef,
    idleTimeout: FiniteDuration
)(implicit logger: Logger)
    extends Actor {

  import AgentConversationActor._
  import AgentConversationManager.{AcquireSlot, ReleaseSlot, SlotGranted, SlotQueued}

  private implicit val ec:  scala.concurrent.ExecutionContext = context.dispatcher
  private implicit val mat: Materializer                      = Materializer(context)

  private val conversationId = row.id
  /** The slot-budget key: the owning user, or 0 for an ownerless thread (a tool, a job). */
  private val userId         = row.userId.getOrElse(0L)

  // Rebuilt per turn — see `refreshProfile`. The system prompt is composed from
  // the editable prompt blocks, so an owner's edit lands on the NEXT turn.
  private var profile: AgentProfile = buildProfile()

  private def buildProfile(): AgentProfile =
    config.profiles
      .build(row.profile, config.prompts)
      .getOrElse(throw new IllegalStateException(s"no agent profile '${row.profile}'"))

  /** The engine's own conversation id — also the workspace folder name. */
  private val providerSessionId: String =
    row.providerSessionId.filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString)

  private val seqAlloc = new SeqAllocator(conversationId, store)

  // Live fan-out. The permanent `Sink.ignore` consumer keeps the hub draining
  // when nobody is watching, so a turn that runs with no browser attached is
  // never held up by the transport.
  private val (liveQueue, liveHub) =
    Source
      .queue[AgentEventEnvelope](256, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[AgentEventEnvelope](bufferSize = 256))(Keep.both)
      .run()
  liveHub.runWith(Sink.ignore)

  private var pending: Vector[PendingTurn]        = Vector.empty
  private var awaiting: Option[PendingTurn]       = None
  private var current: Option[(String, UniqueKillSwitch)] = None
  private var sessionF: Option[Future[ProviderSession]]   = None
  private var openSession: Option[ProviderSession]        = None
  private var announced: Boolean                          = false
  private var contextCache: Option[(SessionContext, Long)] = None
  private var workspace: Option[Path]                      = row.workspaceDir.map(Paths.get(_))
  private var closing: Boolean                             = false
  /** The message a still-untitled thread takes its title from. */
  private var firstUserMessage: Option[String]             = None

  context.setReceiveTimeout(idleTimeout)
  logger.info(
    s"[agent] conversation $conversationId open user=$userId profile=${row.profile} provider=${provider.id}"
  )

  override def receive: Receive = {

    case Send(text, attachments) =>
      val turn = PendingTurn(UUID.randomUUID().toString, text, attachments, Some(sender()))
      if (current.isDefined || awaiting.isDefined) {
        pending = pending :+ turn
        val position = pending.size
        emit(AgentEvent.TurnQueued(TurnId(turn.turnId), position))
        reply(turn, Accepted(turn.turnId, queued = true, position = position))
      } else {
        awaiting = Some(turn)
        manager ! AcquireSlot(userId)
      }

    case SlotQueued(position) =>
      awaiting.foreach { turn =>
        emit(AgentEvent.TurnQueued(TurnId(turn.turnId), position))
        reply(turn, Accepted(turn.turnId, queued = true, position = position))
      }

    case SlotGranted =>
      awaiting match {
        case None => manager ! ReleaseSlot(userId) // nothing to run any more
        case Some(turn) =>
          reply(turn, Accepted(turn.turnId, queued = false, position = 0))
          prepareTurn(turn)
      }

    case TurnReady(turn, session, ctx) =>
      runTurn(turn, session, ctx)

    case TurnFailed(turn, cause) =>
      logger.warning(s"[agent] turn setup failed conv=$conversationId: ${cause.getMessage}")
      emit(
        AgentEvent.RuntimeError(
          Some(TurnId(turn.turnId)),
          kind      = "internal",
          message   = Option(cause.getMessage).getOrElse("agent unavailable"),
          retryable = true
        )
      )
      emit(AgentEvent.TurnCompleted(TurnId(turn.turnId), TurnOutcome.Failed, Usage.empty, 0L))
      awaiting = None
      current = None
      manager ! ReleaseSlot(userId)
      startNext()

    case TurnFinished(turnId, acc, elapsed) =>
      finishTurn(turnId, acc, elapsed)

    case Publish(env) =>
      publish(env)

    case Interrupt =>
      current.foreach { case (_, ks) => try ks.shutdown() catch { case NonFatal(_) => () } }
      openSession.foreach(s => try s.interrupt() catch { case NonFatal(_) => () })
      sender() ! Ack

    case SubscribeLive =>
      // A subscriber that cannot keep up FAILS rather than silently skipping:
      // the browser reconnects with `Last-Event-ID` and replays from the store,
      // which is the only way it can end up with the complete turn.
      sender() ! LiveEvents(liveHub.buffer(2048, OverflowStrategy.fail).mapMaterializedValue(_ => NotUsed))

    case Snapshot =>
      sender() ! SnapshotReply(
        status      = if (current.isDefined) ConversationStatus.Running else ConversationStatus.Idle,
        runningTurn = current.map(_._1),
        queued      = pending.size
      )

    case Close =>
      closing = true
      current.foreach { case (_, ks) => try ks.shutdown() catch { case NonFatal(_) => () } }
      sender() ! Ack
      context.stop(self)

    case ReceiveTimeout =>
      if (current.isEmpty && pending.isEmpty && awaiting.isEmpty) {
        logger.info(s"[agent] conversation $conversationId idle; stopping")
        context.stop(self)
      }
  }

  // ── turn lifecycle ────────────────────────────────────────────────────────

  /** Open (or reuse) the engine session and refresh the context, then run. */
  private def prepareTurn(turn: PendingTurn): Unit = {
    val prepared = for {
      ctx     <- freshContext()
      _        = refreshProfile()
      session <- ensureSession(ctx)
    } yield {
      ctx.foreach(c => try session.updateContext(c) catch { case NonFatal(_) => () })
      ctx.foreach(writeSessionJson)
      TurnReady(turn, session, ctx)
    }
    prepared.onComplete {
      case Success(ready) => self ! ready
      case Failure(t)     => self ! TurnFailed(turn, t)
    }
  }

  private def runTurn(turn: PendingTurn, session: ProviderSession, ctx: Option[SessionContext]): Unit = {
    val turnId  = TurnId(turn.turnId)
    val started = System.currentTimeMillis()
    val acc     = new TurnAccumulator

    if (firstUserMessage.isEmpty) firstUserMessage = Some(turn.text)
    store.setStatus(conversationId, ConversationStatus.Running)
    store
      .appendMessage(conversationId, turn.turnId, "user", AgentEventStore.textContent(turn.text))
      .failed
      .foreach(t => logger.warning(s"[agent] user message write failed conv=$conversationId: ${t.getMessage}"))

    // The context frame comes FIRST, before a single token: a client learns
    // the engine and model before the first token.
    val head: List[ProviderEvent] =
      (if (announced) Nil
       else
         List(
           ProviderEvent(
             AgentEvent.SessionStarted(
               provider          = provider.id,
               model             = row.model,
               providerSessionId = Some(providerSessionId),
               context           = ctx.map(_.view).getOrElse(SessionContextView.empty)
             )
           )
         )) :+ ProviderEvent(AgentEvent.TurnStarted(turnId, turn.text))
    announced = true

    val attachments = workspaceAttachments(turn.attachments)
    val source =
      Source(head).concatMat(session.sendTurnRaw(turnId, turn.text, attachments))(Keep.right)

    val (ks, done) = source
      .mapAsync(1)(pe => seqAlloc.next().map(s => AgentEnvelopes.of(conversationId, s, pe.event, pe.raw)))
      .alsoTo(Sink.foreach { env =>
        acc.observe(env.event)
        publish(env)
      })
      // Persist in windows rather than per event: a chatty turn is hundreds of
      // deltas, and one INSERT per token would cost more than the model call.
      // Deliberately NOT merged into one row — the SSE `id:` is the row's seq,
      // so merging would make a mid-window reconnect either duplicate or drop
      // text.
      .groupedWithin(100, 50.millis)
      .mapAsync(1) { batch =>
        store.append(batch).recover {
          case NonFatal(t) =>
            logger.warning(s"[agent] event append failed conv=$conversationId: ${t.getMessage}")
            0
        }
      }
      .toMat(Sink.ignore)(Keep.both)
      .run()

    current = Some((turn.turnId, ks))
    awaiting = None

    done.onComplete { res =>
      res.failed.foreach(t => acc.error = Some(Option(t.getMessage).getOrElse(t.toString)))
      self ! TurnFinished(turn.turnId, acc, System.currentTimeMillis() - started)
    }
  }

  private def finishTurn(turnId: String, acc: TurnAccumulator, elapsedMs: Long): Unit = {
    val id = TurnId(turnId)

    // The provider normally closes its own turn; a killed or crashed stream
    // does not, and a turn with no ending is a spinner that never stops.
    if (acc.outcome.isEmpty) {
      val outcome = if (acc.error.isDefined) TurnOutcome.Failed else TurnOutcome.Interrupted
      acc.error.foreach(m => emit(AgentEvent.RuntimeError(Some(id), "internal", m, retryable = true)))
      emit(AgentEvent.TurnCompleted(id, outcome, acc.usage, elapsedMs))
      acc.outcome = Some(outcome)
    }

    acc.assistantContent.foreach { c =>
      store
        .appendMessage(conversationId, turnId, "assistant", c)
        .failed
        .foreach(t => logger.warning(s"[agent] assistant message write failed: ${t.getMessage}"))
    }
    acc.toolContent.foreach { c =>
      store
        .appendMessage(conversationId, turnId, "tool", c)
        .failed
        .foreach(t => logger.warning(s"[agent] tool message write failed: ${t.getMessage}"))
    }

    val title = titleFrom()
    store
      .completeTurn(conversationId, acc.usage, ConversationStatus.Idle, title)
      .failed
      .foreach(t => logger.warning(s"[agent] turn rollup failed conv=$conversationId: ${t.getMessage}"))

    // The engine may have minted its resume handle on this turn.
    openSession.foreach { s =>
      val cursor = s.resumeCursor
      cursor.sessionId.filter(sid => !row.providerSessionId.contains(sid)).foreach { sid =>
        store
          .setProviderSession(conversationId, Some(sid), workspace.map(_.toString))
          .failed
          .foreach(t => logger.warning(s"[agent] resume cursor write failed: ${t.getMessage}"))
      }
    }

    current = None
    manager ! ReleaseSlot(userId)
    context.setReceiveTimeout(idleTimeout)
    startNext()
  }

  /** Adopt the next queued turn, if the host has room for it. */
  private def startNext(): Unit =
    if (!closing && current.isEmpty && awaiting.isEmpty) {
      pending.headOption.foreach { turn =>
        pending = pending.drop(1)
        awaiting = Some(turn)
        manager ! AcquireSlot(userId)
      }
    }

  /** The thread's title: the first user message, trimmed to something readable.
    * Empty when the thread already has one — the store then leaves it alone. */
  private def titleFrom(): String =
    if (row.title.trim.nonEmpty) ""
    else {
      val line = firstUserMessage.getOrElse("").replaceAll("\\s+", " ").trim
      if (line.length <= 80) line else line.take(77) + "…"
    }

  // ── engine session ────────────────────────────────────────────────────────

  private def ensureSession(ctx: Option[SessionContext]): Future[ProviderSession] =
    sessionF match {
      case Some(f) => f
      case None =>
        val f = openProviderSession(ctx)
        sessionF = Some(f)
        f.onComplete {
          case Success(s) => openSession = Some(s)
          case Failure(_) => sessionF = None // let the next turn retry
        }
        f
    }

  private def openProviderSession(ctx: Option[SessionContext]): Future[ProviderSession] = {
    val ws = workspace.getOrElse(createWorkspace())
    workspace = Some(ws)

    // Which half of the tool story applies. An engine that speaks MCP dials the
    // profile's MCP servers itself (a `.mcp.json` in the workspace); an engine
    // whose tools WE execute gets an in-process executor instead.
    val dialsMcp   = profile.mcpServers.nonEmpty && provider.capabilities.has(Capability.McpNative)
    val runsInProc = profile.toolsEnabled && provider.capabilities.has(Capability.InProcessTools)

    val mcpConfigPath: Option[Path] = if (dialsMcp && writeMcpConfig(ws)) Some(ws.resolve(".mcp.json")) else None
    val executor: Option[AgentToolExecutor] =
      if (runsInProc) config.toolExecutorFor(profile, ctx.getOrElse(SessionContext.anonymous)) else None

    val source = provider.credentialLabel
    if (source != row.credentialSource)
      store
        .setCredentialSource(conversationId, source)
        .failed
        .foreach(t => logger.warning(s"[agent] credential-source write failed: ${t.getMessage}"))

    // A conversation that has run before resumes the SAME engine session. TURN
    // COUNT is the only honest test: the row is created with a provider session
    // id, and a CLI told to `--resume` a session it never opened fails the turn.
    val resume = if (row.turnCount > 0) Some(ResumeCursor.forSession(providerSessionId)) else None

    provider.open(
      SessionSpec(
        conversationId = providerSessionId,
        profile        = profile,
        context        = ctx,
        model          = row.model,
        workspace      = Some(ws),
        resume         = resume,
        tools          = ToolSurface(
          mcpConfigPath = mcpConfigPath,
          allowedTools  = profile.allowedTools,
          executor      = executor
        ),
        // Read before the turn's own user message is written (the session opens
        // in `prepareTurn`, the message is written in `runTurn`), so a direct-API
        // provider never sends the new question twice.
        history = () => store.messages(conversationId, AgentConversationActor.HistoryMessages)
      )
    )
  }

  /**
   * Re-resolve this turn's system prompt from the prompt store. An open engine
   * session is told about the new text — the CLI passes it with each turn, the
   * direct-API providers send it with each request — so no restart is involved.
   */
  private def refreshProfile(): Unit =
    try {
      val next = buildProfile()
      if (next.systemPrompt != profile.systemPrompt) {
        profile = next
        openSession.foreach(s => try s.updateProfile(next) catch { case NonFatal(_) => () })
      }
    } catch {
      case NonFatal(t) =>
        logger.warning(s"[agent] prompt refresh failed conv=$conversationId: ${t.getMessage}")
    }

  /** Rebuilt per turn, but not more often than the TTL — it is a few reads. */
  private def freshContext(): Future[Option[SessionContext]] = {
    val now = System.currentTimeMillis()
    contextCache match {
      case Some((c, at)) if now - at < AgentConversationActor.ContextTtlMs => Future.successful(Some(c))
      case _ =>
        config
          .sessionContextFor(row.userId)
          .map {
            case Some(c) =>
              val stamped = c.copy(
                provider       = provider.id,
                model          = row.model,
                credential     = provider.credentialLabel,
                tools          = if (c.tools.allowed.isEmpty) c.tools.copy(allowed = profile.allowedTools.toList) else c.tools,
                conversationId = Some(conversationId)
              )
              contextCache = Some((stamped, now))
              Some(stamped)
            case None => contextCache.map(_._1)
          }
          .recover { case NonFatal(t) =>
            logger.warning(s"[agent] session context build failed conv=$conversationId: ${t.getMessage}")
            contextCache.map(_._1)
          }
    }
  }

  // ── event emission ────────────────────────────────────────────────────────

  /** Number, fan out and persist ONE event produced outside a turn stream. */
  private def emit(ev: AgentEvent): Unit =
    seqAlloc
      .next()
      .map { s =>
        val env = AgentEnvelopes.of(conversationId, s, ev)
        publish(env)
        store.append(Seq(env))
      }
      .failed
      .foreach(t => logger.warning(s"[agent] emit failed conv=$conversationId: ${t.getMessage}"))

  private def publish(env: AgentEventEnvelope): Unit = {
    liveQueue.offer(env.withoutRaw)
    ()
  }

  private def reply(turn: PendingTurn, msg: Any): Unit = {
    turn.replyTo.foreach(_ ! msg)
    turn.replyTo = None
  }

  // ── workspace ─────────────────────────────────────────────────────────────

  /** `<root>/<profile>/<providerSessionId>`, owner-only permissions. */
  private def createWorkspace(): Path = {
    val dir = config.workspaceRoot.resolve(row.profile).resolve(providerSessionId)
    Files.createDirectories(dir)
    lockDownOwnerOnly(dir.getParent, directory = true)
    lockDownOwnerOnly(dir, directory = true)
    profile.skillFiles.foreach { case (rel, contents) =>
      val p = dir.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.write(p, contents.getBytes(StandardCharsets.UTF_8))
    }
    store
      .setProviderSession(conversationId, Some(providerSessionId), Some(dir.toString))
      .failed
      .foreach(t => logger.warning(s"[agent] workspace write-back failed: ${t.getMessage}"))
    dir
  }

  /**
   * Attachment names come from a browser, so they are treated as names and not
   * as paths: only a file that already exists INSIDE this conversation's
   * workspace can be attached. Anything else is dropped rather than handed to a
   * provider that would happily read it.
   */
  private def workspaceAttachments(names: Seq[String]): Seq[Path] =
    workspace.toSeq.flatMap { ws =>
      val root = ws.toAbsolutePath.normalize()
      names.flatMap { n =>
        val candidate = root.resolve(n).toAbsolutePath.normalize()
        if (candidate.startsWith(root) && Files.isRegularFile(candidate)) Some(candidate)
        else {
          logger.warning(s"[agent] ignoring attachment outside the workspace conv=$conversationId name=$n")
          None
        }
      }
    }

  private def writeSessionJson(ctx: SessionContext): Unit =
    workspace.foreach { ws =>
      try
        Files.write(
          ws.resolve("session.json"),
          SessionContextRenderer.sessionJson(ctx).noSpaces.getBytes(StandardCharsets.UTF_8)
        )
      catch { case NonFatal(_) => () }
    }

  /**
   * The profile's MCP servers as a workspace `.mcp.json` (owner-only file).
   * Bearer tokens are resolved now and written only here — never into argv.
   */
  private def writeMcpConfig(ws: Path): Boolean = {
    val servers = profile.mcpServers.map { srv =>
      val base = Json.obj("type" -> Json.fromString("http"), "url" -> Json.fromString(srv.url))
      val withAuth = srv.bearer() match {
        case Some(token) => base.deepMerge(Json.obj("headers" -> Json.obj("Authorization" -> Json.fromString(s"Bearer $token"))))
        case None        => base
      }
      srv.name -> withAuth
    }
    try {
      val p = ws.resolve(".mcp.json")
      Files.write(p, Json.obj("mcpServers" -> Json.obj(servers: _*)).noSpaces.getBytes(StandardCharsets.UTF_8))
      lockDownOwnerOnly(p, directory = false)
      true
    } catch {
      case NonFatal(t) =>
        logger.warning(s"[agent] .mcp.json write failed: ${t.getMessage}; running without MCP")
        false
    }
  }

  private def lockDownOwnerOnly(p: Path, directory: Boolean): Unit =
    try {
      val perms = if (directory) "rwx------" else "rw-------"
      Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(perms))
    } catch { case NonFatal(_) => () }

  // ── lifecycle ─────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    // Titles are derived from the FIRST user message; remember it if the thread
    // already has turns but no title (an interrupted first turn).
    if (row.title.trim.isEmpty && row.turnCount > 0) {
      store.messages(conversationId, 1).foreach { msgs =>
        firstUserMessage = msgs.headOption.map(m => AgentEventStore.contentText(m.content))
      }
    }
  }

  override def postStop(): Unit = {
    if (current.isDefined) manager ! ReleaseSlot(userId)
    try liveQueue.complete() catch { case NonFatal(_) => () }
    openSession.foreach(s => try s.close() catch { case NonFatal(_) => () })
    store.setStatus(conversationId, ConversationStatus.Idle)
    logger.info(s"[agent] conversation $conversationId stopped")
  }
}
