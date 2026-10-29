package dev.sargunv.tongs.feature.review

import androidx.compose.runtime.snapshots.Snapshot
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.*
import dev.sargunv.tongs.engine.*
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Real Room with a deliberately slow layout: exercises the displayed snapshot, not overrides. */
class ChangesControllerTest {
  private val job = SupervisorJob()
  private val scope = CoroutineScope(job + Dispatchers.Unconfined)
  private val db =
    Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
      .setDriver(BundledSQLiteDriver())
      .build()
  private val clients = ForgeClients(db, InMemoryTokenStore(), emptyMap())
  private val drafts = DraftRepository(db, scope, Clock.System)
  private val repository = PullRequestChangesRepository(db, clients, drafts, scope, Clock.System)
  private val load = PullRequestLoad("octo/hello#1") {}
  private val base = TestSnapshot()
  private val loader = ReviewLoader(EmptySession, GitTransport { _, _ -> })

  private suspend fun controller(): ChangesController {
    val id =
      db
        .accounts()
        .upsert(
          AccountEntity(
            forge = "GitHub",
            webUrl = "https://github.com",
            apiUrl = "https://api.github.com",
            userId = "me",
            login = "me",
            name = null,
            avatarUrl = null,
            authMethod = "ClassicToken",
            capabilities = "",
            missingScopes = "",
          ),
        )
    val key = PullRequestKey(id, RepoRef("octo", "hello"), 1)
    repository.saveDraft(key, ReviewAnchor("head1", "Cargo.lock", DiffSide.New, 20, 20), "Why?")
    load.loader.value = loader
    val controller = ChangesController(scope, load, repository, drafts, key)
    loader.snapshots.send(base)
    until { controller.codeView != null && controller.items.isNotEmpty() }
    return controller
  }

  private suspend fun until(check: () -> Boolean) =
    withTimeout(5_000) {
      while (!check()) {
        Snapshot.sendApplyNotifications()
        delay(10)
      }
    }

  @AfterTest
  fun close() = runBlocking {
    base.release.countDown()
    job.cancelAndJoin()
    loader.close()
    db.close()
  }

  @Test
  fun snapshotsBelongToTheRetainedControllerUntilItsScopeEnds() = runBlocking {
    val controller = controller()
    val retained = controller.codeView!!.rows as TestSnapshot
    // A new screen reads the same controller; it doesn't acquire or release native ownership.
    assertSame(retained, controller.load.codeView!!.rows)
    assertFalse(base.closed)
    assertFalse(retained.closed)
    job.cancelAndJoin()
    assertTrue(base.closed)
    assertTrue(retained.closed)
  }

  @Test
  fun aNativeLayoutReturningAfterCancellationIsFreed() = runBlocking {
    val controller = controller()
    controller.jumpTo(controller.items.values.single())
    until { base.openLayoutStarted.count == 0L }
    job.cancel()
    base.release.countDown()
    job.join()
    assertTrue(base.closed)
    assertTrue(base.created.all { it.closed })
  }

  @Test
  fun aJumpWaitsUntilTheDisplayedSnapshotContainsTheDiscussion() = runBlocking {
    val controller = controller()
    val view = controller.codeView!!
    val item = controller.items.values.single()
    assertEquals(1, view.rows.rowCount) // A lockfile starts collapsed.
    controller.jumpTo(item)
    until { base.openLayoutStarted.count == 0L }
    assertEquals(0.0, view.offsetY)
    base.release.countDown()
    until { view.offsetY > 0 }
    assertEquals(17.0, view.offsetY) // Annotation at row 20, with three rows of context.
  }

