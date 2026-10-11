package escalator.agent.providers

import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.{StoredMessage, ToolDef}
import escalator.agent.protocol._

/**
 * The two pieces of the OpenAI provider a compiler cannot check: what a stored
 * thread turns INTO, and what a streamed turn turns into.
 *
 * The history half round-trips through the SDK's own Jackson mapper
 * ([[OpenAiSdk.inputItem]] / [[OpenAiSdk.functionTool]]) on purpose. That is
 * what makes these tests worth more than comparing two of our own case classes:
 * a shape this suite accepts is a shape the SDK — and therefore the API —
 * accepts, so a wrong field name fails here instead of in production.
 */
class OpenAiWireSpec extends AnyFunSuite with Matchers {

  private val turn = TurnId("turn-1")

  private def ev(s: String): Json = parseJson(s).fold(throw _, identity)

  private def stored(role: String, blocks: String): StoredMessage =
    StoredMessage("t1", role, ev(blocks), 0L)

  private def typeOf(j: Json): Option[String] = j.hcursor.get[String]("type").toOption

  // ── stored thread → request input ─────────────────────────────────────────

  test("a plain exchange becomes two simple messages the SDK accepts") {
    val input = OpenAiHistory.toInput(
      Seq(
        stored("user", """[{"type":"text","text":"hello"}]"""),
        stored("assistant", """[{"type":"text","text":"hi there"}]""")
      )
    )

    input.map(j => (j.hcursor.get[String]("role").toOption, j.hcursor.get[String]("content").toOption)) shouldBe
      List((Some("user"), Some("hello")), (Some("assistant"), Some("hi there")))

    // The round trip is the real assertion: the SDK parses every item.
    noException should be thrownBy input.map(OpenAiSdk.inputItem)
  }

  test("a tool round trip becomes a function_call paired with its output") {
    val input = OpenAiHistory.toInput(
      Seq(
        stored("user", """[{"type":"text","text":"what domains?"}]"""),
        stored(
          "assistant",
          """[{"type":"tool_use","id":"call_1","name":"mcp__app__list_domains","input":{"tld":"IO"}}]"""
        ),
        stored(
          "tool",
          """[{"type":"tool_result","tool_use_id":"call_1","content":[{"type":"text","text":"{\"domains\":[]}"}],"is_error":false}]"""
        )
      )
    )

    input.map(typeOf) shouldBe List(None, Some("function_call"), Some("function_call_output"))

    val call = input(1).hcursor
    call.get[String]("call_id").toOption   shouldBe Some("call_1")
    call.get[String]("name").toOption      shouldBe Some("mcp__app__list_domains")
    // Arguments are a STRING to the Responses API, not an object.
    call.get[String]("arguments").toOption shouldBe Some("""{"tld":"IO"}""")

    val out = input(2).hcursor
    out.get[String]("call_id").toOption shouldBe Some("call_1")
    out.get[String]("output").toOption  shouldBe Some("""{"domains":[]}""")

    noException should be thrownBy input.map(OpenAiSdk.inputItem)
  }

  test("an interrupted turn's unanswered tool call is dropped rather than sent") {
    val input = OpenAiHistory.toInput(
      Seq(
        stored("user", """[{"type":"text","text":"go"}]"""),
        stored("assistant", """[{"type":"tool_use","id":"call_1","name":"t","input":{}}]""")
      )
    )

    // An unpaired call is a 400 on the next request; the missing card is not.
    input.map(typeOf) shouldBe List(None)
  }

  test("an orphaned tool result is dropped too") {
    val input = OpenAiHistory.toInput(
      Seq(stored("tool", """[{"type":"tool_result","tool_use_id":"call_ghost","content":"{}"}]"""))
    )
    input shouldBe empty
  }

  test("Anthropic thinking blocks are dropped — they mean nothing to another vendor") {
    val input = OpenAiHistory.toInput(
      Seq(
        stored("user", """[{"type":"text","text":"hi"}]"""),
        stored(
          "assistant",
          """[{"type":"thinking","thinking":"hmm","signature":"sig"},{"type":"text","text":"hello"}]"""
        )
      )
    )

    input.flatMap(_.hcursor.get[String]("content").toOption) shouldBe List("hi", "hello")
  }

  // ── tools ─────────────────────────────────────────────────────────────────

  test("a closed schema with every property required is sent strict; anything else is not") {
    val closed = ToolDef(
      "list_domains",
      "List domains",
      ev("""{"type":"object","properties":{"tld":{"type":"string"}},"required":["tld"],"additionalProperties":false}""")
    )
    val open = ToolDef("get_pnl", "P&L", ev("""{"type":"object","properties":{"days":{"type":"integer"}}}"""))

    OpenAiHistory.functionTool(closed).hcursor.get[Boolean]("strict").toOption shouldBe Some(true)
    // Strict on a schema that cannot satisfy it is a 400 on EVERY request.
    OpenAiHistory.functionTool(open).hcursor.get[Boolean]("strict").toOption shouldBe Some(false)

    noException should be thrownBy List(closed, open).map(t => OpenAiSdk.functionTool(OpenAiHistory.functionTool(t)))
  }

  // ── the stream ────────────────────────────────────────────────────────────

