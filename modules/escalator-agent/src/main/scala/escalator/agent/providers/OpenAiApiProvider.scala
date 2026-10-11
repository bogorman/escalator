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

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.ObjectMappers
import com.openai.core.http.StreamResponse
import com.openai.models.Reasoning
import com.openai.models.responses.{FunctionTool, ResponseCreateParams, ResponseInputItem, ResponseStreamEvent}

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import escalator.util.logging.Logger

import escalator.agent._
import escalator.agent.protocol._

/**
 * ==The Responses API, no wrapper==
 *
 * The OpenAI twin of [[AnthropicApiProvider]], and deliberately the same
 * program: we own the loop (send the thread, stream the answer, run the tools
 * the model asks for, send the results back, repeat), history is OURS
 * ([[ResumeKind.StoredMessages]]), and every provider-specific thing is JSON in
 * and JSON out ([[OpenAiWire]]) so the SDK appears only where it must.
 *
 * Two decisions are worth stating because they are not obvious:
 *
 *  - **Stored messages, not `previous_response_id`.** The API would happily
 *    keep the thread for us, and it would be one line. But then a conversation
 *    would live in two places with different truths, `agent_messages` would
 *    stop being replayable, and a thread could never move between engines. One
 *    history, one code path, both direct-API providers.
 *  - **The model id comes from config or the engine is off.** There is no
 *    default in this file on purpose: OpenAI's model names change on a
 *    different clock than our releases, and a hard-coded one is a 404 nobody
 *    notices until a user sees it. Unset ⇒ [[configured]] is false ⇒ the
 *    router never offers it and the picker greys it out.
 */
class OpenAiApiProvider(
    cfg: OpenAiApiConfig
)(implicit ec: ExecutionContext, logger: Logger)
    extends AgentProvider {

  override def id: String = OpenAiApiProvider.Id

  override def capabilities: ProviderCapabilities = ProviderCapabilities(
    // No workspace and no shell: the tools are OURS to execute, which is also
    // why a codegen profile can never be routed here.
    caps       = Set(Capability.InProcessTools, Capability.Reasoning),
    resumeKind = ResumeKind.StoredMessages
  )

  override val models: Seq[ModelInfo] =
    cfg.models.map(m => ModelInfo(m, m, isDefault = m == cfg.model))

  override def defaultModel: String = cfg.model

  override def credentialAvailable: Boolean = cfg.apiKey.exists(_.trim.nonEmpty)

  override def credentialLabel: String =
    if (credentialAvailable) CredentialSource.ApiKey else CredentialSource.NoCredential

  /**
   * No model, no engine. A key with no model would otherwise be offered by the
   * router and then fail at the first request, which is a worse answer than
   * "not available".
   */
  override def configured: Boolean = cfg.model.trim.nonEmpty

  override def open(spec: SessionSpec): Future[ProviderSession] = {
    val key = cfg.apiKey.filter(_.trim.nonEmpty)
    if (cfg.model.trim.isEmpty)
      Future.failed(new IllegalStateException("no OpenAI model configured: set <PREFIX>_OPENAI_MODEL"))
    else
      key match {
        case None =>
          Future.failed(
            new IllegalStateException(
              "no OpenAI API key: set OPENAI_API_KEY"
            )
          )
        case Some(k) =>
          // History is read BEFORE the turn's own user message is written, so
          // the question is never counted twice.
          spec.history().recover { case NonFatal(t) =>
            logger.warning(s"[openai-api] history load failed: ${t.getMessage}; starting from empty")
            Seq.empty[StoredMessage]
          }.map { stored =>
            new OpenAiApiSession(spec, cfg, k, OpenAiHistory.toInput(stored))
          }
      }
  }
}

object OpenAiApiProvider {

  val Id: String = "openai-api"

}

