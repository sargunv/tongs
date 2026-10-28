package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.forge.api.DraftReviewComment
import dev.sargunv.tongs.forge.api.FileViewedState
import dev.sargunv.tongs.forge.api.PullRequestReviews
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.sargunv.tongs.forge.api.ReviewSubmission
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A PR, for one account. */
data class PullRequestKey(val accountId: Long, val repo: RepoRef, val number: Int)

/** A file at one version: viewed state is kept per version, so it resets when a file changes. */
data class FileVersion(val path: String, val blob: String)

class ReviewThreadWithComments(
  val thread: ReviewThreadEntity,
  val comments: List<ReviewCommentEntity>,
)

/** A pending review comment, with its anchor decoded. */
data class DraftComment(
  val id: Long,
  val anchor: ReviewAnchor,
  val body: String,
  val updatedAt: Long,
)

/**
 * The PR changes screen's data: the PR's review metadata and existing threads, viewed files, and
 * the pending review, all in Room. Viewed state is optimistic: it changes here at once, the forge
 * call follows in [scope], and a refusal undoes it with a message on [failures]. The pending review
 * is local until [submit] sends it as one batch, so drafts survive going offline and restarts.
 */
@SingleIn(AppScope::class)
@Inject
class PullRequestChangesRepository(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val scope: CoroutineScope,
  private val clock: Clock,
) {
  private val dao = db.pullRequestChanges()
  private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)

  /** Messages for changes the forge refused. */
  val failures: SharedFlow<String> = _failures

  fun overview(key: PullRequestKey): Flow<PullRequestOverviewEntity?> =
    dao.observeOverview(key.accountId, key.repo.owner, key.repo.name, key.number)

  /** Every file version marked viewed, including ones still on their way to the forge. */
  fun viewed(key: PullRequestKey): Flow<Set<FileVersion>> =
    dao
      .observeViewed(key.accountId, key.repo.owner, key.repo.name, key.number)
      .map { rows ->
        rows.filter { it.viewed }.mapTo(HashSet()) { FileVersion(it.path, it.blobOid) }
      }
      .distinctUntilChanged()

  fun drafts(key: PullRequestKey): Flow<List<DraftComment>> =
    dao.observeDrafts(key.accountId, key.repo.owner, key.repo.name, key.number).map { rows ->
      rows.map { it.toDraft() }
    }

  fun summary(key: PullRequestKey): Flow<String> =
    dao.observeDraftReview(key.accountId, key.repo.owner, key.repo.name, key.number).map {
      it?.summary.orEmpty()
    }

  fun threads(key: PullRequestKey): Flow<List<ReviewThreadWithComments>> =
    combine(
      dao.observeThreads(key.accountId, key.repo.owner, key.repo.name, key.number),
      dao.observeThreadComments(key.accountId, key.repo.owner, key.repo.name, key.number),
    ) { threads, comments ->
      val byThread = comments.groupBy { it.threadId }
      threads.map { ReviewThreadWithComments(it, byThread[it.id].orEmpty()) }
    }

  /** Fetches the PR's review metadata and threads. Throws if the forge can't be reached. */
  suspend fun refresh(key: PullRequestKey) {
    val api = api(key)
    val overview = api.overview(key.repo, key.number)
    val threads = api.threads(key.repo, key.number)
    val now = clock.now().toEpochMilliseconds()
    db.withWriteTransaction {
      dao.upsertOverview(
        PullRequestOverviewEntity(
          accountId = key.accountId,
          owner = key.repo.owner,
          repo = key.repo.name,
          number = key.number,
          nodeId = overview.id,
          title = overview.title,
          state = overview.state,
          headOid = overview.headOid,
          authorLogin = overview.authorLogin,
          webUrl = overview.webUrl,
          viewerDidAuthor = overview.viewerDidAuthor,
          viewerReviewRequested = overview.viewerReviewRequested,
          viewerHasPendingReview = overview.viewerHasPendingReview,
          syncedAt = now,
        ),
      )
      dao.deleteThreads(key.accountId, key.repo.owner, key.repo.name, key.number)
      dao.upsertThreads(
        threads.mapIndexed { i, t ->
          ReviewThreadEntity(
            accountId = key.accountId,
            id = t.id,
            owner = key.repo.owner,
            repo = key.repo.name,
            number = key.number,
            path = t.path,
            side = t.side.name,
            startSide = t.startSide?.name,
            line = t.line,
            startLine = t.startLine,
            originalLine = t.originalLine,
            isResolved = t.isResolved,
            isOutdated = t.isOutdated,
            isFileLevel = t.isFileLevel,
            position = i,
          )
        },
      )
      dao.upsertThreadComments(
        threads.flatMap { t ->
          t.comments.mapIndexed { i, c ->
            ReviewCommentEntity(
              accountId = key.accountId,
              id = c.id,
              threadId = t.id,
              authorLogin = c.authorLogin,
              authorAvatarUrl = c.authorAvatarUrl,
              body = c.body,
              createdAt = c.createdAt.toEpochMilliseconds(),
              position = i,
            )
          }
        },
      )
    }
  }

  /**
   * Takes the forge's viewed state for [files], the PR's current versions. A file the forge says is
   * viewed is viewed at that version; one it says isn't, or changed since, isn't. Changes still on
   * their way to the forge are left alone, and so are files the forge didn't list.
   */
  suspend fun syncViewed(key: PullRequestKey, files: Collection<FileVersion>) {
    val remote = api(key).viewedFiles(key.repo, key.number) ?: return
    mergeViewed(key, files, remote)
  }

  internal suspend fun mergeViewed(
    key: PullRequestKey,
    files: Collection<FileVersion>,
    remote: Map<String, FileViewedState>,
  ) {
    val now = clock.now().toEpochMilliseconds()
    db.withWriteTransaction {
      val local =
        dao.viewed(key.accountId, key.repo.owner, key.repo.name, key.number).associateBy {
          FileVersion(it.path, it.blobOid)
        }
      val viewed = mutableListOf<ViewedFileEntity>()
      for (file in files) {
        val row = local[file]
        if (row?.pending == true) continue
        when (remote[file.path]) {
          FileViewedState.Viewed ->
            if (row?.viewed != true) viewed += key.viewedRow(file, true, pending = false, now)
          FileViewedState.Unviewed,
          FileViewedState.Dismissed ->
            if (row != null) {
              dao.deleteViewed(
                key.accountId,
                key.repo.owner,
                key.repo.name,
                key.number,
                file.path,
                file.blob,
              )
            }
          null -> Unit
        }
      }
      if (viewed.isNotEmpty()) dao.upsertViewed(viewed)
    }
  }

  /** Marks [file] viewed or not, here at once and then on the forge. */
  suspend fun setViewed(key: PullRequestKey, file: FileVersion, viewed: Boolean) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val before = dao.viewedFile(key.accountId, owner, repo, key.number, file.path, file.blob)
    if ((before?.viewed == true) == viewed && before?.pending != true) return
    val now = clock.now().toEpochMilliseconds()
    val mine = key.viewedRow(file, viewed, pending = true, now)
    dao.upsertViewed(listOf(mine))
    scope.launch {
      // One at a time, so the forge sees toggles in the order they happened.
      viewedCalls.withLock {
        val result = runCatching {
          val prId =
            dao.overview(key.accountId, owner, repo, key.number)?.nodeId
              ?: error("the PR hasn't loaded yet")
          api(key).setViewed(prId, file.path, viewed)
        }
        // A later toggle owns the row now; it will settle it.
        val current = dao.viewedFile(key.accountId, owner, repo, key.number, file.path, file.blob)
        if (current != mine) return@withLock
        result
          .onSuccess {
            if (viewed) dao.upsertViewed(listOf(mine.copy(pending = false)))
            else dao.deleteViewed(key.accountId, owner, repo, key.number, file.path, file.blob)
          }
          .onFailure {
            if (before == null) {
              dao.deleteViewed(key.accountId, owner, repo, key.number, file.path, file.blob)
            } else {
              dao.upsertViewed(listOf(before.copy(pending = false)))
            }
            val what = if (viewed) "mark ${file.path} viewed" else "unmark ${file.path}"
            _failures.tryEmit("Couldn't $what: ${it.message}")
          }
      }
    }
  }

  private val viewedCalls = Mutex()

  /**
   * Saves a line comment to the pending review, creating the review if it's the first. Pass the
   * draft's [id] to edit it. Returns its id.
   */
  suspend fun saveDraft(
    key: PullRequestKey,
    anchor: ReviewAnchor,
    body: String,
    id: Long = 0,
  ): Long {
    val now = clock.now().toEpochMilliseconds()
    val created = if (id != 0L) dao.draft(id)?.createdAt ?: now else now
    val entity =
      DraftCommentEntity(
        id = id,
        accountId = key.accountId,
        owner = key.repo.owner,
        repo = key.repo.name,
        number = key.number,
        commitOid = anchor.commitOid,
        path = anchor.path,
        side = anchor.side.name,
        startSide = anchor.startSide.name,
        startLine = anchor.startLine,
        endLine = anchor.endLine,
        body = body,
        createdAt = created,
        updatedAt = now,
      )
    val rowId = dao.upsertDraft(entity)
    return if (id != 0L) id else rowId
  }

  suspend fun deleteDraft(id: Long) = dao.deleteDraft(id)

  suspend fun setSummary(key: PullRequestKey, summary: String) =
    dao.upsertDraftReview(
      DraftReviewEntity(
        key.accountId,
        key.repo.owner,
        key.repo.name,
        key.number,
        summary,
        clock.now().toEpochMilliseconds(),
      ),
    )

  /**
   * Submits the pending review: [summary], [event], and every draft, in one call. The review pins
   * the commit its drafts were written against, or [headOid] without drafts or if they span
   * several. On success the drafts are gone and the threads refreshed; on failure they stay, and
   * the exception says why.
   */
  suspend fun submit(key: PullRequestKey, event: ReviewEvent, summary: String, headOid: String) {
    val drafts = dao.drafts(key.accountId, key.repo.owner, key.repo.name, key.number)
    val commit = drafts.map { it.commitOid }.distinct().singleOrNull() ?: headOid
    api(key)
      .submitReview(
        key.repo,
        key.number,
        ReviewSubmission(
          commitOid = commit,
          event = event,
          body = summary,
          comments = drafts.map { DraftReviewComment(it.toDraft().anchor, it.body) },
        ),
      )
    db.withWriteTransaction {
      dao.deleteDrafts(
        key.accountId,
        key.repo.owner,
        key.repo.name,
        key.number,
        drafts.map { it.id },
      )
      dao.deleteDraftReview(key.accountId, key.repo.owner, key.repo.name, key.number)
    }
    runCatching { refresh(key) }
  }

  private suspend fun api(key: PullRequestKey): PullRequestReviews {
    val client = clients.client(key.accountId) ?: error("This account is signed out")
    return client.pullRequestReviews ?: error("This forge can't review PRs here yet")
  }

  private fun PullRequestKey.viewedRow(
    file: FileVersion,
    viewed: Boolean,
    pending: Boolean,
    at: Long,
  ) =
    ViewedFileEntity(
      accountId,
      repo.owner,
      repo.name,
      number,
      file.path,
      file.blob,
      viewed,
      pending,
      at,
    )
}

private fun DraftCommentEntity.toDraft() =
  DraftComment(
    id = id,
    anchor =
      ReviewAnchor(
        commitOid = commitOid,
        path = path,
        side = DiffSide.valueOf(side),
        startLine = startLine,
        endLine = endLine,
        startSide = DiffSide.valueOf(startSide),
      ),
    body = body,
    updatedAt = updatedAt,
  )
