package escalator.agent.protocol

import io.circe.{Decoder, DecodingFailure, Encoder, Json, JsonObject}
import io.circe.syntax._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

/**
 * ==Provider-neutral agent event model==
 *
 * One vocabulary for everything an agent turn can produce, shared by the JVM
 * (providers, transport, event log) and Scala.js (the conversation reducer and
 * the tool cards). It is deliberately NOT shaped like any one provider's
 * stream: a Claude CLI `stream_event`, a Codex JSONL `item.completed` and a
 * direct Messages-API `content_block_delta` all land on the SAME variants, so
 * the UI never learns which engine answered.
 *
 * The wire form is a JSON object with a `type` discriminator in kebab-case
 * (`"content-delta"`, `"item-started"`, …), which is ALSO the SSE `event:`
 * name. Ids serialize as their primitive (a `TurnId` is a
 * string, an `AgentSeq` is a number) so the payload reads the same from curl as
 * it does from the reducer.
 */
final case class AgentSeq(value: Long) extends AnyVal
object AgentSeq {
  val Zero: AgentSeq = AgentSeq(0L)
  implicit val encoder: Encoder[AgentSeq] = Encoder.encodeLong.contramap(_.value)
  implicit val decoder: Decoder[AgentSeq] = Decoder.decodeLong.map(AgentSeq(_))
}

/** One user turn (question → answer). Stable across a reconnect. */
final case class TurnId(value: String) extends AnyVal
object TurnId {
  implicit val encoder: Encoder[TurnId] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[TurnId] = Decoder.decodeString.map(TurnId(_))
}

/** One item WITHIN a turn: a message, a thought, a tool call, a file change. */
final case class ItemId(value: String) extends AnyVal
object ItemId {
  implicit val encoder: Encoder[ItemId] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[ItemId] = Decoder.decodeString.map(ItemId(_))
}

/** What an item IS — drives which card the UI renders. */
sealed abstract class ItemType(val ident: String)
object ItemType {
  case object AssistantMessage extends ItemType("assistant-message")
  case object Reasoning        extends ItemType("reasoning")
  case object McpToolCall      extends ItemType("mcp-tool-call")
  case object CommandExecution extends ItemType("command-execution")
  case object FileChange       extends ItemType("file-change")
  case object WebSearch        extends ItemType("web-search")
  case object Unknown          extends ItemType("unknown")

  val all: List[ItemType] =
    List(AssistantMessage, Reasoning, McpToolCall, CommandExecution, FileChange, WebSearch, Unknown)

  def fromIdent(s: String): ItemType = all.find(_.ident == s).getOrElse(Unknown)

  implicit val encoder: Encoder[ItemType] = Encoder.encodeString.contramap(_.ident)
  implicit val decoder: Decoder[ItemType] = Decoder.decodeString.map(fromIdent)
}

/** Where an item got to. `Declined` = the user (or policy) refused it. */
sealed abstract class ItemStatus(val ident: String)
object ItemStatus {
  case object InProgress extends ItemStatus("in-progress")
  case object Completed  extends ItemStatus("completed")
  case object Failed     extends ItemStatus("failed")
  case object Declined   extends ItemStatus("declined")

  val all: List[ItemStatus] = List(InProgress, Completed, Failed, Declined)
  def fromIdent(s: String): ItemStatus = all.find(_.ident == s).getOrElse(Completed)

  implicit val encoder: Encoder[ItemStatus] = Encoder.encodeString.contramap(_.ident)
  implicit val decoder: Decoder[ItemStatus] = Decoder.decodeString.map(fromIdent)
}

/** Which text stream a [[AgentEvent.ContentDelta]] belongs to. */
sealed abstract class StreamKind(val ident: String)
object StreamKind {
  case object AssistantText extends StreamKind("assistant-text")
  case object ReasoningText extends StreamKind("reasoning-text")

  val all: List[StreamKind] = List(AssistantText, ReasoningText)
  def fromIdent(s: String): StreamKind = all.find(_.ident == s).getOrElse(AssistantText)

  implicit val encoder: Encoder[StreamKind] = Encoder.encodeString.contramap(_.ident)
  implicit val decoder: Decoder[StreamKind] = Decoder.decodeString.map(fromIdent)
}

/** How a turn ended. `Interrupted` = cancelled (by the user or a kill switch). */
sealed abstract class TurnOutcome(val ident: String)
object TurnOutcome {
  case object Completed   extends TurnOutcome("completed")
  case object Interrupted extends TurnOutcome("interrupted")
  case object Failed      extends TurnOutcome("failed")

