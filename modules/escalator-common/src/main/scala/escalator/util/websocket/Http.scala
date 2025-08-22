package escalator.websocket

import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration._
import scala.util.Try

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.HttpExt
import org.apache.pekko.http.scaladsl.model.ws.TextMessage.Strict
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.pattern.after

import play.api.libs.json.{Json, Reads, Writes}

import escalator.util.logging.Logger

abstract class Http {
  // TODO: Decouple from Akka HTTP request
  def singleRequestJson[T: Reads](request: HttpRequest): Future[T]

  def httpRequest(): HttpExt

  // Materialized future indicates connection establishment success or failure
  def asFlow[Request: Writes, Response: Reads](
    uri: Uri,
    responseTransform: String => String
  ): Flow[Request, Either[String, Response], Future[Unit]]
}

class PekkoHTTP(implicit
  materializer: Materializer,
  system: ActorSystem,
  executionContext: ExecutionContext,
  logger: Logger
) extends Http {

  implicit val httpExt: HttpExt = org.apache.pekko.http.scaladsl.Http()

  override def httpRequest(): HttpExt = {
    return httpExt
  }

  override def singleRequestJson[T: Reads](request: HttpRequest): Future[T] =
    httpExt.singleRequest(request)
      .flatMap(response => response.entity.toStrict(30.seconds).map(s => Json.parse(s.data.utf8String).as[T]))

  def encode[Request: Writes]: Flow[Request, Strict, NotUsed] =
    Flow[Request].map(request => TextMessage(Json.toJson(request).toString()))

  def decode[Response: Reads]: Flow[String, Either[String, Response], NotUsed] =
    Flow[String].map(input => Try(Json.parse(input).as[Response]).fold(_ => Left(input), Right.apply))

  val asString: Flow[Message, String, NotUsed] =
    Flow[Message].map {
      case TextMessage.Strict(text) =>
        Future.successful(text)

      case TextMessage.Streamed(stream) =>
        stream.runFold("")(_ + _)

      case unexpected =>
        logger.error("Received unexpected data", "data" -> unexpected)
        throw new Exception(s"Unexpected data $unexpected")
    }.mapAsync(1)(identity)

  override def asFlow[Request: Writes, Response: Reads](
    uri: Uri,
    responseTransform: (String) => String
  ): Flow[Request, Either[String, Response], Future[Unit]] = {
    val webSocketFlow = httpExt.webSocketClientFlow(WebSocketRequest(uri)).mapMaterializedValue { upgradeResponse =>

      // Timeout future because it doesn't complete on wss otherwise
      // https://github.com/akka/akka-http/issues/793
      val withTimeout = Future.firstCompletedOf(
        List(
          after(15.seconds, system.scheduler)(Future.failed(new TimeoutException("Timeout establishing connection"))),
          upgradeResponse
        )
      )

      withTimeout.failed.foreach { e =>
        logger.error(
          e,
          "Websocket could not be established",
          "uri" -> uri
        )
      }

      withTimeout.flatMap { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
          logger.info(
            "Websocket successfully established",
            "uri" -> uri
          )
          Future.successful(())
        }
        else {
          logger.error(
            "Websocket could not be established",
            "response" -> upgrade.response.status,
            "uri" -> uri
          )
          Future.failed(new RuntimeException(s"Connection failed: ${upgrade.response.status}"))
        }
      }
    }

    encode
      .viaMat(webSocketFlow)(Keep.right)
      .via(asString)
      .via(Flow[String].map(responseTransform))
      .via(decode)
  }
}
