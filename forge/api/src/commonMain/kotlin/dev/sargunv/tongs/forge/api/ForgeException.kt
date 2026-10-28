package dev.sargunv.tongs.forge.api

import kotlin.time.Instant

/**
 * A forge call that failed for a reason the UI can explain and act on. Adapters throw these where
 * they can tell the cause; anything else is a plain exception with a message.
 */
sealed class ForgeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** The forge couldn't be reached: no network, DNS, or a timeout. */
  class Offline(cause: Throwable) : ForgeException("Can't reach the server", cause)

  /** The forge no longer accepts the token: revoked, expired, or deleted. */
  class Unauthorized(message: String) : ForgeException(message)

  /**
   * The token works but can't do this, as when a GitHub token lacks the `notifications` scope or is
   * fine-grained. [acceptedScopes] are the scopes that would allow it, if the forge says.
   */
  class NoAccess(message: String, val acceptedScopes: List<String> = emptyList()) :
    ForgeException(message)

  /** Out of rate limit until [resetAt], if the forge says when. */
  class RateLimited(val resetAt: Instant?) : ForgeException("Rate limited")

  /** An organization requires SAML single sign-on, and the token isn't authorized for it yet. */
  class SsoRequired(val authorization: SsoAuthorization) :
    ForgeException("SAML SSO authorization required")
}

/** Where the user authorizes their token for an organization's SAML single sign-on. */
data class SsoAuthorization(
  /** The organization's login, if known. */
  val organization: String?,
  val url: String,
)
