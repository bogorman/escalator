package escalator.util.oauth

import java.security.SecureRandom
import java.util.Base64
import scala.util.Random
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.Uri.Query
import scala.util.chaining._

object OAuthCryptoUtil {
  private val rng = new SecureRandom()

  def randomString(len: Int = 32): String = {
    val bytes = new Array[Byte](len)
    rng.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def pkcePair(): (String /*verifier*/, String /*challenge*/) = {
    val verifier  = randomString(64)
    val challenge = java.security.MessageDigest.getInstance("SHA-256")
      .digest(verifier.getBytes("US-ASCII"))
      .pipe(b => Base64.getUrlEncoder.withoutPadding().encodeToString(b))
    (verifier, challenge)
  }
}

object OAuthUriUtil {
  def addParams(uri: Uri, params: Map[String,String]): Uri = uri.withQuery(Query(uri.query().toMap ++ params))
}