/** Everything the provider reads from config (see `AgentSettings`). NO model default — see the class doc. */
final case class OpenAiApiConfig(
    model:          String,
    models:         List[String],
    /** minimal | low | medium | high — or "off" to omit `reasoning` entirely,
      * which is what a non-reasoning model needs (it 400s otherwise). */
    effort:         String,
    maxTokens:      Long,
    maxIterations:  Int,
    toolTimeout:    FiniteDuration,
    requestTimeout: FiniteDuration,
    apiKey:         Option[String],
    baseUrl:        Option[String] = None,
    prices:         Map[String, ModelPrice] = Map.empty
) {
  override def toString: String =
    s"OpenAiApiConfig($model, $models, $effort, key=${apiKey.map(CredentialSource.mask).getOrElse("none")}, ${baseUrl.getOrElse("default")})"

  def reasoningEnabled: Boolean = {
    val e = effort.trim.toLowerCase
    e.nonEmpty && e != "off" && e != "none"
  }
}

object OpenAiApiConfig {
  val DefaultMaxTokens: Long = 32000L

  /** A tool result is replayed on every later request of the thread, so its
    * size is a recurring cost. Same cap as the Anthropic provider. */
  val MaxToolResultChars: Int = 64 * 1024
}

/**
 * One conversation with the Responses API. Holds the input item list (that IS
 * the session), the client, and the stream of the turn that is running so Stop
 * can close it.
 *
 * Not thread-safe for concurrent turns by design — the actor above serialises
 * them, exactly as it does for the CLI.
 */
