package escalator.agent.providers

import java.nio.file.Path
import java.time.Duration

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.pekko.stream.{ActorAttributes, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.Source

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.ObjectMappers
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.{
  CacheControlEphemeral,
  MessageCreateParams,
  MessageParam,
  OutputConfig,
  RawMessageStreamEvent,
  TextBlockParam,
  ThinkingConfigAdaptive,
  Tool
}

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.util.logging.Logger

import escalator.agent._
import escalator.agent.protocol._

/**
 * ==The Messages API, no wrapper==
 *
 * The CLI provider spawns a process that owns the loop; this one IS the loop.
 * We send the conversation, stream the answer, run the tools the model asks
 * for, send the results back, and repeat until it stops asking — which is what
 * buys the two things the wrapper cost us: first tokens in ~a second instead of
 * ~five, and prompt caching across turns (the profile prompt is a cache
 * breakpoint, the volatile session context sits after it).
 *
 * Everything provider-specific is JSON in and JSON out ([[AnthropicWire]]), so
 * the SDK appears here only where it must: building the request, opening the
 * stream, and closing it when the Stop button is pressed.
 *
 * History is OURS ([[ResumeKind.StoredMessages]]): `agent_messages` rows are
 * loaded once when the session opens and appended to as the thread runs, so a
 * conversation survives a restart — and a thread a CLI provider started can be
 * picked up here, because both write the same provider-neutral blocks.
 */
class AnthropicApiProvider(
    cfg: AnthropicApiConfig
)(implicit ec: ExecutionContext, logger: Logger)
    extends AgentProvider {

  override def id: String = AnthropicApiProvider.Id

  override def capabilities: ProviderCapabilities = ProviderCapabilities(
    // No workspace and no shell: the tools are OURS to execute, which is also
    // why a codegen profile can never be routed here.
    caps       = Set(Capability.InProcessTools, Capability.Reasoning),
    resumeKind = ResumeKind.StoredMessages
  )

  override val models: Seq[ModelInfo] =
    cfg.models.map(m => ModelInfo(m, AnthropicApiProvider.label(m), isDefault = m == cfg.model))

  override def credentialAvailable: Boolean = cfg.credential.isDefined

  override def credentialLabel: String = cfg.credential.map(_.source).getOrElse(CredentialSource.NoCredential)

  override def open(spec: SessionSpec): Future[ProviderSession] = {
    spec.credential.orElse(cfg.credential) match {
      case None =>
        Future.failed(
          new IllegalStateException(
            "no Anthropic credential: set ANTHROPIC_API_KEY, or run cliproxy's client-setup.sh so " +
              "$CLAUDE_CONFIG_DIR/settings.json (default ~/.claude-cliproxy) holds ANTHROPIC_BASE_URL + ANTHROPIC_AUTH_TOKEN"
          )
        )
      case Some(k) =>
        // History is read BEFORE the turn's own user message is written, so the
        // question is never counted twice.
        spec.history().recover { case NonFatal(t) =>
          logger.warning(s"[anthropic-api] history load failed: ${t.getMessage}; starting from empty")
          Seq.empty[StoredMessage]
        }.map { stored =>
          new AnthropicApiSession(spec, cfg, k, AnthropicHistory.toMessages(stored))
        }
    }
  }
}

object AnthropicApiProvider {

  val Id: String = "anthropic-api"

  def label(model: String): String = model match {
    case "claude-opus-5-5"           => "Opus 5.5"
    case "claude-sonnet-5-5"         => "Sonnet 5.5"
    case "claude-fable-5-1"          => "Fable 5.1"
    case "claude-haiku-4-5"          => "Haiku 4.5"
    case "claude-haiku-4-5-20251001" => "Haiku 4.5"
    case other                       => other
  }

  /** Models that take neither adaptive thinking nor `output_config.effort` (they 400 on both). */
  def supportsAdaptiveThinking(model: String): Boolean = !model.startsWith("claude-haiku-") && !model.startsWith("claude-3")

  /** Fable 5.x runs adaptive thinking always: an explicit setting other than adaptive is a 400. */
  def isFable(model: String): Boolean = model.startsWith("claude-fable-")
}

/** Everything the provider reads from config (see `AgentSettings`). `credential.toString` never prints a secret. */
final case class AnthropicApiConfig(
    model:          String,
    models:         List[String],
    /** low | medium | high | xhigh | max — or "off" to omit `output_config.effort`. */
    effort:         String,
    maxTokens:      Long,
    maxIterations:  Int,
    toolTimeout:    FiniteDuration,
    requestTimeout: FiniteDuration,
    /** "off" | "default" — the server-side refusal fallback (beta header). */
    fallbacks:      String,
    credential:     Option[ProviderCredential],
    prices:         Map[String, ModelPrice] = ModelPrice.defaults
) {
  def fallbacksEnabled: Boolean = fallbacks.trim.equalsIgnoreCase("default")

  def effortEnabled: Boolean = {
    val e = effort.trim.toLowerCase
    e.nonEmpty && e != "off" && e != "none"
  }
}

object AnthropicApiConfig {
  val DefaultModel: String    = "claude-opus-5-5"
  val DefaultMaxTokens: Long  = 64000L

  /** Opt-in beta for `fallbacks`. Sent only when the config flag is on. */
  val FallbackBeta: String = "server-side-fallback-2026-07-01"

  /** A tool result bigger than this is truncated before it enters the history —
    * a big query answer is not a transcript, and every byte is re-sent on
    * every later request of the thread. */
  val MaxToolResultChars: Int = 64 * 1024
}

/**
 * One conversation with the Messages API. Holds the message list (that IS the
 * session), the client, and the stream of the turn that is running so Stop can
 * close it.
 *
 * Not thread-safe for concurrent turns by design — the actor above serialises
 * them, exactly as it does for the CLI.
 */
final class AnthropicApiSession(
    spec:     SessionSpec,
    cfg:      AnthropicApiConfig,
    credential: ProviderCredential,
    initial:  List[Json]
)(implicit ec: ExecutionContext, logger: Logger)
    extends ProviderSession {

  import AnthropicApiSession._

  override def providerId: String = AnthropicApiProvider.Id

  private val model: String =
    Option(spec.model).map(_.trim).filter(_.nonEmpty).getOrElse(cfg.model)

  private val client: AnthropicClient = AnthropicSdk.client(credential, cfg.requestTimeout)

  /** The thread. Loaded once, appended to as it runs. */
  private val messages = mutable.ListBuffer.empty[Json] ++= initial

  private val executor: AgentToolExecutor = spec.tools.executor.getOrElse(AgentToolExecutor.empty)

  @volatile private var context: Option[SessionContext] = spec.context
  // The system prompt is recomposed per turn from the editable prompt blocks,
  // so the profile this session SENDS is a holder, not the captured spec.
  @volatile private var profile: AgentProfile = spec.profile
  @volatile private var live: Option[StreamResponse[RawMessageStreamEvent]] = None
  @volatile private var killed: Boolean = false

  override def resumeCursor: ResumeCursor =
    ResumeCursor(ResumeKind.StoredMessages, None, messages.size)

  override def updateContext(ctx: SessionContext): Unit = context = Some(ctx)

  override def updateProfile(p: AgentProfile): Unit = profile = p

  override def interrupt(): Unit = {
    killed = true
    closeLive()
  }

  override def close(): Unit = {
    interrupt()
    try client.close()
    catch { case NonFatal(_) => () }
  }

  private def closeLive(): Unit = {
    live.foreach(s => try s.close() catch { case NonFatal(_) => () })
    live = None
  }

  override def sendTurnRaw(
      turnId:      TurnId,
      message:     String,
      attachments: Seq[Path]
  ): Source[ProviderEvent, UniqueKillSwitch] = {
    killed = false
    val src = Source
      .fromIterator(() => new TurnIterator(turnId, message))
      // The SDK's stream is blocking; it belongs on the blocking pool, not on
      // the dispatcher that runs actors.
      .addAttributes(ActorAttributes.dispatcher("pekko.actor.default-blocking-io-dispatcher"))
    ProviderSession.withKillSwitch(src, () => interrupt())
  }

  // ── the request ───────────────────────────────────────────────────────────

  /**
   * Profile prompt first WITH the cache breakpoint, session context after it.
   * That order is the whole caching story: the profile half is identical for
   * every user and every turn, so it is read from cache; the context half
   * changes per user and per turn and must not poison it.
   */
  private def systemBlocks(): java.util.List[TextBlockParam] = {
    val profileText = profile.systemPrompt
    val stable =
      TextBlockParam
        .builder()
        .text(if (profileText.trim.isEmpty) " " else profileText)
        .cacheControl(CacheControlEphemeral.builder().build())
        .build()
    val volatileBlock = context
      .map(SessionContextRenderer.systemBlock(_))
      .filter(_.trim.nonEmpty)
      .map(t => TextBlockParam.builder().text(t).build())
    (stable :: volatileBlock.toList).asJava
  }

  private def effort: OutputConfig.Effort = cfg.effort.trim.toLowerCase match {
    case "low"   => OutputConfig.Effort.LOW
    case "high"  => OutputConfig.Effort.HIGH
    case "xhigh" => OutputConfig.Effort.XHIGH
    case "max"   => OutputConfig.Effort.MAX
    case _       => OutputConfig.Effort.MEDIUM
  }

  private def buildParams(): MessageCreateParams = {
    val b = MessageCreateParams
      .builder()
      .model(model)
      .maxTokens(cfg.maxTokens)
      .systemOfTextBlockParams(systemBlocks())
      .messages(messages.iterator.map(AnthropicSdk.messageParam).toList.asJava)

    // Adaptive + summarized: the model decides when to think, and we get a
    // readable summary to stream into the collapsed thinking block. Haiku 4.5
    // (and older) take neither adaptive thinking nor effort.
    if (AnthropicApiProvider.supportsAdaptiveThinking(model)) {
      b.thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
      if (cfg.effortEnabled) b.outputConfig(OutputConfig.builder().effort(effort).build())
    }

    executor.tools.foreach(t => b.addTool(AnthropicSdk.tool(t)))

    // Server-side refusal fallback. Not typed in the SDK at 2.34.0, so it goes
    // on as a body property plus its beta header — and only when the flag says
    // so, because a beta an organisation is not on is a 400 on every turn.
    if (cfg.fallbacksEnabled) {
      b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
      b.putAdditionalHeader("anthropic-beta", AnthropicApiConfig.FallbackBeta)
    }
    b.build()
  }

  // ── one turn ──────────────────────────────────────────────────────────────

  /**
   * The agentic loop as an iterator, so back-pressure from the browser reaches
   * the model's stream instead of buffering a whole answer in memory.
   *
   * Phases: open a request → pump its stream → decide what the stop reason
   * means (run tools and go again, continue a truncated answer once, resend a
   * paused turn, or finish) → terminate with exactly one `TurnCompleted`.
   */
  private final class TurnIterator(turnId: TurnId, userMessage: String) extends Iterator[ProviderEvent] {

    private val pending = mutable.Queue.empty[ProviderEvent]
    private val t0      = System.currentTimeMillis()

    private var phase: Int          = 0 // 0 = open, 1 = stream, 2 = decide, 3 = done
    private var iteration: Int      = 0
    private var continued: Boolean  = false
    private var turnUsage: Usage    = Usage.empty
    private var mapper: AnthropicTurnMapper = _
    private var events: java.util.Iterator[RawMessageStreamEvent] = _

    // The question goes on the thread before the first request, so a tool loop
    // and a restart both see the same history.
    messages += AnthropicHistory.userText(userMessage)

    private def emit(e: AgentEvent, raw: Option[Json] = None): Unit = pending.enqueue(ProviderEvent(e, raw))

    private def finish(outcome: TurnOutcome): Unit = {
      emit(AgentEvent.TurnCompleted(turnId, outcome, priced(turnUsage), System.currentTimeMillis() - t0))
      phase = 3
    }

    private def priced(u: Usage): Usage =
      u.copy(costUsd = cfg.prices.get(model).map(_.cost(u)))

    override def hasNext: Boolean = {
      while (pending.isEmpty && phase < 3) step()
      pending.nonEmpty
    }

    override def next(): ProviderEvent = {
      if (!hasNext) throw new NoSuchElementException("no more agent events")
      pending.dequeue()
    }

    private def step(): Unit = phase match {
      case 0 => openRequest()
      case 1 => pump()
      case 2 => decide()
      case _ => ()
    }

    private def openRequest(): Unit =
      if (killed) { closeLive(); finish(TurnOutcome.Interrupted) }
      else if (iteration >= cfg.maxIterations) {
        logger.warning(s"[anthropic-api] turn ${turnId.value} hit the ${cfg.maxIterations}-request cap")
        emit(
          AgentEvent.RuntimeError(
            Some(turnId),
            kind      = "loop_limit",
            message   = s"the agent made ${cfg.maxIterations} tool rounds without finishing; stopping here",
            retryable = false
          )
        )
        finish(TurnOutcome.Failed)
      } else
        try {
          iteration += 1
          mapper = new AnthropicTurnMapper(turnId, s"${turnId.value}-$iteration")
          val stream = client.messages().createStreaming(buildParams())
          live   = Some(stream)
          events = stream.stream().iterator()
          phase  = 1
        } catch {
          case NonFatal(t) => fail(t)
        }

    private def pump(): Unit =
      if (killed) { closeLive(); appendAssistant(); finish(TurnOutcome.Interrupted) }
      else
        try
          if (events.hasNext) {
            val raw  = events.next()
            val json = AnthropicSdk.toJson(raw)
            mapper.onEvent(json).foreach(e => emit(e, Some(json)))
          } else {
            closeLive()
            phase = 2
          }
        catch {
          case NonFatal(t) =>
            closeLive()
            if (killed) { appendAssistant(); finish(TurnOutcome.Interrupted) } else fail(t)
        }

    /** Keep what the model DID produce, even on a cancel: the actor writes the
      * assistant message from the events, and the next turn replays this. */
    private def appendAssistant(): Unit = {
      val content = mapper.assistantContent
      if (content.nonEmpty) messages += AnthropicHistory.message("assistant", content)
    }

    private def decide(): Unit = {
      turnUsage = turnUsage + mapper.usage
      appendAssistant()

      mapper.stopReason match {
        case Some("tool_use") =>
          runTools()
          // The tools said the work is done (e.g. a collection was accepted): no closing call to the model.
          if (executor.finished && phase < 3) finish(TurnOutcome.Completed) else phase = 0

        case Some("max_tokens") if !continued =>
          // One nudge, then stop: an answer that keeps hitting the ceiling is a
          // prompt problem, not something to spend another 64k tokens on.
          continued = true
          messages += AnthropicHistory.userText(
            "Your previous message was cut off at the output limit. Continue from exactly where you stopped."
          )
          phase = 0

        case Some("max_tokens") =>
          emit(
            AgentEvent.RuntimeError(
              Some(turnId),
              kind      = "max_tokens",
              message   = "the answer was too long to finish, even after continuing once",
              retryable = false
            )
          )
          finish(TurnOutcome.Failed)

        case Some("refusal") =>
          val detail = mapper.stopDetails
            .flatMap(_.hcursor.get[String]("explanation").toOption)
            .getOrElse("the model declined to answer this request")
          emit(AgentEvent.RuntimeError(Some(turnId), kind = "refusal", message = detail, retryable = false))
          finish(TurnOutcome.Failed)

        case Some("pause_turn") =>
          // A server-side tool loop paused; re-send as-is and the server picks
          // up where it left off. Deliberately no extra user message.
          phase = 0

        case _ =>
          finish(TurnOutcome.Completed)
      }
    }

    /**
     * Every tool the model asked for, run CONCURRENTLY, with all results
     * returned in ONE user message — that is what the API expects, and it is
     * also the difference between three round trips and one.
     */
    private def runTools(): Unit = {
      val calls = mapper.toolCalls
      if (calls.isEmpty) { finish(TurnOutcome.Completed); return }

      val started = System.currentTimeMillis()
      val running = calls.map { case (id, name, input) =>
        executor
          // The turn and the tool_use id go IN, so an artifact the call
          // produces is filed against this question as it is written rather
          // than matched up on reload.
          .execute(name, input, ToolCallCtx(Some(turnId.value), Some(id)))
          .recover { case NonFatal(t) => Left(Option(t.getMessage).getOrElse(t.toString)) }
          .flatMap { outcome =>
            // …and the rendered chart comes OUT, for the tool_result below.
            outcome
              .fold(_ => Future.successful(Option.empty[String]), j => executor.imagePng(j))
              .recover { case NonFatal(_) => None }
              .map(image => (id, name, outcome, image))
          }
          .recover { case NonFatal(t) => (id, name, Left(Option(t.getMessage).getOrElse(t.toString)): AgentToolExecutor.ToolOutcome, None) }
      }

      // Bounded wait: the executor already times each tool out, so this only
      // catches an executor that never completes at all.
      val budget = cfg.toolTimeout * (calls.size.toLong + 1)
      val done =
        try Await.result(Future.sequence(running), budget)
        catch {
          case NonFatal(t) =>
            calls.map { case (id, name, _) =>
              (id, name, Left(s"tool timed out after ${budget.toSeconds}s: ${Option(t.getMessage).getOrElse("")}"): AgentToolExecutor.ToolOutcome, None)
            }
        }

      val resultBlocks = done.map { case (id, name, outcome, image) =>
        val (payload, isError) = outcome match {
          case Right(j) => (truncate(j), false)
          case Left(e)  => (Json.fromString(e), true)
        }
        val block = AnthropicHistory.toolResultBlock(id, payload, isError, image)
        emit(
          AgentEvent.ItemCompleted(
            turnId,
            ItemId(id),
            if (isError) ItemStatus.Failed else ItemStatus.Completed,
            // The tool's OWN payload, not the `[{type:"text",…}]` wrapper the
            // Messages API needs: the chat reads `result.artifacts[]` off this,
            // and a chart nested two levels inside a text block is a chart
            // nobody draws.
            Some(payload),
            isError
          )
        )
        logger.info(
          s"[anthropic-api] tool $name ${if (isError) "failed" else "ok"} in ${System.currentTimeMillis() - started}ms"
        )
        block
      }

      messages += AnthropicHistory.message("user", resultBlocks)
    }

    private def fail(t: Throwable): Unit = {
      val msg  = Option(t.getMessage).getOrElse(t.toString)
      val kind = classify(msg)
      logger.warning(s"[anthropic-api] turn ${turnId.value} failed ($kind): $msg")
      emit(AgentEvent.RuntimeError(Some(turnId), kind, msg, retryable = kind == "rate_limited"))
      finish(TurnOutcome.Failed)
    }
  }
}

object AnthropicApiSession {

  /** A tool result is replayed on every later request of the thread, so its
    * size is a recurring cost. Truncate with a note the model can read. */
  private[providers] def truncate(payload: Json): Json = {
    val s = payload.asString.getOrElse(payload.noSpaces)
    if (s.length <= AnthropicApiConfig.MaxToolResultChars) Json.fromString(s)
    else
      Json.fromString(
        s.take(AnthropicApiConfig.MaxToolResultChars) +
          s"\n…[truncated ${s.length - AnthropicApiConfig.MaxToolResultChars} characters; ask for a narrower query]"
      )
  }

  private[providers] def classify(message: String): String = {
    val l = message.toLowerCase
    if (l.contains("429") || l.contains("rate limit") || l.contains("overloaded")) "rate_limited"
    else if (l.contains("401") || l.contains("403") || l.contains("api key") || l.contains("authentication")) "auth"
    else "internal"
  }
}

/**
 * The two-line bridge between the SDK's Jackson types and `io.circe.Json`.
 *
 * Both directions are supported by the SDK itself (its models are Jackson
 * (de)serializable), which is what lets every other piece of this provider be
 * pure JSON — and lets the mapping be tested without a client.
 */
object AnthropicSdk {

  private lazy val mapper = ObjectMappers.jsonMapper()

  /** A client for one credential: a real key (optionally another base URL) or the cliproxy (base URL + bearer token). */
  def client(credential: ProviderCredential, timeout: FiniteDuration): AnthropicClient = {
    val b = AnthropicOkHttpClient.builder().timeout(Duration.ofMillis(timeout.toMillis))
    credential match {
      case ProviderCredential.ApiKey(key, baseUrl) =>
        baseUrl.foreach(u => b.baseUrl(u))
        b.apiKey(key).build()
      case ProviderCredential.Proxy(baseUrl, token, _) =>
        b.baseUrl(baseUrl).authToken(token).build()
    }
  }

  /** Any SDK value → circe. Used for the raw event log and the mapper input. */
  def toJson(value: Any): Json =
    parseJson(mapper.writeValueAsString(value)).getOrElse(Json.Null)

  /** `{"role":…, "content":[…]}` → the SDK's message param. */
  def messageParam(message: Json): MessageParam =
    mapper.readValue(message.noSpaces, classOf[MessageParam])

  /** An MCP tool definition, verbatim: same name, same description, same schema. */
  def tool(t: ToolDef): Tool = {
    val schema =
      if (t.inputSchema.isObject) t.inputSchema
      else Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj())
    val json = Json.obj(
      "name"         -> Json.fromString(t.name),
      "description"  -> Json.fromString(t.description),
      "input_schema" -> schema
    )
    mapper.readValue(json.noSpaces, classOf[Tool])
  }
}