  val all: List[TurnOutcome] = List(Completed, Interrupted, Failed)
  def fromIdent(s: String): TurnOutcome = all.find(_.ident == s).getOrElse(Completed)

  implicit val encoder: Encoder[TurnOutcome] = Encoder.encodeString.contramap(_.ident)
  implicit val decoder: Decoder[TurnOutcome] = Decoder.decodeString.map(fromIdent)
}

/**
 * Token + cost accounting for one turn. Cache tokens are carried separately
 * because they are the whole point of a cache breakpoint — a turn that reads
 * 40k cached tokens and writes 300 is not the same turn as one that reads none,
 * and folding them into `inputTokens` hides that.
 */
final case class Usage(
    inputTokens:      Long = 0L,
    outputTokens:     Long = 0L,
    cacheReadTokens:  Long = 0L,
    cacheWriteTokens: Long = 0L,
    costUsd:          Option[BigDecimal] = None
) {
  def totalTokens: Long = inputTokens + outputTokens
  def +(o: Usage): Usage = Usage(
    inputTokens + o.inputTokens,
    outputTokens + o.outputTokens,
    cacheReadTokens + o.cacheReadTokens,
    cacheWriteTokens + o.cacheWriteTokens,
    (costUsd, o.costUsd) match {
      case (Some(a), Some(b)) => Some(a + b)
      case (a, b)             => a.orElse(b)
    }
  )
}
object Usage {
  val empty: Usage = Usage()
  implicit val encoder: Encoder.AsObject[Usage] = deriveEncoder[Usage]
  implicit val decoder: Decoder[Usage]          = deriveDecoder[Usage]
}

/**
 * The UI-safe subset of the session context: who the agent is talking to,
 * which engine answers, which tools it may use and which credential it runs on
 * (a label such as `proxy` or `api-key`, never the secret). Sent on the first
 * frame of a turn so a client can label the thread without a second round trip.
 */
final case class SessionContextView(
    name:         String       = "",
    role:         String       = "",
    allowedTools: List[String] = Nil,
    provider:     String       = "",
    model:        String       = "",
    credential:   String       = ""
)
object SessionContextView {
  val empty: SessionContextView = SessionContextView()
  implicit val encoder: Encoder.AsObject[SessionContextView] = deriveEncoder[SessionContextView]
  implicit val decoder: Decoder[SessionContextView]          = deriveDecoder[SessionContextView]
}

// ── the event union ────────────────────────────────────────────────────────

sealed trait AgentEvent { def turnId: Option[TurnId] }

object AgentEvent {

  /** The session is live. First frame of the first turn, and again on resume. */
  final case class SessionStarted(
      provider:          String,
      model:             String,
      providerSessionId: Option[String],
      context:           SessionContextView
  ) extends AgentEvent { val turnId: Option[TurnId] = None }

  /** Accepted but not running — another turn holds the slot. */
  final case class TurnQueued(id: TurnId, position: Int) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  final case class TurnStarted(id: TurnId, userMessage: String) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  /** Incremental text on one item's stream (the answer, or the thinking). */
  final case class ContentDelta(id: TurnId, itemId: ItemId, kind: StreamKind, delta: String) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  /** A tool call / thought / message began. `input` is the tool's arguments. */
  final case class ItemStarted(
      id:       TurnId,
      itemId:   ItemId,
      itemType: ItemType,
      title:    String,
      toolName: Option[String] = None,
      input:    Option[Json]   = None
  ) extends AgentEvent { val turnId: Option[TurnId] = Some(id) }

  /** Progress detail for an in-flight item (a longer command's output line, …). */
  final case class ItemUpdated(id: TurnId, itemId: ItemId, detail: String) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  final case class ItemCompleted(
      id:      TurnId,
      itemId:  ItemId,
      status:  ItemStatus,
      result:  Option[Json] = None,
      isError: Boolean      = false
  ) extends AgentEvent { val turnId: Option[TurnId] = Some(id) }

  /** The agent needs a human decision (approval, disambiguation). */
  final case class RequestOpened(
      id:          TurnId,
      requestId:   String,
      requestType: String,
      detail:      String,
      options:     List[String] = Nil
  ) extends AgentEvent { val turnId: Option[TurnId] = Some(id) }

  final case class RequestResolved(id: TurnId, requestId: String, decision: String) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  /** A canonical workspace file changed; `contents` is its full new body. */
  final case class FileUpdated(id: TurnId, path: String, contents: String) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  final case class TurnCompleted(id: TurnId, outcome: TurnOutcome, usage: Usage, elapsedMs: Long) extends AgentEvent {
    val turnId: Option[TurnId] = Some(id)
  }

