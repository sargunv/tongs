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
import kotlin.test.assertFailsWith
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
      headOid = "head1",
      authorLogin = "someone",
      webUrl = "https://github.com/$repo/pull/$number",
      viewerDidAuthor = false,
      viewerReviewRequested = true,
      pendingReview = pendingReview,
    )

  override suspend fun threads(repo: RepoRef, number: Int) = threads

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
    return "R_new"
  }

  override suspend fun addToPendingReview(reviewId: String, comment: DraftReviewComment) =
    call("add $reviewId: ${comment.body}")

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

    repo.submit(key, ReviewEvent.Approve, "Looks good otherwise", headOid = "head2")
    val submitted = forge.submissions.single()
    assertEquals(ReviewEvent.Approve, submitted.event)
    // Pinned to the commit the drafts were written against.
    assertEquals("head1", submitted.commitOid)
    assertEquals(listOf(one, range), submitted.comments.map { it.anchor })
    assertEquals(emptyList(), repo.drafts(key).first())
    assertEquals("", repo.summary(key).first())
  }

  @Test
  fun aFailedSubmissionKeepsTheDrafts() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    repo.saveDraft(key, ReviewAnchor("head1", "a.kt", DiffSide.New, 1, 1), "Hmm")
    forge.gate =
      CompletableDeferred<Unit>().apply { completeExceptionally(RuntimeException("422")) }
    assertFailsWith<RuntimeException> { repo.submit(key, ReviewEvent.Comment, "", "head1") }
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
    val draft = DraftKey(key.accountId, key.repo, key.number, "review/reply:T1")
    drafts.save(draft, "Done")
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    repo.reply(key, "T1", "Done", draft)
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
    val draft = DraftKey(key.accountId, key.repo, key.number, "review/reply:T1")
    drafts.save(draft, "Done")
    forge.refuse = "reply"
    repo.failures.test {
      repo.reply(key, "T1", "Done", draft)
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
    repo.submit(key, ReviewEvent.Comment, "Two things", "head1")
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
  fun aFailedStepKeepsWhatTheForgeDoesntHaveYet() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.threads = listOf(thread("T1", comment("C1", "First")))
    repo.refresh(key)
    repo.saveDraft(key, ReviewAnchor("head1", "src/b.kt", DiffSide.New, 2, 2), "New thread")
    repo.saveDraft(
      key,
      ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4),
      "Agreed",
      inReplyTo = "T1",
    )
    forge.refuse = "reply"
    assertFailsWith<IllegalStateException> { repo.submit(key, ReviewEvent.Comment, "", "head1") }
    // The new thread is in the forge's pending review now; the reply is still here.
    assertEquals(listOf("Agreed"), repo.drafts(key).first().map { it.body })
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
    repo.submit(key, ReviewEvent.Approve, "From the web", "head1")
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
  fun submittingKeepsACommentEditedWhileTheRequestWasInFlight() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 3, 3)
    val id = repo.saveDraft(key, anchor, "First version")
    repo.setSummary(key, "First summary")
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    val submitting = async { repo.submit(key, ReviewEvent.Comment, "First summary", "head1") }
    runCurrent()
    repo.saveDraft(key, anchor, "New wording", id)
    repo.setSummary(key, "New summary")
    val editKey = repo.pendingEditKey(key, id)
    drafts.save(editKey, "Still editing")
    gate.complete(Unit)
    submitting.await()
    assertEquals("First version", forge.submissions.single().comments.single().body)
    assertEquals("New wording", repo.drafts(key).first().single().body)
    assertEquals("New summary", repo.summary(key).first())
    assertEquals("Still editing", drafts.get(editKey))
  }

  @Test
  fun submittingKeepsAnAutosavedEditBeforeUpdateIsPressed() = dbTest { db ->
    val (repo, key) = repository(db)
    repo.refresh(key)
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 3, 3)
    val id = repo.saveDraft(key, anchor, "First version")
    val gate = CompletableDeferred<Unit>().also { forge.gate = it }
    val submitting = async { repo.submit(key, ReviewEvent.Comment, "", "head1") }
    runCurrent()
    val editKey = repo.pendingEditKey(key, id)
    drafts.save(editKey, "Still editing")
    gate.complete(Unit)
    submitting.await()
    assertEquals("First version", forge.submissions.single().comments.single().body)
    assertEquals(id, repo.drafts(key).first().single().id)
    assertEquals("Still editing", drafts.get(editKey))
  }

  @Test
  fun supplementalReviewStateIsPersistedWithoutADraftBadge() = dbTest { db ->
    val (repo, key) = repository(db)
    forge.pendingReview = PendingReview("R_web", "Unfinished", 1)
    forge.threads = listOf(thread("T1", comment("C1", "Mine", pending = true)))
    repo.refresh(key)
    assertFalse(drafts.hasDraft(key.accountId, key.repo, key.number).first())
    assertTrue(drafts.threadsWithDrafts().first().isEmpty())
    val anchor = ReviewAnchor("head1", "src/a.kt", DiffSide.New, 4, 4)
    repo.saveDraft(key, anchor, "Pending reply", inReplyTo = "T1")
    // Database rows don't gain columns; the supplemental document is in Room too.
    val cached =
      decodeReviewCache(
        drafts.get(DraftKey(key.accountId, key.repo, key.number, REVIEW_CACHE_SLOT)),
      )
    assertEquals("R_web", cached.pendingId)
    assertTrue(cached.permissions.getValue("T1").reply)
    assertEquals(setOf("C1"), cached.pendingComments)
    assertEquals("T1", cached.replies.values.single())
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
    val draft = DraftKey(key.accountId, key.repo, key.number, "review/reply:T1")
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
}
