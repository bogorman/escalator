package escalator.frontend.app.utils

import com.raquo.airstream.core.EventStream
import escalator.frontend.utils.http.DefaultHttp.{boilerplate, path, pathWithMultipleParams, responseAs, _}
import io.circe
import escalator.errors.BackendError
import sttp.client3.{Identity, RequestT}
import urldsl.language.QueryParameters

import scala.concurrent.ExecutionContext

final class InfoDownloader(defaultPath: String)(implicit ec: ExecutionContext) {

  private def request[T](p: String)(
      implicit aDecoder: io.circe.Decoder[T]
  ): RequestT[Identity, Either[Either[circe.Error, Map[String, List[BackendError]]], Either[circe.Error, T]], Any] =
    boilerplate
      .get(if (defaultPath.nonEmpty) path(defaultPath, p) else path(p))
      .response(responseAs[T])

  private def requestWithParams[R](p: String, params: Map[String, List[String]])(
      implicit aDecoder: io.circe.Decoder[R]
  ): RequestT[Identity, Either[Either[circe.Error, Map[String, List[BackendError]]], Either[circe.Error, R]], Any] =
    boilerplate
      .get(pathWithMultipleParams(params, defaultPath, p))
      .response(responseAs[R])

  def downloadInfo[T](p: String)(implicit aDecoder: io.circe.Decoder[T]): EventStream[Option[T]] =
    EventStream.fromFuture(
      request(p)
        .send()
        .map(_.body)
        .map(_.toOption.flatMap(_.toOption))
    )

  def downloadInfoWithParams[T, Q](p: String, queryParameters: QueryParameters[Q, _])(
      q: Q
  )(implicit aDecoder: io.circe.Decoder[T]): EventStream[Option[T]] = EventStream.fromFuture(
    requestWithParams(p, queryParameters.createParams(q).map { case (key, param) => key -> param.content })
      .send()
      .map(_.body)
      .map(_.toOption.flatMap(_.toOption))
  )
}
