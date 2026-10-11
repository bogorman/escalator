package escalator.agent

import io.circe.Json
import io.circe.syntax._

import escalator.agent.protocol.SessionContextView

/** The tool surface as the agent will experience it. `denied` carries the reason. */
final case class ToolsView(
    allowed: List[String]           = Nil,
    denied:  List[(String, String)] = Nil
)

/**
 * ==Who the agent is talking to==
 *
 * Built once per conversation and refreshed per turn. It reaches the model as
 * a system-prompt block after the profile text, as `session.json` in a CLI
 * workspace, and as the `SessionStarted` event a client reads — all rendered
 * from this one value so they cannot drift. An app runs one agent per person, so this
 * is deliberately small: who, which engine, which tools, plus an optional
 * free-form `page` the caller supplies (e.g. the domain on screen).
 */
final case class SessionContext(
    userId:         Option[Long] = None,
    name:           String       = "",
    role:           String       = "",
    tools:          ToolsView    = ToolsView(),
    provider:       String       = "",
    model:          String       = "",
    credential:     String       = "",
    page:           Option[Json] = None,
    conversationId: Option[Long] = None
) {

  /** The UI-safe projection. */
  def view: SessionContextView = SessionContextView(
    name         = name,
    role         = role,
    allowedTools = tools.allowed,
    provider     = provider,
    model        = model,
    credential   = credential
  )
}

object SessionContext {
  val anonymous: SessionContext = SessionContext()
}

/** Renders a [[SessionContext]] into the forms an engine consumes. */
object SessionContextRenderer {

  /** The block appended AFTER the profile system prompt. Empty when there is nothing worth saying. */
  def systemBlock(ctx: SessionContext): String = {
    val b = new StringBuilder
    if (ctx.name.trim.nonEmpty) {
      b.append("## Who you are talking to\n\n")
      b.append(s"You are talking to ${ctx.name.trim}")
      if (ctx.role.equalsIgnoreCase("ADMIN")) b.append(" (the owner of this app)")
      b.append(".\n")
    }
    if (ctx.tools.allowed.nonEmpty)
      b.append(s"\n### Tools available to you\n${ctx.tools.allowed.mkString(", ")}.\n")
    if (ctx.tools.denied.nonEmpty) {
      b.append("Not available: ")
      b.append(ctx.tools.denied.map { case (n, why) => s"$n ($why)" }.mkString(", "))
      b.append(". Do not call these.\n")
    }
    ctx.page.filterNot(_.isNull).foreach { p =>
      b.append("\n### Current page context\n")
      b.append(p.spaces2).append("\n")
    }
    b.toString
  }

  /** `session.json`, written into a CLI workspace. */
  def sessionJson(ctx: SessionContext): Json = Json.obj(
    "user"   -> Json.obj("id" -> ctx.userId.asJson, "name" -> ctx.name.asJson, "role" -> ctx.role.asJson),
    "tools"  -> Json.obj(
      "allowed" -> ctx.tools.allowed.asJson,
      "denied"  -> ctx.tools.denied.map { case (n, why) => Json.obj("tool" -> n.asJson, "reason" -> why.asJson) }.asJson
    ),
    "engine" -> Json.obj("provider" -> ctx.provider.asJson, "model" -> ctx.model.asJson, "credential" -> ctx.credential.asJson),
    "page"   -> ctx.page.getOrElse(Json.Null),
    "note"   -> Json.fromString("Authoritative session context, refreshed each turn.")
  )
}
