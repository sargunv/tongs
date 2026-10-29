package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadLoad
import dev.sargunv.tongs.forge.api.ThreadSource
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.api.TimelinePage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** An issue or PR, by account and number. Names, not forge IDs, since that's what links carry. */
data class ThreadRef(val accountId: Long, val repo: RepoRef, val number: Int) {
  /** The draft for replying to this thread. */
  val replyDraft: DraftKey
    get() = DraftKey(accountId, repo, number)
}

/** A thread as stored: what the thread screen shows. */
data class ThreadSnapshot(
  val detail: ThreadDetail,
  /** The loaded timeline, in the forge's order, with the unloaded middle at [gapIndex]. */
  val items: List<TimelineEntry>,
  /** Items before this index are from the timeline's start; the rest from its end. */
  val gapIndex: Int,
  /** Timeline items not loaded yet, between the start and the end. */
  val hiddenCount: Int,
  /** Review comment threads, by the ID of the review that started them. */
  val reviewThreads: Map<String, List<ReviewThread>>,
  val fetchedAt: Instant,
)

data class TimelineEntry(val item: TimelineItem, val pending: Boolean = false)

/** What's in flight for a thread, besides what's stored. */
data class ThreadStatus(
  val refreshing: Boolean = false,
  val loadingHidden: Boolean = false,
  val merging: Boolean = false,
  /** Why the last refresh failed, until one succeeds. */
  val error: String? = null,
)

/**
 * Issue and PR conversations, cached in the database. Screens open a thread from the cache at once
 * and [refresh] it in the background. Writes are optimistic where the result is predictable (a
 * comment, a reaction, closing), and the forge call follows in [scope]: a comment shows at once as
 * pending, and if the forge refuses it, it's removed, its draft is kept, and [failures] says why.
 */
