package escalator.util.oauth

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import io.circe.parser._
import io.circe.generic.auto._
import escalator.websocket.PekkoHTTP
import scala.util.{Success, Failure}

final case class OAuthResult(
  provider: String,
  state: String,
  tokens: TokenResponse,
  // whatever else you want to expose:
  rawProfile: Option[io.circe.Json] = None
)

// OAuthResult(discord,
// kyCH-1wzlft7PUw_n4aESD-Fxnr6rQNqfV2Dlj9XCPo,
// TokenResponse(MTM5OTQwNjY5Nzc1OTUxMDc2MA.IxXCvTaLdXTfQIkxXULxYf7CySjVMN,Some(Bearer),Some(email identify),
//   Some(FICx3tRm98Rl468erzu7hL7HHlH88i),Some(604800),None),None)

object OAuthCallbacks {
  type OAuthOnFinalized = OAuthResult => Future[OAuthUserLogin]  
}

class OAuthRoutes(http: escalator.websocket.Http,onFinalized: OAuthCallbacks.OAuthOnFinalized,redirects: OAtuhRedirects)(implicit ec: ExecutionContext, mat: Materializer) {
  // private val http = Http()
  def route: Route =
    pathPrefix("oauth") {
      concat(
        path(Segment / "callback") { providerName =>
          parameters("code".?, "state".?, "error".?) { (codeOpt, stateOpt, errorOpt) =>
            (codeOpt, stateOpt, errorOpt) match {
              case (_, _, Some(err)) =>
                // complete(StatusCodes.BadRequest -> s"Provider error: $err")
                redirect(redirects.providerError(err), StatusCodes.Found)

              case (Some(code), Some(state), _) =>
                OAuthProviders.supported.get(providerName) match {
                  case Some(p: OAuth2Provider) =>
                    OAuthStateStore.get(state) match {
                      case Some(PendingState(_, verifier)) =>
                        val formParams: Map[String, String] = {
                          val base = Map(
                            "grant_type"   -> "authorization_code",
                            "code"         -> code,
                            "redirect_uri" -> p.redirectUri.toString,
                            "client_id"    -> p.clientId
                          )

                          val pkce    = verifier.map(v => "code_verifier" -> v).toMap          // Map[String,String]
                          val secret  = if (!p.extraTokenParams.contains("client_secret"))
                                          Map("client_secret" -> p.clientSecret) else Map.empty
                          base ++ pkce ++ secret ++ p.extraTokenParams
                        }

                        val form = FormData(formParams).toEntity                        

                        val req = HttpRequest(
                          method = HttpMethods.POST,
                          uri = p.tokenUri,
                          entity = form.withContentType(ContentTypes.`application/x-www-form-urlencoded`)
                        )

                        onSuccess(exchange(req)) {
                          case Right(tokens) => {
                            val result = OAuthResult(p.name, state, tokens, rawProfile = None)
                            // onSuccess(onFinalized(result)) {
                            //   _ => redirect("/app", StatusCodes.Found) // or whatever you want to show
                            // }
                            onComplete(onFinalized(result)) {
                              case Success(_)  => redirect(redirects.success, StatusCodes.Found)
                              case Failure(_)  => redirect(redirects.persistError, StatusCodes.Found)
                            }                            
                          }

                          case Left(msg) =>
                            redirect(redirects.tokenError(msg), StatusCodes.Found)
                        }

                      case None =>
                        println("NO State found")
                        redirect(redirects.invalidState, StatusCodes.Found)
                    }

                  // case Some(_: OAuth1Provider) =>
                    // complete(StatusCodes.NotImplemented -> "OAuth1 callback not implemented here.")
                  case reason =>
                    println(reason)
                    redirect(redirects.invalidState, StatusCodes.Found)
                }

              case reason =>
                println(reason)
                redirect(redirects.invalidState, StatusCodes.Found)
            }
          }
        },
        path(Segment) { providerName =>
          get {
            OAuthProviders.supported.get(providerName) match {
              case Some(p: OAuth2Provider) =>
                val state = OAuthCryptoUtil.randomString()
                val (verifier, challenge) =
                  if (p.usePkce) OAuthCryptoUtil.pkcePair() else (null, null)

                OAuthStateStore.put(state, PendingState(p.name, Option(verifier)))

                val authParams = Map(
                  "response_type" -> "code",
                  "client_id"     -> p.clientId,
                  "redirect_uri"  -> p.redirectUri.toString(),
                  "scope"         -> p.scope.mkString(" "),
                  "state"         -> state
                ) ++ (if (p.usePkce) Map("code_challenge" -> challenge, "code_challenge_method" -> "S256") else Map.empty) ++ p.extraAuthParams

                redirect(OAuthUriUtil.addParams(p.authUri, authParams), StatusCodes.Found)

              case Some(_: OAuth1Provider) =>
                complete(StatusCodes.NotImplemented -> "OAuth1 not shown here (implement request-token step).")
              case _ =>
                complete(StatusCodes.NotFound -> "Unknown provider")
            }
          }
        }        
      )
    }

  private def exchange(req: HttpRequest): Future[Either[String, TokenResponse]] = {
    http.httpRequest.singleRequest(req).flatMap { resp =>
      resp.entity.toStrict(timeout = scala.concurrent.duration.DurationInt(30).seconds).map { ent =>
        val body = ent.data.utf8String
        if (resp.status.isSuccess()) {
          decode[TokenResponse](body).left.map(_.getMessage)
        } else Left(s"Token endpoint ${resp.status}: $body")
      }
    }
  }
}
