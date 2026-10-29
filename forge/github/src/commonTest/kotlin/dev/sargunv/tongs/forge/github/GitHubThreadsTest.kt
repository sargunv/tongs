package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.CheckState
import dev.sargunv.tongs.forge.api.EventKind
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.Label
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Mergeable
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewDecision
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubThreadsTest {
  /** A GraphQL request as the server saw it. */
  private class Request(val operation: String, val variables: JsonObject)

  private val requests = mutableListOf<Request>()
  private val repo = RepoRef("octo", "hello")

  /** A client whose server answers each operation by name with [responses]. */
  private fun threads(vararg responses: Pair<String, (Request) -> String>): GitHubThreads {
    val byName = responses.toMap()
    val http =
      HttpClient(
        MockEngine { request ->
          val body =
            Json.parseToJsonElement(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
              )
              .jsonObject
          val seen =
            Request(
              body["operationName"]!!.jsonPrimitive.content,
              body["variables"]?.jsonObject ?: JsonObject(emptyMap()),
            )
          requests += seen
          val respond = byName[seen.operation] ?: error("Unexpected ${seen.operation}")
          respond(
            respond(seen),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        },
      )
    return GitHubThreads(http, Instance.GitHubDotCom, "token")
  }

  @Test
  fun mapsAPullRequest() = runTest {
    val threads =
      threads(
        "Thread" to { PULL_REQUEST },
        "ThreadPullRequestFiles" to
          { request ->
            if (request.variables["cursor"] == null)
              files(listOf("VIEWED", "UNVIEWED", "VIEWED"), next = "F1")
            else files(listOf("VIEWED", "DISMISSED"), next = null)
          },
      )
    val load = threads.load(repo, 7)
    val detail = load.detail

    assertEquals(ThreadKind.PullRequest, detail.kind)
    assertEquals("PR_1", detail.id)
    assertEquals(ThreadState.Open, detail.state)
    assertEquals(listOf(Label("bug", "d73a4a", "Something isn't working")), detail.labels)
    assertEquals(listOf(Reactions(Reaction.Rocket, 2, viewerReacted = true)), detail.reactions)
    assertEquals(listOf(MergeMethod.Merge, MergeMethod.Squash), detail.viewer.mergeMethods)
    assertEquals(MergeMethod.Squash, detail.viewer.defaultMergeMethod)
    assertTrue(detail.viewer.canComment)

    val pr = detail.pullRequest!!
    assertEquals("fork-owner", pr.headOwner)
    assertEquals(12, pr.changedFiles)
    assertEquals(3, pr.commitCount)
    assertEquals(3, pr.viewedFiles)
    assertEquals(ReviewDecision.ChangesRequested, pr.reviewDecision)
    assertEquals(Mergeable.Conflicting, pr.mergeable)
    assertEquals("DIRTY", pr.mergeStateRaw)
    assertEquals(listOf("alice", "octo/reviewers"), pr.requestedReviewers)
    assertEquals(
      listOf(ReviewState.Approved, ReviewState.ChangesRequested),
      pr.reviews.map { it.state },
    )
    assertEquals(Actor("dependabot", "https://a/dependabot", isBot = true), pr.reviews[1].reviewer)

    val checks = pr.checks!!
    assertEquals(CheckState.Failure, checks.state)
    assertEquals(
      listOf(1, 2, 1, 2),
      listOf(checks.passed, checks.failed, checks.pending, checks.skipped),
    )
    // Failing first, then running, then passed, then skipped.
    assertEquals(
      listOf("CI / test", "ci/circleci", "CI / lint", "CI / build", "CI / docs"),
      checks.checks.map { it.name },
    )
    assertEquals(
      listOf(
        CheckState.Failure,
        CheckState.Error,
        CheckState.Pending,
        CheckState.Success,
        CheckState.Skipped,
      ),
      checks.checks.map { it.state },
    )

    assertEquals(4, load.timelineCount)
    assertEquals("H_END", load.head.endCursor)
    assertEquals(true, load.head.hasNext)
    assertEquals("T_START", load.tail.startCursor)
    val (commit, review, merged) = load.tail.items
    assertIs<TimelineItem.Commit>(commit)
    assertEquals("abc123", commit.oid)
    assertEquals(Instant.parse("2026-10-20T10:00:00Z"), commit.createdAt)
    assertEquals(CheckState.Success, commit.checks)
    assertEquals("Mona", commit.authorName)
    assertIs<TimelineItem.Review>(review)
    // Submitted time, not when the pending review was started.
    assertEquals(Instant.parse("2026-10-21T12:00:00Z"), review.createdAt)
    // GitHub added a state our schema doesn't know: it maps to Other rather than failing.
    assertIs<ReviewState.Other>(review.state)
    assertIs<TimelineItem.Event>(merged)
    assertEquals(EventKind.Merged, merged.kind)
    assertEquals("main", merged.subject)
    assertEquals("def456", merged.commitOid)

    val thread = load.reviewThreads.single()
    assertEquals("PRR_1", thread.reviewId)
    assertEquals("src/main.kt", thread.path)
    assertEquals(3, thread.totalComments)
    assertEquals(2, thread.comments.size)
    // Only the hunk's last six lines.
    assertEquals(6, thread.diffHunk!!.lines().size)
    assertTrue(thread.diffHunk!!.endsWith("+line 9"))

    assertEquals(
      listOf(null, "F1"),
      requests
        .filter { it.operation == "ThreadPullRequestFiles" }
        .map { it.variables["cursor"]?.jsonPrimitive?.content },
    )
    val variables = requests.first().variables
    assertEquals(7, variables["number"]!!.jsonPrimitive.int)
    assertEquals(15, variables["headCount"]!!.jsonPrimitive.int)
    assertEquals(40, variables["tailCount"]!!.jsonPrimitive.int)
  }

  @Test
  fun pagesBackThroughReviewThreads() = runTest {
    fun reviewThread(id: String) =
      """{"__typename":"PullRequestReviewThread","id":"$id","path":"a.kt","line":1,
        "originalLine":1,"isResolved":false,"isOutdated":false,
        "comments":{"totalCount":0,"nodes":[]}}"""
    val newest =
      PULL_REQUEST.replace(
        "\"hasPreviousPage\":false,\"startCursor\":null",
        "\"hasPreviousPage\":true,\"startCursor\":\"RT_2\"",
      )
    val threads =
      threads(
        "Thread" to { newest },
        "ThreadPullRequestFiles" to { files(emptyList(), next = null) },
        "ThreadReviewThreads" to
          { request ->
            val (nodes, previous) =
              when (request.variables["cursor"]!!.jsonPrimitive.content) {
                "RT_2" -> reviewThread("PRRT_B") to "RT_1"
                else -> reviewThread("PRRT_A") to null
              }
            """{"data":{"repository":{"pullRequest":{"reviewThreads":{"__typename":"PullRequestReviewThreadConnection",
              "pageInfo":{"hasPreviousPage":${previous != null},
                "startCursor":${previous?.let { "\"$it\"" }}},
              "nodes":[$nodes]}}}}}"""
          },
      )
    val load = threads.load(repo, 7)
    assertEquals(listOf("PRRT_A", "PRRT_B", "PRRT_1"), load.reviewThreads.map { it.id })
    assertEquals(
      listOf("RT_2", "RT_1"),
      requests
        .filter { it.operation == "ThreadReviewThreads" }
        .map { it.variables["cursor"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun mapsAnIssueAndItsEvents() = runTest {
    val load = threads("Thread" to { ISSUE }).load(repo, 9)
    val detail = load.detail
    assertEquals(ThreadKind.Issue, detail.kind)
    assertEquals(ThreadState.Closed, detail.state)
    assertEquals(StateReason.NotPlanned, detail.stateReason)
    assertNull(detail.pullRequest)
    assertEquals(listOf("mona"), detail.assignees.map { it.login })
    assertEquals("v1.0", detail.milestone)
    assertEquals(false, detail.viewer.canComment, "locked, and the viewer can't update it")
    assertEquals(emptyList(), detail.viewer.mergeMethods)
    // Requested only by kinds, so nothing's skipped.
    assertEquals(5, load.timelineCount)

    val items = load.head.items
    val comment = items[0]
    assertIs<TimelineItem.Comment>(comment)
    assertEquals("Same here", comment.body)
    // Zero counts are dropped.
    assertEquals(listOf(Reactions(Reaction.ThumbsUp, 3, viewerReacted = false)), comment.reactions)

    val labeled = items[1] as TimelineItem.Event
    assertEquals(EventKind.Labeled, labeled.kind)
    assertEquals(Label("needs-triage", "D6393F", null), labeled.label)
    assertEquals(Actor("github-actions", "https://a/github-actions", isBot = true), labeled.actor)

    val renamed = items[2] as TimelineItem.Event
    assertEquals(EventKind.Renamed, renamed.kind)
    assertEquals("Old title", renamed.previous)
    assertEquals("New title", renamed.subject)

    val crossReferenced = items[3] as TimelineItem.Event
    assertEquals(EventKind.CrossReferenced, crossReferenced.kind)
    val reference = crossReferenced.reference!!
    assertEquals(RepoRef("other", "repo"), reference.repo)
    assertEquals(ThreadKind.PullRequest, reference.kind)
    assertEquals(ThreadState.Merged, reference.state)
    assertEquals(true, crossReferenced.willClose)

    val closed = items[4] as TimelineItem.Event
    assertEquals(EventKind.Closed, closed.kind)
    assertEquals(StateReason.NotPlanned, closed.stateReason)
    assertEquals("c0ffee", closed.commitOid)
  }

  @Test
  fun pagesTheTimelineFromACursor() = runTest {
    val page =
      threads("ThreadTimeline" to { TIMELINE_PAGE })
        .loadTimeline(repo, 9, cursor = "H_END", count = 50)
    val variables = requests.single().variables
    assertEquals("H_END", variables["cursor"]!!.jsonPrimitive.content)
    assertEquals(50, variables["count"]!!.jsonPrimitive.int)
    assertEquals(listOf("IC_9"), page.items.map { it.id })
    assertEquals("P_END", page.endCursor)
    assertEquals(false, page.hasNext)
  }

  @Test
  fun addsACommentAndReturnsIt() = runTest {
    val comment =
      threads("ThreadAddComment" to { ADD_COMMENT }).addComment("I_1", "Thanks!\n\n> quoted")
    val variables = requests.single().variables
    assertEquals("I_1", variables["subjectId"]!!.jsonPrimitive.content)
    assertEquals("Thanks!\n\n> quoted", variables["body"]!!.jsonPrimitive.content)
    assertEquals("IC_new", comment.id)
    assertEquals("Thanks!\n\n> quoted", comment.body)
    assertEquals("octocat", comment.author!!.login)
  }

  @Test
  fun reactsWithTheRightMutation() = runTest {
    val ok =
      """{"data":{"addReaction":{"reaction":{"content":"HEART"}},"removeReaction":{"reaction":{"content":"HEART"}}}}"""
    val threads = threads("ThreadAddReaction" to { ok }, "ThreadRemoveReaction" to { ok })
    threads.react("IC_1", Reaction.Heart, add = true)
    threads.react("IC_1", Reaction.Heart, add = false)
    assertEquals(listOf("ThreadAddReaction", "ThreadRemoveReaction"), requests.map { it.operation })
    assertEquals("HEART", requests[0].variables["content"]!!.jsonPrimitive.content)
    assertEquals("IC_1", requests[1].variables["id"]!!.jsonPrimitive.content)
    assertFailsWith<IllegalArgumentException> {
      threads.react("IC_1", Reaction.Other(":blobcat:"), add = true)
    }
  }

  @Test
  fun closesIssuesWithAReasonAndPullRequestsWithout() = runTest {
    val threads =
      threads(
        "ThreadCloseIssue" to { """{"data":{"closeIssue":{"issue":{"id":"I_1"}}}}""" },
        "ThreadClosePullRequest" to
          {
            """{"data":{"closePullRequest":{"pullRequest":{"id":"PR_1"}}}}"""
          },
        "ThreadReopenIssue" to { """{"data":{"reopenIssue":{"issue":{"id":"I_1"}}}}""" },
      )
    threads.close("I_1", ThreadKind.Issue, StateReason.NotPlanned)
    threads.close("PR_1", ThreadKind.PullRequest)
    threads.reopen("I_1", ThreadKind.Issue)
    assertEquals(
      listOf("ThreadCloseIssue", "ThreadClosePullRequest", "ThreadReopenIssue"),
      requests.map { it.operation },
    )
    assertEquals("NOT_PLANNED", requests[0].variables["reason"]!!.jsonPrimitive.content)
  }

  @Test
  fun mergesOnlyTheExpectedHead() = runTest {
    threads(
        "ThreadMerge" to
          {
            """{"data":{"mergePullRequest":{"pullRequest":{"id":"PR_1","merged":true}}}}"""
          },
      )
      .merge("PR_1", MergeMethod.Squash, expectedHeadOid = "abc123")
    val variables = requests.single().variables
    assertEquals("SQUASH", variables["method"]!!.jsonPrimitive.content)
    assertEquals("abc123", variables["expectedHeadOid"]!!.jsonPrimitive.content)
  }

  @Test
  fun reportsGraphQlErrors() = runTest {
    val notFound =
      """
      {"data":{"repository":null},"errors":[{"type":"NOT_FOUND","path":["repository"],
      "locations":[{"line":1,"column":2}],"message":"Could not resolve to a Repository with the name 'octo/hello'."}]}
      """
    val missing =
      assertFailsWith<GitHubGraphQLException> { threads("Thread" to { notFound }).load(repo, 9) }
    assertEquals("Not found: octo/hello#9", missing.message)
    assertEquals("NOT_FOUND", missing.type)

    val locked =
      """
      {"data":{"addComment":null},"errors":[{"type":"UNPROCESSABLE","path":["addComment"],
      "message":"Unable to create comment because issue is locked."}]}
      """
    val refused =
      assertFailsWith<GitHubGraphQLException> {
        threads("ThreadAddComment" to { locked }).addComment("I_1", "hi")
      }
    assertEquals(
      "Couldn't add a comment: Unable to create comment because issue is locked.",
      refused.message,
    )
  }
}

private fun actor(login: String, type: String = "User") =
  """{"__typename":"$type","login":"$login","avatarUrl":"https://a/$login"}"""

private fun reactions(vararg groups: Triple<String, Int, Boolean>) =
  groups.joinToString(",", "[", "]") { (content, count, mine) ->
    """{"__typename":"ReactionGroup","content":"$content","viewerHasReacted":$mine,"reactors":{"totalCount":$count}}"""
  }

private fun pageInfo(start: String?, end: String?, previous: Boolean, next: Boolean) =
  """{"__typename":"PageInfo","startCursor":${start?.let { "\"$it\"" }},"endCursor":${end?.let { "\"$it\"" }},"hasPreviousPage":$previous,"hasNextPage":$next}"""

private fun files(states: List<String>, next: String?) =
  """
  {"data":{"repository":{"pullRequest":{"files":{
    "pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" }}},
    "nodes":[${states.joinToString(",") { """{"viewerViewedState":"$it"}""" }}]
  }}}}}
  """

private val LABEL =
  """{"__typename":"Label","name":"bug","color":"d73a4a","description":"Something isn't working"}"""

private val PULL_REQUEST =
  """
  {"data":{"repository":{
    "viewerPermission":"WRITE","mergeCommitAllowed":true,"squashMergeAllowed":true,
    "rebaseMergeAllowed":false,"viewerDefaultMergeMethod":"SQUASH",
    "issueOrPullRequest":{
      "__typename":"PullRequest","id":"PR_1","number":7,"title":"Stream packs","body":"Body",
      "url":"https://github.com/octo/hello/pull/7","createdAt":"2026-10-20T09:00:00Z",
      "updatedAt":"2026-10-22T09:00:00Z","lastEditedAt":null,"pullRequestState":"OPEN",
      "locked":false,"isDraft":false,"author":${actor("mona")},
      "labels":{"nodes":[$LABEL]},"assignees":{"nodes":[]},"milestone":null,
      "reactionGroups":${reactions(Triple("THUMBS_UP", 0, false), Triple("ROCKET", 2, true))},
      "comments":{"totalCount":4},"viewerCanUpdate":true,"viewerCanReact":true,
      "viewerCanClose":true,"viewerCanReopen":false,"baseRefName":"main",
      "headRefName":"stream","headRefOid":"abc123","isCrossRepository":true,
      "headRepositoryOwner":{"__typename":"User","login":"fork-owner"},
      "additions":120,"deletions":30,"changedFiles":12,"commits":{"totalCount":3},
      "reviewDecision":"CHANGES_REQUESTED","mergeable":"CONFLICTING","mergeStateStatus":"DIRTY",
      "mergedAt":null,"mergedBy":null,"autoMergeRequest":null,
      "latestOpinionatedReviews":{"nodes":[
        {"author":${actor("alice")},"state":"APPROVED"},
        {"author":${actor("dependabot", "Bot")},"state":"CHANGES_REQUESTED"}
      ]},
      "reviewRequests":{"nodes":[
        {"requestedReviewer":{"__typename":"User","login":"alice"}},
        {"requestedReviewer":{"__typename":"Team","combinedSlug":"octo/reviewers"}}
      ]},
      "statusCheckRollup":{"state":"FAILURE","contexts":{
        "checkRunCountsByState":[
          {"state":"SUCCESS","count":1},{"state":"FAILURE","count":1},
          {"state":"IN_PROGRESS","count":1},{"state":"SKIPPED","count":1},
          {"state":"CANCELLED","count":1},{"state":"QUEUED","count":0}
        ],
        "statusContextCountsByState":[{"state":"ERROR","count":1},{"state":"SUCCESS","count":0}],
        "nodes":[
          {"__typename":"CheckRun","name":"build","status":"COMPLETED","conclusion":"SUCCESS",
           "detailsUrl":"https://x/1","title":null,"checkSuite":{"workflowRun":{"workflow":{"name":"CI"}}}},
          {"__typename":"CheckRun","name":"docs","status":"COMPLETED","conclusion":"SKIPPED",
           "detailsUrl":null,"title":null,"checkSuite":{"workflowRun":{"workflow":{"name":"CI"}}}},
          {"__typename":"CheckRun","name":"lint","status":"IN_PROGRESS","conclusion":null,
           "detailsUrl":null,"title":null,"checkSuite":{"workflowRun":{"workflow":{"name":"CI"}}}},
          {"__typename":"CheckRun","name":"test","status":"COMPLETED","conclusion":"TIMED_OUT",
           "detailsUrl":null,"title":"Timed out","checkSuite":{"workflowRun":{"workflow":{"name":"CI"}}}},
          {"__typename":"StatusContext","context":"ci/circleci","state":"ERROR",
           "targetUrl":"https://circleci/1","description":"Build errored"}
        ]
      }},
      "head":{"__typename":"PullRequestTimelineItemsConnection","filteredCount":4,
        "pageInfo":${pageInfo("H_START", "H_END", previous = false, next = true)},
        "nodes":[
          {"__typename":"IssueComment","id":"IC_1","createdAt":"2026-10-20T09:30:00Z",
           "author":${actor("mona")},"body":"First","lastEditedAt":null,
           "url":"https://github.com/octo/hello/pull/7#issuecomment-1","viewerCanReact":true,
           "isMinimized":false,"minimizedReason":null,"reactionGroups":[]}
        ]},
      "tail":{"__typename":"PullRequestTimelineItemsConnection","filteredCount":4,
        "pageInfo":${pageInfo("T_START", "T_END", previous = true, next = false)},
        "nodes":[
          {"__typename":"PullRequestCommit","id":"PRC_1","url":"https://github.com/octo/hello/pull/7/commits/abc123",
           "commit":{"oid":"abc123","messageHeadline":"Stream packs","committedDate":"2026-10-20T10:00:00Z",
             "author":{"name":"Mona","user":${actor("mona")}},"statusCheckRollup":{"state":"SUCCESS"}}},
          {"__typename":"PullRequestReview","id":"PRR_1","createdAt":"2026-10-21T11:00:00Z",
           "submittedAt":"2026-10-21T12:00:00Z","author":${actor("alice")},"state":"BRAND_NEW_STATE",
           "body":"","url":"https://github.com/octo/hello/pull/7#pullrequestreview-1",
           "viewerCanReact":true,"reactionGroups":[]},
          {"__typename":"MergedEvent","id":"ME_1","createdAt":"2026-10-22T09:00:00Z",
           "actor":${actor("mona")},"mergeRefName":"main","mergeCommit":{"oid":"def456"}}
        ]},
      "reviewThreads":{"__typename":"PullRequestReviewThreadConnection","pageInfo":{"hasPreviousPage":false,"startCursor":null},"nodes":[
        {"__typename":"PullRequestReviewThread","id":"PRRT_1","path":"src/main.kt","line":9,
         "originalLine":9,"isResolved":false,"isOutdated":false,
         "comments":{"totalCount":3,"nodes":[
           {"__typename":"PullRequestReviewComment","id":"PRRC_1","createdAt":"2026-10-21T11:00:00Z",
            "author":${actor("alice")},"body":"Why?","lastEditedAt":null,"url":"https://x/c1",
            "viewerCanReact":true,"isMinimized":false,"minimizedReason":null,"reactionGroups":[],
            "diffHunk":"@@ -1,8 +1,9 @@\n line 1\n line 2\n line 3\n line 4\n line 5\n line 6\n line 7\n+line 9",
            "pullRequestReview":{"id":"PRR_1"}},
           {"__typename":"PullRequestReviewComment","id":"PRRC_2","createdAt":"2026-10-21T13:00:00Z",
            "author":${actor("mona")},"body":"Because.","lastEditedAt":null,"url":"https://x/c2",
            "viewerCanReact":true,"isMinimized":false,"minimizedReason":null,"reactionGroups":[],
            "diffHunk":"@@ -1,8 +1,9 @@\n line 1","pullRequestReview":{"id":"PRR_2"}}
         ]}}
      ]}
    }
  }}}
  """

private val ISSUE =
  """
  {"data":{"repository":{
    "viewerPermission":"READ","mergeCommitAllowed":true,"squashMergeAllowed":true,
    "rebaseMergeAllowed":true,"viewerDefaultMergeMethod":"MERGE",
    "issueOrPullRequest":{
      "__typename":"Issue","id":"I_1","number":9,"title":"New title","body":"It crashes",
      "url":"https://github.com/octo/hello/issues/9","createdAt":"2026-10-01T00:00:00Z",
      "updatedAt":"2026-10-05T00:00:00Z","lastEditedAt":"2026-10-02T00:00:00Z",
      "issueState":"CLOSED","stateReason":"NOT_PLANNED","locked":true,"author":${actor("mona")},
      "labels":{"nodes":[]},"assignees":{"nodes":[${actor("mona")}]},"milestone":{"title":"v1.0"},
      "reactionGroups":[],"comments":{"totalCount":1},"viewerCanUpdate":false,
      "viewerCanReact":true,"viewerCanClose":false,"viewerCanReopen":false,
      "head":{"__typename":"IssueTimelineItemsConnection","filteredCount":5,
        "pageInfo":${pageInfo("S", "E", previous = false, next = false)},
        "nodes":[
          {"__typename":"IssueComment","id":"IC_1","createdAt":"2026-10-01T01:00:00Z",
           "author":${actor("hubot", "Bot")},"body":"Same here","lastEditedAt":null,
           "url":"https://github.com/octo/hello/issues/9#issuecomment-1","viewerCanReact":true,
           "isMinimized":false,"minimizedReason":null,
           "reactionGroups":${reactions(Triple("THUMBS_UP", 3, false), Triple("EYES", 0, false))}},
          {"__typename":"LabeledEvent","id":"LE_1","createdAt":"2026-10-01T02:00:00Z",
           "actor":${actor("github-actions", "Bot")},
           "label":{"__typename":"Label","name":"needs-triage","color":"D6393F","description":null}},
          {"__typename":"RenamedTitleEvent","id":"RTE_1","createdAt":"2026-10-02T00:00:00Z",
           "actor":${actor("mona")},"previousTitle":"Old title","currentTitle":"New title"},
          {"__typename":"CrossReferencedEvent","id":"CRE_1","createdAt":"2026-10-03T00:00:00Z",
           "actor":${actor("mona")},"willCloseTarget":true,
           "source":{"__typename":"PullRequest","number":44,"title":"Fix the crash",
             "url":"https://github.com/other/repo/pull/44","pullRequestState":"MERGED",
             "repository":{"__typename":"Repository","name":"repo","owner":{"__typename":"User","login":"other"}}}},
          {"__typename":"ClosedEvent","id":"CE_1","createdAt":"2026-10-05T00:00:00Z",
           "actor":${actor("mona")},"stateReason":"NOT_PLANNED",
           "closer":{"__typename":"Commit","oid":"c0ffee"}}
        ]},
      "tail":{"__typename":"IssueTimelineItemsConnection","filteredCount":5,
        "pageInfo":${pageInfo("S", "E", previous = false, next = false)},"nodes":[]}
    }
  }}}
  """

private val TIMELINE_PAGE =
  """
  {"data":{"repository":{"issueOrPullRequest":{"__typename":"Issue",
    "timelineItems":{"__typename":"IssueTimelineItemsConnection","filteredCount":60,
      "pageInfo":${pageInfo("P_START", "P_END", previous = true, next = false)},
      "nodes":[
        {"__typename":"IssueComment","id":"IC_9","createdAt":"2026-10-03T00:00:00Z",
         "author":null,"body":"From a deleted account","lastEditedAt":null,"url":"https://x/9",
         "viewerCanReact":false,"isMinimized":false,"minimizedReason":null,"reactionGroups":[]}
      ]}
  }}}}
  """

private val ADD_COMMENT =
  """
  {"data":{"addComment":{"commentEdge":{"node":{"__typename":"IssueComment","id":"IC_new",
    "createdAt":"2026-10-28T12:00:00Z","author":${actor("octocat")},"body":"Thanks!\n\n> quoted",
    "lastEditedAt":null,"url":"https://github.com/octo/hello/issues/9#issuecomment-2",
    "viewerCanReact":true,"isMinimized":false,"minimizedReason":null,"reactionGroups":[]}}}}}
  """