  test("a text answer streams as one item with its deltas, and is kept as a text block") {
    val m = new OpenAiTurnMapper(turn)

    val started = m.onEvent(ev("""{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant"}}"""))
    val d1      = m.onEvent(ev("""{"type":"response.output_text.delta","item_id":"msg_1","delta":"Hel"}"""))
    val d2      = m.onEvent(ev("""{"type":"response.output_text.delta","item_id":"msg_1","delta":"lo"}"""))
    val done    = m.onEvent(ev("""{"type":"response.output_item.done","item":{"id":"msg_1","type":"message"}}"""))
    m.onEvent(ev("""{"type":"response.completed","response":{"usage":{"input_tokens":120,"output_tokens":9,"input_tokens_details":{"cached_tokens":80}}}}"""))

    started.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.AssistantMessage)
    (d1 ::: d2).collect { case d: AgentEvent.ContentDelta => d.delta } shouldBe List("Hel", "lo")
    done.collect { case c: AgentEvent.ItemCompleted => c.status } shouldBe List(ItemStatus.Completed)

    m.assistantContent shouldBe List(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString("Hello")))
    m.usage.inputTokens     shouldBe 120L
    m.usage.outputTokens    shouldBe 9L
    m.usage.cacheReadTokens shouldBe 80L
    m.status                shouldBe Some("completed")
  }

  test("a model that sends no deltas still gets its answer to the UI") {
    val m = new OpenAiTurnMapper(turn)
    m.onEvent(ev("""{"type":"response.output_item.added","item":{"id":"msg_1","type":"message"}}"""))
    val done = m.onEvent(
      ev("""{"type":"response.output_item.done","item":{"id":"msg_1","type":"message","content":[{"type":"output_text","text":"OK"}]}}""")
    )

    done.collect { case d: AgentEvent.ContentDelta => d.delta } shouldBe List("OK")
  }

  test("a tool card is opened when the ARGUMENTS are done, keyed by call_id") {
    val m = new OpenAiTurnMapper(turn)

    // item_id is `fc_…`; the id the result must quote is `call_…`.
    val added = m.onEvent(
      ev("""{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"mcp__app__list_domains"}}""")
    )
    val partial = m.onEvent(ev("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"tld\""}"""))
    val ready = m.onEvent(
      ev("""{"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"tld\":\"IO\"}"}""")
    )

    // Nothing until the arguments are whole — half a JSON fragment is not a card.
    added   shouldBe empty
    partial shouldBe empty

    inside(ready.head) { case s: AgentEvent.ItemStarted =>
      s.itemId   shouldBe ItemId("call_1")
      s.toolName shouldBe Some("mcp__app__list_domains")
      s.itemType shouldBe ItemType.McpToolCall
      s.input.flatMap(_.hcursor.get[String]("tld").toOption) shouldBe Some("IO")
    }

    m.toolCalls.map { case (id, name, _) => (id, name) } shouldBe
      List(("call_1", "mcp__app__list_domains"))
    m.assistantContent.map(typeOf) shouldBe List(Some("tool_use"))
  }

  test("a call whose arguments never got their own event is still recovered from the item") {
    val m = new OpenAiTurnMapper(turn)
    m.onEvent(ev("""{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"t"}}"""))
    val done = m.onEvent(
      ev("""{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"t","arguments":"{\"a\":1}"}}""")
    )

    done.collect { case s: AgentEvent.ItemStarted => s.itemId } shouldBe List(ItemId("call_1"))
    m.toolCalls.map(_._1) shouldBe List("call_1")
  }

  test("reasoning summary deltas go to the thinking block, not the answer") {
    val m = new OpenAiTurnMapper(turn)
    val started = m.onEvent(ev("""{"type":"response.output_item.added","item":{"id":"rs_1","type":"reasoning"}}"""))
    val d = m.onEvent(ev("""{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","delta":"Weighing the field."}"""))

    started.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.Reasoning)
    d.collect { case c: AgentEvent.ContentDelta => c.kind } shouldBe List(StreamKind.ReasoningText)
    // Reasoning is NOT replayed to the provider on the next request.
    m.assistantContent shouldBe empty
  }

  test("a failed response is classified so the UI can say whether to retry") {
    val m = new OpenAiTurnMapper(turn)
    m.onEvent(ev("""{"type":"response.failed","response":{"error":{"code":"invalid_api_key","message":"Incorrect API key provided"}}}"""))

    m.status shouldBe Some("failed")
    m.error.map(_._1) shouldBe Some("auth")
  }

  test("a rate limit is retryable, an unknown error is not the same thing") {
    val rate = new OpenAiTurnMapper(turn)
    rate.onEvent(ev("""{"type":"error","code":"rate_limit_exceeded","message":"Rate limit reached"}"""))
    rate.error.map(_._1) shouldBe Some("rate_limited")

    val other = new OpenAiTurnMapper(turn)
    other.onEvent(ev("""{"type":"error","message":"upstream exploded"}"""))
    other.error.map(_._1) shouldBe Some("internal")
  }

  test("an incomplete response is reported rather than silently truncating") {
    val m = new OpenAiTurnMapper(turn)
    m.onEvent(
      ev("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":5,"output_tokens":32000}}}""")
    )

    m.status shouldBe Some("incomplete")
    m.error.map(_._1) shouldBe Some("incomplete")
    m.usage.outputTokens shouldBe 32000L
  }

  private def inside[A](a: A)(f: PartialFunction[A, Any]): Unit = {
    if (!f.isDefinedAt(a)) fail(s"unexpected shape: $a")
    f(a)
    ()
  }
}
