package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.DraftReviewComment
import dev.sargunv.tongs.forge.api.FileViewedState
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PendingReview
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.sargunv.tongs.forge.api.ReviewSubmission
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The adapter against a fake GitHub: no request here reaches the real one. */
class PullRequestReviewsTest {
  private val requests = mutableListOf<Pair<HttpRequestData, String>>()
  private val repo = RepoRef("octo", "hello")

  private fun reviews(respond: (HttpRequestData, String) -> Pair<HttpStatusCode, String>) =
    GitHubPullRequestReviews(
      HttpClient(
        MockEngine { request ->
          val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
          requests += request to body.orEmpty()
          val (status, text) = respond(request, body.orEmpty())
          respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
      ),
      Instance.GitHubDotCom,
      "t0ken",
    )

  private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

  @Test
  fun lowersAnchorsToLineAndSide() {
    val single = ReviewAnchor("abc", "src/a.kt", DiffSide.New, 12, 12)
    assertEquals(GitHubCommentPosition("src/a.kt", 12, "RIGHT"), single.toGitHub())
    val range = ReviewAnchor("abc", "src/a.kt", DiffSide.Old, 3, 7)
    assertEquals(GitHubCommentPosition("src/a.kt", 7, "LEFT", 3, "LEFT"), range.toGitHub())
    // From removed lines into added ones.
    val mixed = ReviewAnchor("abc", "src/a.kt", DiffSide.New, 4, 5, startSide = DiffSide.Old)
    assertEquals(GitHubCommentPosition("src/a.kt", 5, "RIGHT", 4, "LEFT"), mixed.toGitHub())
  }

  @Test
  fun submitsEveryDraftInOneReview() = runTest {
    val reviews = reviews { _, _ -> HttpStatusCode.OK to """{"id": 1}""" }
    reviews.submitReview(
      repo,
      7,
      ReviewSubmission(
        commitOid = "headsha",
        event = ReviewEvent.RequestChanges,
        body = "A few things.",
        comments =
          listOf(
            DraftReviewComment(ReviewAnchor("headsha", "a.kt", DiffSide.New, 2, 2), "Rename this"),
            DraftReviewComment(
              ReviewAnchor("headsha", "b.kt", DiffSide.New, 4, 6),
              "```suggestion\nfoo()\n```",
            ),
          ),
      ),
    )
    val (request, body) = requests.single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("https://api.github.com/repos/octo/hello/pulls/7/reviews", request.url.toString())
    assertEquals("Bearer t0ken", request.headers[HttpHeaders.Authorization])
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals("headsha", json.str("commit_id"))
    assertEquals("REQUEST_CHANGES", json.str("event"))
    assertEquals("A few things.", json.str("body"))
    assertEquals(
      Json.parseToJsonElement(
        """
        [
          {"path": "a.kt", "body": "Rename this", "line": 2, "side": "RIGHT"},
          {"path": "b.kt", "body": "```suggestion\nfoo()\n```", "line": 6, "side": "RIGHT",
           "start_line": 4, "start_side": "RIGHT"}
        ]
        """,
      ),
      json.getValue("comments"),
    )
  }

  @Test
  fun leavesOutAnEmptySummary() = runTest {
    val reviews = reviews { _, _ -> HttpStatusCode.OK to "{}" }
    reviews.submitReview(repo, 7, ReviewSubmission("sha", ReviewEvent.Approve, " ", emptyList()))
    val json = Json.parseToJsonElement(requests.single().second).jsonObject
    assertEquals("APPROVE", json.str("event"))
    assertTrue("body" !in json)
  }

  @Test
  fun surfacesARejectedReview() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.UnprocessableEntity to """{"message": "Line could not be resolved"}"""
    }
    val error =
      assertFailsWith<Exception> {
        reviews.submitReview(
          repo,
          7,
          ReviewSubmission("sha", ReviewEvent.Comment, "x", emptyList()),
        )
      }
    assertTrue("Line could not be resolved" in error.message.orEmpty(), error.message)
  }

  @Test
  fun marksAndUnmarksViewedThroughGraphQl() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.OK to
        """{"data": {"markFileAsViewed": {"clientMutationId": null},
        "unmarkFileAsViewed": {"clientMutationId": null}}}"""
    }
    reviews.setViewed("PR_1", "src/a.kt", viewed = true)
    reviews.setViewed("PR_1", "src/a.kt", viewed = false)
    val (mark, unmark) = requests.map { Json.parseToJsonElement(it.second).jsonObject }
    assertEquals("https://api.github.com/graphql", requests[0].first.url.toString())
    assertEquals("MarkFileAsViewed", mark.str("operationName"))
    assertEquals("UnmarkFileAsViewed", unmark.str("operationName"))
    val variables = mark.getValue("variables").jsonObject
    assertEquals("PR_1", variables.str("pullRequestId"))
    assertEquals("src/a.kt", variables.str("path"))
  }

  @Test
  fun readsThreadsAndViewedStateAcrossPages() = runTest {
    val reviews = reviews { _, body ->
      val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
      val second = "after" in variables
      when {
        "PullRequestReviewThreads" in body ->
          HttpStatusCode.OK to
            """{"data": {"repository": {"pullRequest": {"reviewThreads": {
              "pageInfo": {"hasNextPage": false, "endCursor": null},
              "nodes": [{"id": "T1", "path": "a.kt", "diffSide": "LEFT", "startDiffSide": null,
                "line": 3, "startLine": null, "originalLine": 3, "isResolved": true,
                "isOutdated": false, "subjectType": "LINE",
                "viewerCanReply": true, "viewerCanResolve": false, "viewerCanUnresolve": true,
                "resolvedBy": {"login": "hubot"},
                "comments": {"pageInfo": {"hasNextPage": false, "endCursor": null}, "nodes": [{"id": "C1", "body": "Why?",
                  "createdAt": "2026-10-28T12:00:00Z", "state": "PENDING",
                  "author": {"__typename": "User", "login": "octocat", "avatarUrl": "https://a"}}]}}]
            }}}}}"""
        else ->
          HttpStatusCode.OK to
            """{"data": {"repository": {"pullRequest": {"files": {
              "pageInfo": {"hasNextPage": ${!second}, "endCursor": ${if (second) "null" else "\"c1\""}},
              "nodes": [{"path": "${if (second) "b.kt" else "a.kt"}",
                "viewerViewedState": "${if (second) "DISMISSED" else "VIEWED"}"}]
            }}}}}"""
      }
    }
    val thread = reviews.threads(repo, 7).single()
    assertEquals(DiffSide.Old, thread.side)
    assertEquals(3, thread.line)
    assertTrue(thread.isResolved)
    assertEquals("hubot", thread.resolvedBy)
    assertTrue(thread.viewerCanReply && thread.viewerCanUnresolve && !thread.viewerCanResolve)
    assertEquals("octocat", thread.comments.single().authorLogin)
    assertTrue(thread.comments.single().pending)
    assertEquals(
      mapOf("a.kt" to FileViewedState.Viewed, "b.kt" to FileViewedState.Dismissed),
      reviews.viewedFiles(repo, 7),
    )
  }

  private fun graphql(i: Int) = Json.parseToJsonElement(requests[i].second).jsonObject

  @Test
  fun findsTheViewersPendingReview() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.OK to
        """{"data": {"repository": {"pullRequest": {"id": "PR_1", "title": "T", "state": "OPEN",
          "url": "https://github.com/octo/hello/pull/7", "headRefOid": "abc",
          "viewerDidAuthor": false, "author": null, "viewerLatestReviewRequest": null,
          "reviews": {"nodes": [{"id": "R_1", "body": "Half done", "viewerDidAuthor": true,
            "comments": {"totalCount": 2}}]}}}}}"""
    }
    assertEquals(PendingReview("R_1", "Half done", 2), reviews.overview(repo, 7).pendingReview)
  }

  @Test
  fun repliesDirectlyOrIntoAPendingReview() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.OK to
        """{"data": {"addPullRequestReviewThreadReply": {"comment": {"id": "C9", "body": "Done",
          "createdAt": "2026-10-28T12:00:00Z", "state": "SUBMITTED",
          "author": {"__typename": "User", "login": "me", "avatarUrl": "https://a"}}}}}"""
    }
    val comment = reviews.reply("T1", "Done")
    assertEquals("C9", comment.id)
    assertFalse(comment.pending)
    reviews.reply("T1", "Done", pendingReviewId = "R_1")
    val direct = graphql(0).getValue("variables").jsonObject
    assertEquals("ReplyToReviewThread", graphql(0).str("operationName"))
    assertEquals("T1", direct.str("threadId"))
    assertTrue("reviewId" !in direct)
    assertEquals("R_1", graphql(1).getValue("variables").jsonObject.str("reviewId"))
  }

  @Test
  fun resolvesAndUnresolves() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.OK to
        """{"data": {"resolveReviewThread": {"thread": {"id": "T1", "isResolved": true}},
          "unresolveReviewThread": {"thread": {"id": "T1", "isResolved": false}}}}"""
    }
    reviews.setResolved("T1", true)
    reviews.setResolved("T1", false)
    assertEquals("ResolveReviewThread", graphql(0).str("operationName"))
    assertEquals("UnresolveReviewThread", graphql(1).str("operationName"))
  }

  @Test
  fun buildsUpAndSubmitsAPendingReview() = runTest {
    val reviews = reviews { _, body ->
      HttpStatusCode.OK to
        when {
          "StartPendingReview" in body ->
            """{"data": {"addPullRequestReview": {"pullRequestReview": {"id": "R_2"}}}}"""
          "AddPendingReviewThread" in body ->
            """{"data": {"addPullRequestReviewThread": {"thread": {"id": "T2"}}}}"""
          "SubmitPendingReview" in body ->
            """{"data": {"submitPullRequestReview": {"pullRequestReview": {"id": "R_2"}}}}"""
          else -> """{"data": {"deletePullRequestReview": {"pullRequestReview": {"id": "R_2"}}}}"""
        }
    }
    val range = DraftReviewComment(ReviewAnchor("abc", "b.kt", DiffSide.Old, 4, 6), "Why?")
    assertEquals("R_2", reviews.startPendingReview("PR_1", "abc", listOf(range)))
    reviews.addToPendingReview("R_2", range)
    reviews.submitPendingReview("R_2", ReviewEvent.RequestChanges, "")
    reviews.deletePendingReview("R_2")
    val start = graphql(0).getValue("variables").jsonObject
    assertEquals("abc", start.str("commitOid"))
    assertEquals(
      Json.parseToJsonElement(
        """[{"body": "Why?", "line": 6, "path": "b.kt", "side": "LEFT", "startLine": 4,
          "startSide": "LEFT"}]""",
      ),
      start.getValue("threads"),
    )
    val add = graphql(1).getValue("variables").jsonObject
    assertEquals("LEFT", add.str("side"))
    assertEquals("4", add.str("startLine"))
    val submit = graphql(2).getValue("variables").jsonObject
    assertEquals("REQUEST_CHANGES", submit.str("event"))
    assertEquals("", submit.str("body"))
    assertEquals("DeletePendingReview", graphql(3).str("operationName"))
  }

  @Test
  fun explicitlyClearsPendingSummaryWhenApprovingOrCommenting() = runTest {
    val reviews = reviews { _, _ ->
      HttpStatusCode.OK to
        """{"data": {"submitPullRequestReview": {"pullRequestReview": {"id": "R_1"}}}}"""
    }
    for (event in listOf(ReviewEvent.Approve, ReviewEvent.Comment)) {
      for (body in listOf("", " \n")) {
        reviews.submitPendingReview("R_1", event, body)
        val request = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals("SubmitPendingReview", request.str("operationName"))
        val variables = request.getValue("variables").jsonObject
        assertEquals("R_1", variables.str("reviewId"))
        assertEquals(
          if (event == ReviewEvent.Approve) "APPROVE" else "COMMENT",
          variables.str("event"),
        )
        assertEquals(body, variables.str("body"))
      }
    }
  }

  @Test
  fun loadsCommentsBeyondTheFirstPageOfAThread() = runTest {
    val reviews = reviews { _, body ->
      HttpStatusCode.OK to
        if ("PullRequestReviewThreadComments" in body) {
          """{"data":{"node":{"__typename":"PullRequestReviewThread","comments":{
          "pageInfo":{"hasNextPage":false,"endCursor":null},
          "nodes":[{"id":"C51","body":"Latest reply","createdAt":"2026-10-28T12:00:00Z",
            "state":"SUBMITTED","author":null}]}}}}"""
        } else {
          """{"data":{"repository":{"pullRequest":{"reviewThreads":{
          "pageInfo":{"hasNextPage":false,"endCursor":null},
          "nodes":[{"id":"T1","path":"a.kt","diffSide":"RIGHT","startDiffSide":null,
            "line":3,"startLine":null,"originalLine":3,"isResolved":false,"isOutdated":false,
            "subjectType":"LINE","viewerCanReply":true,"viewerCanResolve":true,
            "viewerCanUnresolve":false,"resolvedBy":null,"comments":{
              "pageInfo":{"hasNextPage":true,"endCursor":"c50"},
              "nodes":[{"id":"C1","body":"First","createdAt":"2026-10-28T12:00:00Z",
                "state":"SUBMITTED","author":null}]}}]}}}}}"""
        }
    }
    assertEquals(
      listOf("First", "Latest reply"),
      reviews.threads(repo, 7).single().comments.map { it.body },
    )
    val variables = graphql(1).getValue("variables").jsonObject
    assertEquals("T1", variables.str("id"))
    assertEquals("c50", variables.str("after"))
  }
}
