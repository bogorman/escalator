package escalator.agent

import io.circe.{Json, parser}

/**
 * ==An MCP tool result, as the event log wants it==
 *
 * MCP hands a tool result back as a CONTENT ENVELOPE — `{content:[{type:"text",
 * text:"<json>"}, {type:"resource_link", …}], structuredContent:{…}}` — while
 * everything downstream of a provider (the event log, a tool card, the
 * replayed history) wants the tool's own JSON.
 *
 * This unwraps one into the other, for the engines that speak MCP over a wire
 * we do not control (the Claude CLI and Codex). Two rules:
 *
 *  - `structuredContent` WINS when it is there. It is the same payload the text
 *    item summarises, without the summarising.
 *  - Otherwise the text items are parsed as JSON, and ONLY if that succeeds. A
 *    tool result that is genuinely prose (a file read, a shell command) is left
 *    exactly as it arrived — turning it into a JSON string would change what
 *    every existing tool card renders.
 *
 * `resource_link` and `image` items are dropped either way: a link is derivable
 * from the artifact id and an image is bytes, and both would be re-sent on
 * every later request of the thread.
 */
object McpResultJson {

  /**
   * The tool's own JSON, or `content` MINUS its binary items when it is not an
   * envelope we can read.
   *
   * The fallback matters as much as the happy path: it is what a caller then
   * size-caps, and a 390 KB base64 chart in front of the text item means the
   * cap keeps the picture and throws the JSON away. Dropping those items first costs a prose result nothing.
   */
  def unwrap(content: Json): Json = unwrap(content, depth = 2)

  /** A CLI that hands the envelope over as a STRING is unwrapped again, once:
    * what the string holds is the same `[{type:"text"…},{type:"image"…}]` array
    * every other client gets, and stopping at the parse would keep the image. */
  private def unwrap(content: Json, depth: Int): Json =
    structured(content)
      .orElse(fromTextItems(content))
      .orElse(fromJsonString(content).map(j => if (depth > 0) unwrap(j, depth - 1) else j))
      .getOrElse(withoutBinaryItems(content))

  /** Content item types that are bytes or a derivable address, never an answer. */
  private val DroppedItemTypes: Set[String] = Set("image", "audio", "resource_link")

  /**
   * The same envelope with its `image` / `audio` / `resource_link` items gone.
   *
   * Call this BEFORE any size cap: the items are the biggest thing in a result
   * and the least useful thing in it — a link is derivable from the artifact id
   * and bytes would be re-sent on every later request of the thread.
   */
  def withoutBinaryItems(content: Json): Json = {
    def keep(item: Json): Boolean =
      !item.hcursor.get[String]("type").toOption.exists(DroppedItemTypes.contains)
    content.asArray match {
      case Some(items) => Json.fromValues(items.filter(keep))
      case None =>
        (for {
          obj   <- content.asObject
          items <- obj("content").flatMap(_.asArray)
        } yield Json.fromJsonObject(obj.add("content", Json.fromValues(items.filter(keep)))))
          .getOrElse(content)
    }
  }

  private def structured(content: Json): Option[Json] =
    content.asObject.flatMap(_("structuredContent")).filter(j => j.isObject || j.isArray)

  /** The `content` array's text items, parsed as JSON when they ARE JSON. */
  private def fromTextItems(content: Json): Option[Json] = {
    val items = content.asObject.flatMap(_("content")).flatMap(_.asArray).orElse(content.asArray)
    items.flatMap { blocks =>
      val texts = blocks.toList.flatMap { b =>
        if (b.hcursor.get[String]("type").toOption.contains("text")) b.hcursor.get[String]("text").toOption
        else None
      }
      if (texts.isEmpty) None
      else
        // The joined form first (one text item is the normal case), then item
        // by item: the Claude CLI flattens a `resource_link` block into its own
        // PROSE text item, so the concatenation stops parsing and the tool's
        // own JSON would be thrown away.
        asPayload(texts.mkString("\n")).orElse(texts.iterator.flatMap(asPayload).toList.headOption)
    }
  }

  /** A tool result the CLI handed over as a JSON STRING, not as an envelope. */
  private def fromJsonString(content: Json): Option[Json] =
    content.asString.flatMap(asPayload)

  /** Text parsed as the tool's own JSON, or nothing when it is prose. */
  private def asPayload(text: String): Option[Json] =
    parser.parse(text).toOption.filter(j => j.isObject || j.isArray)
}
