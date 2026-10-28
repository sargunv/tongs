package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.Subject
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.ThreadHydration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InboxHydratorTest {
  private val bodies = mutableListOf<String>()

  private fun hydrator(
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Map<String, String> = emptyMap(),
    respond: (String) -> String,
  ) =
    GitHubHydrator(
      HttpClient(
        MockEngine { request ->
          val body = (request.body as TextContent).text
          bodies += body
          respond(
            respond(body),
            status,
            headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()),
          )
        },
      ),
      Instance.GitHubDotCom,
      "token",
      viewer = "me",
      batchSize = 2,
    )

  private fun thread(
    id: String,
    number: Int?,
    type: SubjectType = SubjectType.PullRequest,
    lastReadAt: String? = "2026-10-28T10:00:00Z",
    unread: Boolean = true,
  ) =
    InboxThread(
      id = id,
      repo = RepoRef("octo", "hello"),
      subject = Subject("Thread $id", type, number, null),
      reason = NotificationReason.Author,
      unread = unread,
      updatedAt = Instant.parse("2026-10-28T12:00:00Z"),
      lastReadAt = lastReadAt?.let(Instant::parse),
    )

  private val pullRequest =
    """
    { "repository": { "issueOrPullRequest": {
      "__typename": "PullRequest", "prState": "OPEN", "isDraft": false,
      "reviewDecision": "CHANGES_REQUESTED",
      "commits": { "nodes": [ { "commit": {
        "committedDate": "2026-10-28T11:00:00Z", "statusCheckRollup": { "state": "FAILURE" } } } ] },
      "prTimeline": { "nodes": [
        { "__typename": "PullRequestCommit", "commit": { "author": { "user": { "login": "al" } } } },
        { "__typename": "PullRequestReview", "state": "CHANGES_REQUESTED", "author": { "login": "bo" } },
        { "__typename": "IssueComment", "author": { "login": "me" } }
      ] } } } }
    """

  private val issue =
    """
    { "repository": { "issueOrPullRequest": {
      "__typename": "Issue", "issueState": "CLOSED", "stateReason": "COMPLETED",
      "issueTimeline": { "nodes": [
        { "__typename": "ClosedEvent", "actor": { "login": "cy" } }
      ] } } } }
    """

  @Test
  fun batchesThreadsUnderAliasesAndParsesEach() = runTest {
    val hydrator = hydrator { body ->
      val data =
        if ("t1:" in body) """"t0": ${pullRequest.repo()}, "t1": ${issue.repo()}"""
        else """"t0": ${pullRequest.repo()}"""
      """{ "data": { "rateLimit": { "cost": 1, "remaining": 4999, "resetAt": "x" }, $data } }"""
    }
    val threads =
      listOf(
        thread("1", 41),
        thread("2", 42, SubjectType.Issue, lastReadAt = null),
        thread("3", 43),
        thread("4", null, SubjectType.Release),
      )
    val result = hydrator.hydrate(threads)

    // Two batches of two (the release has nothing to hydrate), one point each.
    assertEquals(2, bodies.size)
    assertEquals(2, result.cost)
    val first = Json.parseToJsonElement(bodies.first { "t1:" in it }).jsonObject
    val query = first["query"]!!.jsonPrimitive.content
    assertTrue("t0: repository(owner: \$owner_0, name: \$name_0)" in query, query)
    assertTrue("t1: repository(owner: \$owner_1, name: \$name_1)" in query, query)
    assertTrue("rateLimit { cost" in query)
    val variables = first["variables"]!!.jsonObject
    assertEquals("2026-10-28T10:00:00Z", variables["since_0"]!!.jsonPrimitive.content)
    assertEquals("null", variables["since_1"].toString())
    assertEquals("42", variables["number_1"].toString())

    val byId = result.threads.associateBy { it.threadId }
    assertEquals(
      ThreadHydration(
        threadId = "1",
        subjectState = "OPEN",
        checks = "FAILURE",
        reviewDecision = "CHANGES_REQUESTED",
        whatsNew = "Changes requested by bo, checks failed, 1 new commit",
      ),
      byId["1"],
    )
    assertEquals(
      ThreadHydration(
        "2",
        subjectState = "CLOSED",
        stateReason = "COMPLETED",
        whatsNew = "Closed by cy",
      ),
      byId["2"],
    )
    assertEquals(ThreadHydration("4"), byId["4"])
    assertEquals(setOf("1", "2", "3", "4"), byId.keys)
  }

  @Test
  fun failingChecksAreNewsOnlyOnAnUnseenCommit() = runTest {
    val hydrator = hydrator {
      """{ "data": { "rateLimit": { "cost": 1 }, "t0": ${pullRequest.repo()} } }"""
    }
    // Read after the head commit: its checks are old news.
    val seen = hydrator.hydrate(listOf(thread("1", 41, lastReadAt = "2026-10-28T11:30:00Z")))
    assertEquals("Changes requested by bo, 1 new commit", seen.threads.single().whatsNew)
  }

  @Test
  fun aThreadGitHubCantResolveComesBackEmpty() = runTest {
    val hydrator = hydrator {
      """{ "data": { "rateLimit": { "cost": 1 }, "t0": null, "t1": ${issue.repo()} },
         "errors": [ { "type": "NOT_FOUND", "path": ["t0"] } ] }"""
    }
    val result = hydrator.hydrate(listOf(thread("1", 41), thread("2", 42, SubjectType.Issue)))
    assertEquals(ThreadHydration("1"), result.threads.first { it.threadId == "1" })
    assertEquals("CLOSED", result.threads.first { it.threadId == "2" }.subjectState)
  }

  @Test
  fun reportsRateLimits() = runTest {
    val limited =
      hydrator(
        HttpStatusCode.Forbidden,
        mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1793224369"),
      ) {
        """{ "message": "API rate limit exceeded" }"""
      }
    val e = assertFailsWith<ForgeException.RateLimited> { limited.hydrate(listOf(thread("1", 41))) }
    assertEquals(Instant.fromEpochSeconds(1793224369), e.resetAt)

    val graphQlLimited = hydrator {
      """{ "errors": [ { "type": "RATE_LIMITED", "message": "API rate limit exceeded" } ] }"""
    }
    assertFailsWith<ForgeException.RateLimited> { graphQlLimited.hydrate(listOf(thread("1", 41))) }
  }

  /** The `repository` object out of a one-thread `data`. */
  private fun String.repo() = Json.parseToJsonElement(this).jsonObject["repository"].toString()
}
