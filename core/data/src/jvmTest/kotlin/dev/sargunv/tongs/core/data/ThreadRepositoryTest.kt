package dev.sargunv.tongs.core.data

import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.AuthProvider
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeClient
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadLoad
import dev.sargunv.tongs.forge.api.ThreadSource
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.api.TimelinePage
import dev.sargunv.tongs.forge.api.VerifiedToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext

class ThreadRepositoryTest {
  private val repo = RepoRef("octo", "hello")

  private fun comment(id: String, minutes: Int, body: String = "Comment $id") =
    TimelineItem.Comment(id, START + minutes.minutes, Actor("mona"), body)

  private fun detail(state: ThreadState = ThreadState.Open) =
    ThreadDetail(
      id = "PR_1",
      repo = repo,
      number = 7,
      kind = ThreadKind.PullRequest,
      title = "Stream packs",
      body = "Body",
      author = Actor("mona"),
      createdAt = START,
      updatedAt = START,
      webUrl = "https://github.com/octo/hello/pull/7",
      state = state,
    )

  private fun page(items: List<TimelineItem>, hasPrevious: Boolean, hasNext: Boolean) =
    TimelinePage(
      items,
      startCursor = items.firstOrNull()?.let { "c:${it.id}" },
      endCursor = items.lastOrNull()?.let { "c:${it.id}" },
      hasPrevious = hasPrevious,
      hasNext = hasNext,
    )

  /** A timeline of [count] comments, 0 to count-1, served from the start and end like GitHub. */
  private inner class FakeThreads(var count: Int, val headCount: Int = 2, val tailCount: Int = 3) :
    ThreadSource {
    var detail = detail()
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var pageGate: CompletableDeferred<Unit>? = null
    var reviewThreads = emptyList<ReviewThread>()
    var refreshGate: CompletableDeferred<Unit>? = null

    private fun timeline() = (0 until count).map { comment("$it", it) }

    override suspend fun load(
      repo: RepoRef,
      number: Int,
      headCount: Int,
      tailCount: Int,
    ): ThreadLoad {
      val all = timeline()
      val snapshot =
        ThreadLoad(
          detail,
          head = page(all.take(this.headCount), false, all.size > this.headCount),
          tail = page(all.takeLast(this.tailCount), all.size > this.tailCount, false),
          timelineCount = all.size,
          reviewThreads = reviewThreads,
        )
      refreshGate?.await()
      return snapshot
    }

    override suspend fun loadTimeline(
      repo: RepoRef,
      number: Int,
      cursor: String,
      count: Int,
    ): TimelinePage {
      calls += "page $cursor"
      pageGate?.await()
      val all = timeline()
      val start = all.indexOfFirst { "c:${it.id}" == cursor } + 1
      val items = all.drop(start).take(2)
      return page(items, true, start + items.size < all.size)
    }

    override suspend fun addComment(threadId: String, body: String): TimelineItem.Comment {
      calls += "comment $body"
      gate?.await()
      return comment("posted", 100, body)
    }

    override suspend fun close(threadId: String, kind: ThreadKind, reason: StateReason?) {
      calls += "close ${reason?.raw}"
      gate?.await()
      detail = detail.copy(state = ThreadState.Closed, stateReason = reason)
    }

    override suspend fun reopen(threadId: String, kind: ThreadKind) {
      calls += "reopen"
    }

    override suspend fun react(subjectId: String, reaction: Reaction, add: Boolean) {
      calls += "react $subjectId ${reaction.raw} $add"
      gate?.await()
    }

    override suspend fun merge(
      pullRequestId: String,
      method: MergeMethod,
      expectedHeadOid: String,
    ) {
      calls += "merge ${method.raw}"
    }
  }

  private class Adapter(private val threads: ThreadSource) : ForgeAdapter {
    override val kind = ForgeKind.GitHub
    override val auth: AuthProvider =
      object : AuthProvider {
        override val tokenScopes = emptyList<String>()

        override fun newTokenUrl(instance: Instance) = ""

        override suspend fun verify(instance: Instance, token: String) =
          VerifiedToken(
            "U_1",
            "octocat",
            null,
            null,
            AuthMethod.ClassicToken,
            emptySet(),
            emptySet(),
            setOf(Capability.Inbox),
          )
      }

    override fun client(account: Account, token: String): ForgeClient =
      object : ForgeClient {
        override val inbox = null
        override val reviews = null
        override val threads = this@Adapter.threads

        override fun close() {}
      }
  }

