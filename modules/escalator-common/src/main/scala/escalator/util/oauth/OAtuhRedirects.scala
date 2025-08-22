package escalator.util.oauth

import org.apache.pekko.http.scaladsl.model.Uri

final case class OAtuhRedirects(
  success: Uri,                             // when everything is persisted
  persistError: Uri,                        // when onFinalized fails
  invalidState: Uri,                        // bad/missing state
  tokenError: String => Uri,                // token endpoint failed
  providerError: String => Uri              // provider sent ?error=...
)