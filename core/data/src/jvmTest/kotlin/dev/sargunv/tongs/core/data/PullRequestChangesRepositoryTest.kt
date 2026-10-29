package dev.sargunv.tongs.core.data

import app.cash.turbine.test
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.DiffReviewThread
import dev.sargunv.tongs.forge.api.DraftReviewComment
import dev.sargunv.tongs.forge.api.FileViewedState
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.PendingReview
import dev.sargunv.tongs.forge.api.PullRequestOverview
import dev.sargunv.tongs.forge.api.PullRequestReviews
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.sargunv.tongs.forge.api.ReviewComment
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.sargunv.tongs.forge.api.ReviewSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/** A forge that records every review call and answers from fields. */
class FakeReviews : PullRequestReviews {
  val calls = mutableListOf<String>()
  val submissions = mutableListOf<ReviewSubmission>()
  var remoteViewed = mapOf<String, FileViewedState>()
  var threads = listOf<DiffReviewThread>()
  var pendingReview: PendingReview? = null
  var refreshGate: CompletableDeferred<Unit>? = null
  var head = "head1"
  /** If set, mutations wait for it; complete it exceptionally to fail them. */
  var gate: CompletableDeferred<Unit>? = null
  /** Fails the call whose log entry starts with this. */
  var refuse: String? = null

  private suspend fun call(entry: String) {
    calls += entry
    gate?.await()
    refuse?.let { if (entry.startsWith(it)) error("GitHub refused $entry") }
  }

  override suspend fun overview(repo: RepoRef, number: Int) =
    PullRequestOverview(
      id = "PR_1",
      title = "Make it fast",
      state = "open",
      headOid = head,
      authorLogin = "someone",
      webUrl = "https://github.com/$repo/pull/$number",
      viewerDidAuthor = false,
      viewerReviewRequested = true,
      pendingReview = pendingReview,
    )

  override suspend fun threads(repo: RepoRef, number: Int): List<DiffReviewThread> {
    val snapshot = threads
    refreshGate?.await()
    return snapshot
  }

  override suspend fun viewedFiles(repo: RepoRef, number: Int) = remoteViewed

  override suspend fun setViewed(pullRequestId: String, path: String, viewed: Boolean) {
    calls += "${if (viewed) "mark" else "unmark"} $path"
    gate?.await()
  }

  override suspend fun submitReview(repo: RepoRef, number: Int, submission: ReviewSubmission) {
    call("submit ${submission.event}")
    submissions += submission
  }

  override suspend fun reply(threadId: String, body: String, pendingReviewId: String?) =
    call("reply $threadId ${pendingReviewId ?: "now"}: $body").let {
      ReviewComment("C-$body", "me", null, body, Instant.parse("2026-10-28T02:00:00Z"))
    }

  override suspend fun setResolved(threadId: String, resolved: Boolean) =
    call("${if (resolved) "resolve" else "unresolve"} $threadId")

  override suspend fun startPendingReview(
    pullRequestId: String,
    commitOid: String,
    comments: List<DraftReviewComment>,
  ): String {
    call("start $pullRequestId@$commitOid ${comments.map { it.body }}")
    pendingReview = PendingReview("R_new", "", comments.size)
    return "R_new"
  }

  override suspend fun addToPendingReview(reviewId: String, comment: DraftReviewComment) {
    call("add $reviewId: ${comment.body}")
    pendingReview = pendingReview?.let { it.copy(commentCount = it.commentCount + 1) }
  }

  override suspend fun submitPendingReview(reviewId: String, event: ReviewEvent, body: String) =
    call("submit $reviewId $event: $body")

  override suspend fun deletePendingReview(reviewId: String) {
    call("delete $reviewId")
    pendingReview = null
    threads =
      threads
        .map { t -> t.copy(comments = t.comments.filterNot { it.pending }) }
        .filter { it.comments.isNotEmpty() }
  }
}

class PullRequestChangesRepositoryTest {
  private val forge = FakeReviews()
  private lateinit var drafts: DraftRepository