  private class Fixture(
    val threads: ThreadRepository,
    val drafts: DraftRepository,
    val ref: ThreadRef,
  )

  private suspend fun TestScope.fixture(db: TongsDatabase, source: ThreadSource): Fixture {
    val tokens = InMemoryTokenStore()
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to Adapter(source))
    val clients = ForgeClients(db, tokens, adapters)
    val accounts = AccountRepository(db, tokens, adapters, clients)
    val id = accounts.signIn(Instance.GitHubDotCom, "t").account.id.value
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    val threads = ThreadRepository(db, clients, accounts, drafts, backgroundScope, virtualClock())
    return Fixture(threads, drafts, ThreadRef(id, repo, 7))
  }

  private fun ThreadSnapshot.ids() = items.map { it.item.id }

  @Test
  fun shortTimelinesLoadWhole() = dbTest { db ->
    val source = FakeThreads(count = 4, headCount = 3, tailCount = 3)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    val snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1", "2", "3"), snapshot.ids())
    assertEquals(0, snapshot.hiddenCount)
    assertEquals("Stream packs", snapshot.detail.title)
  }

  @Test
  fun longTimelinesFillTheGapFromTheStart() = dbTest { db ->
    val f = fixture(db, FakeThreads(count = 10))
    f.threads.refresh(f.ref)
    var snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1", "7", "8", "9"), snapshot.ids())
    assertEquals(2, snapshot.gapIndex)
    assertEquals(5, snapshot.hiddenCount)

    f.threads.loadHidden(f.ref)
    snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1", "2", "3", "7", "8", "9"), snapshot.ids())
    assertEquals(3, snapshot.hiddenCount)

    f.threads.loadHidden(f.ref)
    f.threads.loadHidden(f.ref)
    snapshot = f.threads.observe(f.ref).first()!!
    assertEquals((0..9).map { "$it" }, snapshot.ids())
    assertEquals(0, snapshot.hiddenCount)
  }

  @Test
  fun aPageOfHiddenItemsFromBeforeARefreshIsDropped() = dbTest { db ->
    val source = FakeThreads(count = 10)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.threads.loadHidden(f.ref)
    assertEquals(
      listOf("0", "1", "2", "3", "7", "8", "9"),
      f.threads.observe(f.ref).first()!!.ids(),
    )

    // The next page is in flight when a refresh resets the timeline to its two ends.
    source.pageGate = CompletableDeferred()
    val loading = launch { f.threads.loadHidden(f.ref) }
    eventually { "page c:3" in source.calls }
    f.threads.refresh(f.ref)
    source.pageGate!!.complete(Unit)
    loading.join()

    var snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1", "7", "8", "9"), snapshot.ids())
    assertEquals(5, snapshot.hiddenCount)
    source.pageGate = null
    f.threads.loadHidden(f.ref)
    snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1", "2", "3", "7", "8", "9"), snapshot.ids())
  }

  @Test
  fun refreshReplacesItemsButKeepsPendingComments() = dbTest { db ->
    val source = FakeThreads(count = 3)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    source.gate = CompletableDeferred()
    f.drafts.save(f.ref.replyDraft, "Looks good")
    f.threads.comment(f.ref)
    runCurrent()
    source.count = 2
    f.threads.refresh(f.ref)
    val snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf("0", "1"), snapshot.ids().dropLast(1))
    assertTrue(snapshot.items.last().pending)
    assertEquals("octocat", (snapshot.items.last().item as TimelineItem.Comment).author?.login)
  }

  @Test
  fun commentingShowsPendingThenConfirmsAndDeletesTheDraft() = dbTest { db ->
    val source = FakeThreads(count = 2).apply { gate = CompletableDeferred() }
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.drafts.save(f.ref.replyDraft, "Looks good")

    f.drafts.save(f.ref.replyDraft, "Looks good")
    f.threads.comment(f.ref)
    runCurrent()
    var last = f.threads.observe(f.ref).first()!!.items.last()
    assertTrue(last.pending)
    assertEquals("Looks good", (last.item as TimelineItem.Comment).body)

    source.gate!!.complete(Unit)
    eventually { f.drafts.get(f.ref.replyDraft) == null }
    last = f.threads.observe(f.ref).first()!!.items.last()
    assertFalse(last.pending)
    assertEquals("posted", last.item.id)
    assertNull(f.drafts.get(f.ref.replyDraft))
  }

  @Test
  fun aRefusedCommentIsRemovedAndItsDraftKept() = dbTest { db ->
    val source = FakeThreads(count = 2).apply { gate = CompletableDeferred() }
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.drafts.save(f.ref.replyDraft, "Looks good")
    val failures = mutableListOf<String>()
    backgroundScope.launchCollect(f.threads.failures, failures)

    f.drafts.save(f.ref.replyDraft, "Looks good")
    f.threads.comment(f.ref)
    runCurrent()
    source.gate!!.completeExceptionally(IllegalStateException("403 Forbidden"))
    eventually { failures.isNotEmpty() }

    assertEquals(listOf("0", "1"), f.threads.observe(f.ref).first()!!.ids())
    assertEquals("Looks good", f.drafts.get(f.ref.replyDraft))
    assertTrue(failures.single().contains("403 Forbidden"))
  }

  @Test
  fun aRefusedCommentKeepsTheTextSentNotAnOlderDraft() = dbTest { db ->
    val source = FakeThreads(count = 2).apply { gate = CompletableDeferred() }
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    // The final edit queues immediately, before submit waits for the writer.
    f.drafts.save(f.ref.replyDraft, "Looks")
    val failures = mutableListOf<String>()
    backgroundScope.launchCollect(f.threads.failures, failures)

    f.drafts.save(f.ref.replyDraft, "Looks good")
    f.threads.comment(f.ref)
    runCurrent()
    source.gate!!.completeExceptionally(IllegalStateException("403 Forbidden"))
    eventually { failures.isNotEmpty() }

    assertEquals("Looks good", f.drafts.get(f.ref.replyDraft))
  }

  @Test
  fun aSlowSendFreezesInputAndDoesNotCreateAFollowupCopy() = dbTest { db ->
    val source = FakeThreads(count = 2).apply { gate = CompletableDeferred() }
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.drafts.save(f.ref.replyDraft, "Looks good")

    f.drafts.save(f.ref.replyDraft, "Looks good")
    f.threads.comment(f.ref, close = StateReason.Completed)
    runCurrent()
    eventually { source.calls.isNotEmpty() }
    // The same draft is read-only in a reopened composer.
    assertFalse(f.drafts.writer(f.ref.replyDraft).write("One more thing"))
    source.gate!!.complete(Unit)
    eventually { "close COMPLETED" in source.calls }

    eventually { f.drafts.get(f.ref.replyDraft) == null }
  }

  @Test
  fun closingWithACommentPostsThenClosesAndRefetches() = dbTest { db ->
    val source = FakeThreads(count = 1)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.drafts.save(f.ref.replyDraft, "Fixed in #8")
    f.threads.comment(f.ref, close = StateReason.Completed)
    runCurrent()
    eventually { f.threads.observe(f.ref).first()!!.detail.state == ThreadState.Closed }
    assertEquals(listOf("comment Fixed in #8", "close COMPLETED"), source.calls)
  }

  @Test
  fun reactionsToggleOptimistically() = dbTest { db ->
    val source = FakeThreads(count = 1)
    source.detail = source.detail.copy(reactions = listOf(Reactions(Reaction.Heart, 2, false)))
    val f = fixture(db, source)
    f.threads.refresh(f.ref)

    f.threads.react(f.ref, "PR_1", Reaction.Heart)
    f.threads.react(f.ref, "0", Reaction.ThumbsUp)
    var snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(listOf(Reactions(Reaction.Heart, 3, true)), snapshot.detail.reactions)
    assertEquals(
      listOf(Reactions(Reaction.ThumbsUp, 1, true)),
      (snapshot.items.single().item as TimelineItem.Comment).reactions,
    )

    f.threads.react(f.ref, "0", Reaction.ThumbsUp)
    eventually { source.calls.size == 3 }
    snapshot = f.threads.observe(f.ref).first()!!
    assertEquals(emptyList(), (snapshot.items.single().item as TimelineItem.Comment).reactions)
    assertEquals(
      listOf("react PR_1 HEART true", "react 0 THUMBS_UP true", "react 0 THUMBS_UP false"),
      source.calls,
    )
  }

  @Test
  fun reviewThreadsGroupByReview() = dbTest { db ->
    val source = FakeThreads(count = 0)
    source.reviewThreads =
      listOf(
        ReviewThread("T1", "R1", "a.kt", 3, null, false, false, null, listOf(comment("c1", 1))),
        ReviewThread("T2", "R1", "b.kt", 9, null, true, false, null, listOf(comment("c2", 2))),
        ReviewThread("T3", "R2", "a.kt", 5, null, false, true, null, listOf(comment("c3", 3))),
      )
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    val grouped = f.threads.observe(f.ref).first()!!.reviewThreads
    assertEquals(setOf("T1", "T2"), grouped["R1"]!!.map { it.id }.toSet())
    assertEquals(listOf("T3"), grouped["R2"]!!.map { it.id })
  }

  @Test
  fun aFailedRefreshKeepsTheCacheAndReportsTheError() = dbTest { db ->
    val source = FakeThreads(count = 2)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    val failing =
      object : ThreadSource by source {
        override suspend fun load(repo: RepoRef, number: Int, headCount: Int, tailCount: Int) =
          error("Network is unreachable")
      }
    val g = fixture2(db, failing, f.ref)
    g.refresh(f.ref)
    assertEquals("Network is unreachable", g.status(f.ref).value.error)
    assertEquals(listOf("0", "1"), g.observe(f.ref).first()!!.ids())
  }

  private suspend fun TestScope.fixture2(
    db: TongsDatabase,
    source: ThreadSource,
    ref: ThreadRef,
  ): ThreadRepository {
    val tokens = InMemoryTokenStore()
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to Adapter(source))
    val clients = ForgeClients(db, tokens, adapters)
    val accounts = AccountRepository(db, tokens, adapters, clients)
    tokens.put(ref.accountId, "t")
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    return ThreadRepository(db, clients, accounts, drafts, backgroundScope, virtualClock())
  }

  @Test
  fun unknownReviewStatesSurviveTheCache() = dbTest { db ->
    val source = FakeThreads(count = 0)
    val f = fixture(db, source)
    source.count = 0
    f.threads.refresh(f.ref)
    val stored = TimelineItem.Review("R9", START, Actor("mona"), ReviewState.of("SUPERSEDED"), "")
    f.threads.store(
      f.ref,
      ThreadLoad(
        source.detail,
        page(listOf(stored), false, false),
        page(listOf(stored), false, false),
        1,
      ),
    )
    val review = f.threads.observe(f.ref).first()!!.items.single().item as TimelineItem.Review
    assertEquals("SUPERSEDED", review.state.raw)
  }

  @Test
  fun aCommentConfirmedAfterRefreshStartsSurvivesThatRefresh() = dbTest { db ->
    val source = FakeThreads(2)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    val gate = CompletableDeferred<Unit>().also { source.refreshGate = it }
    val read = backgroundScope.launch { f.threads.refresh(f.ref) }
    runCurrent()
    f.drafts.save(f.ref.replyDraft, "Posted during refresh")
    f.threads.comment(f.ref).await()
    gate.complete(Unit)
    read.join()
    assertEquals(listOf("0", "1", "posted"), f.threads.observe(f.ref).first()!!.ids())
    source.refreshGate = null
    f.threads.refresh(f.ref)
    assertEquals(listOf("0", "1"), f.threads.observe(f.ref).first()!!.ids())
  }

  @Test
  fun repeatedSubmitAndTypingStopBeforeABusyWriterReturns() = dbTest { db ->
    val source = FakeThreads(2)
    val f = fixture(db, source)
    f.threads.refresh(f.ref)
    f.drafts.save(f.ref.replyDraft, "First")
    val writer = f.drafts.writer(f.ref.replyDraft)
    val busy = CompletableDeferred<Unit>()
    writer.enqueue { busy.await() }
    writer.write("Last edit")
    val send = f.threads.comment(f.ref)
    assertFalse(writer.write("Typed during submit"))
    assertFalse(f.threads.comment(f.ref).await())
    // A dismissed caller cannot cancel preparation already accepted by the repository.
    val screen = backgroundScope.launch { send.await() }
    screen.cancel()
    busy.complete(Unit)
    send.await()
    assertEquals(listOf("comment Last edit"), source.calls)
    assertNull(f.drafts.get(f.ref.replyDraft))
  }
}

