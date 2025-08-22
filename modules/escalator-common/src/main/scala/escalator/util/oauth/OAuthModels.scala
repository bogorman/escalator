package escalator.util.oauth

import org.apache.pekko.http.scaladsl.model.Uri

sealed trait OAuthProvider {
  def name: String
}

final case class OAuth2Provider(
  name: String,
  authUri: Uri,
  tokenUri: Uri,
  clientId: String,
  clientSecret: String,
  redirectUri: Uri,
  scope: Seq[String],
  usePkce: Boolean = true,
  extraAuthParams: Map[String, String] = Map.empty,
  extraTokenParams: Map[String, String] = Map.empty
) extends OAuthProvider

// Twitter legacy OAuth1a (optional)
final case class OAuth1Provider(
  name: String,
  requestTokenUri: Uri,
  authUri: Uri,
  accessTokenUri: Uri,
  consumerKey: String,
  consumerSecret: String,
  callbackUri: Uri
) extends OAuthProvider

final case class TokenResponse(
  access_token: String,
  token_type: Option[String],
  scope: Option[String],
  refresh_token: Option[String],
  expires_in: Option[Long],
  id_token: Option[String]
)

final case class OAuthUserLogin(
  userModel: Option[Any],
  success: Boolean,
  message: String
)