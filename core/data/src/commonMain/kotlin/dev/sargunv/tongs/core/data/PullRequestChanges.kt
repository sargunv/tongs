package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A PR, for one account. */
data class PullRequestKey(val accountId: Long, val repo: RepoRef, val number: Int)

/** A file at one version: viewed state is kept per version, so it resets when a file changes. */
data class FileVersion(val path: String, val blob: String)

class ReviewThreadWithComments(
  val thread: ChangesThreadEntity,
  val comments: List<ChangesThreadCommentEntity>,
)

/**
 * The PR changes screen's data: the PR's review metadata and existing threads, viewed files, and
 * the pending review, all in Room. Viewed state, replies, and resolving are optimistic: they change
 * here at once, the forge call follows in [scope], and a refusal undoes them with a message on
 * [failures]. The pending review is local until [submit] sends it as one batch, so drafts survive
 * going offline and restarts.
 */
@SingleIn(AppScope::class)
@Inject
class PullRequestChangesRepository(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val drafts: DraftRepository,
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

  fun drafts(key: PullRequestKey): Flow<List<DraftEntity>> =
    drafts.all(key).map { rows ->
      rows.filter {
        (it.body.isNotBlank() || it.inReview) &&
          (it.destination == DraftDestination.LineComment ||
            it.destination == DraftDestination.ReviewReply)
      }
    }

  fun summary(key: PullRequestKey): Flow<String> =
    drafts.observe(DraftKey.summary(key)).map { it.orEmpty() }

  fun reviewStarted(key: PullRequestKey): Flow<Boolean> =
    drafts.all(key).map { rows -> rows.any { it.inReview } }.distinctUntilChanged()

  fun pendingReviewContinued(key: PullRequestKey): Flow<Boolean> =
    combine(overview(key), drafts.all(key)) { overview, rows ->
      overview?.pendingReviewId == null ||
        rows.any { it.continuedReviewId == overview.pendingReviewId }
    }

  fun threads(key: PullRequestKey): Flow<List<ReviewThreadWithComments>> =
    combine(
      dao.observeThreads(key.accountId, key.repo.owner, key.repo.name, key.number),
      dao.observeThreadComments(key.accountId, key.repo.owner, key.repo.name, key.number),
    ) { threads, comments ->
      val byThread = comments.groupBy { it.threadId }
      threads.map {
        ReviewThreadWithComments(it, byThread[it.id].orEmpty())
      }
    }

  /** Fetches the PR's review metadata and threads. Throws if the forge can't be reached. */
  suspend fun refresh(key: PullRequestKey) {
    val started = db.withWriteTransaction { confirmationSequence }
    val api = api(key)
    val overview = api.overview(key.repo, key.number)
    val threads = api.threads(key.repo, key.number)
    val now = clock.now().toEpochMilliseconds()
    db.withWriteTransaction {
      // Replies still on their way to the forge outlive the refresh, if their thread does.
      val sending =
        dao.sendingComments(key.accountId, key.repo.owner, key.repo.name, key.number).filter {
          it.id in sendingReplies
        }
      val newer =
        dao
          .observeThreadComments(key.accountId, key.repo.owner, key.repo.name, key.number)
          .first()
          .filter { (confirmations[key to it.id] ?: 0) > started }
      val newerThreads =
        dao
          .observeThreads(key.accountId, key.repo.owner, key.repo.name, key.number)
          .first()
          .filter { t -> newer.any { it.threadId == t.id } }
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
          viewerHasPendingReview = overview.pendingReview != null,
          pendingReviewId = overview.pendingReview?.id,
          pendingReviewBody = overview.pendingReview?.body,
          pendingReviewComments = overview.pendingReview?.commentCount ?: 0,
          syncedAt = now,
        ),
      )
      dao.deleteThreads(key.accountId, key.repo.owner, key.repo.name, key.number)
      dao.upsertThreads(
        threads.mapIndexed { i, t ->
          ChangesThreadEntity(
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
            isResolved = resolving[key to t.id] ?: t.isResolved,
            isOutdated = t.isOutdated,
            isFileLevel = t.isFileLevel,
            position = i,
            viewerCanReply = t.viewerCanReply,
            viewerCanResolve = t.viewerCanResolve && (key to t.id) !in resolving,
            viewerCanUnresolve = t.viewerCanUnresolve && (key to t.id) !in resolving,
            resolvedBy = t.resolvedBy,
          )
        },
      )
      dao.upsertThreadComments(
        threads.flatMap { t ->
          t.comments.mapIndexed { i, c ->
            ChangesThreadCommentEntity(
              accountId = key.accountId,
              id = c.id,
              threadId = t.id,
              authorLogin = c.authorLogin,
              authorAvatarUrl = c.authorAvatarUrl,
              body = c.body,
              createdAt = c.createdAt.toEpochMilliseconds(),
              position = i,
              state = if (c.pending) CommentState.Pending else CommentState.Published,
            )
          }
        },
      )
      val remoteIds = threads.flatMap { it.comments }.mapTo(HashSet()) { it.id }
      dao.upsertThreads(newerThreads.filter { t -> threads.none { it.id == t.id } })
      dao.upsertThreadComments(newer.filter { it.id !in remoteIds })
      val threadIds = (threads.map { it.id } + newerThreads.map { it.id }).toSet()
      val sizes = threads.associate { it.id to it.comments.size }
      dao.upsertThreadComments(
        sending
          .filter { it.threadId in threadIds }
          .map { it.copy(position = (sizes[it.threadId] ?: 0) + it.position) },
      )
    }
  }

  /**
   * Takes the forge's viewed state for [files], the PR's current versions. A file the forge says is
   * viewed is viewed at that version; one it says isn't, or changed since, isn't. Changes still on
   * their way to the forge are left alone, and so are files the forge didn't list.
   *
   * A change the app was sending when it last stopped is still marked pending in Room with nothing
   * sending it; it goes to the forge again only if its path and blob are still current. Obsolete
   * mutations are deleted. If the forge refuses, it's dropped, and the next sync takes the forge's
   * state.
   */
  suspend fun syncViewed(key: PullRequestKey, files: Collection<FileVersion>) {
    val (owner, repo) = key.repo.owner to key.repo.name
    for (row in dao.viewed(key.accountId, owner, repo, key.number)) {
      if (!row.pending) continue
      val id = ViewedId(key, FileVersion(row.path, row.blobOid))
      val abandoned = viewedLock.withLock { (id !in queued).also { if (it) confirmed[id] = null } }
      if (!abandoned) continue
      if (id.file in files) {
        send(key, id.file, row.viewed, row)
      } else {
        dao.deleteViewed(key.accountId, owner, repo, key.number, row.path, row.blobOid)
        viewedLock.withLock { confirmed -= id }
      }
    }
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
    val now = maxOf(clock.now().toEpochMilliseconds(), (before?.updatedAt ?: 0) + 1)
    val mine = key.viewedRow(file, viewed, pending = true, now)
    dao.upsertViewed(listOf(mine))
    val id = ViewedId(key, file)
    // With nothing queued for the file, what's here is what the forge last confirmed.
    viewedLock.withLock { if (id !in queued) confirmed[id] = before?.takeIf { !it.pending } }
    send(key, file, viewed, mine)
  }

  /**
   * Queues the forge call that settles [mine], the row in Room for [file]. Calls run one at a time,
   * so the forge sees toggles in the order they happened. The last call queued owns the row: if it
   * fails, the file goes back to what the forge last confirmed, which earlier calls in the queue
   * may have changed.
   */
  private suspend fun send(
    key: PullRequestKey,
    file: FileVersion,
    viewed: Boolean,
    mine: ViewedFileEntity,
  ) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val id = ViewedId(key, file)
    viewedLock.withLock { queued[id] = (queued[id] ?: 0) + 1 }
    scope.launch {
      viewedCalls.withLock {
        val result = runCatching {
          val prId =
            dao.overview(key.accountId, owner, repo, key.number)?.nodeId
              ?: error("the PR hasn't loaded yet")
          api(key).setViewed(prId, file.path, viewed)
        }
        val baseline = viewedLock.withLock {
          if (result.isSuccess) confirmed[id] = mine.copy(pending = false).takeIf { viewed }
          val left = (queued[id] ?: 1) - 1
          if (left == 0) queued -= id else queued[id] = left
          confirmed[id].also { if (left == 0) confirmed -= id }
        }
        // A later toggle owns the row now; it will settle it.
        val current = dao.viewedFile(key.accountId, owner, repo, key.number, file.path, file.blob)
        if (current == mine) {
          if (baseline == null) {
            dao.deleteViewed(key.accountId, owner, repo, key.number, file.path, file.blob)
          } else {
            dao.upsertViewed(listOf(baseline))
          }
        }
        result.onFailure {
          val what = if (viewed) "mark ${file.path} viewed" else "unmark ${file.path}"
          _failures.tryEmit("Couldn't $what: ${it.message}")
        }
      }
    }
  }

  /** A file version of one PR, for the viewed-state queue. */
  private data class ViewedId(val key: PullRequestKey, val file: FileVersion)

  private val viewedCalls = Mutex()
  private val viewedLock = Mutex()
  /** Calls queued per file, and the state the forge last confirmed for files with calls queued. */
  private val queued = HashMap<ViewedId, Int>()
  private val confirmed = HashMap<ViewedId, ViewedFileEntity?>()

  /** Creates a draft with a fixed destination; subsequent edits touch only its body. */
  suspend fun saveDraft(
    key: PullRequestKey,
    anchor: ReviewAnchor,
    body: String,
    id: Long = 0,
    inReplyTo: String? = null,
  ): Long {
    val existing = if (id != 0L) db.drafts().byId(id) else null
    val draft =
      existing?.draftKey
        ?: DraftKey(
          key.accountId,
          key.repo,
          key.number,
          "review/comment:${kotlin.random.Random.nextLong()}",
          if (inReplyTo == null) DraftDestination.LineComment else DraftDestination.ReviewReply,
          anchor.takeIf { inReplyTo == null },
          inReplyTo,
        )
    drafts.save(draft, body)
    drafts.membership(draft, true)
    drafts.writer(draft).flush()
    return checkNotNull(db.drafts().get(draft.accountId, draft.value)).id
  }

  suspend fun setSummary(key: PullRequestKey, summary: String) {
    val draft = DraftKey.summary(key)
    drafts.save(draft, summary)
    drafts.membership(draft, true)
    drafts.writer(draft).flush()
  }

  /** Claim in Room, deliver outside the transaction, then delete or unlock the same rows. */
  fun submit(
    key: PullRequestKey,
    event: ReviewEvent,
    headOid: String,
  ): kotlinx.coroutines.Deferred<Boolean> =
    drafts.submitReview(key) { rows ->
      val summary = rows.singleOrNull { it.destination == DraftDestination.Summary }?.body.orEmpty()
      val comments = rows.filter { it.destination != DraftDestination.Summary }
      val replies = comments.filter { it.destination == DraftDestination.ReviewReply }
      val lines = comments.filter { it.destination == DraftDestination.LineComment }
      check(lines.all { !it.commitOid.isNullOrEmpty() }) {
        "A draft's anchor is unknown. Discard it or create a new comment on the intended lines."
      }
      val commits = lines.map { it.commitOid!! }.distinct()
      check(commits.size <= 1) { "Drafts refer to different commits. Submit them separately." }
      val commit = commits.singleOrNull() ?: headOid
      val overview = dao.overview(key.accountId, key.repo.owner, key.repo.name, key.number)
      check(
        overview?.pendingReviewId == null ||
          rows.any { it.continuedReviewId == overview.pendingReviewId },
      ) {
        "Continue or discard the review started elsewhere first"
      }
      val api = api(key)
      try {
        val remote = overview?.pendingReviewId
        if (remote == null && replies.isEmpty()) {
          api.submitReview(
            key.repo,
            key.number,
            ReviewSubmission(
              commit,
              event,
              summary,
              lines.map { DraftReviewComment(checkNotNull(it.anchor), it.body) },
            ),
          )
        } else {
          val reviewId =
            remote
              ?: api
                .startPendingReview(
                  overview?.nodeId ?: error("The PR hasn't loaded yet"),
                  commit,
                  lines.map { DraftReviewComment(checkNotNull(it.anchor), it.body) },
                )
                .also { drafts.accepted(lines) }
          if (remote != null)
            for (line in lines) {
              api.addToPendingReview(
                reviewId,
                DraftReviewComment(checkNotNull(line.anchor), line.body),
              )
              drafts.accepted(listOf(line))
            }
          for (reply in replies) {
            api.reply(checkNotNull(reply.inReplyTo), reply.body, pendingReviewId = reviewId)
            drafts.accepted(listOf(reply))
          }
          api.submitPendingReview(reviewId, event, summary)
        }
      } finally {
        runCatching { refresh(key) }
      }
    }

  /** Continue explicitly: import the summary only when no local summary exists. */
  suspend fun continuePendingReview(key: PullRequestKey) {
    val draft = DraftKey.summary(key)
    val local = drafts.get(draft)
    val remote =
      dao
        .overview(key.accountId, key.repo.owner, key.repo.name, key.number)
        ?.pendingReviewBody
        .orEmpty()
    setSummary(key, local ?: remote)
    val reviewId =
      dao.overview(key.accountId, key.repo.owner, key.repo.name, key.number)?.pendingReviewId
        ?: return
    db.drafts().continueReview(key.accountId, draft.value, reviewId)
  }

  /**
   * Discards the review left pending on the forge by another client, with its comments. They go
   * from here at once; if the forge refuses, the refresh brings them back and [failures] says why.
   */
  suspend fun discardPendingReview(key: PullRequestKey) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val reviewId = dao.overview(key.accountId, owner, repo, key.number)?.pendingReviewId ?: return
    val before = overview(key).first()
    val beforeThreads = threads(key).first()
    db.withWriteTransaction {
      dao.clearPendingReview(key.accountId, owner, repo, key.number)
      dao.deletePendingComments(key.accountId, owner, repo, key.number)
      dao.deleteEmptyThreads(key.accountId, owner, repo, key.number)
    }
    scope.launch {
      runCatching { api(key).deletePendingReview(reviewId) }
        .onFailure {
          db.withWriteTransaction {
            before?.let { dao.upsertOverview(it) }
            dao.upsertThreads(beforeThreads.map { it.thread })
            dao.upsertThreadComments(beforeThreads.flatMap { it.comments })
          }
          _failures.tryEmit("Couldn't discard the pending review: ${it.message}")
        }
      runCatching { refresh(key) }
    }
  }

  /** An immediate reply uses the same app-scoped claim as a batch review. */
  fun reply(key: PullRequestKey, draft: DraftKey): kotlinx.coroutines.Deferred<Boolean> =
    drafts.submit(listOf(draft)) { rows ->
      val row = rows.single()
      val threadId = checkNotNull(row.inReplyTo)
      val account = db.accounts().get(key.accountId)
      val placeholder =
        ChangesThreadCommentEntity(
          key.accountId,
          "sending:${clock.now().toEpochMilliseconds()}:${placeholders++}",
          threadId,
          account?.login,
          account?.avatarUrl,
          row.body,
          clock.now().toEpochMilliseconds(),
          dao.nextCommentPosition(key.accountId, threadId),
          CommentState.Sending,
        )
      db.withWriteTransaction {
        sendingReplies += placeholder.id
        dao.upsertThreadComments(listOf(placeholder))
      }
      try {
        val comment = api(key).reply(threadId, row.body)
        db.withWriteTransaction {
          sendingReplies -= placeholder.id
          dao.deleteThreadComment(key.accountId, placeholder.id)
          val confirmed =
            placeholder.copy(
              id = comment.id,
              authorLogin = comment.authorLogin,
              authorAvatarUrl = comment.authorAvatarUrl,
              body = comment.body,
              createdAt = comment.createdAt.toEpochMilliseconds(),
              state = if (comment.pending) CommentState.Pending else CommentState.Published,
            )
          confirmations[key to comment.id] = ++confirmationSequence
          if (dao.thread(key.accountId, threadId) != null)
            dao.upsertThreadComments(listOf(confirmed))
        }
      } catch (e: Exception) {
        db.withWriteTransaction {
          sendingReplies -= placeholder.id
          dao.deleteThreadComment(key.accountId, placeholder.id)
        }
        _failures.tryEmit("Couldn't reply: ${e.message}")
        throw e
      }
    }

  private var confirmationSequence = 0L
  private val confirmations = HashMap<Pair<PullRequestKey, String>, Long>()
  private var placeholders = 0
  /** Guarded by Room transactions; an orphaned sending row is dropped on the next refresh. */
  private val sendingReplies = HashSet<String>()

  /** Resolves thread [threadId] or reopens it, here at once and then on the forge. */
  suspend fun setResolved(key: PullRequestKey, threadId: String, resolved: Boolean) {
    val id = key to threadId
    val before =
      db.withWriteTransaction<ChangesThreadEntity?> {
        if (id in resolving) return@withWriteTransaction null
        val row = dao.thread(key.accountId, threadId) ?: return@withWriteTransaction null
        if (row.isResolved == resolved) return@withWriteTransaction null
        resolving[id] = resolved
        dao.setThreadResolved(key.accountId, threadId, resolved, false, false)
        row
      } ?: return
    scope.launch {
      val result = runCatching { api(key).setResolved(threadId, resolved) }
      db.withWriteTransaction {
        resolving -= id
        val settled = if (result.isSuccess) resolved else before.isResolved
        dao.setThreadResolved(
          key.accountId,
          threadId,
          settled,
          if (result.isSuccess) !settled else before.viewerCanResolve,
          if (result.isSuccess) settled else before.viewerCanUnresolve,
        )
      }
      result.onFailure {
        _failures.tryEmit(
          "Couldn't ${if (resolved) "resolve" else "unresolve"} the thread: ${it.message}",
        )
      }
    }
  }

  /** Guarded by Room's write transaction; a thread's action stays disabled until it settles. */
  private val resolving = HashMap<Pair<PullRequestKey, String>, Boolean>()

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
