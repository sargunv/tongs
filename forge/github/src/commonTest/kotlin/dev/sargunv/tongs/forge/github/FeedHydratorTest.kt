package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.FeedCommit
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PushCommits
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SubjectRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class FeedHydratorTest {
  private val repo = RepoRef("octo", "hello")
  private val blocked = PushRef(repo, "main", "before", "blocked")
  private val accessible = PushRef(repo, "main", "before", "accessible")

  @Test
  fun ssoBlockedComparisonPreservesSubjectsAndOtherPushes() = runTest {
    HttpClient(
        MockEngine { request ->
          when {
            request.url.encodedPath == "/graphql" ->
              respond(
                """{ "data": { "rateLimit": { "cost": 1 }, "s0": {
                  "issueOrPullRequest": { "__typename": "Issue", "title": "Still readable",
                    "issueState": "OPEN", "stateReason": null }
                } } }""",
              )
            request.url.encodedPath.endsWith("...blocked") ->
              respond(
                """{ "message": "Resource protected by organization SAML enforcement." }""",
                HttpStatusCode.Forbidden,
                headersOf("X-GitHub-SSO", "required; url=https://github.com/orgs/octo/sso"),
              )
            else ->
              respond(
                """{ "total_commits": 1, "commits": [
                  { "sha": "sha", "commit": { "message": "Readable commit" } }
                ] }""",
              )
          }
        },
      )
      .use { http ->
        val subject = SubjectRef(repo, 7)
        val result =
          GitHubFeedHydrator(http, Instance.GitHubDotCom, "token")
            .hydrate(listOf(subject), listOf(blocked, accessible))

        assertEquals(subject, result.subjects.single().subject)
        assertEquals("Still readable", result.subjects.single().title)
        assertEquals("OPEN", result.subjects.single().state)
        assertEquals(
          listOf(
            PushCommits(blocked, null, null),
            PushCommits(accessible, listOf(FeedCommit("sha", "Readable commit")), 1),
          ),
          result.pushes,
        )
        assertEquals(2, result.requests)
        assertEquals(1, result.points)
      }
  }

  @Test
  fun comparisonRateLimitsStillFailHydration() = runTest {
    HttpClient(
        MockEngine {
          respond(
            """{ "message": "API rate limit exceeded" }""",
            HttpStatusCode.Forbidden,
            headersOf("X-RateLimit-Remaining", "0"),
          )
        },
      )
      .use { http ->
        assertFailsWith<ForgeException.RateLimited> {
          GitHubFeedHydrator(http, Instance.GitHubDotCom, "token")
            .hydrate(emptyList(), listOf(blocked))
        }
      }
  }
}
