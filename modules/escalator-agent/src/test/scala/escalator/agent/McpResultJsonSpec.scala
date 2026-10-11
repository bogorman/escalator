package escalator.agent

import io.circe.Json
import io.circe.parser.parse

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class McpResultJsonSpec extends AnyFunSuite with Matchers {

  private def j(s: String): Json = parse(s).toOption.get

  test("structuredContent wins over the text items") {
    val env = j("""{"content":[{"type":"text","text":"summary"}],"structuredContent":{"domains":["a.io"]}}""")
    McpResultJson.unwrap(env) shouldBe j("""{"domains":["a.io"]}""")
  }

  test("a JSON text item is parsed back into the tool's own JSON") {
    McpResultJson.unwrap(j("""{"content":[{"type":"text","text":"{\"count\":3}"}]}""")) shouldBe j("""{"count":3}""")
    McpResultJson.unwrap(j("""[{"type":"text","text":"[1,2]"}]""")) shouldBe j("[1,2]")
  }

  test("a JSON item still parses when the CLI added a prose item next to it") {
    val env = j("""[{"type":"text","text":"{\"ok\":true}"},{"type":"text","text":"see the link"}]""")
    McpResultJson.unwrap(env) shouldBe j("""{"ok":true}""")
  }

  test("an envelope handed over as a STRING is unwrapped, images dropped") {
    val inner = """[{"type":"text","text":"{\"ok\":true}"},{"type":"image","data":"AAAA","mimeType":"image/png"}]"""
    McpResultJson.unwrap(Json.fromString(inner)) shouldBe j("""{"ok":true}""")
  }

  test("prose stays prose, minus binary and link items") {
    val env = j("""[{"type":"text","text":"line one"},{"type":"image","data":"AAAA"},{"type":"resource_link","uri":"x://1"}]""")
    McpResultJson.unwrap(env) shouldBe j("""[{"type":"text","text":"line one"}]""")
    McpResultJson.unwrap(Json.fromString("plain words")) shouldBe Json.fromString("plain words")
  }
}