class DraftRepositoryTest {
  private val repo = RepoRef("octo", "hello")

  @Test
  fun draftsSaveAppendAndDeleteWhenBlank() = dbTest { db ->
    val (account, _) = signIn(db, FakeInbox())
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    val key = DraftKey(account, repo, 7)
    assertNull(drafts.get(key))
    assertFalse(drafts.hasDraft(account, repo, 7).first())

    drafts.save(key, "Thanks!")
    drafts.append(key, "> quoted")
    assertEquals("Thanks!\n\n> quoted", drafts.observe(key).first())
    assertTrue(drafts.hasDraft(account, repo, 7).first())
    assertEquals(setOf(DraftThread(account, repo, 7)), drafts.threadsWithDrafts().first())

    drafts.save(key, "   ")
    assertEquals("   ", drafts.get(key))
    assertEquals(emptySet(), drafts.threadsWithDrafts().first())
  }

  @Test
  fun inboxRowsShowTheirThreadsDraft() = dbTest { db ->
    val (account, _) = signIn(db, FakeInbox())
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    // Fakes' threads are octo/hello PRs numbered by their ID.
    db
      .inbox()
      .upsert(
        listOf(
          thread("7", START).toEntity(account, null),
          thread("8", START).toEntity(account, null),
        ),
      )
    drafts.save(DraftKey(account, repo, 7), "Thanks, merging after CI")
    val rows = db.inbox().observeInbox(START.toEpochMilliseconds()).first().associateBy { it.id }
    assertEquals("Thanks, merging after CI", rows.getValue("7").draft)
    assertNull(rows.getValue("8").draft)
  }

