package escalator.web.http

import java.util.concurrent.ConcurrentHashMap

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import escalator.web.auth.{SessionUser, Sessions}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.server.Directives._

/** A failure with an HTTP status and a stable machine-readable code. */
final case class ApiError(status: StatusCode, code: String, message: String, extra: Json = Json.obj()) extends Exception(message)

object ApiError {
  def bad(msg: String, code: String = "bad_request"): ApiError = ApiError(StatusCodes.BadRequest, code, msg)
  def notFound(what: String): ApiError = ApiError(StatusCodes.NotFound, "not_found", s"$what not found")
  def forbidden(msg: String = "forbidden"): ApiError = ApiError(StatusCodes.Forbidden, "forbidden", msg)
  def conflict(msg: String, code: String = "conflict"): ApiError = ApiError(StatusCodes.Conflict, code, msg)
}

/** Cookie sessions, CSRF origin check, JSON errors and role checks for an app's routes.
  *
  * An app supplies `sessions`, `secureCookies`, `appUrl` and its `SessionCookieName` (e.g. "hm_session"), and may
  * override `extraOrigins` (usually `HttpSupport.originsFromEnv("<APP>_ALLOWED_ORIGINS")`), `appErrors` (its own
  * exceptions, tried first) and `onError` (e.g. self-tracking).
  */
trait HttpSupport extends FailFastCirceSupport {
  def sessions: Sessions
  def secureCookies: Boolean
  def appUrl: String

  def SessionCookieName: String
  /** Extra browser origins allowed for state-changing requests (e.g. the staging host). */
  def extraOrigins: Set[String] = Set.empty
  /** Whether `http://localhost…` origins pass the same-origin check (local dev against a deployed API). */
  def allowLocalhostOrigins: Boolean = true
  def uploadLimitMessage: String = "uploads are limited to 50 MB"

  /** App-specific exceptions → responses, tried before the defaults. */
  def appErrors: PartialFunction[Throwable, Route] = PartialFunction.empty
  /** Called with "error" when an unexpected exception becomes a 500. */
  def onError(kind: String): Unit = ()

  def errorJson(e: ApiError): Json =
    Json.obj("ok" -> false.asJson, "code" -> e.code.asJson, "message" -> e.message.asJson).deepMerge(e.extra)

  private def tooLarge: Route =
    complete(StatusCodes.PayloadTooLarge -> errorJson(ApiError(StatusCodes.PayloadTooLarge, "too_large", uploadLimitMessage)))

  lazy val errorHandler: ExceptionHandler = ExceptionHandler(appErrors orElse {
    case e: ApiError => complete(e.status -> errorJson(e))
    case _: org.apache.pekko.http.scaladsl.model.EntityStreamSizeException => tooLarge
    case e: SecurityException => complete(StatusCodes.Forbidden -> errorJson(ApiError.forbidden(e.getMessage)))
    case e: NoSuchElementException => complete(StatusCodes.NotFound -> errorJson(ApiError.notFound(Option(e.getMessage).getOrElse("resource"))))
    case e: IllegalStateException => complete(StatusCodes.Conflict -> errorJson(ApiError.conflict(e.getMessage)))
    case e: IllegalArgumentException => complete(StatusCodes.BadRequest -> errorJson(ApiError.bad(e.getMessage)))
    case e: Throwable =>
      e.printStackTrace()
      onError("error")
      complete(StatusCodes.InternalServerError -> errorJson(ApiError(StatusCodes.InternalServerError, "internal", "internal error")))
  }: PartialFunction[Throwable, Route])

  lazy val rejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handle { case AuthorizationFailedRejection => complete(StatusCodes.Forbidden -> errorJson(ApiError.forbidden())) }
    .handle { case MalformedRequestContentRejection(_, _: org.apache.pekko.http.scaladsl.model.EntityStreamSizeException) => tooLarge }
    .handle { case MalformedRequestContentRejection(msg, _) => complete(StatusCodes.BadRequest -> errorJson(ApiError.bad(msg))) }
    .result().withFallback(RejectionHandler.default)

  def sessionCookie(value: String, maxAgeSec: Long): HttpCookie =
    HttpCookie(SessionCookieName, value, maxAge = Some(maxAgeSec), path = Some("/"), httpOnly = true, secure = secureCookies)
      .withSameSite(SameSite.Lax)

  def clientIp: Directive1[Option[String]] =
    optionalHeaderValueByName("X-Forwarded-For").map(_.map(_.split(",").head.trim))

  /** Reject cross-site state-changing browser requests (cookie auth only). */
  def sameOrigin: Directive0 = extractMethod.flatMap { m =>
    if (m == HttpMethods.GET || m == HttpMethods.HEAD) pass
    else optionalHeaderValueByName("Origin").flatMap {
      case Some(o) if o != appUrl && !extraOrigins.contains(o) && !(allowLocalhostOrigins && o.startsWith("http://localhost")) =>
        reject(AuthorizationFailedRejection)
      case _ => pass
    }
  }

  def optionalUser: Directive1[Option[SessionUser]] =
    optionalCookie(SessionCookieName).map(_.flatMap(c => sessions.lookup(c.value)))

  def authenticated: Directive1[SessionUser] = sameOrigin & optionalUser.flatMap {
    case Some(u) => provide(u)
    case None    => complete(StatusCodes.Unauthorized -> errorJson(ApiError(StatusCodes.Unauthorized, "unauthenticated", "please sign in"))).toDirective[Tuple1[SessionUser]]
  }

  def requireRole(roles: String*): Directive1[SessionUser] = authenticated.flatMap { u =>
    if (roles.contains(u.role) || u.isAdmin) provide(u) else reject(AuthorizationFailedRejection)
  }
}

object HttpSupport {
  /** A comma-separated origin list from the environment, e.g. `originsFromEnv("HOWMANY_ALLOWED_ORIGINS")`. */
  def originsFromEnv(name: String, env: Map[String, String] = sys.env): Set[String] =
    env.get(name).map(_.split(",").map(_.trim.stripSuffix("/")).filter(_.nonEmpty).toSet).getOrElse(Set.empty)
}

/** Sliding-window limiter for login / code endpoints (per key, in memory). */
final class AttemptLimiter(maxAttempts: Int, windowMs: Long) {
  private val hits = new ConcurrentHashMap[String, List[Long]]()
  def allow(key: String): Boolean = {
    val now = System.currentTimeMillis()
    val recent = Option(hits.get(key)).getOrElse(Nil).filter(_ > now - windowMs)
    if (recent.size >= maxAttempts) { hits.put(key, recent); false } else { hits.put(key, now :: recent); true }
  }
}
