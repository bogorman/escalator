package escalator.frontend.utils.http

import sttp.client3._
import sttp.model.Uri

import scala.concurrent.Future

trait Http {

  implicit val backend: SttpBackend[Future, sttp.capabilities.WebSockets]

  def boilerplate: RequestT[Empty, Either[String, String], Nothing]

  def host: Uri

  def path(s: String, ss: String*): Uri

}
