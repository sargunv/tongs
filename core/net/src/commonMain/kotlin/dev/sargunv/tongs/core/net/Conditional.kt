package dev.sargunv.tongs.core.net

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a server said identifies a response's version, to send back on the next request. GitHub
 * doesn't count a 304 against the rate limit.
 */
data class Validators(val lastModified: String? = null, val etag: String? = null)

/** Makes the request conditional on the resource having changed since [validators]. */
fun HttpRequestBuilder.ifChangedSince(validators: Validators?) {
  validators?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
  validators?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
}

val HttpResponse.validators: Validators
  get() = Validators(headers[HttpHeaders.LastModified], headers[HttpHeaders.ETag])

val HttpResponse.isNotModified: Boolean
  get() = status == HttpStatusCode.NotModified

/** GitHub's `X-Poll-Interval`: how long it asks clients to wait before polling again. */
val HttpResponse.pollInterval: Duration?
  get() = headers["X-Poll-Interval"]?.toLongOrNull()?.seconds

/** The `rel="next"` URL from a `Link` header, for paged APIs. */
val HttpResponse.nextPageUrl: String?
  get() = parseLinks(headers[HttpHeaders.Link])["next"]

internal fun parseLinks(header: String?): Map<String, String> {
  if (header == null) return emptyMap()
  return header
    .split(',')
    .mapNotNull { part ->
      val url = part.substringAfter('<', "").substringBefore('>', "")
      val rel = Regex("""rel="([^"]+)"""").find(part)?.groupValues?.get(1)
      if (url.isEmpty() || rel == null) null else rel to url
    }
    .toMap()
}

/** Rate limit headers, as GitHub and Forgejo send them. */
data class RateLimit(val limit: Int, val remaining: Int, val resetEpochSeconds: Long)

val HttpResponse.rateLimit: RateLimit?
  get() {
    val limit = headers["X-RateLimit-Limit"]?.toIntOrNull() ?: return null
    val remaining = headers["X-RateLimit-Remaining"]?.toIntOrNull() ?: return null
    val reset = headers["X-RateLimit-Reset"]?.toLongOrNull() ?: return null
    return RateLimit(limit, remaining, reset)
  }

/** A failed HTTP request, with the start of its body for the message. */
class HttpException(val status: HttpStatusCode, what: String, body: String) :
  Exception("$what: $status ${body.take(200)}")
