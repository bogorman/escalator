package escalator.util.oauth

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.Uri

object OAuthProviders {
  private lazy val conf = ConfigFactory.load()

  // val google = OAuth2Provider(
  //   name        = "google",
  //   authUri     = Uri("https://accounts.google.com/o/oauth2/v2/auth"),
  //   tokenUri    = Uri("https://oauth2.googleapis.com/token"),
  //   clientId    = conf.getString("oauth.google.client-id"),
  //   clientSecret= conf.getString("oauth.google.client-secret"),
  //   redirectUri = Uri(conf.getString("oauth.redirect-base")) withPath Uri.Path("/oauth/callback/google"),
  //   scope       = Seq("openid","email","profile"),
  //   usePkce     = true
  // )

  // val facebook = OAuth2Provider(
  //   "facebook",
  //   Uri("https://www.facebook.com/v20.0/dialog/oauth"),
  //   Uri("https://graph.facebook.com/v20.0/oauth/access_token"),
  //   conf.getString("oauth.facebook.client-id"),
  //   conf.getString("oauth.facebook.client-secret"),
  //   Uri(conf.getString("oauth.redirect-base")) withPath Uri.Path("/oauth/callback/facebook"),
  //   Seq("public_profile","email"),
  //   usePkce = false
  // )

  val discord = OAuth2Provider(
    "discord",
    Uri("https://discord.com/api/oauth2/authorize"),
    Uri("https://discord.com/api/oauth2/token"),
    conf.getString("discord.client-id"),
    conf.getString("discord.client-secret"),
    // Uri(conf.getString("discord.redirect-base")) withPath Uri.Path("/oauth/callback/discord"),
    Uri(conf.getString("discord.redirect-uri")),
    Seq("identify","email", "guilds.join"),
    usePkce = true
  )

  // val twitter2 = OAuth2Provider( // Twitter OAuth2
  //   "twitter",
  //   Uri("https://twitter.com/i/oauth2/authorize"),
  //   Uri("https://api.twitter.com/2/oauth2/token"),
  //   conf.getString("oauth.twitter.client-id"),
  //   conf.getString("oauth.twitter.client-secret"),
  //   Uri(conf.getString("oauth.redirect-base")) withPath Uri.Path("/oauth/callback/twitter"),
  //   Seq("tweet.read","users.read","offline.access"),
  //   usePkce = true,
  //   extraTokenParams = Map("client_secret" -> conf.getString("oauth.twitter.client-secret"))
  // )

  // Optional OAuth1a:
  // val twitter1 = OAuth1Provider(...)
  // google, facebook, discord, twitter2
  val supported: Map[String, OAuthProvider] = Seq(discord).map(p => p.name -> p).toMap
}
