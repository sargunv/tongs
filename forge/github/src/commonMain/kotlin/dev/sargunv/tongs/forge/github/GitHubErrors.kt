package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.HttpException
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.SsoAuthorization
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant

/**
 * Throws the [ForgeException] that explains a failed GitHub response, or an [HttpException] with
 * its status and body. A 304 isn't a failure.
 */
internal suspend fun HttpResponse.throwIfFailed(what: String) {
  if (status.value in 200..299 || status == HttpStatusCode.NotModified) return
  val body = bodyAsText()
  throw gitHubFailure(status, headers::get, body) ?: HttpException(status, what, body)
}

internal val HttpResponse.rateLimitReset: Instant?
  get() = headers["X-RateLimit-Reset"]?.toLongOrNull()?.let(Instant::fromEpochSeconds)

/** The [ForgeException] for a failed GitHub response, if it's one the app can explain. */
internal fun gitHubFailure(
  status: HttpStatusCode,
  header: (String) -> String?,
  body: String,
): ForgeException? {
  val sso = parseSsoHeader(header("X-GitHub-SSO"))
  if (sso is SsoHeader.Required) return ForgeException.SsoRequired(sso.authorization)
  val remaining = header("X-RateLimit-Remaining")?.toIntOrNull()
  val reset = header("X-RateLimit-Reset")?.toLongOrNull()?.let(Instant::fromEpochSeconds)
  val limited =
    status == HttpStatusCode.TooManyRequests ||
      (status == HttpStatusCode.Forbidden &&
        (remaining == 0 || header("Retry-After") != null || "rate limit" in body.lowercase()))
  if (limited) return ForgeException.RateLimited(reset)
  if (status == HttpStatusCode.Unauthorized) {
    return ForgeException.Unauthorized("GitHub no longer accepts this account's token")
  }
  if (status == HttpStatusCode.Forbidden || status == HttpStatusCode.NotFound) {
    val accepted = header("X-Accepted-OAuth-Scopes").scopes()
    val granted = header("X-OAuth-Scopes")?.scopes()
    // Fine-grained tokens and GitHub App tokens can't read notifications at all.
    val fineGrained = "not accessible by" in body
    val lacksScope = granted != null && accepted.isNotEmpty() && accepted.none { it in granted }
    if (fineGrained || lacksScope) {
      return ForgeException.NoAccess("This token can't read notifications", accepted)
    }
  }
  return null
}

private fun String?.scopes(): List<String> =
  this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** GitHub's `X-GitHub-SSO` header, which says when SAML single sign-on stands in the way. */
internal sealed interface SsoHeader {
  /** `required; url=…`: this request needs the token authorized at [authorization]'s URL. */
  data class Required(val authorization: SsoAuthorization) : SsoHeader

  /**
   * `partial-results; organizations=1,2`: a list left out what these organizations, by ID, hide
   * until the token is authorized for them.
   */
  data class PartialResults(val organizationIds: List<Long>) : SsoHeader
}

internal fun parseSsoHeader(value: String?): SsoHeader? {
  if (value == null) return null
  val parts = value.split(';').map { it.trim() }
  val params =
    parts.drop(1).associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
  return when (parts.first()) {
    "required" -> {
      val url = params["url"] ?: return null
      // https://github.com/orgs/<org>/sso?authorization_request=…
      val org = Regex("""/orgs/([^/?]+)/sso""").find(url)?.groupValues?.get(1)
      SsoHeader.Required(SsoAuthorization(org, url))
    }
    "partial-results" ->
      SsoHeader.PartialResults(
        params["organizations"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() },
      )
    else -> null
  }
}