final class OpenAiApiSession(
    spec:    SessionSpec,
    cfg:     OpenAiApiConfig,
    apiKey:  String,
    initial: List[Json]
)(implicit ec: ExecutionContext, logger: Logger)
    extends ProviderSession {

  import OpenAiApiSession._

  override def providerId: String = OpenAiApiProvider.Id

  private val model: String =
    Option(spec.model).map(_.trim).filter(_.nonEmpty).getOrElse(cfg.model)

  private val client: OpenAIClient = {
    val b = OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMillis(cfg.requestTimeout.toMillis))
    cfg.baseUrl.foreach(u => b.baseUrl(u))
    b.build()
  }

  /** The thread, as Responses input items. Loaded once, appended to as it runs. */
  private val input = mutable.ListBuffer.empty[Json] ++= initial

  private val executor: AgentToolExecutor = spec.tools.executor.getOrElse(AgentToolExecutor.empty)

  /** Built once: the tool list must be byte-identical across turns for the
    * prompt cache to hold, and it is scope-filtered before it gets here. */
  private lazy val tools: List[FunctionTool] =
    executor.tools.toList.map(t => OpenAiSdk.functionTool(OpenAiHistory.functionTool(t)))

  @volatile private var context: Option[SessionContext] = spec.context
  // The system prompt is recomposed per turn from the editable prompt blocks,
  // so the profile this session SENDS is a holder, not the captured spec.
  @volatile private var profile: AgentProfile = spec.profile
  @volatile private var live: Option[StreamResponse[ResponseStreamEvent]] = None
  @volatile private var killed: Boolean = false

  override def resumeCursor: ResumeCursor =
    ResumeCursor(ResumeKind.StoredMessages, None, input.size)

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
   * Profile prompt first, session context after it — the same order every other
   * provider uses, for the same two reasons: the stable half is what OpenAI's
   * automatic prompt caching can match on, and the volatile half is the last
   * thing the model reads before the question.
   */
  private def instructions: String =
    context.map(SessionContextRenderer.systemBlock(_)).filter(_.trim.nonEmpty) match {
      case Some(block) => profile.systemPrompt + "\n\n" + block
      case None        => profile.systemPrompt
    }

  private def buildParams(): ResponseCreateParams = {
    val b = ResponseCreateParams
      .builder()
      .model(model)
      .maxOutputTokens(cfg.maxTokens)
      .instructions(instructions)
      .inputOfResponse(input.iterator.map(OpenAiSdk.inputItem).toList.asJava)
      // We keep the thread; asking OpenAI to keep it too would mean two records
      // of one conversation and a retention surface we do not need.
      .store(false)
      // A stable key lets the API's automatic caching recognise this thread's
      // prefix across turns — the same win the Anthropic provider gets from an
      // explicit cache breakpoint.
      .promptCacheKey(s"agent-${spec.conversationId}")

    tools.foreach(b.addTool)
    if (cfg.reasoningEnabled) b.reasoning(OpenAiSdk.reasoning(cfg.effort))
    b.build()
  }

  // ── one turn ──────────────────────────────────────────────────────────────

  /**
   * The agentic loop as an iterator, so back-pressure from the browser reaches
   * the model's stream instead of buffering a whole answer in memory.
   *
   * Phases: open a request → pump its stream → decide what it means (run tools
   * and go again, or finish) → terminate with exactly one `TurnCompleted`.
   */
  private final class TurnIterator(turnId: TurnId, userMessage: String) extends Iterator[ProviderEvent] {

    private val pending = mutable.Queue.empty[ProviderEvent]
    private val t0      = System.currentTimeMillis()

    private var phase: Int       = 0 // 0 = open, 1 = stream, 2 = decide, 3 = done
    private var iteration: Int   = 0
    private var turnUsage: Usage = Usage.empty
    private var mapper: OpenAiTurnMapper = _
    private var events: java.util.Iterator[ResponseStreamEvent] = _

    // The question goes on the thread before the first request, so a tool loop
    // and a restart both see the same history.
    input += OpenAiHistory.message("user", userMessage)

    private def emit(e: AgentEvent, raw: Option[Json] = None): Unit = pending.enqueue(ProviderEvent(e, raw))

    private def finish(outcome: TurnOutcome): Unit = {
      emit(AgentEvent.TurnCompleted(turnId, outcome, priced(turnUsage), System.currentTimeMillis() - t0))
      phase = 3
    }

    private def priced(u: Usage): Usage = u.copy(costUsd = cfg.prices.get(model).map(_.cost(u)))

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
        logger.warning(s"[openai-api] turn ${turnId.value} hit the ${cfg.maxIterations}-request cap")
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
          mapper = new OpenAiTurnMapper(turnId)
          val stream = client.responses().createStreaming(buildParams())
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
            val json = OpenAiSdk.toJson(events.next())
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
    private def appendAssistant(): Unit =
      mapper.assistantContent.foreach { block =>
        block.hcursor.get[String]("type").toOption match {
          case Some("text") =>
            block.hcursor.get[String]("text").toOption.filter(_.trim.nonEmpty)
              .foreach(t => input += OpenAiHistory.message("assistant", t))
          case Some("tool_use") =>
            for {
              id   <- block.hcursor.get[String]("id").toOption
              name <- block.hcursor.get[String]("name").toOption
            } input += OpenAiHistory.functionCall(id, name, block.hcursor.downField("input").focus.getOrElse(Json.obj()))
          case _ => ()
        }
      }

    private def decide(): Unit = {
      turnUsage = turnUsage + mapper.usage
      appendAssistant()

      mapper.error match {
        case Some((kind, msg)) =>
          emit(AgentEvent.RuntimeError(Some(turnId), kind, msg, retryable = kind == "rate_limited"))
          finish(TurnOutcome.Failed)

        case None if mapper.toolCalls.nonEmpty =>
          runTools()
          // The tools said the work is done (e.g. a collection was accepted): no closing call to the model.
          if (executor.finished && phase < 3) finish(TurnOutcome.Completed) else phase = 0

        case None =>
          finish(TurnOutcome.Completed)
      }
    }

    /**
     * Every tool the model asked for, run CONCURRENTLY, with each result
     * appended as its own `function_call_output` — the Responses API's shape,
     * one item per call, in the order the calls were made.
     */
    private def runTools(): Unit = {
      val calls   = mapper.toolCalls
      val started = System.currentTimeMillis()
      val running = calls.map { case (id, name, args) =>
        executor
          // The turn and the call id travel with the request so an artifact is
          // filed under this question; the RESULT stays text — a
          // `function_call_output` has no other shape, so a chart goes back as
          // its HTTPS link rather than as bytes.
          .execute(name, args, ToolCallCtx(Some(turnId.value), Some(id)))
          .recover { case NonFatal(t) => Left(Option(t.getMessage).getOrElse(t.toString)) }
          .map(outcome => (id, name, outcome))
      }

      // Bounded wait: the executor already times each tool out, so this only
      // catches an executor that never completes at all.
      val budget = cfg.toolTimeout * (calls.size.toLong + 1)
      val done =
        try Await.result(Future.sequence(running), budget)
        catch {
          case NonFatal(t) =>
            calls.map { case (id, name, _) =>
              (id, name, Left(s"tool timed out after ${budget.toSeconds}s: ${Option(t.getMessage).getOrElse("")}"): AgentToolExecutor.ToolOutcome)
            }
        }

      done.foreach { case (id, name, outcome) =>
        val (text, isError) = outcome match {
          case Right(j) => (truncate(j), false)
          case Left(e)  => (e, true)
        }
        input += OpenAiHistory.functionCallOutput(id, text)
        // The wire stays text (`function_call_output` has no other shape), but
        // the EVENT carries the tool's own JSON so the chat can lift
        // `result.artifacts[]` out of it exactly as it does on every other
        // engine. Prose results (and errors) stay strings.
        val resultJson =
          if (isError) Json.fromString(text)
          else io.circe.parser.parse(text).toOption.filter(j => j.isObject || j.isArray).getOrElse(Json.fromString(text))
        emit(
          AgentEvent.ItemCompleted(
            turnId,
            ItemId(id),
            if (isError) ItemStatus.Failed else ItemStatus.Completed,
            Some(resultJson),
            isError
          )
        )
        logger.info(
          s"[openai-api] tool $name ${if (isError) "failed" else "ok"} in ${System.currentTimeMillis() - started}ms"
        )
      }
    }

    private def fail(t: Throwable): Unit = {
      val msg  = Option(t.getMessage).getOrElse(t.toString)
      val kind = OpenAiTurnMapper.classify(msg)
      logger.warning(s"[openai-api] turn ${turnId.value} failed ($kind): $msg")
      emit(AgentEvent.RuntimeError(Some(turnId), kind, msg, retryable = kind == "rate_limited"))
      finish(TurnOutcome.Failed)
    }
  }
}

