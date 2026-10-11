package escalator.agent.providers

import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.agent.protocol._

/**
 * What `codex exec --json` becomes.
 *
 * The `thread.started` / `turn.completed` / `command_execution` lines below are
 * VERBATIM from codex-cli 0.153.4 (captured by running it), and the item shapes
 * that need a tool or a file to appear follow the same vocabulary, taken from
 * the exec event tags in the binary. That distinction matters: the first group
 * proves the mapper against reality, the second proves it against the contract.
 */
class CodexWireSpec extends AnyFunSuite with Matchers {

  private val turn = TurnId("turn-1")

  private def ev(s: String): Json = parseJson(s).fold(throw _, identity)

  private def mapper = new CodexEventMapper(turn, () => SessionContextView.empty, "gpt-5.1")

  // ── the real lines ────────────────────────────────────────────────────────

  test("thread.started becomes SessionStarted carrying Codex's own thread id") {
    val m  = mapper
    val es = m.onEvent(ev("""{"type":"thread.started","thread_id":"01a07cec-8bd9-7243-8aa9-929ced951ce9"}"""))

    es should have size 1
    inside(es.head) { case s: AgentEvent.SessionStarted =>
      s.provider          shouldBe "codex-cli"
      s.model             shouldBe "gpt-5.1"
      s.providerSessionId shouldBe Some("01a07cec-8bd9-7243-8aa9-929ced951ce9")
    }
    // The id is what the NEXT turn resumes with, so the session must expose it.
    m.threadId shouldBe Some("01a07cec-8bd9-7243-8aa9-929ced951ce9")
  }