  /** `kind` is one of: not_configured | rate_limited | auth | refusal | internal. */
  final case class RuntimeError(
      turnId:    Option[TurnId],
      kind:      String,
      message:   String,
      retryable: Boolean = false
  ) extends AgentEvent

  // ── wire naming (also the SSE `event:` name) ──────────────────────────────

  val SessionStartedType:  String = "session-started"
  val TurnQueuedType:      String = "turn-queued"
  val TurnStartedType:     String = "turn-started"
  val ContentDeltaType:    String = "content-delta"
  val ItemStartedType:     String = "item-started"
  val ItemUpdatedType:     String = "item-updated"
  val ItemCompletedType:   String = "item-completed"
  val RequestOpenedType:   String = "request-opened"
  val RequestResolvedType: String = "request-resolved"
  val FileUpdatedType:     String = "file-updated"
  val TurnCompletedType:   String = "turn-completed"
  val RuntimeErrorType:    String = "runtime-error"

  val typeNames: List[String] = List(
    SessionStartedType, TurnQueuedType, TurnStartedType, ContentDeltaType, ItemStartedType,
    ItemUpdatedType, ItemCompletedType, RequestOpenedType, RequestResolvedType, FileUpdatedType,
    TurnCompletedType, RuntimeErrorType
  )

  /** The kebab-case discriminator for an event (SSE `event:` name + log `kind`). */
  def typeName(e: AgentEvent): String = e match {
    case _: SessionStarted  => SessionStartedType
    case _: TurnQueued      => TurnQueuedType
    case _: TurnStarted     => TurnStartedType
    case _: ContentDelta    => ContentDeltaType
    case _: ItemStarted     => ItemStartedType
    case _: ItemUpdated     => ItemUpdatedType
    case _: ItemCompleted   => ItemCompletedType
    case _: RequestOpened   => RequestOpenedType
    case _: RequestResolved => RequestResolvedType
    case _: FileUpdated     => FileUpdatedType
    case _: TurnCompleted   => TurnCompletedType
    case _: RuntimeError    => RuntimeErrorType
  }

  // ── codecs ────────────────────────────────────────────────────────────────
  // Hand-wired rather than generic-derived so the discriminator is a flat
  // `type` field (not a wrapper object) and the names stay stable if a case
  // class is ever renamed.

  private implicit val encSessionStarted:  Encoder.AsObject[SessionStarted]  = deriveEncoder[SessionStarted]
  private implicit val encTurnQueued:      Encoder.AsObject[TurnQueued]      = deriveEncoder[TurnQueued]
  private implicit val encTurnStarted:     Encoder.AsObject[TurnStarted]     = deriveEncoder[TurnStarted]
  private implicit val encContentDelta:    Encoder.AsObject[ContentDelta]    = deriveEncoder[ContentDelta]
  private implicit val encItemStarted:     Encoder.AsObject[ItemStarted]     = deriveEncoder[ItemStarted]
  private implicit val encItemUpdated:     Encoder.AsObject[ItemUpdated]     = deriveEncoder[ItemUpdated]
  private implicit val encItemCompleted:   Encoder.AsObject[ItemCompleted]   = deriveEncoder[ItemCompleted]
  private implicit val encRequestOpened:   Encoder.AsObject[RequestOpened]   = deriveEncoder[RequestOpened]
  private implicit val encRequestResolved: Encoder.AsObject[RequestResolved] = deriveEncoder[RequestResolved]
  private implicit val encFileUpdated:     Encoder.AsObject[FileUpdated]     = deriveEncoder[FileUpdated]
  private implicit val encTurnCompleted:   Encoder.AsObject[TurnCompleted]   = deriveEncoder[TurnCompleted]
  private implicit val encRuntimeError:    Encoder.AsObject[RuntimeError]    = deriveEncoder[RuntimeError]

  private implicit val decSessionStarted:  Decoder[SessionStarted]  = deriveDecoder[SessionStarted]
  private implicit val decTurnQueued:      Decoder[TurnQueued]      = deriveDecoder[TurnQueued]
  private implicit val decTurnStarted:     Decoder[TurnStarted]     = deriveDecoder[TurnStarted]
  private implicit val decContentDelta:    Decoder[ContentDelta]    = deriveDecoder[ContentDelta]
  private implicit val decItemStarted:     Decoder[ItemStarted]     = deriveDecoder[ItemStarted]
  private implicit val decItemUpdated:     Decoder[ItemUpdated]     = deriveDecoder[ItemUpdated]
  private implicit val decItemCompleted:   Decoder[ItemCompleted]   = deriveDecoder[ItemCompleted]
  private implicit val decRequestOpened:   Decoder[RequestOpened]   = deriveDecoder[RequestOpened]
  private implicit val decRequestResolved: Decoder[RequestResolved] = deriveDecoder[RequestResolved]
  private implicit val decFileUpdated:     Decoder[FileUpdated]     = deriveDecoder[FileUpdated]
  private implicit val decTurnCompleted:   Decoder[TurnCompleted]   = deriveDecoder[TurnCompleted]
  private implicit val decRuntimeError:    Decoder[RuntimeError]    = deriveDecoder[RuntimeError]

