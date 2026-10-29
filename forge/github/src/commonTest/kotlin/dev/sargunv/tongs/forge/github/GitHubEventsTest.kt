package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.FeedAction
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.FeedPoll
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.RefType
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SubjectRef
import dev.sargunv.tongs.forge.api.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubEventsTest {
  private val requests = mutableListOf<HttpRequestData>()

  private fun client(
    handler: (HttpRequestData) -> Triple<HttpStatusCode, String, Map<String, String>>,
  ) =
    HttpClient(
      MockEngine { request ->
        requests += request
        val (status, body, headers) = handler(request)
        respond(body, status, headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()))
      },
    )

  private fun event(id: String, type: String, payload: String, repo: String = "octo/hello") =
    """
    {
      "id": "$id",
      "type": "$type",
      "actor": {"login": "alice", "avatar_url": "https://avatars.githubusercontent.com/u/1?"},
      "repo": {"name": "$repo"},
      "payload": $payload,
      "public": true,
      "created_at": "2026-10-28T12:00:00Z"
    }
    """

  private fun parse(json: String) =
    parseEvent(Json.parseToJsonElement(json).jsonObject, "https://github.com")!!

  @Test
  fun parsesTrimmedPushAndPullRequestPayloads() {
    val push =
      parse(
        event(
          "1",
          "PushEvent",
          """{"ref": "refs/heads/main", "before": "aaaaaaaaaaaaaaaa", "head": "bbbbbbbbbbbbbbbb", "push_id": 9}""",
        ),
      )
    assertEquals(FeedEventKind.Push, push.kind)
    assertEquals("main", push.ref)
    assertEquals(RefType.Branch, push.refType)
    assertNull(push.commits)
    assertEquals("https://github.com/octo/hello/compare/aaaaaaaaaaaa...bbbbbbbbbbbb", push.webUrl)

    val pr =
      parse(
        event(
          "2",
          "PullRequestEvent",
          """{"action": "merged", "number": 12, "pull_request": {"number": 12, "url": "x"}}""",
        ),
      )
    assertEquals(FeedEventKind.PullRequest, pr.kind)
    assertEquals(FeedAction.Merged, pr.action)
    assertEquals(SubjectType.PullRequest, pr.subject?.type)
    assertEquals(12, pr.subject?.number)
    assertNull(pr.subject?.title)
    assertEquals("https://github.com/octo/hello/pull/12", pr.webUrl)
  }

  @Test
  fun parsesCommentsOnPullRequestsAndOtherKinds() {
    val comment =
      parse(
        event(
          "3",
          "IssueCommentEvent",
          """{"action": "created", "issue": {"number": 4, "title": "Fix it", "state": "open",
            "pull_request": {}, "html_url": "https://github.com/octo/hello/pull/4"},
            "comment": {"body": "> quoted\n\nLooks   good\nto me", "html_url": "https://c"}}""",
        ),
      )
    assertEquals(FeedEventKind.Comment, comment.kind)
    assertEquals(SubjectType.PullRequest, comment.subject?.type)
    assertEquals("Fix it", comment.subject?.title)
    assertEquals("Looks good to me", comment.excerpt)

    val star = parse(event("4", "WatchEvent", """{"action": "started"}"""))
    assertEquals(FeedEventKind.Star, star.kind)
    val release =
      parse(
        event(
          "5",
          "ReleaseEvent",
          """{"action": "published", "release": {"tag_name": "v1", "name": "", "html_url": "https://r"}}""",
        ),
      )
    assertEquals("v1", release.title)
    assertEquals("v1", release.ref)
    val unknown = parse(event("6", "SponsorshipEvent", "{}"))
    assertEquals(FeedEventKind.Other, unknown.kind)
    assertEquals("SponsorshipEvent", unknown.kindRaw)
  }

  @Test
  fun pollsEachStreamConditionallyAndStopsPagingAtWhatItHasSeen() = runTest {
    var round = 0
    val http = client { request ->
      val url = request.url.toString()
      when {
        "/user/orgs" in url ->
          Triple(HttpStatusCode.OK, """[{"login": "corp"}]""", mapOf("ETag" to "orgs-1"))
        request.headers["If-None-Match"] != null ->
          Triple(HttpStatusCode.NotModified, "", mapOf("X-Poll-Interval" to "60"))
        "/events/orgs/corp" in url ->
          Triple(
            HttpStatusCode.Forbidden,
            """{"message": "Resource protected by organization SAML enforcement."}""",
            mapOf(
              "X-GitHub-SSO" to
                "required; url=https://github.com/orgs/corp/sso?authorization_request=1",
            ),
          )
        "received_events" in url && "page=2" in url ->
          Triple(HttpStatusCode.OK, "[${event("r2", "WatchEvent", "{}")}]", emptyMap())
        "received_events" in url -> {
          val events =
            if (round == 0) "[${event("r1", "WatchEvent", "{}")}]"
            else "[${event("r3", "WatchEvent", "{}")}, ${event("r1", "WatchEvent", "{}")}]"
          Triple(
            HttpStatusCode.OK,
            events,
            mapOf(
              "ETag" to "recv-$round",
              "X-Poll-Interval" to "60",
              "Link" to
                "<https://api.github.com/users/alice/received_events?per_page=100&page=2>; rel=\"next\"",
            ),
          )
        }
        else ->
          Triple(
            HttpStatusCode.OK,
            "[${event("o1", "WatchEvent", "{}")}]",
            mapOf("ETag" to "own-1"),
          )
      }
    }
    val source = GitHubEvents(http, Instance.GitHubDotCom, "t", "alice")

    val first = assertIs<FeedPoll.Changed>(source.poll(null))
    // A first poll pages back as far as it may: received has two pages here.
    assertEquals(setOf("r1", "r2", "o1"), first.events.map { it.id }.toSet())
    assertEquals(listOf("corp"), first.ssoRequired.map { it.organization })
    assertEquals(60.seconds, first.pollInterval)
    // Orgs, received twice, own; the org's 403 counts too.
    assertEquals(5, first.requests)

    requests.clear()
    round = 1
    val second = assertIs<FeedPoll.NotModified>(source.poll(first.cursor))
    assertEquals(0, second.requests)
    assertTrue(requests.none { "/user/orgs" in it.url.toString() })

    // A changed stream stops at the first page holding an event it has seen.
    requests.clear()
    val cursorWithoutEtag = first.cursor!!.replace("\"etag\":\"recv-0\",", "")
    val third = assertIs<FeedPoll.Changed>(source.poll(cursorWithoutEtag))
    assertEquals(listOf("r3", "r1"), third.events.map { it.id })
    assertTrue(requests.none { "page=2" in it.url.toString() })
  }

  @Test
  fun mergesAllStreamsPagesOrganizationsAndHonorsTheLargestInterval() = runTest {
    val http = client { request ->
      val url = request.url
      when {
        request.headers["If-None-Match"] != null ->
          Triple(HttpStatusCode.NotModified, "", mapOf("X-Poll-Interval" to "90"))
        url.encodedPath == "/user/orgs" && url.parameters["page"] == "2" ->
          Triple(HttpStatusCode.OK, """[{"login":"second"}]""", emptyMap())
        url.encodedPath == "/user/orgs" ->
          Triple(
            HttpStatusCode.OK,
            """[{"login":"first"}]""",
            mapOf(
              "ETag" to "orgs",
              "Link" to "<https://api.github.com/user/orgs?per_page=100&page=2>; rel=\"next\"",
            ),
          )
        else -> {
          val id =
            when {
              url.encodedPath.endsWith("/events") -> "own"
              url.encodedPath.endsWith("/orgs/second") -> "organization"
              else -> "shared"
            }
          Triple(
            HttpStatusCode.OK,
            "[${event(id, "WatchEvent", "{}")}]",
            mapOf(
              "ETag" to url.encodedPath,
              "X-Poll-Interval" to if (id == "organization") "90" else "60",
            ),
          )
        }
      }
    }
    val source = GitHubEvents(http, Instance.GitHubDotCom, "t", "alice")
    val first = assertIs<FeedPoll.Changed>(source.poll(null))
    assertEquals(setOf("shared", "own", "organization"), first.events.map { it.id }.toSet())
    assertEquals(6, first.requests)
    assertEquals(90.seconds, first.pollInterval)
    val second = assertIs<FeedPoll.NotModified>(source.poll(first.cursor))
    assertEquals(0, second.requests)
  }

  @Test
  fun aTokenTheEventsApiRefusesIsNoAccess() = runTest {
    val http = client { request ->
      if ("/user/orgs" in request.url.toString()) Triple(HttpStatusCode.OK, "[]", emptyMap())
      else
        Triple(
          HttpStatusCode.Forbidden,
          """{"message": "Resource not accessible by personal access token"}""",
          emptyMap(),
        )
    }
    val e =
      assertFailsWith<ForgeException.NoAccess> {
        GitHubEvents(http, Instance.GitHubDotCom, "t", "alice").poll(null)
      }
    assertEquals("This token can't read activity", e.message)
  }

  @Test
  fun batchesSubjectStateAndComparesPushesExactly() = runTest {
    val http = client { request ->
      val url = request.url.toString()
      if (url.endsWith("/graphql")) {
        val body = (request.body as io.ktor.http.content.TextContent).text
        val query = Json.parseToJsonElement(body).jsonObject["query"]!!.jsonPrimitive.content
        assertTrue("s0: repository(owner: \$owner_s0" in query, query)
        assertTrue("p1: repository(owner: \$owner_p1" in query, query)
        Triple(
          HttpStatusCode.OK,
          """
          {"data": {
            "rateLimit": {"cost": 1},
            "s0": {"issueOrPullRequest": {"__typename": "PullRequest", "title": "Add it", "prState": "MERGED", "isDraft": false}},
            "p1": {"defaultBranchRef": {"name": "main"}, "object": {"__typename": "Commit", "oid": "new", "messageHeadline": "New branch"}}
          }}
          """,
          emptyMap(),
        )
      } else {
        assertTrue("/compare/c1...c3" in url || "/compare/main...new" in url, url)
        Triple(
          HttpStatusCode.OK,
          """{"total_commits": 3, "commits": [
            {"sha": "c2", "commit": {"message": "Second\n\nbody"}},
            {"sha": "side", "commit": {"message": "Older merged work"}},
            {"sha": "c3", "commit": {"message": "Third"}}], "files": []}""",
          emptyMap(),
        )
      }
    }
    val repo = RepoRef("octo", "hello")
    val hydration =
      GitHubFeedHydrator(http, Instance.GitHubDotCom, "t")
        .hydrate(
          listOf(SubjectRef(repo, 7)),
          listOf(PushRef(repo, "main", "c1", "c3"), PushRef(repo, "feature", ZERO_SHA, "new")),
        )

    assertEquals("MERGED", hydration.subjects.single().state)
    assertEquals("Add it", hydration.subjects.single().title)
    for (push in hydration.pushes) {
      assertEquals(listOf("Third", "Older merged work", "Second"), push.commits?.map { it.title })
      assertEquals(3, push.count)
    }
    assertEquals(1, hydration.points)
    assertEquals(2, hydration.requests)
  }

  @Test
  fun longPushesShowTheirNewestPageAndCountBothRequests() = runTest {
    val http = client { request ->
      val last = request.url.parameters["page"] == "3"
      Triple(
        HttpStatusCode.OK,
        """{"total_commits": 201, "commits": [
        {"sha": "sha", "commit": {"message": "${if (last) "Newest" else "Oldest"}"}}
      ]}""",
        emptyMap(),
      )
    }
    val repo = RepoRef("octo", "hello")
    val hydration =
      GitHubFeedHydrator(http, Instance.GitHubDotCom, "t")
        .hydrate(emptyList(), listOf(PushRef(repo, "main", "base", "head")))
    assertEquals(2, hydration.requests)
    assertEquals(201, hydration.pushes.single().count)
    assertEquals("Newest", hydration.pushes.single().commits?.single()?.title)
  }

  @Test
  fun limitsCompareCostAndNeedsNoGraphQlForOrdinaryPushes() = runTest {
    val http = client { request ->
      assertTrue("/compare/base..." in request.url.toString())
      Triple(HttpStatusCode.OK, """{"total_commits": 0, "commits": []}""", emptyMap())
    }
    val repo = RepoRef("octo", "hello")
    val hydration =
      GitHubFeedHydrator(http, Instance.GitHubDotCom, "t", compareLimit = 2)
        .hydrate(emptyList(), (1..5).map { PushRef(repo, "main", "base", "head$it") })
    assertEquals(2, hydration.requests)
    assertEquals(2, hydration.pushes.size)
    assertNull(hydration.points)
    assertEquals(2, requests.size)
  }
}