  @Test
  fun draftsOnMissingFilesRemainEditableInTheInventory() = runBlocking {
    val controller = controller()
    val key = controller.key
    val draft =
      DraftKey(
        key.accountId,
        key.repo,
        key.number,
        "gone",
        DraftDestination.LineComment,
        ReviewAnchor("headA", "gone.kt", DiffSide.New, 3, 3),
      )
    drafts.save(draft, "Text on a file that left the diff")
    until { controller.allDrafts.any { it.draftKey == draft } }
    assertEquals(-1, controller.fileIndex("gone.kt"))
    val row = controller.allDrafts.single { it.draftKey == draft }
    assertFalse(row.inReview)
    controller.editDraft(row)
    val target = controller.composer as ComposerTarget.Line
    assertEquals("headA", target.commitOid)
    assertEquals(draft, controller.draftKey(target))
    assertNull(target.suggestion)
    controller.deleteDraft(row)
    until { controller.allDrafts.none { it.id == row.id } }
    assertEquals(draft, controller.draftKey(target))

    // Migrated replies retain their existing key, rather than copying to the new-reply slot.
    val reply =
      draft.copy(
        slot = "review/comment:42",
        destination = DraftDestination.ReviewReply,
        anchor = null,
        threadId = "thread1",
      )
    drafts.save(reply, "Existing reply")
    until { controller.allDrafts.any { it.draftKey == reply } }
    controller.editDraft(controller.allDrafts.single { it.draftKey == reply })
    assertEquals(reply, controller.draftKey(checkNotNull(controller.composer)))
  }

  @Test
  fun editingAnOlderPendingSuggestionNeverQuotesTheNewHeadsLines() = runBlocking {
    val controller = controller()
    val row = controller.drafts.single()
    // The snapshot has text at these line numbers, but comes from a later head.
    loader.startReview("base", "headB")
    controller.editDraft(row)
    val target = controller.composer as ComposerTarget.Line
    assertEquals("head1", target.commitOid)
    assertNull(target.suggestion)
    assertTrue(target.quoted.isEmpty())
    assertTrue(controller.draftLabel(row).contains("Outdated"))
  }
}

private class TestSnapshot(private val layout: ReviewLayout? = null) : ReviewSnapshot {
  var closed = false
  val created = java.util.concurrent.CopyOnWriteArrayList<TestSnapshot>()
  val openLayoutStarted = CountDownLatch(1)
  val release = CountDownLatch(1)
  private val collapsed
    get() = layout?.collapsed?.firstOrNull() != false

  override val fileCount = 1
  override val rowCount
    get() = if (collapsed) 1 else 100

  override fun filePath(file: Int) = "Cargo.lock"

  override fun fileIndex(path: String) = if (path == "Cargo.lock") 0 else -1

  override fun fileFirstRow(file: Int) = 0

  override fun fileLoaded(file: Int) = true

  override fun files() =
    listOf(
      ReviewFile(
        "Cargo.lock",
        null,
        ChangeKind.Modified,
        FileClass.Lockfile,
        99,
        0,
        "old",
        "new",
        true,
        false,
      ),
    )

  override fun rows(first: Int, count: Int) =
    (first until minOf(first + count, rowCount)).map {
      Row(if (it == 0) RowKind.FileHeader else RowKind.Context, 0, it, it, "line $it")
    }

  override fun findRow(file: Int, row: Row) =
    if (
      !collapsed && row.kind == RowKind.Annotation && layout!!.inserts.any { it.id == row.oldLine }
    )
      20
    else -1

  override fun withLayout(layout: ReviewLayout): ReviewSnapshot {
    check(!closed)
    if (!layout.collapsed[0]) {
      openLayoutStarted.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    return TestSnapshot(layout).also { created += it }
  }

  override fun close() {
    closed = true
  }
}

private object EmptySession : FetchSession {
  override fun wantCommits(commits: List<String>) {}

  override fun startReview(oldCommit: String, newCommit: String) {}

  override fun pollRequest(): PackRequest? = null

  override fun openSink(request: PackRequest): PackSink = error("no requests")

  override fun prioritize(paths: List<String>) {}

  override fun takeSnapshot(): ReviewSnapshot? = null

  override fun isAncestor(ancestor: String, descendant: String) = true

  override val isDone = true
  override val stats = FetchStats(0, 0, 0)

  override fun close() {}
}