  private def tagged(t: String, o: JsonObject): Json =
    Json.fromJsonObject(o.add("type", Json.fromString(t)))

  implicit val encoder: Encoder[AgentEvent] = Encoder.instance {
    case e: SessionStarted  => tagged(SessionStartedType,  encSessionStarted.encodeObject(e))
    case e: TurnQueued      => tagged(TurnQueuedType,      encTurnQueued.encodeObject(e))
    case e: TurnStarted     => tagged(TurnStartedType,     encTurnStarted.encodeObject(e))
    case e: ContentDelta    => tagged(ContentDeltaType,    encContentDelta.encodeObject(e))
    case e: ItemStarted     => tagged(ItemStartedType,     encItemStarted.encodeObject(e))
    case e: ItemUpdated     => tagged(ItemUpdatedType,     encItemUpdated.encodeObject(e))
    case e: ItemCompleted   => tagged(ItemCompletedType,   encItemCompleted.encodeObject(e))
    case e: RequestOpened   => tagged(RequestOpenedType,   encRequestOpened.encodeObject(e))
    case e: RequestResolved => tagged(RequestResolvedType, encRequestResolved.encodeObject(e))
    case e: FileUpdated     => tagged(FileUpdatedType,     encFileUpdated.encodeObject(e))
    case e: TurnCompleted   => tagged(TurnCompletedType,   encTurnCompleted.encodeObject(e))
    case e: RuntimeError    => tagged(RuntimeErrorType,    encRuntimeError.encodeObject(e))
  }

  implicit val decoder: Decoder[AgentEvent] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case SessionStartedType  => decSessionStarted.tryDecode(c)
      case TurnQueuedType      => decTurnQueued.tryDecode(c)
      case TurnStartedType     => decTurnStarted.tryDecode(c)
      case ContentDeltaType    => decContentDelta.tryDecode(c)
      case ItemStartedType     => decItemStarted.tryDecode(c)
      case ItemUpdatedType     => decItemUpdated.tryDecode(c)
      case ItemCompletedType   => decItemCompleted.tryDecode(c)
      case RequestOpenedType   => decRequestOpened.tryDecode(c)
      case RequestResolvedType => decRequestResolved.tryDecode(c)
      case FileUpdatedType     => decFileUpdated.tryDecode(c)
      case TurnCompletedType   => decTurnCompleted.tryDecode(c)
      case RuntimeErrorType    => decRuntimeError.tryDecode(c)
      case other               => Left(DecodingFailure(s"unknown agent event type '$other'", c.history))
    }
  }
}

/**
 * A stored/streamed event. `seq` is the per-conversation monotonic sequence the
 * event log assigns (and the SSE `id:`, so `Last-Event-ID` resumes exactly);
 * `raw` keeps the native provider payload for debugging and is dropped from
 * anything sent to a browser.
 */
final case class AgentEventEnvelope(
    conversationId: Long,
    seq:            AgentSeq,
    at:             Long,
    event:          AgentEvent,
    raw:            Option[Json] = None
) {
  def kind: String = AgentEvent.typeName(event)
  /** The browser-facing form: identical, minus the provider payload. */
  def withoutRaw: AgentEventEnvelope = if (raw.isEmpty) this else copy(raw = None)
}
object AgentEventEnvelope {
  implicit val encoder: Encoder.AsObject[AgentEventEnvelope] = Encoder.AsObject.instance { e =>
    JsonObject(
      "conversationId" -> e.conversationId.asJson,
      "seq"            -> e.seq.asJson,
      "at"             -> e.at.asJson,
      "event"          -> e.event.asJson,
      "raw"            -> e.raw.asJson
    )
  }
  implicit val decoder: Decoder[AgentEventEnvelope] = Decoder.instance { c =>
    for {
      cid <- c.get[Long]("conversationId")
      seq <- c.get[AgentSeq]("seq")
      at  <- c.get[Long]("at")
      ev  <- c.get[AgentEvent]("event")
      raw <- c.get[Option[Json]]("raw")
    } yield AgentEventEnvelope(cid, seq, at, ev, raw)
  }
}