@SingleIn(AppScope::class)
@Inject
class ThreadRepository(
  db: TongsDatabase,
  private val clients: ForgeClients,
  private val accounts: AccountRepository,
  private val drafts: DraftRepository,
  private val scope: CoroutineScope,
  private val clock: Clock,
) {
  private val database = db
  private val dao = db.threads()
  private val statuses = mutableMapOf<ThreadRef, MutableStateFlow<ThreadStatus>>()
  private val statusLock = Mutex()
  private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)

  /** Messages for writes the forge refused, and failed loads of older items. */
  val failures: SharedFlow<String> = _failures

  fun observe(ref: ThreadRef): Flow<ThreadSnapshot?> {
    val (accountId, repo, number) = ref
    return combine(
        dao.observeThread(accountId, repo.owner, repo.name, number),
        dao.observeItems(accountId, repo.owner, repo.name, number),
        dao.observeReviewThreads(accountId, repo.owner, repo.name, number),
      ) { thread, items, reviewThreads ->
        thread?.let { snapshot(it, items, reviewThreads) }
      }
      .distinctUntilChanged()
  }

  suspend fun status(ref: ThreadRef): StateFlow<ThreadStatus> = statusFlow(ref).asStateFlow()

  /** Fetches the thread's start and end, replacing what's stored. Records failures in [status]. */
  suspend fun refresh(ref: ThreadRef) {
    val status = statusFlow(ref)
    if (status.value.refreshing) return
    status.update { it.copy(refreshing = true) }
    try {
      val started = database.withWriteTransaction { confirmationSequence }
      val load = source(ref).load(ref.repo, ref.number)
      store(ref, load, started)
      status.update { it.copy(refreshing = false, error = null) }
    } catch (e: CancellationException) {
      status.update { it.copy(refreshing = false) }
      throw e
    } catch (e: Exception) {
      status.update { it.copy(refreshing = false, error = e.message ?: e.toString()) }
    }
  }

  /** Loads the next part of the timeline's unloaded middle, from the start side. */
  suspend fun loadHidden(ref: ThreadRef) {
    val status = statusFlow(ref)
    if (status.value.loadingHidden) return
    val (accountId, repo, number) = ref
    val cursor = dao.thread(accountId, repo.owner, repo.name, number)?.gapCursor ?: return
    status.update { it.copy(loadingHidden = true) }
    try {
      appendHidden(ref, cursor, source(ref).loadTimeline(repo, number, cursor))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _failures.tryEmit("Couldn't load more of the timeline: ${e.message}")
    } finally {
      status.update { it.copy(loadingHidden = false) }
    }
  }

  /** Adds [page], fetched from [cursor], unless a refresh has moved the gap since. */
  private suspend fun appendHidden(ref: ThreadRef, cursor: String, page: TimelinePage) {
    val (accountId, repo, number) = ref
    database.withWriteTransaction {
      val thread =
        dao.thread(accountId, repo.owner, repo.name, number) ?: return@withWriteTransaction
      if (thread.gapCursor != cursor) return@withWriteTransaction
      val known = dao.items(accountId, repo.owner, repo.name, number).map { it.id }.toSet()
      // The page runs into the items from the end once the gap closes.
      val fresh = page.items.takeWhile { it.id !in known }
      val closed = fresh.size < page.items.size || !page.hasNext
      var position = dao.maxPosition(accountId, repo.owner, repo.name, number, 0, TAIL_POSITION)
      dao.insertNewItems(fresh.map { item(ref, it, ++position) })
      dao.upsertThread(thread.copy(gapCursor = if (closed) null else page.endCursor))
    }
  }

  /** The whole submission belongs to the app, including waiting for Room's writer. */
  fun comment(
    ref: ThreadRef,
    close: StateReason? = null,
    reopen: Boolean = false,
  ): kotlinx.coroutines.Deferred<Boolean> =
    drafts.submit(listOf(ref.replyDraft)) { rows ->
      val body = rows.single().body
      val thread = stored(ref) ?: error("The thread hasn't loaded yet")
      val account = accounts.account(ref.accountId)
      val localId = LOCAL_ID_PREFIX + Random.nextLong().toULong().toString(16)
      var accepted = false
      if (body.isNotBlank()) {
        val pending =
          TimelineItem.Comment(
            localId,
            clock.now(),
            account?.let { Actor(it.login, it.avatarUrl) },
            body,
          )
        val position =
          dao.maxPosition(
            ref.accountId,
            ref.repo.owner,
            ref.repo.name,
            ref.number,
            LOCAL_POSITION,
            Long.MAX_VALUE,
          )
        dao.upsertItems(
          listOf(item(ref, pending, maxOf(position + 1, LOCAL_POSITION), isPending = true)),
        )
      }
      try {
        val source = source(ref)
        if (body.isNotBlank()) {
          val posted =
            drafts.deliverAccepted(rows) {
              source.addComment(thread.id, body).also { accepted = true }
            }
          confirmComment(ref, localId, posted)
        }
        when {
          close != null -> setState(ref, thread, ThreadState.Closed, close)
          reopen -> setState(ref, thread, ThreadState.Open, StateReason.Reopened)
        }
      } catch (e: Exception) {
        if (!accepted)
          dao.deleteItem(ref.accountId, ref.repo.owner, ref.repo.name, ref.number, localId)
        _failures.tryEmit(
          if (accepted) "Comment posted, but couldn't finish: ${e.message}"
          else "Couldn't post your comment: ${e.message}. Your draft is saved.",
        )
        throw e
      }
    }

  /** Closes or reopens without using the reply draft. */
  suspend fun setState(ref: ThreadRef, close: Boolean, reason: StateReason? = null) {
    val thread = stored(ref) ?: return
    scope.launch {
      runCatching {
        setState(
          ref,
          thread,
          if (close) ThreadState.Closed else ThreadState.Open,
          if (close) reason ?: StateReason.Completed else StateReason.Reopened,
        )
      }
        .onFailure { _failures.tryEmit("Couldn't change the state: ${it.message}") }
    }
  }

  /**
   * Toggles the account's [reaction] on [subjectId]: the thread's body, a comment, or a review.
   * Optimistic; a refusal refetches the thread.
   */
  suspend fun react(ref: ThreadRef, subjectId: String, reaction: Reaction) {
    val add = toggleReaction(ref, subjectId, reaction)?.not() ?: return
    scope.launch {
      try {
        source(ref).react(subjectId, reaction, add)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _failures.tryEmit("Couldn't react: ${e.message}")
        refresh(ref)
      }
    }
  }

  /** Merges a PR with [method], then refetches it. */
  suspend fun merge(ref: ThreadRef, method: MergeMethod) {
    val thread = stored(ref) ?: return
    val headOid = thread.pullRequest?.headOid ?: return
    val status = statusFlow(ref)
    status.update { it.copy(merging = true) }
    scope.launch {
      try {
        source(ref).merge(thread.id, method, headOid)
        refresh(ref)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _failures.tryEmit("Couldn't merge: ${e.message}")
      } finally {
        status.update { it.copy(merging = false) }
      }
    }
  }

  private suspend fun setState(
    ref: ThreadRef,
    thread: ThreadDetail,
    state: ThreadState,
    reason: StateReason,
  ) {
    updateDetail(ref) { it.copy(state = state, stateReason = reason) }
    try {
      if (state == ThreadState.Closed) {
        source(ref).close(thread.id, thread.kind, reason)
      } else {
        source(ref).reopen(thread.id, thread.kind)
      }
    } catch (e: Exception) {
      updateDetail(ref) { it.copy(state = thread.state, stateReason = thread.stateReason) }
      throw e
    }
    // The forge adds its own closed or reopened event; fetch it.
    refresh(ref)
  }

  private suspend fun confirmComment(
    ref: ThreadRef,
    localId: String,
    posted: TimelineItem.Comment,
  ) {
    val (accountId, repo, number) = ref
    database.withWriteTransaction {
      val local = dao.items(accountId, repo.owner, repo.name, number).find { it.id == localId }
      confirmations[ref to posted.id] = ++confirmationSequence
      dao.deleteItem(accountId, repo.owner, repo.name, number, localId)
      dao.upsertItems(
        listOf(item(ref, posted, local?.position ?: LOCAL_POSITION, isPending = false)),
      )
    }
  }

  /** Flips the reaction where it's stored. Returns whether the account had reacted, or null. */
  private suspend fun toggleReaction(
    ref: ThreadRef,
    subjectId: String,
    reaction: Reaction,
  ): Boolean? {
    val (accountId, repo, number) = ref
    var had: Boolean? = null
    fun flip(reactions: List<Reactions>): List<Reactions> {
      val existing = reactions.find { it.reaction == reaction }
      had = existing?.viewerReacted ?: false
      return when {
        existing == null -> reactions + Reactions(reaction, 1, viewerReacted = true)
        existing.viewerReacted && existing.count <= 1 -> reactions - existing
        else ->
          reactions.map {
            if (it != existing) it
            else
              it.copy(
                count = it.count + if (it.viewerReacted) -1 else 1,
                viewerReacted = !it.viewerReacted,
              )
          }
      }
    }
    database.withWriteTransaction {
      val thread =
        dao.thread(accountId, repo.owner, repo.name, number) ?: return@withWriteTransaction
      val detail = decodeDetail(thread) ?: return@withWriteTransaction
      if (detail.id == subjectId) {
        dao.upsertThread(
          thread.copy(
            detail = json.encodeToString(detail.copy(reactions = flip(detail.reactions))),
          ),
        )
        return@withWriteTransaction
      }
      for (entity in dao.items(accountId, repo.owner, repo.name, number)) {
        if (entity.id != subjectId) continue
        val updated =
          when (val item = decodeItem(entity)) {
            is TimelineItem.Comment -> item.copy(reactions = flip(item.reactions))
            is TimelineItem.Review -> item.copy(reactions = flip(item.reactions))
            else -> null
          } ?: return@withWriteTransaction
        dao.upsertItems(listOf(entity.copy(item = json.encodeToString<TimelineItem>(updated))))
        return@withWriteTransaction
      }
      for (entity in dao.reviewThreads(accountId, repo.owner, repo.name, number)) {
        val reviewThread = decodeReviewThread(entity) ?: continue
        if (reviewThread.comments.none { it.id == subjectId }) continue
        val updated =
          reviewThread.copy(
            comments =
              reviewThread.comments.map {
                if (it.id == subjectId) it.copy(reactions = flip(it.reactions)) else it
              },
          )
        dao.upsertReviewThreads(listOf(entity.copy(thread = json.encodeToString(updated))))
        return@withWriteTransaction
      }
    }
    return had
  }

  private suspend fun updateDetail(ref: ThreadRef, change: (ThreadDetail) -> ThreadDetail) {
    val (accountId, repo, number) = ref
    database.withWriteTransaction {
      val thread =
        dao.thread(accountId, repo.owner, repo.name, number) ?: return@withWriteTransaction
      val detail = decodeDetail(thread) ?: return@withWriteTransaction
      dao.upsertThread(thread.copy(detail = json.encodeToString(change(detail))))
    }
  }

  private suspend fun stored(ref: ThreadRef): ThreadDetail? =
    dao.thread(ref.accountId, ref.repo.owner, ref.repo.name, ref.number)?.let(::decodeDetail)

  /** Replaces the stored thread with [load]. Comments still pending stay at the end. */
  private var confirmationSequence = 0L
  private val confirmations = HashMap<Pair<ThreadRef, String>, Long>()

  internal suspend fun store(
    ref: ThreadRef,
    load: ThreadLoad,
    started: Long = confirmationSequence,
  ) {
    val (accountId, repo, number) = ref
    val headIds = load.head.items.map { it.id }.toSet()
    // Short timelines come back whole in both pages; long ones leave a gap between them.
    val tail = load.tail.items.filter { it.id !in headIds }
    val gapClosed = !load.head.hasNext || tail.size < load.tail.items.size || !load.tail.hasPrevious
    database.withWriteTransaction {
      dao.upsertThread(
        ThreadEntity(
          accountId = accountId,
          repoOwner = repo.owner,
          repoName = repo.name,
          number = number,
          kind = load.detail.kind.raw,
          detail = json.encodeToString(load.detail),
          fetchedAt = clock.now().toEpochMilliseconds(),
          timelineCount = load.timelineCount,
          gapCursor = if (gapClosed) null else load.head.endCursor,
        ),
      )
      val newer =
        dao.items(accountId, repo.owner, repo.name, number).filter {
          (confirmations[ref to it.id] ?: 0) > started
        }
      dao.deleteConfirmedItems(accountId, repo.owner, repo.name, number)
      dao.upsertItems(
        load.head.items.mapIndexed { i, it -> item(ref, it, i.toLong()) } +
          tail.mapIndexed { i, it -> item(ref, it, TAIL_POSITION + i) },
      )
      val remoteIds = (load.head.items + tail).mapTo(HashSet()) { it.id }
      dao.upsertItems(newer.filter { it.id !in remoteIds })
      dao.deleteReviewThreads(accountId, repo.owner, repo.name, number)
      dao.upsertReviewThreads(
        load.reviewThreads.map {
          ReviewThreadEntity(
            accountId,
            repo.owner,
            repo.name,
            number,
            it.id,
            json.encodeToString(it),
          )
        },
      )
    }
  }

  private fun snapshot(
    thread: ThreadEntity,
    items: List<ThreadItemEntity>,
    reviewThreads: List<ReviewThreadEntity>,
  ): ThreadSnapshot? {
    val detail = decodeDetail(thread) ?: return null
    val entries = items.mapNotNull { e -> decodeItem(e)?.let { TimelineEntry(it, e.pending) } }
    val loaded = items.count { !it.pending }
    return ThreadSnapshot(
      detail = detail,
      items = entries,
      gapIndex = items.count { it.position < TAIL_POSITION },
      hiddenCount =
        if (thread.gapCursor == null) 0 else (thread.timelineCount - loaded).coerceAtLeast(0),
      reviewThreads =
        reviewThreads.mapNotNull(::decodeReviewThread).groupBy { it.reviewId.orEmpty() },
      fetchedAt = Instant.fromEpochMilliseconds(thread.fetchedAt),
    )
  }

  private fun item(ref: ThreadRef, item: TimelineItem, position: Long, isPending: Boolean = false) =
    ThreadItemEntity(
      accountId = ref.accountId,
      repoOwner = ref.repo.owner,
      repoName = ref.repo.name,
      number = ref.number,
      id = item.id,
      position = position,
      item = json.encodeToString(item),
      pending = isPending,
    )

  private suspend fun source(ref: ThreadRef): ThreadSource =
    clients.client(ref.accountId)?.threads ?: error("this account can't read threads")

  private suspend fun statusFlow(ref: ThreadRef) = statusLock.withLock {
    statuses.getOrPut(ref) { MutableStateFlow(ThreadStatus()) }
  }

  private fun decodeDetail(thread: ThreadEntity): ThreadDetail? = runCatching {
    json.decodeFromString<ThreadDetail>(thread.detail)
  }
    .getOrNull()

  private fun decodeItem(entity: ThreadItemEntity): TimelineItem? = runCatching {
    json.decodeFromString<TimelineItem>(entity.item)
  }
    .getOrNull()

  private fun decodeReviewThread(entity: ReviewThreadEntity): ReviewThread? = runCatching {
    json.decodeFromString<ReviewThread>(entity.thread)
  }
    .getOrNull()

  private companion object {
    /** Lenient, so a document from an older build still decodes. */
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}