  test("an agent_message arrives whole and still streams as one delta") {
    val m = mapper
    val es = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"OK"}}""")
    )

    es.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.AssistantMessage)
    es.collect { case d: AgentEvent.ContentDelta => (d.kind, d.delta) } shouldBe
      List((StreamKind.AssistantText, "OK"))
    es.collect { case c: AgentEvent.ItemCompleted => c.status } shouldBe List(ItemStatus.Completed)
  }

  test("text already streamed is not re-sent — only the new suffix is") {
    val m = mapper
    m.onEvent(ev("""{"type":"item.started","item":{"id":"item_0","type":"agent_message","text":"Hel"}}"""))
    val second = m.onEvent(
      ev("""{"type":"item.updated","item":{"id":"item_0","type":"agent_message","text":"Hello there"}}""")
    )

    second.collect { case d: AgentEvent.ContentDelta => d.delta } shouldBe List("lo there")
    // One card, not two: the second event must not re-open the item.
    second.collect { case s: AgentEvent.ItemStarted => s } shouldBe empty
  }

  test("a command_execution opens a card with the command and closes it with the output") {
    val m = mapper
    val started = m.onEvent(
      ev("""{"type":"item.started","item":{"id":"item_1","type":"command_execution",
            |"command":"/bin/zsh -lc 'echo hello-from-codex'","aggregated_output":"",
            |"exit_code":null,"status":"in_progress"}}""".stripMargin.replaceAll("\n", ""))
    )
    val done = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_1","type":"command_execution",
            |"command":"/bin/zsh -lc 'echo hello-from-codex'","aggregated_output":"hello-from-codex\n",
            |"exit_code":0,"status":"completed"}}""".stripMargin.replaceAll("\n", ""))
    )

    inside(started.head) { case s: AgentEvent.ItemStarted =>
      s.itemType shouldBe ItemType.CommandExecution
      s.input.flatMap(_.hcursor.get[String]("command").toOption) shouldBe
        Some("/bin/zsh -lc 'echo hello-from-codex'")
    }
    // The completion must land on the SAME item id the card was opened with.
    val completed = done.collect { case c: AgentEvent.ItemCompleted => c }
    completed should have size 1
    completed.head.itemId shouldBe started.collect { case s: AgentEvent.ItemStarted => s.itemId }.head
    completed.head.status shouldBe ItemStatus.Completed
    completed.head.isError shouldBe false
    completed.head.result.flatMap(_.hcursor.get[String]("output").toOption) shouldBe Some("hello-from-codex\n")
  }

  test("a non-zero exit code fails the card even when Codex calls the item completed") {
    val m = mapper
    m.onEvent(ev("""{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"false"}}"""))
    val done = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"false","exit_code":1,"status":"completed"}}""")
    )

    val c = done.collect { case c: AgentEvent.ItemCompleted => c }.head
    c.status  shouldBe ItemStatus.Failed
    c.isError shouldBe true
  }

  test("turn.completed folds Codex's usage, counting reasoning as output") {
    val m = mapper
    m.onEvent(
      ev("""{"type":"turn.completed","usage":{"input_tokens":15224,"cached_input_tokens":12160,
            |"cache_write_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":40}}""".stripMargin.replaceAll("\n", ""))
    )

    m.usage.inputTokens      shouldBe 15224L
    m.usage.cacheReadTokens  shouldBe 12160L
    m.usage.outputTokens     shouldBe 45L // 5 answer + 40 reasoning
    m.sawTurnEnd             shouldBe true
    m.failure                shouldBe None
  }

  test("an error item is surfaced but does not decide the turn's outcome") {
    val m = mapper
    val es = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata for `x` not found."}}""")
    )

    es.collect { case e: AgentEvent.RuntimeError => e.retryable } shouldBe List(true)
    // `turn.failed` is what fails a turn; a warning Codex carries on from is not.
    m.failure shouldBe None
  }

  test("turn.failed classifies what went wrong") {
    val m = mapper
    m.onEvent(
      ev("""{"type":"turn.failed","error":{"message":"The 'gpt-5.1-codex' model is not supported when using Codex with a ChatGPT account."}}""")
    )

    m.sawTurnEnd    shouldBe true
    m.failure.map(_._1) shouldBe Some("not_configured")
  }

  test("a top-level error is the turn's failure, and the FIRST one wins") {
    val m = mapper
    m.onEvent(ev("""{"type":"error","message":"429 rate limit reached"}"""))
    m.onEvent(ev("""{"type":"turn.failed","error":{"message":"something vaguer"}}"""))

    m.failure shouldBe Some(("rate_limited", "429 rate limit reached"))
  }

  // ── the contract ──────────────────────────────────────────────────────────

  test("an mcp_tool_call becomes the same tool name the Claude engine produces") {
    val m = mapper
    val started = m.onEvent(
      ev("""{"type":"item.started","item":{"id":"item_2","type":"mcp_tool_call","server":"app",
            |"tool":"list_domains","arguments":"{\"tld\":\"IO\"}","status":"in_progress"}}""".stripMargin.replaceAll("\n", ""))
    )
    val done = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_2","type":"mcp_tool_call","server":"app",
            |"tool":"list_domains","result":{"domains":[]},"status":"completed"}}""".stripMargin.replaceAll("\n", ""))
    )

    inside(started.head) { case s: AgentEvent.ItemStarted =>
      s.toolName shouldBe Some("mcp__app__list_domains")
      s.itemType shouldBe ItemType.McpToolCall
      // Arguments arrive as a JSON *string*; a tool card renders an object.
      s.input.flatMap(_.hcursor.get[String]("tld").toOption) shouldBe Some("IO")
    }
    val c = done.collect { case c: AgentEvent.ItemCompleted => c }.head
    c.status  shouldBe ItemStatus.Completed
    c.isError shouldBe false
  }

  test("an mcp_tool_call that reports an error fails its card with the message") {
    val m = mapper
    m.onEvent(ev("""{"type":"item.started","item":{"id":"item_2","type":"mcp_tool_call","server":"app","tool":"register_domain"}}"""))
    val done = m.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_2","type":"mcp_tool_call","server":"app","tool":"register_domain","error":"insufficient scope","status":"failed"}}""")
    )

    val c = done.collect { case c: AgentEvent.ItemCompleted => c }.head
    c.status  shouldBe ItemStatus.Failed
    c.isError shouldBe true
    c.result.flatMap(_.asString) shouldBe Some("insufficient scope")
  }

  test("a file_change names the files it touched, whichever shape `changes` takes") {
    val listShape = mapper.onEvent(
      ev("""{"type":"item.started","item":{"id":"item_3","type":"file_change","changes":[{"path":"Foo.java","kind":"update"}]}}""")
    )
    val mapShape = mapper.onEvent(
      ev("""{"type":"item.started","item":{"id":"item_3","type":"file_change","changes":{"Foo.java":"update"}}}""")
    )

    def title(es: List[AgentEvent]): String = es.collect { case s: AgentEvent.ItemStarted => s.title }.head
    title(listShape) shouldBe "Editing Foo.java"
    title(mapShape)  shouldBe "Editing Foo.java"
    listShape.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.FileChange)
  }

  test("a web_search shows its query") {
    val es = mapper.onEvent(
      ev("""{"type":"item.started","item":{"id":"item_4","type":"web_search","query":"cheltenham results"}}""")
    )
    inside(es.head) { case s: AgentEvent.ItemStarted =>
      s.itemType shouldBe ItemType.WebSearch
      s.title    shouldBe """Searching the web for "cheltenham results""""
    }
  }

  test("reasoning streams into the thinking block, not the answer") {
    val es = mapper.onEvent(
      ev("""{"type":"item.completed","item":{"id":"item_5","type":"reasoning","text":"Checking the card."}}""")
    )
    es.collect { case d: AgentEvent.ContentDelta => d.kind } shouldBe List(StreamKind.ReasoningText)
    es.collect { case s: AgentEvent.ItemStarted => s.itemType } shouldBe List(ItemType.Reasoning)
  }

  test("an item left open when the process dies is closed, not left spinning") {
    val m = mapper
    m.onEvent(ev("""{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"sleep 100"}}"""))
    val closed = m.closeOpenItems()

    closed.collect { case c: AgentEvent.ItemCompleted => c.status } shouldBe List(ItemStatus.Failed)
    // Idempotent: a second call has nothing left to close.
    m.closeOpenItems() shouldBe empty
  }

  test("item ids are namespaced by turn, so turn 2 cannot overwrite turn 1's cards") {
    val one = new CodexEventMapper(TurnId("turn-1"))
    val two = new CodexEventMapper(TurnId("turn-2"))
    def id(m: CodexEventMapper): ItemId =
      m.onEvent(ev("""{"type":"item.started","item":{"id":"item_0","type":"agent_message","text":""}}"""))
        .collect { case s: AgentEvent.ItemStarted => s.itemId }.head

    // Codex restarts item ids at `item_0` every thread; ours must not collide.
    id(one) should not be id(two)
  }

  private def inside[A](a: A)(f: PartialFunction[A, Any]): Unit = {
    if (!f.isDefinedAt(a)) fail(s"unexpected shape: $a")
    f(a)
    ()
  }
}
