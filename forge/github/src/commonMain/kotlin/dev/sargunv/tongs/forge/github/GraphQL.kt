package dev.sargunv.tongs.forge.github

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpMethod
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.network.http.HttpEngine
import dev.sargunv.tongs.forge.api.Instance
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import okio.Buffer

/**
 * An Apollo client for an instance's GraphQL API, over the app's Ktor client. Room is the store, so
 * there's no normalized cache.
 */
internal fun gitHubApollo(http: HttpClient, instance: Instance, token: String): ApolloClient =
  ApolloClient.Builder()
    .serverUrl(graphQlUrl(instance))
    .httpEngine(KtorHttpEngine(http))
    .addHttpHeader(HttpHeaders.Authorization, "Bearer $token")
    .build()

/** `api.github.com/graphql` on github.com, `<host>/api/graphql` on GitHub Enterprise Server. */
internal fun graphQlUrl(instance: Instance): String =
  if (instance.apiUrl.endsWith("/api/v3")) instance.apiUrl.removeSuffix("/v3") + "/graphql"
  else instance.apiUrl + "/graphql"

/** Sends Apollo's requests through Ktor, so GraphQL shares the app's connections and config. */
private class KtorHttpEngine(private val http: HttpClient) : HttpEngine {
  override suspend fun execute(request: HttpRequest): HttpResponse {
    val response =
      try {
        http.request(Url(request.url)) {
          method =
            when (request.method) {
              HttpMethod.Get -> io.ktor.http.HttpMethod.Get
              HttpMethod.Post -> io.ktor.http.HttpMethod.Post
              HttpMethod.Query -> io.ktor.http.HttpMethod("QUERY")
            }
          // Ktor sets the body's own headers from the content.
          for (h in request.headers) {
            if (h.name.equals(HttpHeaders.ContentType, ignoreCase = true)) continue
            if (h.name.equals(HttpHeaders.ContentLength, ignoreCase = true)) continue
            header(h.name, h.value)
          }
          request.body?.let { body ->
            val bytes = Buffer().also(body::writeTo).readByteArray()
            setBody(ByteArrayContent(bytes, ContentType.parse(body.contentType)))
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw ApolloNetworkException("GraphQL request failed: ${e.message}", e)
      }
    return HttpResponse.Builder(response.status.value)
      .apply {
        response.headers.forEach { name, values -> values.forEach { addHeader(name, it) } }
      }
      .body(Buffer().write(response.readRawBytes()))
      .build()
  }
}
