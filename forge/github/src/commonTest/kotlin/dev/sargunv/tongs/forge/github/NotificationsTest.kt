package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.DeviceFlowPoll
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class NotificationsTest {
  private val requests = mutableListOf<HttpRequestData>()

  private fun client(
    handler: (HttpRequestData) -> Triple<HttpStatusCode, String, Map<String, String>>,
  ) =
    HttpClient(
      MockEngine { request ->
        requests += request
        val (status, body, headers) = handler(request)
        respond(
          body,
          status,
          headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()),
        )
      },
    )

  private fun notification(id: String, updatedAt: String, type: String = "PullRequest") =
    """
    {
      "id": "$id",
      "unread": true,
      "reason": "review_requested",
      "updated_at": "$updatedAt",
      "last_read_at": null,
      "subject": {
        "title": "Thread $id",
        "url": "https://api.github.com/repos/octo/hello/pulls/4$id",
        "latest_comment_url": null,
        "type": "$type"
      },
      "repository": {
        "name": "hello",
        "owner": { "login": "octo" },
        "html_url": "https://github.com/octo/hello"
      }
    }
    """

  @Test
  fun sendsTheCursorAndReportsNotModified() = runTest {
    val http = client {
      Triple(HttpStatusCode.NotModified, "", mapOf("X-Poll-Interval" to "60"))
    }
    val source = GitHubNotifications(http, Instance.GitHubDotCom, "token")
    val poll = source.poll("Tue, 28 Oct 2026 10:00:00 GMT")
    assertEquals(InboxPoll.NotModified(60.seconds), poll)
    val request = requests.single()
    assertEquals("Tue, 28 Oct 2026 10:00:00 GMT", request.headers[HttpHeaders.IfModifiedSince])
    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    assertEquals("true", request.url.parameters["all"])
  }

  @Test
  fun followsPagesUpToTheLimit() = runTest {
    val http = client { request ->
      val page = request.url.parameters["page"]?.toInt() ?: 1
      val body = "[${notification("$page", "2026-10-2${8 - page}T00:00:00Z")}]"
      val link = """<https://api.github.com/notifications?all=true&page=${page + 1}>; rel="next""""
      Triple(
        HttpStatusCode.OK,
        body,
        mapOf(
          HttpHeaders.ContentType to "application/json",
          HttpHeaders.Link to link,
          HttpHeaders.LastModified to "Tue, 28 Oct 2026 12:00:00 GMT",
          "X-Poll-Interval" to "60",
        ),
      )
    }
    val source = GitHubNotifications(http, Instance.GitHubDotCom, "token", maxPages = 2)
    val poll = assertIs<InboxPoll.Changed>(source.poll(null))
    assertEquals(listOf("1", "2"), poll.threads.map { it.id })
    assertEquals(false, poll.complete)
    assertEquals("Tue, 28 Oct 2026 12:00:00 GMT", poll.cursor)
    assertEquals(60.seconds, poll.pollInterval)
    assertNull(requests.first().headers[HttpHeaders.IfModifiedSince])

    val thread = poll.threads.first()
    assertEquals(RepoRef("octo", "hello"), thread.repo)
    assertEquals(SubjectType.PullRequest, thread.subject.type)
    assertEquals(41, thread.subject.number)
    assertEquals("https://github.com/octo/hello/pull/41", thread.subject.webUrl)
    assertEquals(NotificationReason.ReviewRequested, thread.reason)
    assertEquals(Instant.parse("2026-10-27T00:00:00Z"), thread.updatedAt)
  }

  @Test
  fun keepsUnknownValues() = runTest {
    val http = client {
      Triple(
        HttpStatusCode.OK,
        "[${notification("1", "2026-10-28T00:00:00Z", type = "WorkflowRun")}]",
        mapOf(HttpHeaders.ContentType to "application/json"),
      )
    }
    val poll = GitHubNotifications(http, Instance.GitHubDotCom, "token").poll(null)
    val thread = assertIs<InboxPoll.Changed>(poll).threads.single()
    assertEquals(SubjectType.Other("WorkflowRun"), thread.subject.type)
    assertEquals("WorkflowRun", thread.subject.type.raw)
    assertNull(thread.subject.number)
    assertEquals(true, poll.complete)
  }

  @Test
  fun marksReadAndDone() = runTest {
    val http = client { Triple(HttpStatusCode.ResetContent, "", emptyMap()) }
    val source = GitHubNotifications(http, Instance.GitHubDotCom, "token")
    source.markRead("7")
    source.markDone("7")
    assertEquals(
      listOf(
        HttpMethod.Patch to "/notifications/threads/7",
        HttpMethod.Delete to "/notifications/threads/7",
      ),
      requests.map { it.method to it.url.encodedPath },
    )
  }

  @Test
  fun derivesCapabilitiesFromTheToken() {
    assertEquals(AuthMethod.ClassicToken, authMethodOf("ghp_x"))
    assertEquals(AuthMethod.OAuth, authMethodOf("gho_x"))
    assertEquals(AuthMethod.ScopedToken, authMethodOf("github_pat_x"))
    assertEquals(
      true,
      Capability.Inbox in capabilities(AuthMethod.ClassicToken, setOf("notifications")),
    )
    assertEquals(true, Capability.Inbox in capabilities(AuthMethod.OAuth, setOf("repo")))
    assertEquals(false, Capability.Inbox in capabilities(AuthMethod.ScopedToken, emptySet()))
    assertEquals(true, grants(setOf("admin:org"), "read:org"))
  }

  @Test
  fun parsesDeviceFlowPolls() {
    assertEquals(
      DeviceFlowPoll.Pending,
      parseDevicePoll(AccessTokenDto(error = "authorization_pending")),
    )
    assertEquals(
      DeviceFlowPoll.SlowDown(15.seconds),
      parseDevicePoll(AccessTokenDto(error = "slow_down", interval = 15)),
    )
    assertEquals(
      DeviceFlowPoll.Granted("gho_x", setOf("repo", "notifications")),
      parseDevicePoll(AccessTokenDto(accessToken = "gho_x", scope = "repo,notifications")),
    )
  }
}
