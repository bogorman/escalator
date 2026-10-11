package escalator.web.http

import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Origin
import org.apache.pekko.http.scaladsl.model.headers.HttpOrigin
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import escalator.web.auth.Sessions

/** Requests without a session cookie: `sessions` is never consulted, so the spec needs no database. */
class HttpSupportSpec extends AnyFunSuite with Matchers with ScalatestRouteTest {

  final case class OverBudget(msg: String) extends Exception(msg)

  private val support = new HttpSupport {
    val sessions: Sessions          = null
    val secureCookies: Boolean      = true
    val appUrl: String              = "https://app.example.com"
    val SessionCookieName: String   = "ex_session"
    override val extraOrigins       = HttpSupport.originsFromEnv("EX_ALLOWED_ORIGINS", Map("EX_ALLOWED_ORIGINS" -> "https://staging.example.com/, "))
    override val appErrors: PartialFunction[Throwable, Route] = { case OverBudget(m) => complete(StatusCodes.PaymentRequired -> m) }
  }
  import support._

  private val routes: Route = handleExceptions(errorHandler) {
    handleRejections(rejectionHandler) {
      concat(
        path("change") { post { sameOrigin { complete("ok") } } },
        path("me") { authenticated { u => complete(u.email) } },
        path("budget") { get { throw OverBudget("over budget") } },
        path("missing") { get { throw ApiError.notFound("thing") } },
        path("denied") { get { throw new SecurityException("not yours") } }
      )
    }
  }

  test("state-changing requests from a foreign origin are refused; own, configured and absent origins pass") {
    Post("/change").withHeaders(Origin(HttpOrigin("https://evil.example"))) ~> routes ~> check(status shouldBe StatusCodes.Forbidden)
    Post("/change").withHeaders(Origin(HttpOrigin("https://app.example.com"))) ~> routes ~> check(status shouldBe StatusCodes.OK)
    Post("/change").withHeaders(Origin(HttpOrigin("https://staging.example.com"))) ~> routes ~> check(status shouldBe StatusCodes.OK)
    Post("/change") ~> routes ~> check(status shouldBe StatusCodes.OK)
  }

  test("no session cookie is a JSON 401") {
    Get("/me") ~> routes ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[Json].hcursor.get[String]("code") shouldBe Right("unauthenticated")
    }
  }

  test("app errors are tried first; ApiError and SecurityException map to their statuses") {
    Get("/budget") ~> routes ~> check(status shouldBe StatusCodes.PaymentRequired)
    Get("/missing") ~> routes ~> check {
      status shouldBe StatusCodes.NotFound
      responseAs[Json].hcursor.get[String]("message") shouldBe Right("thing not found")
    }
    Get("/denied") ~> routes ~> check(status shouldBe StatusCodes.Forbidden)
  }

  test("the session cookie is http-only, secure and SameSite=Lax under the app's name") {
    val c = sessionCookie("v", 60)
    (c.name, c.httpOnly, c.secure) shouldBe (("ex_session", true, true))
  }

  test("the limiter allows N attempts per window per key") {
    val l = new AttemptLimiter(2, 60000)
    Seq(l.allow("a"), l.allow("a"), l.allow("a"), l.allow("b")) shouldBe Seq(true, true, false, true)
  }
}
