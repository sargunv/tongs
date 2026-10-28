package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.SsoAuthorization
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class GitHubErrorsTest {
  private fun failure(
    status: HttpStatusCode,
    vararg headers: Pair<String, String>,
    body: String = "",
  ) = gitHubFailure(status, headers.toMap()::get, body)

  @Test
  fun explainsKnownFailures() {
    assertIs<ForgeException.Unauthorized>(failure(HttpStatusCode.Unauthorized))
    assertIs<ForgeException.RateLimited>(
      failure(HttpStatusCode.Forbidden, "X-RateLimit-Remaining" to "0"),
    )
    assertIs<ForgeException.RateLimited>(failure(HttpStatusCode.TooManyRequests))
    val noScope =
      failure(
        HttpStatusCode.Forbidden,
        "X-Accepted-OAuth-Scopes" to "notifications, repo",
        "X-OAuth-Scopes" to "read:org, gist",
      )
    assertIs<ForgeException.NoAccess>(noScope)
    assertEquals(listOf("notifications", "repo"), noScope.acceptedScopes)
    assertIs<ForgeException.NoAccess>(
      failure(
        HttpStatusCode.Forbidden,
        body = """{"message":"Resource not accessible by personal access token"}""",
      ),
    )
    // A 404 for a token that has the scope is just a 404.
    assertNull(
      failure(
        HttpStatusCode.NotFound,
        "X-Accepted-OAuth-Scopes" to "notifications, repo",
        "X-OAuth-Scopes" to "repo",
      ),
    )
  }

  @Test
  fun readsTheSsoHeader() {
    val url = "https://github.com/orgs/octo-org/sso?authorization_request=abc"
    val required = failure(HttpStatusCode.Forbidden, "X-GitHub-SSO" to "required; url=$url")
    assertIs<ForgeException.SsoRequired>(required)
    assertEquals(SsoAuthorization("octo-org", url), required.authorization)
    assertEquals(
      SsoHeader.PartialResults(listOf(21955855, 20582480)),
      parseSsoHeader("partial-results; organizations=21955855,20582480"),
    )
  }

  @Test
  fun aPollNamesTheOrganizationsSsoHid() = runTest {
    val http =
      HttpClient(
        MockEngine { request ->
          if (request.url.encodedPath == "/organizations/7") {
            respond("""{ "login": "octo-org" }""")
          } else {
            respond(
              "[]",
              headers = headersOf("X-GitHub-SSO" to listOf("partial-results; organizations=7")),
            )
          }
        },
      )
    val poll = GitHubNotifications(http, Instance.GitHubDotCom, "token").poll(null)
    assertIs<InboxPoll.Changed>(poll)
    assertEquals(
      listOf(SsoAuthorization("octo-org", "https://github.com/orgs/octo-org/sso")),
      poll.ssoRequired,
    )
  }

  @Test
  fun aPollThatCantConnectIsOffline() = runTest {
    val http = HttpClient(MockEngine { throw kotlinx.io.IOException("no route") })
    val source = GitHubNotifications(http, Instance.GitHubDotCom, "token")
    kotlin.test.assertFailsWith<ForgeException.Offline> { source.poll(null) }
  }
}
