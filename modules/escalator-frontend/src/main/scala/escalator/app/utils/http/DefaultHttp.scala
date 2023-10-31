package escalator.frontend.utils.http

import io.circe._
import io.circe.parser._
import io.circe.syntax._
import escalator.errors.BackendError
import org.scalajs.dom.document
import org.scalajs.dom

import sttp.client3._
import sttp.model.{MediaType, Uri}

// MultiQueryParams
import sttp.model.QueryParams

import scala.concurrent.Future

object DefaultHttp extends Http {

  implicit val backend: SttpBackend[Future, sttp.capabilities.WebSockets] = FetchBackend()

  final val csrfTokenName = "Csrf-Token"

  def maybeCsrfToken: Option[String] =
    dom.document.cookie
      .split(";")
      .map(_.trim)
      .find(_.startsWith(s"$csrfTokenName="))
      .map(_.drop(csrfTokenName.length + 1))

  def boilerplate(): RequestT[Empty, Either[String, String], Any] =
    basicRequest
      //.header("Csrf-Token", maybeCsrfToken.getOrElse("none"))

  def host: Uri = Uri.parse(document.location.origin.toString).right.get //uri"http://localhost:8080"

  def path(s: String, ss: String*): Uri = host.path("api", s, ss: _*)

  def pathWithMultipleParams(params: Map[String, List[String]], s: String, ss: String*): Uri = {
    path(s, ss: _*).params(QueryParams.fromMultiMap(params))
  }

  def responseAs[A](
      implicit aDecoder: Decoder[A]
  ): ResponseAs[Either[Either[Error, Map[String, List[BackendError]]], Either[Error, A]], Any] = asEither(
    asStringAlways.map(
      decode[Map[String, List[BackendError]]](_)
    ),
    asStringAlways.map(
      decode[A]
    )
  )

  def asErrorOnly: ResponseAs[Either[Either[Error, Map[String, List[BackendError]]], Unit], Any] =
    asEither(asStringAlways.map(decode[Map[String, List[BackendError]]](_)), ignore)

  implicit def bodySerializer[A](implicit aEncoder: Encoder[A]): A => BasicRequestBody =
    (a: A) =>
      StringBody(
        a.asJson.noSpaces,
        "utf-8",
        MediaType.ApplicationJson
      )

}
