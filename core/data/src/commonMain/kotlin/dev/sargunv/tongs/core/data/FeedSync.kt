package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.forge.api.FeedEvent
import dev.sargunv.tongs.forge.api.FeedEventKind
import dev.sargunv.tongs.forge.api.FeedPoll
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.PushRef
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.SubjectRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What feed sync has cost this session, in the forge's units. */
data class FeedCost(
  val polls: Int = 0,
  /** REST requests that counted against the rate limit: polls that changed, and compares. */
  val requests: Int = 0,
  /** GraphQL points hydration cost. */
  val points: Int = 0,
)

/**
 * Syncs each account's activity feed into the database, the way [InboxSync] syncs the inbox: polls
 * are conditional, so an unchanged feed costs nothing; each account waits as long as the forge's
 * poll interval asks; failures are stored by kind and back off; and polling runs only while the app
 * is in the foreground. Events are kept beyond what the forge keeps, up to [KEEP_EVENTS] an
 * account, so the feed reaches further back the longer Tongs runs.
 *
 * After each poll, what the events leave out is hydrated: the commits of new pushes and the current
 * state of issues and PRs with new events.
 */
@SingleIn(AppScope::class)
@Inject
class FeedSync(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val clock: Clock,
) {
  private val dao = db.feed()
  private val lock = Mutex()
  private val _refreshing = MutableStateFlow(false)
  private val _cost = MutableStateFlow(FeedCost())
  private val hydrationBackoff = mutableMapOf<Long, Instant>()

  val refreshing: StateFlow<Boolean> = _refreshing

  /** What sync has cost this session. */
  val cost: StateFlow<FeedCost> = _cost

  /** Polls every account whenever it's due, until cancelled, as [InboxSync.pollWhileActive]. */
  suspend fun pollWhileActive() {
    db
      .accounts()
      .observeAll()
      .map { rows -> rows.map { it.id } }
      .distinctUntilChanged()
      .collectLatest { accountIds ->
        while (true) {
          var next = Instant.DISTANT_FUTURE
          for (id in accountIds) {
            var due = dueAt(id)
            if (due <= clock.now()) {
              poll(id)
              due = dueAt(id)
            }
            if (due < next) next = due
          }
          if (next == Instant.DISTANT_FUTURE) return@collectLatest
          delay(next - clock.now())
        }
      }
  }

  /**
   * Polls every account now, whatever their intervals, and refreshes the state of the most recently
   * active issues and PRs, which change without the feed saying so.
   */
  suspend fun refresh() {
    _refreshing.value = true
    try {
      for (account in db.accounts().all()) poll(account.id, refresh = true)
    } finally {
      _refreshing.value = false
    }
  }

  internal suspend fun dueAt(accountId: Long): Instant {
    if (clients.client(accountId)?.let { it.feed == null } == true) return Instant.DISTANT_FUTURE
    val state = dao.syncState(accountId) ?: return Instant.DISTANT_PAST
    val last =
      state.lastPolledAt?.let(Instant::fromEpochMilliseconds) ?: return Instant.DISTANT_PAST
    val interval = state.pollIntervalSeconds?.seconds ?: InboxSync.DEFAULT_POLL_INTERVAL
    val due =
      last + if (state.lastError != null) maxOf(interval, InboxSync.ERROR_BACKOFF) else interval
    val retry = state.retryAt?.let(Instant::fromEpochMilliseconds)
    return if (retry != null && retry > due) retry else due
  }

  /** Polls one account once, stores what's new, and hydrates it. */
  suspend fun poll(accountId: Long, refresh: Boolean = false) = lock.withLock {
    if (refresh) hydrationBackoff.remove(accountId)
    val state = dao.syncState(accountId)
    val now = clock.now().toEpochMilliseconds()
    val client = clients.client(accountId)
    // A forge without a feed has nothing to sync; that isn't a failure.
    if (client != null && client.feed == null) return@withLock
    val result =
      try {
        val feed = client?.feed ?: error("No token for this account")
        // A fresh cursor retries cached stream failures, including SSO authorization.
        feed.poll(state?.cursor.takeUnless { refresh })
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        dao.upsertSyncState(
          FeedSyncEntity(
            accountId = accountId,
            cursor = state?.cursor,
            pollIntervalSeconds = state?.pollIntervalSeconds,
            lastPolledAt = now,
            lastError = e.message ?: e.toString(),
            errorKind = e.problemKind().name,
            retryAt = (e as? ForgeException.RateLimited)?.resetAt?.toEpochMilliseconds(),
            lastSyncedAt = state?.lastSyncedAt,
            sso =
              (e as? ForgeException.SsoRequired)?.let {
                encodeSso(listOf(it.authorization.toNotice()))
              } ?: state?.sso,
          ),
        )
        return@withLock
      }
    _cost.update { it.copy(polls = it.polls + 1, requests = it.requests + result.requests) }
    val interval = result.pollInterval?.inWholeSeconds ?: state?.pollIntervalSeconds
    when (result) {
      is FeedPoll.NotModified ->
        dao.upsertSyncState(
          FeedSyncEntity(
            accountId = accountId,
            cursor = state?.cursor,
            pollIntervalSeconds = interval,
            lastPolledAt = now,
            lastError = null,
            errorKind = null,
            retryAt = null,
            lastSyncedAt = now,
            sso = state?.sso?.takeIf { !refresh && state.lastError == null },
          ),
        )
      is FeedPoll.Changed ->
        db.withWriteTransaction {
          merge(accountId, result.events)
          dao.pruneEvents(accountId, KEEP_EVENTS)
          dao.pruneSubjects(accountId)
          dao.upsertSyncState(
            FeedSyncEntity(
              accountId = accountId,
              cursor = result.cursor,
              pollIntervalSeconds = interval,
              lastPolledAt = now,
              lastError = null,
              errorKind = null,
              retryAt = null,
              lastSyncedAt = now,
              sso = encodeSso(result.ssoRequired.map { it.toNotice() }),
            ),
          )
        }
    }
    hydrate(accountId, refresh)
  }

  /**
   * Stores events not seen before, and marks the issues and PRs they're on as needing hydration. An
   * issue event's title and state stand in until hydration says what they are now.
   */
  private suspend fun merge(accountId: Long, events: List<FeedEvent>) {
    if (events.isEmpty()) return
    val inserted = dao.insertEvents(events.map { it.toEntity(accountId) })
    val fresh = events.filterIndexed { i, _ -> inserted[i] != -1L }
    for (event in fresh) {
      val subject = event.subject ?: continue
      val at = event.createdAt.toEpochMilliseconds()
      val old = dao.subject(accountId, event.repo.owner, event.repo.name, subject.number)
      dao.upsertSubject(
        FeedSubjectEntity(
          accountId = accountId,
          repoOwner = event.repo.owner,
          repoName = event.repo.name,
          number = subject.number,
          type = old?.type ?: subject.type.raw,
          title = old?.title ?: subject.title,
          state = old?.state ?: subject.state,
          stateReason = old?.stateReason,
          isDraft = old?.isDraft ?: false,
          hydrated = false,
          lastEventAt = maxOf(old?.lastEventAt ?: 0, at),
        ),
      )
    }
  }

  /**
   * Hydrates the account's new pushes (up to [PUSH_LIMIT]) and stale subjects (up to
   * [SUBJECT_LIMIT]), newest first. On a refresh, the most recently active subjects are refreshed
   * too. After a failure, waits [InboxSync.ERROR_BACKOFF] before trying again.
   */
  private suspend fun hydrate(accountId: Long, refresh: Boolean) {
    val now = clock.now()
    if ((hydrationBackoff[accountId] ?: Instant.DISTANT_PAST) > now) return
    val hydrator = clients.client(accountId)?.feedHydrator ?: return
    val pushes = dao.unhydratedPushes(accountId, PUSH_LIMIT, retryMissing = refresh)
    val stale = dao.staleSubjects(accountId, SUBJECT_LIMIT)
    val subjects =
      if (refresh)
        (stale + dao.recentSubjects(accountId, SUBJECT_LIMIT))
          .distinctBy { it.key }
          .take(SUBJECT_LIMIT)
      else stale
    if (pushes.isEmpty() && subjects.isEmpty()) return
    val pushRefs =
      pushes
        .map { PushRef(RepoRef(it.repoOwner, it.repoName), it.ref, it.before, it.head!!) }
        .distinct()
    val hydration =
      try {
        hydrator.hydrate(
          subjects.map { SubjectRef(RepoRef(it.repoOwner, it.repoName), it.number) },
          pushRefs,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        hydrationBackoff[accountId] = now + InboxSync.ERROR_BACKOFF
        return
      }
    hydrationBackoff.remove(accountId)
    _cost.update {
      it.copy(
        requests = it.requests + hydration.requests,
        points = it.points + (hydration.points ?: 0),
      )
    }
    db.withWriteTransaction {
      for (s in hydration.subjects) {
        dao.setSubjectState(
          accountId = accountId,
          owner = s.subject.repo.owner,
          name = s.subject.repo.name,
          number = s.subject.number,
          type = s.type?.raw,
          title = s.title,
          state = s.state,
          stateReason = s.stateReason,
          isDraft = s.isDraft,
        )
      }
      for (p in hydration.pushes) {
        dao.setPushCommits(
          accountId = accountId,
          owner = p.push.repo.owner,
          name = p.push.repo.name,
          before = p.push.before,
          head = p.push.head,
          commits = encodeCommits(p.commits),
          count = p.count,
        )
      }
    }
  }

  companion object {
    /** Events kept per account; the forge keeps fewer (GitHub: 300 a stream, 30 days). */
    const val KEEP_EVENTS = 5_000

    /** Maximum pending pushes and subjects offered to the adapter per poll. */
    const val PUSH_LIMIT = 50
    const val SUBJECT_LIMIT = 100
  }
}

private val FeedSubjectEntity.key
  get() = "$repoOwner/$repoName#$number"

internal fun FeedEvent.toEntity(accountId: Long) =
  FeedEventEntity(
    accountId = accountId,
    id = id,
    kind = kind.name,
    kindRaw = kindRaw,
    action = action?.name,
    actionRaw = actionRaw,
    actorLogin = actor.login,
    actorAvatarUrl = actor.avatarUrl,
    repoOwner = repo.owner,
    repoName = repo.name,
    createdAt = createdAt.toEpochMilliseconds(),
    isPublic = isPublic,
    subjectType = subject?.type?.raw,
    subjectNumber = subject?.number,
    subjectTitle = subject?.title,
    subjectState = subject?.state,
    ref = ref,
    refType = refType?.name,
    before = before,
    head = head,
    commits = encodeCommits(commits),
    commitCount = commitCount,
    // Pushes that came with their commits need no hydration.
    pushHydrated = kind != FeedEventKind.Push || commits != null || head == null,
    title = title,
    excerpt = excerpt,
    reviewState = reviewState,
    target = target,
    webUrl = webUrl,
  )

internal fun FeedSyncEntity.status(login: String): AccountSyncStatus =
  InboxSyncEntity(
      accountId = accountId,
      cursor = cursor,
      pollIntervalSeconds = pollIntervalSeconds,
      lastPolledAt = lastPolledAt,
      lastError = lastError,
      errorKind = errorKind,
      retryAt = retryAt,
      lastSyncedAt = lastSyncedAt,
      sso = sso,
    )
    .status(login)
