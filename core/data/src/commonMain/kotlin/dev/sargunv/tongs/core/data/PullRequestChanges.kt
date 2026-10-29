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
 * A pending review comment, with its anchor decoded: a line comment, or a reply to thread
 * [inReplyTo], anchored where the thread is.
 */
data class DraftComment(
  val id: Long,
  val anchor: ReviewAnchor,
  val body: String,
  val updatedAt: Long,
  val inReplyTo: String? = null,
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
    combine(
      dao.observeOverview(key.accountId, key.repo.owner, key.repo.name, key.number),
      cacheFlow(key),
    ) { row, cache ->
      row?.let(cache::overview)
    }

  /** Every file version marked viewed, including ones still on their way to the forge. */
  fun viewed(key: PullRequestKey): Flow<Set<FileVersion>> =
    dao
      .observeViewed(key.accountId, key.repo.owner, key.repo.name, key.number)
      .map { rows ->
        rows.filter { it.viewed }.mapTo(HashSet()) { FileVersion(it.path, it.blobOid) }
      }
      .distinctUntilChanged()

  fun drafts(key: PullRequestKey): Flow<List<DraftComment>> =
    combine(
      dao.observeDrafts(key.accountId, key.repo.owner, key.repo.name, key.number),
      cacheFlow(key),
    ) { rows, cache ->
      rows.map { cache.draft(it).toDraft() }
    }

  fun summary(key: PullRequestKey): Flow<String> =
    dao.observeDraftReview(key.accountId, key.repo.owner, key.repo.name, key.number).map {
      it?.summary.orEmpty()
    }

  /**
   * Whether a review is under way here: it has a summary row, which [continuePendingReview] also
   * makes. A pending review from another client asks to be continued or discarded until it is.
   */
  fun reviewStarted(key: PullRequestKey): Flow<Boolean> =
    dao
      .observeDraftReview(key.accountId, key.repo.owner, key.repo.name, key.number)
      .map { it != null }
      .distinctUntilChanged()

  fun threads(key: PullRequestKey): Flow<List<ReviewThreadWithComments>> =
    combine(
      dao.observeThreads(key.accountId, key.repo.owner, key.repo.name, key.number),
      dao.observeThreadComments(key.accountId, key.repo.owner, key.repo.name, key.number),
      cacheFlow(key),
    ) { threads, comments, cache ->
      val byThread = comments.groupBy { it.threadId }
      threads.map {
        ReviewThreadWithComments(cache.thread(it), byThread[it.id].orEmpty().map(cache::comment))
      }
    }

  /** Fetches the PR's review metadata and threads. Throws if the forge can't be reached. */
  suspend fun refresh(key: PullRequestKey) {
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
      val oldCache = cache(key)
      saveCache(
        key,
        oldCache.copy(
          pendingId = overview.pendingReview?.id,
          pendingBody = overview.pendingReview?.body,
          pendingCount = overview.pendingReview?.commentCount ?: 0,
          permissions =
            threads.associate {
              it.id to
                ThreadPermissions(
                  it.viewerCanReply,
                  it.viewerCanResolve && (key to it.id) !in resolving,
                  it.viewerCanUnresolve && (key to it.id) !in resolving,
                  it.resolvedBy,
                )
            },
          pendingComments =
            threads.flatMap { it.comments }.filter { it.pending }.mapTo(HashSet()) { it.id },
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
            viewerCanResolve = t.viewerCanResolve,
            viewerCanUnresolve = t.viewerCanUnresolve,
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
      val threadIds = threads.mapTo(HashSet()) { it.id }
      val sizes = threads.associate { it.id to it.comments.size }
      dao.upsertThreadComments(
        sending
          .filter { it.threadId in threadIds }
          .map { it.copy(position = sizes.getValue(it.threadId) + it.position) },
      )
    }
  }

  /**
   * Takes the forge's viewed state for [files], the PR's current versions. A file the forge says is
   * viewed is viewed at that version; one it says isn't, or changed since, isn't. Changes still on
   * their way to the forge are left alone, and so are files the forge didn't list.
   *
   * A change the app was sending when it last stopped is still marked pending in Room with nothing
   * sending it; it goes to the forge again now. If the forge refuses, it's dropped, and the next
   * sync takes the forge's state.
   */
  suspend fun syncViewed(key: PullRequestKey, files: Collection<FileVersion>) {
    val (owner, repo) = key.repo.owner to key.repo.name
    for (row in dao.viewed(key.accountId, owner, repo, key.number)) {
      if (!row.pending) continue
      val id = ViewedId(key, FileVersion(row.path, row.blobOid))
      val abandoned = viewedLock.withLock { (id !in queued).also { if (it) confirmed[id] = null } }
      if (abandoned) send(key, id.file, row.viewed, row)
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

  /**
   * Saves a comment to the pending review, creating the review if it's the first: a line comment at
   * [anchor], or a reply to thread [inReplyTo] anchored where it is. Pass the draft's [id] to edit
   * it. Returns its id.
   */
  suspend fun saveDraft(
    key: PullRequestKey,
    anchor: ReviewAnchor,
    body: String,
    id: Long = 0,
    inReplyTo: String? = null,
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
        inReplyTo = inReplyTo,
      )
    return db.withWriteTransaction {
      val rowId = dao.upsertDraft(entity)
      val savedId = if (id != 0L) id else rowId
      val cache = cache(key)
      saveCache(
        key,
        cache.copy(
          replies =
            cache.replies - savedId + (inReplyTo?.let { mapOf(savedId to it) } ?: emptyMap()),
        ),
      )
      savedId
    }
  }

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
   * Submits the pending review: [summary], [event], and every draft. The review pins the commit its
   * drafts were written against, or [headOid] without drafts or if they span several.
   *
   * Line comments alone go in one call, so it all lands or none of it does. Replies to threads, and
   * a pending review left on the forge by another client, need several: the review is started (or
   * continued) on the forge, each draft is added and then deleted here, and the review is
   * submitted. If a step fails, what the forge already has stays in its pending review, which shows
   * here after the refresh, and the rest stays here; submitting again picks up where it stopped. On
   * success the drafts are gone and the threads refreshed; on failure the exception says why.
   */
  suspend fun submit(key: PullRequestKey, event: ReviewEvent, summary: String, headOid: String) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val cache = cache(key)
    val drafts = dao.drafts(key.accountId, owner, repo, key.number).map(cache::draft)
    val commit = drafts.map { it.commitOid }.distinct().singleOrNull() ?: headOid
    val (replies, lines) = drafts.partition { it.inReplyTo != null }
    val overview = dao.overview(key.accountId, owner, repo, key.number)?.let(cache::overview)
    val api = api(key)
    try {
      val remote = overview?.pendingReviewId
      if (remote == null && replies.isEmpty()) {
        api.submitReview(
          key.repo,
          key.number,
          ReviewSubmission(
            commitOid = commit,
            event = event,
            body = summary,
            comments = lines.map { DraftReviewComment(it.toDraft().anchor, it.body) },
          ),
        )
        deleteSubmitted(lines)
      } else {
        val reviewId =
          remote
            ?: api
              .startPendingReview(
                pullRequestId = overview?.nodeId ?: error("the PR hasn't loaded yet"),
                commitOid = commit,
                comments = lines.map { DraftReviewComment(it.toDraft().anchor, it.body) },
              )
              .also { deleteSubmitted(lines) }
        if (remote != null) {
          for (line in lines) {
            api.addToPendingReview(reviewId, DraftReviewComment(line.toDraft().anchor, line.body))
            deleteSubmitted(listOf(line))
          }
        }
        for (reply in replies) {
          api.reply(checkNotNull(reply.inReplyTo), reply.body, pendingReviewId = reviewId)
          deleteSubmitted(listOf(reply))
        }
        api.submitPendingReview(reviewId, event, summary)
      }
      db.withWriteTransaction {
        if (dao.draftReview(key.accountId, owner, repo, key.number)?.summary == summary) {
          dao.deleteDraftReview(key.accountId, owner, repo, key.number)
        }
        for (draft in drafts) {
          if (dao.draft(draft.id) == null)
            this@PullRequestChangesRepository.drafts.delete(pendingEditKey(key, draft.id))
        }
      }
    } finally {
      runCatching { refresh(key) }
    }
  }

  /**
   * Deletes [sent] drafts the forge now has, unless they were edited since they were read: an edit
   * saved while the review was on its way stays, to go out with the next one.
   */
  private suspend fun deleteSubmitted(sent: List<DraftCommentEntity>) {
    db.withWriteTransaction {
      for (draft in sent) {
        val key = PullRequestKey(draft.accountId, RepoRef(draft.owner, draft.repo), draft.number)
        val editing = drafts.get(pendingEditKey(key, draft.id))
        // An open composer can have newer words even before Update saves them to this row.
        if (editing != null && editing != draft.body) continue
        dao.deleteDraftIfUnchanged(draft.id, draft.updatedAt, draft.body)
        if (dao.draft(draft.id) == null) {
          val cache = cache(key)
          saveCache(key, cache.copy(replies = cache.replies - draft.id))
        }
      }
    }
  }

  /**
   * Picks up the review left pending on the forge by another client: its summary becomes this
   * review's, unless one is written here already, and its comments go out with this review.
   */
  suspend fun continuePendingReview(key: PullRequestKey) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val remote = cache(key).pendingBody.orEmpty()
    db.withWriteTransaction {
      val local = dao.draftReview(key.accountId, owner, repo, key.number)?.summary.orEmpty()
      setSummary(key, local.ifBlank { remote })
    }
  }

  /**
   * Discards the review left pending on the forge by another client, with its comments. They go
   * from here at once; if the forge refuses, the refresh brings them back and [failures] says why.
   */
  suspend fun discardPendingReview(key: PullRequestKey) {
    val (owner, repo) = key.repo.owner to key.repo.name
    val reviewId = cache(key).pendingId ?: return
    val before = overview(key).first()
    val beforeThreads = threads(key).first()
    val beforeCache = cache(key)
    db.withWriteTransaction {
      dao.clearPendingReview(key.accountId, owner, repo, key.number)
      val cache = cache(key)
      dao.deletePendingComments(
        key.accountId,
        owner,
        repo,
        key.number,
        cache.pendingComments.toList(),
      )
      saveCache(
        key,
        cache.copy(
          pendingId = null,
          pendingBody = null,
          pendingCount = 0,
          pendingComments = emptySet(),
        ),
      )
      dao.deleteEmptyThreads(key.accountId, owner, repo, key.number)
    }
    scope.launch {
      runCatching { api(key).deletePendingReview(reviewId) }
        .onFailure {
          db.withWriteTransaction {
            before?.let { dao.upsertOverview(it) }
            dao.upsertThreads(beforeThreads.map { it.thread })
            dao.upsertThreadComments(beforeThreads.flatMap { it.comments })
            val current = cache(key)
            saveCache(
              key,
              current.copy(
                pendingId = beforeCache.pendingId,
                pendingBody = beforeCache.pendingBody,
                pendingCount = beforeCache.pendingCount,
                pendingComments = beforeCache.pendingComments,
              ),
            )
          }
          _failures.tryEmit("Couldn't discard the pending review: ${it.message}")
        }
      runCatching { refresh(key) }
    }
  }

  /**
   * Replies to thread [threadId] at once, as when there's no pending review. The reply shows as
   * sending at once and becomes the forge's comment once it has it; only then is the draft at
   * [draft] deleted. If the forge refuses, the reply disappears, the draft stays, and [failures]
   * says why.
   */
  suspend fun reply(key: PullRequestKey, threadId: String, body: String, draft: DraftKey) {
    drafts.save(draft, body)
    val account = db.accounts().get(key.accountId)
    val placeholder =
      ChangesThreadCommentEntity(
        accountId = key.accountId,
        id = "sending:${clock.now().toEpochMilliseconds()}:${placeholders++}",
        threadId = threadId,
        authorLogin = account?.login,
        authorAvatarUrl = account?.avatarUrl,
        body = body,
        createdAt = clock.now().toEpochMilliseconds(),
        position = dao.nextCommentPosition(key.accountId, threadId),
        state = CommentState.Sending,
      )
    db.withWriteTransaction {
      sendingReplies += placeholder.id
      dao.upsertThreadComments(listOf(placeholder))
    }
    scope.launch {
      runCatching { api(key).reply(threadId, body) }
        .onSuccess { comment ->
          db.withWriteTransaction {
            sendingReplies -= placeholder.id
            dao.deleteThreadComment(key.accountId, placeholder.id)
            if (dao.thread(key.accountId, threadId) != null) {
              dao.upsertThreadComments(
                listOf(
                  placeholder.copy(
                    id = comment.id,
                    authorLogin = comment.authorLogin,
                    authorAvatarUrl = comment.authorAvatarUrl,
                    body = comment.body,
                    createdAt = comment.createdAt.toEpochMilliseconds(),
                    state = if (comment.pending) CommentState.Pending else CommentState.Published,
                  ),
                ),
              )
            }
          }
          if (drafts.get(draft) == body) drafts.delete(draft)
        }
        .onFailure {
          db.withWriteTransaction {
            sendingReplies -= placeholder.id
            dao.deleteThreadComment(key.accountId, placeholder.id)
          }
          _failures.tryEmit("Couldn't reply: ${it.message}")
        }
    }
  }

  private var placeholders = 0
  /** Guarded by Room transactions; an orphaned sending row is dropped on the next refresh. */
  private val sendingReplies = HashSet<String>()

  /** Resolves thread [threadId] or reopens it, here at once and then on the forge. */
  suspend fun setResolved(key: PullRequestKey, threadId: String, resolved: Boolean) {
    val id = key to threadId
    val before =
      db.withWriteTransaction<ChangesThreadEntity?> {
        if (id in resolving) return@withWriteTransaction null
        val cache = cache(key)
        val row =
          dao.thread(key.accountId, threadId)?.let(cache::thread)
            ?: return@withWriteTransaction null
        if (row.isResolved == resolved) return@withWriteTransaction null
        resolving[id] = resolved
        dao.setThreadResolved(key.accountId, threadId, resolved)
        cache.permissions[threadId]?.let { permission ->
          saveCache(
            key,
            cache.copy(
              permissions =
                cache.permissions +
                  (threadId to permission.copy(resolve = false, unresolve = false)),
            ),
          )
        }
        row
      } ?: return
    scope.launch {
      val result = runCatching { api(key).setResolved(threadId, resolved) }
      db.withWriteTransaction {
        resolving -= id
        val settled = if (result.isSuccess) resolved else before.isResolved
        dao.setThreadResolved(key.accountId, threadId, settled)
        val cache = cache(key)
        val permission =
          ThreadPermissions(
            before.viewerCanReply,
            if (result.isSuccess) !settled else before.viewerCanResolve,
            if (result.isSuccess) settled else before.viewerCanUnresolve,
            before.resolvedBy,
          )
        saveCache(key, cache.copy(permissions = cache.permissions + (threadId to permission)))
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

  /** Where an edit to pending comment [id] is drafted until it's saved back into the review. */
  fun pendingEditKey(key: PullRequestKey, id: Long) =
    DraftKey(key.accountId, key.repo, key.number, "$PENDING_EDIT_SLOT$id")

  /** Deletes pending comment [id], and any edit drafted for it. */
  suspend fun deleteDraft(key: PullRequestKey, id: Long) {
    db.withWriteTransaction {
      dao.deleteDraft(id)
      val cache = cache(key)
      saveCache(key, cache.copy(replies = cache.replies - id))
      drafts.delete(pendingEditKey(key, id))
    }
  }

  private fun cacheKey(key: PullRequestKey) =
    DraftKey(key.accountId, key.repo, key.number, REVIEW_CACHE_SLOT)

  private fun cacheFlow(key: PullRequestKey) =
    drafts.observe(cacheKey(key)).map(::decodeReviewCache)

  private suspend fun cache(key: PullRequestKey) = decodeReviewCache(drafts.get(cacheKey(key)))

  private suspend fun saveCache(key: PullRequestKey, cache: ReviewCache) =
    drafts.save(cacheKey(key), cache.encode())

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

/** Draft slots for edits to pending review comments: the comment's id follows. */
const val PENDING_EDIT_SLOT = "review/edit:"

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
    inReplyTo = inReplyTo,
  )
