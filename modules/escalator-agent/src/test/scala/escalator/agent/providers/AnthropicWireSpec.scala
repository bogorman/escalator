package escalator.agent.providers

import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.{StoredMessage, ToolDef}
import escalator.agent.protocol._

/**
 * The two pieces of the direct-API provider that can actually be wrong in a way
 * a compiler cannot see: what a streamed turn becomes, and what a stored thread
 * turns back into.
 *
 * Both are exercised as JSON, because that is how they are written — the SDK's
 * own Jackson mapper is used at the seams, so a shape this suite accepts is a
 * shape the SDK accepts too (the round-trip tests below go through it on
 * purpose, which is what makes them worth more than a comparison of two of our
 * own case classes).
 */
class AnthropicWireSpec extends AnyFunSuite with Matchers {

  private val turn = TurnId("turn-1")

  private def ev(s: String): Json = parseJson(s).fold(throw _, identity)

  // ── the stream ────────────────────────────────────────────────────────────

  test("a text answer streams as one item with its deltas, and is kept as a text block") {
    val m = new AnthropicTurnMapper(turn, "req-1")

    m.onEvent(ev("""{"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":4000}}}"""))
    val started = m.onEvent(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
    val d1      = m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}"""))
    val d2      = m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}"""))
    val stopped = m.onEvent(ev("""{"type":"content_block_stop","index":0}"""))
    m.onEvent(ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}"""))

    started.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.AssistantMessage)
    (d1 ++ d2).collect { case c: AgentEvent.ContentDelta => (c.kind, c.delta) } shouldBe
      List((StreamKind.AssistantText, "Hel"), (StreamKind.AssistantText, "lo"))
    stopped.collect { case c: AgentEvent.ItemCompleted => c.status } shouldBe List(ItemStatus.Completed)

    m.assistantText shouldBe "Hello"
    m.assistantContent shouldBe List(AnthropicHistory.textBlock("Hello"))
    m.stopReason shouldBe Some("end_turn")
    m.usage.inputTokens shouldBe 120
    m.usage.cacheReadTokens shouldBe 4000
    m.usage.outputTokens shouldBe 9
  }

  test("a tool call is announced only once its arguments have finished arriving") {
    val m = new AnthropicTurnMapper(turn, "req-1")

    m.onEvent(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"list_domains","input":{}}}""")) shouldBe Nil
    m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"tld\":"}}""")) shouldBe Nil
    m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"IO\"}"}}""")) shouldBe Nil

    val emitted = m.onEvent(ev("""{"type":"content_block_stop","index":0}"""))
    val started = emitted.collect { case s: AgentEvent.ItemStarted => s }
    started.size shouldBe 1
    started.head.itemId shouldBe ItemId("tu_1")
    started.head.toolName shouldBe Some("list_domains")
    started.head.input shouldBe Some(Json.obj("tld" -> Json.fromString("IO")))

    m.toolCalls.map(_._1) shouldBe List("tu_1")
    m.assistantContent.head.hcursor.get[String]("type").toOption shouldBe Some("tool_use")
  }

  test("thinking streams as reasoning and is kept verbatim, signature included") {
    val m = new AnthropicTurnMapper(turn, "req-1")

    val started = m.onEvent(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""))
    val delta   = m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"weighing it up"}}"""))
    m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}"""))
    m.onEvent(ev("""{"type":"content_block_stop","index":0}"""))

    started.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.Reasoning)
    delta.collect { case c: AgentEvent.ContentDelta => c.kind } shouldBe List(StreamKind.ReasoningText)

    val block = m.assistantContent.head.hcursor
    block.get[String]("type").toOption shouldBe Some("thinking")
    block.get[String]("thinking").toOption shouldBe Some("weighing it up")
    block.get[String]("signature").toOption shouldBe Some("sig-abc")
  }

  test("a truncated tool argument stream degrades to an empty input rather than failing the turn") {
    val m = new AnthropicTurnMapper(turn, "req-1")
    m.onEvent(ev("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_2","name":"whoami","input":{}}}"""))
    m.onEvent(ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}"""))
    val emitted = m.onEvent(ev("""{"type":"content_block_stop","index":0}"""))
    emitted.collect { case s: AgentEvent.ItemStarted => s.input } shouldBe List(Some(Json.obj()))
  }

  test("usage accumulates across the requests of one tool-using turn") {
    val m = new AnthropicTurnMapper(turn, "req-1")
    m.onEvent(ev("""{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_creation_input_tokens":50}}}"""))
    m.onEvent(ev("""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}"""))
    m.usage.inputTokens shouldBe 100
    m.usage.cacheWriteTokens shouldBe 50
    m.usage.outputTokens shouldBe 20
    m.stopReason shouldBe Some("tool_use")
  }

  // ── history ───────────────────────────────────────────────────────────────

  private def stored(role: String, content: Json) = StoredMessage("turn-1", role, content, 0L)

  test("stored rows become an alternating user/assistant conversation") {
    val messages = AnthropicHistory.toMessages(
      Seq(
        stored("user", Json.arr(AnthropicHistory.textBlock("hi"))),
        stored("assistant", Json.arr(AnthropicHistory.textBlock("hello"))),
        stored("user", Json.arr(AnthropicHistory.textBlock("and again")))
      )
    )
    messages.map(_.hcursor.get[String]("role").toOption.get) shouldBe List("user", "assistant", "user")
  }

  test("an image rides back with the tool result, so the model sees it") {
    val block = AnthropicHistory.toolResultBlock(
      "tu_1",
      Json.fromString("""{"ok":true,"files":[{"id":12}]}"""),
      isError        = false,
      imagePngBase64 = Some("aGVsbG8=")
    )
    val content = block.hcursor.downField("content").as[List[Json]].getOrElse(Nil)

    content.map(_.hcursor.get[String]("type").getOrElse("")) shouldBe List("text", "image")
    // The text still carries the WHOLE payload: a result is never image-only.
    content.head.hcursor.get[String]("text").getOrElse("") should include("files")
    val source = content(1).hcursor.downField("source")
    source.get[String]("type") shouldBe Right("base64")
    source.get[String]("media_type") shouldBe Right("image/png")
    source.get[String]("data") shouldBe Right("aGVsbG8=")
  }

  test("a tool result with no render is exactly the one text block it always was") {
    val block   = AnthropicHistory.toolResultBlock("tu_1", Json.fromString("""{"ok":true}"""), isError = false)
    val content = block.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    content.map(_.hcursor.get[String]("type").getOrElse("")) shouldBe List("text")
    block.hcursor.get[Boolean]("is_error") shouldBe Right(false)
  }

  test("a FAILED call carries no picture: it would be a picture of the last answer") {
    val block = AnthropicHistory.toolResultBlock("tu_1", Json.fromString("boom"), isError = true, Some("aGVsbG8="))
    block.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
      .map(_.hcursor.get[String]("type").getOrElse("")) shouldBe List("text")
    block.hcursor.get[Boolean]("is_error") shouldBe Right(true)
  }

  test("a turn's tool results merge with the next question so roles still alternate") {
    val toolUse = Json.obj(
      "type"  -> Json.fromString("tool_use"),
      "id"    -> Json.fromString("tu_1"),
      "name"  -> Json.fromString("whoami"),
      "input" -> Json.obj()
    )
    val result = AnthropicHistory.toolResultBlock("tu_1", Json.fromString("{\"ok\":true}"), isError = false)

    val messages = AnthropicHistory.toMessages(
      Seq(
        stored("user", Json.arr(AnthropicHistory.textBlock("who am i"))),
        stored("assistant", Json.arr(toolUse)),
        stored("tool", Json.arr(result)),
        stored("user", Json.arr(AnthropicHistory.textBlock("thanks")))
      )
    )

    messages.map(_.hcursor.get[String]("role").toOption.get) shouldBe List("user", "assistant", "user")
    // The tool result and the next question share one user message.
    val merged = messages.last.hcursor.downField("content").values.toList.flatten
    merged.map(_.hcursor.get[String]("type").toOption.get) shouldBe List("tool_result", "text")
  }

  test("an interrupted tool round is repaired rather than replayed into a 400") {
    val toolUse = Json.obj(
      "type"  -> Json.fromString("tool_use"),
      "id"    -> Json.fromString("tu_orphan"),
      "name"  -> Json.fromString("list_domains"),
      "input" -> Json.obj()
    )
    val messages = AnthropicHistory.toMessages(
      Seq(
        stored("user", Json.arr(AnthropicHistory.textBlock("domains?"))),
        // Stop was pressed: the call was made, the result never came back.
        stored("assistant", Json.arr(AnthropicHistory.textBlock("looking"), toolUse))
      )
    )
    val assistant = messages.last.hcursor.downField("content").values.toList.flatten
    assistant.map(_.hcursor.get[String]("type").toOption.get) shouldBe List("text")
  }

  test("a tool_result with nothing to answer is dropped") {
    val orphanResult = AnthropicHistory.toolResultBlock("tu_gone", Json.fromString("{}"), isError = false)
    val messages = AnthropicHistory.toMessages(
      Seq(
        stored("user", Json.arr(AnthropicHistory.textBlock("hi"))),
        stored("assistant", Json.arr(AnthropicHistory.textBlock("hello"))),
        stored("tool", Json.arr(orphanResult))
      )
    )
    messages.flatMap(_.hcursor.downField("content").values.toList.flatten)
      .map(_.hcursor.get[String]("type").toOption.get) should not contain "tool_result"
  }

  test("a thread that starts mid-turn drops the leading assistant message") {
    val messages = AnthropicHistory.toMessages(
      Seq(
        stored("assistant", Json.arr(AnthropicHistory.textBlock("…continued"))),
        stored("user", Json.arr(AnthropicHistory.textBlock("carry on")))
      )
    )
    messages.map(_.hcursor.get[String]("role").toOption.get) shouldBe List("user")
  }

  // ── the SDK seam ──────────────────────────────────────────────────────────

  test("text, tool_use and tool_result round-trip through the SDK's own message type") {
    val blocks = List(
      AnthropicHistory.textBlock("hello"),
      Json.obj(
        "type"  -> Json.fromString("tool_use"),
        "id"    -> Json.fromString("tu_1"),
        "name"  -> Json.fromString("list_domains"),
        "input" -> Json.obj("tld" -> Json.fromString("IO"))
      )
    )
    val assistant = AnthropicHistory.message("assistant", blocks)
    val back      = AnthropicSdk.toJson(AnthropicSdk.messageParam(assistant))

    back.hcursor.get[String]("role").toOption shouldBe Some("assistant")
    val types = back.hcursor.downField("content").values.toList.flatten.map(_.hcursor.get[String]("type").toOption.get)
    types shouldBe List("text", "tool_use")
    back.hcursor.downField("content").downN(1).get[String]("id").toOption shouldBe Some("tu_1")
    back.hcursor.downField("content").downN(1).downField("input").get[String]("tld").toOption shouldBe Some("IO")

    val user = AnthropicHistory.message(
      "user",
      List(AnthropicHistory.toolResultBlock("tu_1", Json.fromString("{\"domains\":3}"), isError = false))
    )
    val userBack = AnthropicSdk.toJson(AnthropicSdk.messageParam(user))
    userBack.hcursor.get[String]("role").toOption shouldBe Some("user")
    val result = userBack.hcursor.downField("content").downN(0)
    result.get[String]("type").toOption shouldBe Some("tool_result")
    result.get[String]("tool_use_id").toOption shouldBe Some("tu_1")
    result.get[Boolean]("is_error").toOption shouldBe Some(false)
  }

  test("an MCP tool definition becomes an SDK tool with the same schema") {
    val schema = Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.obj("tld" -> Json.obj("type" -> Json.fromString("string"))),
      "required"   -> Json.arr(Json.fromString("tld"))
    )
    val json = AnthropicSdk.toJson(AnthropicSdk.tool(ToolDef("list_domains", "List domains", schema)))
    json.hcursor.get[String]("name").toOption shouldBe Some("list_domains")
    json.hcursor.get[String]("description").toOption shouldBe Some("List domains")
    json.hcursor.downField("input_schema").downField("properties").downField("tld").get[String]("type").toOption shouldBe
      Some("string")
  }

  test("an oversized tool result is truncated with a note the model can act on") {
    val big     = Json.fromString("x" * (AnthropicApiConfig.MaxToolResultChars + 100))
    val trimmed = AnthropicApiSession.truncate(big).asString.getOrElse("")
    trimmed.length should be < (AnthropicApiConfig.MaxToolResultChars + 100)
    trimmed should include("truncated")
  }
}