  @Test
  fun slotsKeepSeparateDraftsOnOneThread() = dbTest { db ->
    val (account, _) = signIn(db, FakeInbox())
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    val reply = DraftKey(account, repo, 7)
    val line = DraftKey(account, repo, 7, slot = "review/src/main.rs:42")
    drafts.save(reply, "Reply")
    drafts.save(line, "Nit")
    assertEquals("Reply", drafts.get(reply))
    assertEquals("Nit", drafts.get(line))
    drafts.delete(reply)
    assertTrue(drafts.hasDraft(account, repo, 7).first())
  }

  @Test
  fun draftsGoWithTheirAccount() = dbTest { db ->
    val tokens = InMemoryTokenStore()
    val (account, clients) = signIn(db, FakeInbox())
    val drafts = DraftRepository(db, backgroundScope, virtualClock())
    drafts.save(DraftKey(account, repo, 7), "Reply")
    AccountRepository(db, tokens, emptyMap(), clients).signOut(account)
    assertEquals(emptySet(), drafts.threadsWithDrafts().first())
  }

  @Test
  fun restartUnlocksEveryClaimedDestinationWithAWarningAndDoesNotReplay() = dbTest { db ->
    val (account, _) = signIn(db, FakeInbox())
    val job = kotlinx.coroutines.SupervisorJob()
    val app = CoroutineScope(job + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    val drafts = DraftRepository(db, app, virtualClock())
    val pr = PullRequestKey(account, repo, 7)
    val keys =
      listOf(
        DraftKey(account, repo, 7),
        DraftKey(
          account,
          repo,
          7,
          "line",
          DraftDestination.LineComment,
          dev.sargunv.tongs.forge.api.ReviewAnchor(
            "headA",
            "gone.kt",
            dev.sargunv.tongs.engine.DiffSide.New,
            3,
            3,
          ),
        ),
        DraftKey(account, repo, 7, "reply:T1", DraftDestination.ReviewReply, threadId = "T1"),
        DraftKey.summary(pr),
      )
    keys.forEach { drafts.save(it, "Keep ${it.slot}") }
    var deliveries = 0
    val network = CompletableDeferred<Unit>()
    val submission =
      drafts.submit(keys) {
        deliveries++
        network.await()
      }
    runCurrent()
    assertTrue(db.drafts().all(account, repo.owner, repo.name, 7).all { it.submitting })
    job.cancel()
    submission.join()
    val restarted = DraftRepository(db, backgroundScope, virtualClock())
    for (key in keys) {
      val editor = restarted.writer(key)
      editor.ready.first { it }
      assertTrue(editor.warning.value)
      assertFalse(editor.locked.value)
      assertEquals("Keep ${key.slot}", editor.body.value)
    }
    val rows = db.drafts().all(account, repo.owner, repo.name, 7)
    assertTrue(rows.all { it.mayHaveBeenSent && !it.submitting })
    assertEquals(1, deliveries)
  }
}

private fun <T> CoroutineScope.launchCollect(flow: Flow<T>, into: MutableList<T>) =
  launch(UnconfinedTestDispatcher()) { flow.collect { into += it } }

/**
 * Runs the scheduler until [check] passes. Room runs some transaction work off the test scheduler,
 * so background writes can land a moment after it goes idle.
 */
private suspend fun TestScope.eventually(check: suspend () -> Boolean) {
  repeat(200) {
    advanceUntilIdle()
    if (check()) return
    withContext(Dispatchers.Default) { delay(5) }
  }
  fail("Condition never held")
}