  private suspend fun TestScope.repository(
    db: TongsDatabase,
  ): Pair<PullRequestChangesRepository, PullRequestKey> {
    val tokens = InMemoryTokenStore()
    val adapter =
      object : ForgeAdapter by FakeAdapter(FakeInbox()) {
        override fun client(account: Account, token: String): ForgeClient =
          object : ForgeClient {
            override val inbox = null
            override val reviews = null
            override val pullRequestReviews = forge

            override fun close() {}
          }
      }
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to adapter)
    val clients = ForgeClients(db, tokens, adapters)
    val account =
      AccountRepository(db, tokens, adapters, clients).signIn(Instance.GitHubDotCom, "t")
    drafts = DraftRepository(db, backgroundScope, virtualClock())
    val repo = PullRequestChangesRepository(db, clients, drafts, backgroundScope, virtualClock())
    return repo to PullRequestKey(account.account.id.value, RepoRef("octo", "hello"), 7)
  }

  private val a1 = FileVersion("src/a.kt", "blob1")

  @Test
  fun viewedStateIsKeyedByBlob() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.setViewed(key, a1, viewed = true)
    advanceUntilIdle()
    assertEquals(setOf(a1), repo.viewed(key).first())
    // The file changed: the new version isn't viewed.
    val a2 = FileVersion("src/a.kt", "blob2")
    assertTrue(a2 !in repo.viewed(key).first())
    assertEquals(listOf("mark src/a.kt"), forge.calls)
  }

  @Test
  fun aRefusedViewedChangeIsUndone() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.failures.test {
      repo.setViewed(key, a1, viewed = true)
      // Optimistic: viewed before the forge answers.
      assertEquals(setOf(a1), repo.viewed(key).first())
      gate.completeExceptionally(RuntimeException("403"))
      advanceUntilIdle()
      assertEquals(emptySet(), repo.viewed(key).first())
      assertTrue("403" in awaitItem())
    }
  }

  @Test
  fun aQuickUndoWins() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.setViewed(key, a1, viewed = true)
    repo.setViewed(key, a1, viewed = false)
    advanceUntilIdle()
    assertEquals(emptySet(), repo.viewed(key).first())
    assertEquals(listOf("mark src/a.kt", "unmark src/a.kt"), forge.calls)
  }

  @Test
  fun syncTakesTheForgesViewedStateButKeepsPendingChanges() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val b = FileVersion("src/b.kt", "blobB")
    val c = FileVersion("src/c.kt", "blobC")
    val d = FileVersion("src/d.kt", "blobD")
    // c was viewed here, and the forge has since unmarked it; d's mark is still on its way.
    repo.mergeViewed(key, listOf(c), mapOf(c.path to FileViewedState.Viewed))
    forge.gate = CompletableDeferred()
    repo.setViewed(key, d, viewed = true)
    forge.remoteViewed =
      mapOf(
        a1.path to FileViewedState.Viewed,
        b.path to FileViewedState.Dismissed,
        c.path to FileViewedState.Unviewed,
        d.path to FileViewedState.Unviewed,
      )
    repo.syncViewed(key, listOf(a1, b, c, d))
    assertEquals(setOf(a1, d), repo.viewed(key).first())
  }

  @Test
  fun draftsMakeAPendingReviewThatSubmitsAsOneBatch() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val one = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 3, 3)
    val range = ReviewAnchor("head1", "src/b.kt", DiffSide.Old, 10, 12)
    val id = repo.saveDraft(key, one, "Typo")
    repo.saveDraft(key, range, "Why remove these?")
    repo.saveDraft(key, one, "Typo here", id = id)
    repo.setSummary(key, "Looks good otherwise")
    val drafts = repo.drafts(key).first()
    assertEquals(listOf("Typo here", "Why remove these?"), drafts.map { it.body })
    assertEquals(range, drafts[1].anchor)
    assertEquals("Looks good otherwise", repo.summary(key).first())

    repo.submit(key, ReviewEvent.Approve, "head2").await()
    val submitted = forge.submissions.single()
    assertEquals(ReviewEvent.Approve, submitted.event)
    // Pinned to the commit the drafts were written against.
    assertEquals("head1", submitted.commitOid)
    assertEquals(listOf(one, range), submitted.comments.map { it.anchor })
    assertEquals(emptyList(), repo.drafts(key).first())
    assertEquals("", repo.summary(key).first())
  }

  @Test
  fun blankedReviewCommentsStayEditableAndSurviveSubmissionUnchanged() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4)
    val blankLine = repo.saveDraft(key, anchor.copy(commitOid = "oldHead"), "Erase this")
    val blankReply = repo.saveDraft(key, anchor, "Erase this reply", inReplyTo = "T_blank")
    val blankKeys = listOf(blankLine, blankReply).map { db.drafts().byId(it)!!.draftKey }
    repo.saveDraft(key, anchor, "Keep this line")
    repo.saveDraft(key, anchor, "Keep this reply", inReplyTo = "T1")
    repo.setSummary(key, "Keep this summary")
    blankKeys.zip(listOf("", " \n\t")).forEach { (draft, body) ->
      assertTrue(drafts.writer(draft).write(body))
    }
    // Submit before the erased bodies have reached Room.
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    val submission = repo.submit(key, ReviewEvent.Comment, "head1")
    blankKeys.forEach { assertFalse(drafts.writer(it).locked.value) }
    runCurrent()
    val before = blankKeys.map { drafts.row(it)!! }
    assertEquals(listOf("", " \n\t"), before.map { it.body })
    assertTrue(before.all { it.inReview && !it.submitting })
    blankKeys.forEach { assertFalse(drafts.writer(it).locked.value) }
    assertEquals(listOf("start PR_1@head1 [Keep this line]"), forge.calls)
    gate.complete(Unit)
    assertTrue(submission.await())
    assertEquals(
      listOf(
        "start PR_1@head1 [Keep this line]",
        "reply T1 R_new: Keep this reply",
        "submit R_new Comment: Keep this summary",
      ),
      forge.calls,
    )
    assertEquals(before, drafts.all(key).first())
    blankKeys.forEach {
      assertTrue(drafts.writer(it).write("Retyped"))
      drafts.writer(it).flush()
      assertTrue(drafts.row(it)!!.inReview)
    }
  }

  @Test
  fun blankCommentsDoNotAccompanyASummaryOnlyReview() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("oldHead", "src/a.kt", DiffSide.New, 4, 4)
    val line = repo.saveDraft(key, anchor, "")
    val reply = repo.saveDraft(key, anchor, " \n\t", inReplyTo = "T1")
    val before = listOf(line, reply).map { db.drafts().byId(it)!! }
    repo.setSummary(key, "Summary only")
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(listOf("submit Comment"), forge.calls)
    val submitted = forge.submissions.single()
    assertEquals("head1", submitted.commitOid)
    assertEquals("Summary only", submitted.body)
    assertTrue(submitted.comments.isEmpty())
    assertEquals(before, drafts.all(key).first())
  }

  @Test
  fun aFailedSubmissionKeepsTheDrafts() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.saveDraft(key, ReviewAnchor("head1", "a.kt", DiffSide.New, 1, 1), "Hmm")
    forge.gate =
      CompletableDeferred<Unit>().apply { completeExceptionally(RuntimeException("422")) }
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(1, repo.drafts(key).first().size)
  }

  @Test
  fun refreshStoresThreadsWithTheirComments() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads =
      listOf(
        DiffReviewThread(
          id = "T1",
          path = "src/a.kt",
          side = DiffSide.New,
          startSide = null,
          line = 4,
          startLine = null,
          originalLine = 4,
          isResolved = false,
          isOutdated = false,
          isFileLevel = false,
          comments =
            listOf(
              ReviewComment("C1", "octocat", null, "First", Instant.parse("2026-10-28T00:00:00Z")),
              ReviewComment("C2", "hubot", null, "Second", Instant.parse("2026-10-28T01:00:00Z")),
            ),
        ),
      )
    repo.refresh(key)
    val thread = repo.threads(key).first().single()
    assertEquals("src/a.kt", thread.thread.path)
    assertEquals(listOf("First", "Second"), thread.comments.map { it.body })
    // A refresh replaces them.
    forge.threads = emptyList()
    repo.refresh(key)
    assertEquals(emptyList(), repo.threads(key).first())
    assertTrue(repo.overview(key).first()!!.viewerReviewRequested)
  }

  private fun thread(id: String, vararg comments: ReviewComment, resolved: Boolean = false) =
    DiffReviewThread(
      id = id,
      path = "src/a.kt",
      side = DiffSide.New,
      startSide = null,
      line = 4,
      startLine = null,
      originalLine = 4,
      isResolved = resolved,
      isOutdated = false,
      isFileLevel = false,
      comments = comments.toList(),
      viewerCanReply = true,
      viewerCanResolve = true,
      viewerCanUnresolve = true,
    )

  private fun comment(id: String, body: String, pending: Boolean = false) =
    ReviewComment(id, "octocat", null, body, Instant.parse("2026-10-28T00:00:00Z"), pending)

  @Test
  fun aReplyShowsAtOnceAndBecomesTheForgesComment() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val draft =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "review/reply:T1",
        DraftDestination.ReviewReply,
        threadId = "T1",
      )
    drafts.save(draft, "Done")
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.reply(key, draft)
    runCurrent()
    val sending = repo.threads(key).first().single().comments
    assertEquals(listOf("First", "Done"), sending.map { it.body })
    assertEquals(CommentState.Sending, sending.last().state)
    // A refresh before the forge answers keeps it.
    forge.gate = null
    repo.refresh(key)
    assertEquals(CommentState.Sending, repo.threads(key).first().single().comments.last().state)
    gate.complete(Unit)
    advanceUntilIdle()
    val posted = repo.threads(key).first().single().comments
    assertEquals(listOf("C1", "C-Done"), posted.map { it.id })
    assertEquals(CommentState.Published, posted.last().state)
    assertNull(drafts.get(draft))
    assertEquals(listOf("reply T1 now: Done"), forge.calls)
  }

  @Test
  fun aRefusedReplyDisappearsAndKeepsItsDraft() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val draft =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "review/reply:T1",
        DraftDestination.ReviewReply,
        threadId = "T1",
      )
    drafts.save(draft, "Done")
    forge.refuse = "reply"
    repo.failures.test {
      repo.reply(key, draft)
      advanceUntilIdle()
      assertTrue("refused" in awaitItem())
    }
    assertEquals(listOf("First"), repo.threads(key).first().single().comments.map { it.body })
    assertEquals("Done", drafts.get(draft))
  }

  @Test
  fun resolvingIsOptimisticAndARefusalUndoesIt() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    repo.setResolved(key, "T1", true)
    assertTrue(repo.threads(key).first().single().thread.isResolved)
    advanceUntilIdle()
    assertTrue(repo.threads(key).first().single().thread.isResolved)
    forge.refuse = "unresolve"
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.failures.test {
      repo.setResolved(key, "T1", false)
      assertFalse(repo.threads(key).first().single().thread.isResolved)
      gate.complete(Unit)
      advanceUntilIdle()
      assertTrue("unresolve" in awaitItem())
    }
    assertTrue(repo.threads(key).first().single().thread.isResolved)
    assertEquals(listOf("resolve T1", "unresolve T1"), forge.calls)
  }

  @Test
  fun repliesInAPendingReviewGoOutWithIt() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val line = ReviewAnchor("head1", "src/b.kt", DiffSide.New, 2, 2)
    repo.saveDraft(key, line, "New thread")
    repo.saveDraft(
      key,
      ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4),
      "Agreed",
      inReplyTo = "T1",
    )
    assertEquals(
      listOf(null, "T1"),
      repo.drafts(key).first().map { it.inReplyTo }.sortedBy { it ?: "" },
    )
    repo.setSummary(key, "Two things")
    repo.submit(key, ReviewEvent.Comment, "head1").await()
    assertEquals(
      listOf(
        "start PR_1@head1 [New thread]",
        "reply T1 R_new: Agreed",
        "submit R_new Comment: Two things",
      ),
      forge.calls,
    )
    assertEquals(emptyList(), repo.drafts(key).first())
  }

  @Test
  fun aFailedSecondStepRetriesOnlyTheUnacceptedDraftAfterContinue() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val line =
      repo.saveDraft(key, ReviewAnchor("head1", "src/b.kt", DiffSide.New, 2, 2), "New thread")
    val lineKey = db.drafts().byId(line)!!.draftKey
    repo.saveDraft(
      key,
      ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4),
      "Agreed",
      inReplyTo = "T1",
    )
    repo.setSummary(key, "Two things")
    // The next refresh sees the line comment staged by startPendingReview.
    forge.threads += thread("T2", comment("C2", "New thread", pending = true))
    forge.refuse = "reply"
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(listOf("Agreed"), repo.drafts(key).first().map { it.body })
    assertTrue(repo.drafts(key).first().none { it.submitting })
    assertNull(db.drafts().byId(line))
    assertEquals("", drafts.writer(lineKey).body.value)
    assertFalse(drafts.writer(lineKey).inReview.value)
    assertNull(drafts.writer(lineKey).error.value)
    assertEquals("Two things", repo.summary(key).first())
    assertEquals("R_new", repo.overview(key).first()!!.pendingReviewId)
    assertEquals(CommentState.Pending, repo.threads(key).first().last().comments.single().state)

    forge.refuse = null
    // Continuing is explicit; simply retrying cannot stage anything else.
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(2, forge.calls.size)
    repo.continuePendingReview(key)
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(
      listOf(
        "start PR_1@head1 [New thread]",
        "reply T1 R_new: Agreed",
        "reply T1 R_new: Agreed",
        "submit R_new Comment: Two things",
      ),
      forge.calls,
    )
    assertTrue(repo.drafts(key).first().isEmpty())
  }

  @Test
  fun aFailedSecondStagedLineRetriesOnlyTheRemainingLines() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Summary", 0)
    repo.refresh(key)
    repo.continuePendingReview(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 2, 2)
    for (body in listOf("One", "Two", "Three")) repo.saveDraft(key, anchor, body)
    forge.refuse = "add R_web: Two"
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(listOf("Two", "Three"), repo.drafts(key).first().map { it.body })
    assertTrue(repo.drafts(key).first().none { it.submitting })
    assertEquals(1, repo.overview(key).first()!!.pendingReviewComments)
    forge.refuse = null
    repo.continuePendingReview(key)
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(
      listOf(
        "add R_web: One",
        "add R_web: Two",
        "add R_web: Two",
        "add R_web: Three",
        "submit R_web Comment: Summary",
      ),
      forge.calls,
    )
  }

  @Test
  fun aFailedFinalSubmitDoesNotRestageAcceptedLinesOrReplies() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Summary", 0)
    repo.refresh(key)
    repo.continuePendingReview(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 2, 2)
    repo.saveDraft(key, anchor, "Line")
    val reply = repo.saveDraft(key, anchor, "Reply", inReplyTo = "T1")
    val replyKey = db.drafts().byId(reply)!!.draftKey
    forge.refuse = "submit"
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertTrue(repo.drafts(key).first().isEmpty())
    assertEquals("", drafts.writer(replyKey).body.value)
    assertFalse(drafts.writer(replyKey).inReview.value)
    assertEquals("Summary", repo.summary(key).first())
    forge.refuse = null
    repo.continuePendingReview(key)
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(
      listOf(
        "add R_web: Line",
        "reply T1 R_web: Reply",
        "submit R_web Comment: Summary",
        "submit R_web Comment: Summary",
      ),
      forge.calls,
    )
  }

  @Test
  fun aPendingReviewFromElsewhereIsContinuedOrDiscarded() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "From the web", 1)
    forge.threads =
      listOf(
        thread("T1", comment("C1", "First")),
        thread("T2", comment("C2", "Mine", pending = true)),
      )
    repo.refresh(key)
    assertEquals("R_web", repo.overview(key).first()!!.pendingReviewId)
    assertEquals(CommentState.Pending, repo.threads(key).first()[1].comments.single().state)
    assertFalse(repo.reviewStarted(key).first())

    repo.continuePendingReview(key)
    assertTrue(repo.reviewStarted(key).first())
    assertEquals("From the web", repo.summary(key).first())
    repo.saveDraft(key, ReviewAnchor("head1", "src/b.kt", DiffSide.New, 2, 2), "Another")
    repo.submit(key, ReviewEvent.Approve, "head1").await()
    assertEquals(listOf("add R_web: Another", "submit R_web Approve: From the web"), forge.calls)

    // Discarding drops its comments here at once.
    forge.calls.clear()
    repo.refresh(key)
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.discardPendingReview(key)
    assertNull(repo.overview(key).first()!!.pendingReviewId)
    assertEquals(listOf("T1"), repo.threads(key).first().map { it.thread.id })
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf("delete R_web"), forge.calls)
    assertNull(repo.overview(key).first()!!.pendingReviewId)
  }

  @Test
  fun continuingPreservesQueuedSummaryInputAheadOfRemoteAdoption() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Remote summary", 0)
    repo.refresh(key)
    repo.setSummary(key, "Previously saved")
    val summary = DraftKey.summary(key)
    val writer = drafts.writer(summary)
    val busy = CompletableDeferred<Unit>()
    writer.enqueue { busy.await() }
    assertTrue(writer.write("Locally typed"))
    val continuation = async { repo.continuePendingReview(key) }
    runCurrent()
    assertFalse(continuation.isCompleted)
    busy.complete(Unit)
    continuation.await()
    assertEquals("Locally typed", writer.body.value)
    assertEquals("Locally typed", drafts.get(summary))
    assertEquals("R_web", drafts.row(summary)?.continuedReviewId)
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(listOf("submit R_web Comment: Locally typed"), forge.calls)
  }

  @Test
  fun aDescendingCrossSideAnchorRemainsEditableAndSubmittable() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 100, 95, DiffSide.Old)
    val id = repo.saveDraft(key, anchor, "Across the changed lines")
    assertEquals(anchor, db.drafts().byId(id)?.anchor)
    assertEquals(anchor, repo.drafts(key).first().single().anchor)
    assertTrue(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals(anchor, forge.submissions.single().comments.single().anchor)
  }

  @Test
  fun twoRefusedViewedTogglesRestoreTheConfirmedState() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.setViewed(key, a1, true)
    repo.setViewed(key, a1, false)
    gate.completeExceptionally(RuntimeException("403"))
    advanceUntilIdle()
    assertTrue(repo.viewed(key).first().isEmpty())
    assertTrue(
      db
        .pullRequestChanges()
        .viewed(key.accountId, key.repo.owner, key.repo.name, key.number)
        .isEmpty(),
    )
  }

  @Test
  fun anAbandonedViewedMutationIsReplayedAfterRestart() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    db
      .pullRequestChanges()
      .upsertViewed(
        listOf(
          ViewedFileEntity(
            key.accountId,
            key.repo.owner,
            key.repo.name,
            key.number,
            a1.path,
            a1.blob,
            true,
            true,
            1,
          ),
        ),
      )
    repo.syncViewed(key, listOf(a1))
    advanceUntilIdle()
    assertEquals(listOf("mark src/a.kt"), forge.calls)
    db
      .pullRequestChanges()
      .observeViewed(key.accountId, key.repo.owner, key.repo.name, key.number)
      .first { rows -> rows.none { it.pending } }
  }

  @Test
  fun obsoleteViewedMutationsAreRetiredAfterRestart() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val dao = db.pullRequestChanges()
    // Both marks and unmarks are tied to the version the reader saw.
    dao.upsertViewed(
      listOf(true, false).mapIndexed { i, viewed ->
        ViewedFileEntity(
          key.accountId,
          key.repo.owner,
          key.repo.name,
          key.number,
          a1.path,
          "old$i",
          viewed,
          true,
          1,
        )
      },
    )
    forge.remoteViewed = mapOf(a1.path to FileViewedState.Unviewed)
    repo.syncViewed(key, listOf(a1))
    advanceUntilIdle()
    assertTrue(forge.calls.isEmpty())
    assertTrue(dao.viewed(key.accountId, key.repo.owner, key.repo.name, key.number).isEmpty())
  }

  @Test
  fun discardingASummaryWhileTheWriterIsBusyPreservesNewerInput() = dbTest { db ->
    val (_, key) = repository(db)
    val summary = DraftKey.summary(key)
    drafts.save(summary, "Discard this")
    val writer = drafts.writer(summary)
    val busy = CompletableDeferred<Unit>()
    writer.enqueue { busy.await() }
    val deletion = async { drafts.delete(summary) }
    runCurrent()
    assertEquals("", writer.body.value)
    assertTrue(writer.write("New text"))
    busy.complete(Unit)
    deletion.await()
    writer.flush()
    assertEquals("New text", writer.body.value)
    assertEquals("New text", drafts.get(summary))
    assertTrue(writer.inReview.value)
    // Subsequent input starts from the text still displayed in the composer.
    assertTrue(writer.write(writer.body.value + " continued"))
    writer.flush()
    assertEquals("New text continued", drafts.get(summary))
  }

  @Test
  fun submittingFlushesTheLatestWriteThroughSummary() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.setSummary(key, "Old summary")
    drafts.writer(DraftKey.summary(key)).write("Just typed")
    repo.submit(key, ReviewEvent.Comment, "head1").await()
    assertEquals("Just typed", forge.submissions.single().body)
    assertEquals("", repo.summary(key).first())
    assertFalse(repo.reviewStarted(key).first())
  }

  @Test
  fun aRefusedSubmissionKeepsTheLatestWriteThroughSummary() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.setSummary(key, "Old summary")
    drafts.writer(DraftKey.summary(key)).write("Just typed")
    forge.refuse = "submit"
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    assertEquals("Just typed", repo.summary(key).first())
  }

  @Test
  fun aDraftReplyHasItsTargetInTheSamePersistedRow() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val id =
      repo.saveDraft(
        key,
        ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4),
        "Agreed",
        inReplyTo = "T1",
      )
    val row = db.drafts().byId(id)!!
    assertEquals("T1", row.inReplyTo)
    assertEquals("Agreed", row.body)
    repo.submit(key, ReviewEvent.Comment, "head1").await()
    assertEquals(
      listOf(
        "start PR_1@head1 []",
        "reply T1 R_new: Agreed",
        "submit R_new Comment: ",
      ),
      forge.calls,
    )
    assertTrue(forge.submissions.isEmpty())
  }

  @Test
  fun submittingLocksTheSameCommentAndSummaryBeforeWaitingForRoom() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 3, 3)
    val id = repo.saveDraft(key, anchor, "First version")
    repo.setSummary(key, "First summary")
    val editor = drafts.writer(db.drafts().byId(id)!!.draftKey)
    val busy = CompletableDeferred<Unit>()
    editor.enqueue { busy.await() }
    editor.write("Latest version")
    val network = CompletableDeferred<Unit>().also { forge.gate = it }
    val submission = repo.submit(key, ReviewEvent.Comment, "head1")
    // Input and formatting are refused even before the first database suspension returns.
    assertFalse(editor.write("Too late"))
    assertFalse(drafts.writer(DraftKey.summary(key)).write("Too late"))
    assertFalse(repo.submit(key, ReviewEvent.Comment, "head1").await())
    busy.complete(Unit)
    runCurrent()
    assertTrue(db.drafts().byId(id)!!.submitting)
    network.complete(Unit)
    submission.await()
    assertEquals("Latest version", forge.submissions.single().comments.single().body)
    assertEquals("First summary", forge.submissions.single().body)
    assertTrue(repo.drafts(key).first().isEmpty())
  }

  @Test
  fun repliesHaveNoCommitAndDoNotReanchorOlderLineComments() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("headA", "old/path.kt", DiffSide.New, 3, 3)
    val id = repo.saveDraft(key, anchor, "On A")
    // Body edits after a push keep A; a reply created on B contributes no commit.
    repo.saveDraft(key, anchor.copy(commitOid = "headB"), "Still on A", id)
    val reply = repo.saveDraft(key, anchor.copy(commitOid = "headB"), "Reply", inReplyTo = "T1")
    assertNull(db.drafts().byId(reply)!!.commitOid)
    repo.submit(key, ReviewEvent.Comment, "headB").await()
    assertEquals("start PR_1@headA [Still on A]", forge.calls.first())
  }

  @Test
  fun mixedOrUnknownLineAnchorsBlockSubmissionAndKeepTheText() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val a = ReviewAnchor("headA", "gone.kt", DiffSide.New, 3, 3)
    repo.saveDraft(key, a, "On A")
    val other = repo.saveDraft(key, a.copy(commitOid = "headB"), "On B")
    assertFalse(repo.submit(key, ReviewEvent.Comment, "headB").await())
    drafts.delete(db.drafts().byId(other)!!.draftKey)
    repo.saveDraft(key, a.copy(commitOid = ""), "Unknown")
    assertFalse(repo.submit(key, ReviewEvent.Comment, "headB").await())
    assertTrue(forge.calls.isEmpty())
    assertEquals(listOf("On A", "Unknown"), repo.drafts(key).first().map { it.body })
  }

  @Test
  fun anExplicitlyClearedRemoteSummarySubmitsAnEmptyBody() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "From the web", 1)
    repo.refresh(key)
    repo.continuePendingReview(key)
    drafts.writer(DraftKey.summary(key)).write("")
    repo.submit(key, ReviewEvent.Approve, "head1").await()
    assertEquals(listOf("submit R_web Approve: "), forge.calls)
  }

  @Test
  fun aRemoteReviewRequiresAnExplicitContinueEvenWithLocalDrafts() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.saveDraft(key, ReviewAnchor("head1", "a.kt", DiffSide.New, 1, 1), "Local")
    forge.pendingReview = PendingReview("R_web", "From the web", 1)
    repo.refresh(key)
    assertFalse(repo.submit(key, ReviewEvent.Approve, "head1").await())
    assertTrue(forge.calls.isEmpty())
  }

  @Test
  fun reviewMetadataIsPersistedInChangesRowsWithoutADraftBadge() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Unfinished", 1)
    forge.threads = listOf(thread("T1", comment("C1", "Mine", pending = true)))
    repo.refresh(key)
    assertFalse(drafts.hasDraft(key.accountId, key.repo, key.number).first())
    assertTrue(drafts.threadsWithDrafts().first().isEmpty())
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4)
    repo.saveDraft(key, anchor, "Pending reply", inReplyTo = "T1")
    val dao = db.pullRequestChanges()
    val overview = dao.overview(key.accountId, key.repo.owner, key.repo.name, key.number)!!
    assertEquals("R_web", overview.pendingReviewId)
    assertEquals("Unfinished", overview.pendingReviewBody)
    assertEquals(1, overview.pendingReviewComments)
    assertTrue(dao.thread(key.accountId, "T1")!!.viewerCanReply)
    assertEquals(
      CommentState.Pending,
      dao
        .observeThreadComments(key.accountId, key.repo.owner, key.repo.name, key.number)
        .first()
        .single()
        .state,
    )
    assertEquals(
      "T1",
      db.drafts().all(key.accountId, key.repo.owner, key.repo.name, key.number).single().inReplyTo,
    )
  }

  @Test
  fun aRefusedDiscardRestoresThePendingReviewAndItsComments() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Unfinished", 1)
    forge.threads = listOf(thread("T1", comment("C1", "Mine", pending = true)))
    repo.refresh(key)
    forge.refuse = "delete"
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.failures.test {
      repo.discardPendingReview(key)
      assertNull(repo.overview(key).first()!!.pendingReviewId)
      gate.complete(Unit)
      advanceUntilIdle()
      assertTrue("discard" in awaitItem())
    }
    assertEquals("R_web", repo.overview(key).first()!!.pendingReviewId)
    assertEquals("Mine", repo.threads(key).first().single().comments.single().body)
  }

  @Test
  fun refreshingDropsASendingReplyAbandonedByAnotherProcess() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val draft =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "review/reply:T1",
        DraftDestination.ReviewReply,
        threadId = "T1",
      )
    drafts.save(draft, "Unconfirmed")
    db
      .pullRequestChanges()
      .upsertThreadComments(
        listOf(
          ChangesThreadCommentEntity(
            key.accountId,
            "sending:old-process",
            "T1",
            "me",
            null,
            "Unconfirmed",
            1,
            1,
          ),
        ),
      )
    repo.refresh(key)
    assertEquals(listOf("C1"), repo.threads(key).first().single().comments.map { it.id })
    assertEquals("Unconfirmed", drafts.get(draft))
  }

  @Test
  fun aReplyConfirmedDuringARefreshSurvivesByServerIdThenFollowsTheNextRefresh() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    val draft =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "review/reply:T1",
        DraftDestination.ReviewReply,
        threadId = "T1",
      )
    drafts.save(draft, "Posted")
    val read = CompletableDeferred<Unit>().also { forge.refreshGate = it }
    val refresh = async { repo.refresh(key) }
    runCurrent()
    repo.reply(key, draft).await()
    assertNull(drafts.get(draft))
    read.complete(Unit)
    refresh.await()
    assertEquals(
      listOf("C1", "C-Posted"),
      repo.threads(key).first().single().comments.map { it.id },
    )
    // A later server snapshot owns edits/deletions; the preservation is only for overlapping reads.
    forge.refreshGate = null
    repo.refresh(key)
    assertEquals(listOf("C1"), repo.threads(key).first().single().comments.map { it.id })
  }
}
