package dev.sargunv.tongs.core.data

import app.cash.turbine.test
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.DiffReviewThread
import dev.sargunv.tongs.forge.api.FileViewedState
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
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
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/** A forge that records every review call and answers from fields. */
class FakeReviews : PullRequestReviews {
  val calls = mutableListOf<String>()
  val submissions = mutableListOf<ReviewSubmission>()
  var remoteViewed = mapOf<String, FileViewedState>()
  var threads = listOf<DiffReviewThread>()
  /** If set, mutations wait for it; complete it exceptionally to fail them. */
  var gate: CompletableDeferred<Unit>? = null

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
      viewerHasPendingReview = false,
    )

  override suspend fun threads(repo: RepoRef, number: Int) = threads

  override suspend fun viewedFiles(repo: RepoRef, number: Int) = remoteViewed

  override suspend fun setViewed(pullRequestId: String, path: String, viewed: Boolean) {
    calls += "${if (viewed) "mark" else "unmark"} $path"
    gate?.await()
  }

  override suspend fun submitReview(repo: RepoRef, number: Int, submission: ReviewSubmission) {
    calls += "submit ${submission.event}"
    gate?.await()
    submissions += submission
  }
}

class PullRequestChangesRepositoryTest {
  private val forge = FakeReviews()

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
    val repo = PullRequestChangesRepository(db, clients, backgroundScope, virtualClock())
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
}