object OpenAiApiSession {

  /** A tool result is replayed on every later request; truncate with a note the
    * model can act on. `output` is a STRING to the Responses API either way. */
  private[providers] def truncate(payload: Json): String = {
    val s = payload.asString.getOrElse(payload.noSpaces)
    if (s.length <= OpenAiApiConfig.MaxToolResultChars) s
    else
      s.take(OpenAiApiConfig.MaxToolResultChars) +
        s"\n…[truncated ${s.length - OpenAiApiConfig.MaxToolResultChars} characters; ask for a narrower query]"
  }
}

/**
 * The two-line bridge between the OpenAI SDK's Jackson types and
 * `io.circe.Json`, exactly as [[AnthropicSdk]] is for Anthropic — which is what
 * lets every other piece of this provider be pure JSON, and lets the mapping be
 * tested without a client.
 */
object OpenAiSdk {

  private lazy val mapper = ObjectMappers.jsonMapper()

  /** Any SDK value → circe. Used for the raw event log and the mapper input. */
  def toJson(value: Any): Json =
    parseJson(mapper.writeValueAsString(value)).getOrElse(Json.Null)

  /** One element of the request's `input` array. */
  def inputItem(item: Json): ResponseInputItem =
    mapper.readValue(item.noSpaces, classOf[ResponseInputItem])

  /** A function tool, from the JSON [[OpenAiHistory.functionTool]] built. */
  def functionTool(tool: Json): FunctionTool =
    mapper.readValue(tool.noSpaces, classOf[FunctionTool])

  /** `{"effort": …, "summary": "auto"}` — a summary we can stream as thinking. */
  def reasoning(effort: String): Reasoning =
    mapper.readValue(
      Json
        .obj(
          "effort"  -> Json.fromString(effort.trim.toLowerCase),
          "summary" -> Json.fromString("auto")
        )
        .noSpaces,
      classOf[Reasoning]
    )
}
