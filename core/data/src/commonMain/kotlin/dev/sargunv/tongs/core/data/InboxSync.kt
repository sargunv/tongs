package dev.sargunv.tongs.core.data

import androidx.room3.withWriteTransaction
import dev.sargunv.tongs.forge.api.ForgeException
import dev.sargunv.tongs.forge.api.InboxPoll
import dev.sargunv.tongs.forge.api.InboxThread
import dev.sargunv.tongs.forge.api.NotificationReason
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.Subject
import dev.sargunv.tongs.forge.api.SubjectType
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs each account's inbox into the database. Polls are conditional on the forge's cursor, so an
 * unchanged inbox costs GitHub a 304, and each account waits as long as its forge's poll interval
 * (`X-Poll-Interval`) asks between polls. [pollWhileActive] runs only while the app is in the
 * foreground: there are no push notifications, so there's no reason to poll in the background.
 */
@SingleIn(AppScope::class)
@Inject
class InboxSync(
  private val db: TongsDatabase,
  private val clients: ForgeClients,
  private val clock: Clock,
) {
  private val dao = db.inbox()
  private val lock = Mutex()
  private val _refreshing = MutableStateFlow(false)

  private val hydrationBackoff = mutableMapOf<Long, Instant>()
  private val lastFullPoll = mutableMapOf<Long, Instant>()
  private val _hydrationCost = MutableStateFlow(0)

  /** True while [refresh] runs. */
  val refreshing: StateFlow<Boolean> = _refreshing

  /** Rate limit units hydration has cost this session, as forges report them. */
  val hydrationCost: StateFlow<Int> = _hydrationCost

  /**
   * Polls every account whenever it's due, until cancelled. Polls at once for accounts whose
   * interval has passed since their last poll, including in an earlier session.
   */
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

  /** Polls every account now, whatever their intervals, as when the user pulls to refresh. */
  suspend fun refresh() {
    _refreshing.value = true
    try {
      for (account in db.accounts().all()) poll(account.id, full = true)
    } finally {
      _refreshing.value = false
    }
  }

  /** When [accountId] may next be polled. */
  internal suspend fun dueAt(accountId: Long): Instant {
    val state = dao.syncState(accountId) ?: return Instant.DISTANT_PAST
    val last =
      state.lastPolledAt?.let(Instant::fromEpochMilliseconds) ?: return Instant.DISTANT_PAST
    val interval = state.pollIntervalSeconds?.seconds ?: DEFAULT_POLL_INTERVAL
    // After an error, back off to at least the default, or until the forge says to retry.
    val due = last + if (state.lastError != null) maxOf(interval, ERROR_BACKOFF) else interval
    val retry = state.retryAt?.let(Instant::fromEpochMilliseconds)
    return if (retry != null && retry > due) retry else due
  }

  /**
   * Polls one account once and stores the result, then hydrates what changed. Usually the poll is
   * conditional on the cursor, so it asks only for what changed since; [full] asks for the whole
   * inbox instead, which is how threads read or done elsewhere are noticed. The first poll of a
   * session is full, then one every [FULL_POLL_INTERVAL], and every refresh.
   */
  suspend fun poll(accountId: Long, full: Boolean = false) = lock.withLock {
    val state = dao.syncState(accountId)
    val now = clock.now().toEpochMilliseconds()
    val lastFull = lastFullPoll[accountId]
    val fullPoll = full || lastFull == null || clock.now() - lastFull >= FULL_POLL_INTERVAL
    val cursor = state?.cursor.takeUnless { fullPoll }
    val result =
      try {
        // Without a token or the capability, record the error, so the account isn't retried
        // until its backoff passes.
        val client = clients.client(accountId) ?: error("No token for this account")
        val inbox =
          client.inbox
            ?: throw ForgeException.NoAccess("This account's token can't read notifications")
        inbox.poll(cursor).also { if (fullPoll) lastFullPoll[accountId] = clock.now() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        dao.upsertSyncState(
          InboxSyncEntity(
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
    val interval = result.pollInterval?.inWholeSeconds ?: state?.pollIntervalSeconds
    when (result) {
      is InboxPoll.NotModified ->
        dao.upsertSyncState(
          InboxSyncEntity(
            accountId = accountId,
            cursor = state?.cursor,
            pollIntervalSeconds = interval,
            lastPolledAt = now,
            lastError = null,
            lastSyncedAt = now,
            // A 304 says nothing new about SSO; keep what the last full answer said.
            sso = state?.sso?.takeIf { state.lastError == null },
          ),
        )
      is InboxPoll.Changed ->
        db.withWriteTransaction {
          merge(accountId, result)
          dao.upsertSyncState(
            InboxSyncEntity(
              accountId = accountId,
              cursor = result.cursor,
              pollIntervalSeconds = interval,
              lastPolledAt = now,
              lastError = null,
              lastSyncedAt = now,
              sso = encodeSso(result.ssoRequired.map { it.toNotice() }),
            ),
          )
        }
    }
    hydrate(accountId)
  }

  /**
   * Writes the forge's view of each unread thread, keeping local state, then settles the stored
   * threads the poll covers but doesn't list: they aren't unread on the forge anymore. Every thread
   * stays until it's done here; those that stopped being unread without Tongs reading them are
   * marked read elsewhere.
   */
  private suspend fun merge(accountId: Long, poll: InboxPoll.Changed) {
    val existing = dao.threads(accountId).associateBy { it.id }
    dao.upsert(poll.threads.map { it.toEntity(accountId, existing[it.id]) })
    val seen = poll.threads.mapTo(HashSet()) { it.id }
    val coveredFrom = poll.threads.minOfOrNull { it.updatedAt.toEpochMilliseconds() }
    // Threads in organizations the forge hid for SSO may be missing without having changed.
    val hiddenOrgs = poll.ssoRequired.mapNotNullTo(HashSet()) { it.organization?.lowercase() }
    val missing =
      existing.values.filter {
        it.id !in seen &&
          it.repoOwner.lowercase() !in hiddenOrgs &&
          (poll.complete || (coveredFrom != null && it.updatedAt >= coveredFrom))
      }
    // Tongs keeps its own inbox: a thread joins when the forge says it's unread, and leaves only
    // when it's marked done here. A missing thread is never deleted, because GitHub can't say
    // whether it was read or done elsewhere. `DELETE /notifications/threads/{id}` sets done, but
    // nothing reads it back: with `all=true`, done threads come back as plain read ones
    // (`unread: false`), a single thread's GET has no done field, and GraphQL's
    // `notificationThreads`, which GitHub's own apps use, isn't public. Reported, unanswered:
    // https://github.com/orgs/community/discussions/152852. So polls ask for unread only (see
    // `GitHubNotifications`), and a thread that stops being unread without Tongs reading it stays,
    // read, and is marked read elsewhere: dropping it would also drop threads that were only read,
    // which are still in the user's inbox on GitHub. A thread read here is already read (`unread`
    // is false while its read is pending), so it isn't marked.
    val nowRead = missing.filter { it.unread }
    if (nowRead.isNotEmpty()) dao.markReadElsewhere(accountId, nowRead.map { it.id })
  }

  /**
   * Hydrates the account's threads whose latest update hasn't been, newest first, up to
   * [HYDRATION_LIMIT] a poll. A thread is hydrated once per update: reading it doesn't change what
   * it says, so its line keeps saying what was new when it last changed. After a failure, waits
   * [ERROR_BACKOFF] before trying again, so a broken hydration doesn't cost every poll.
   */
  private suspend fun hydrate(accountId: Long) {
    val now = clock.now()
    if ((hydrationBackoff[accountId] ?: Instant.DISTANT_PAST) > now) return
    val hydrator = clients.client(accountId)?.inboxHydrator ?: return
    val threads = dao.unhydrated(accountId, HYDRATION_LIMIT)
    if (threads.isEmpty()) return
    val hydration =
      try {
        hydrator.hydrate(threads.map { it.toThread() })
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        hydrationBackoff[accountId] = now + ERROR_BACKOFF
        return
      }
    hydration.cost?.let { cost -> _hydrationCost.value += cost }
    val updatedAt = threads.associate { it.id to it.updatedAt }
    db.withWriteTransaction {
      for (h in hydration.threads) {
        dao.setHydration(
          accountId = accountId,
          id = h.threadId,
          updatedAt = updatedAt[h.threadId] ?: continue,
          subjectState = h.subjectState,
          stateReason = h.stateReason,
          isDraft = h.isDraft,
          checks = h.checks,
          reviewDecision = h.reviewDecision,
          whatsNew = h.whatsNew,
        )
      }
    }
  }

  companion object {
    /** GitHub's usual `X-Poll-Interval`, for forges that don't say. */
    val DEFAULT_POLL_INTERVAL: Duration = 60.seconds
    val ERROR_BACKOFF: Duration = 120.seconds

    /** How often a poll asks for the whole inbox rather than what changed. */
    val FULL_POLL_INTERVAL: Duration = 15.minutes

    /** The most threads one poll hydrates; GitHub's take three GraphQL points. */
    const val HYDRATION_LIMIT = 150
  }
}

/** The forge's fields from [thread], with local state carried over from [old]. */
internal fun InboxThread.toEntity(accountId: Long, old: InboxThreadEntity?): InboxThreadEntity {
  val updated = updatedAt.toEpochMilliseconds()
  return InboxThreadEntity(
    accountId = accountId,
    id = id,
    repoOwner = repo.owner,
    repoName = repo.name,
    title = subject.title,
    subjectType = subject.type.raw,
    subjectNumber = subject.number,
    webUrl = subject.webUrl,
    reason = reason.raw,
    // A read the forge hasn't confirmed yet stays read.
    unread = unread && old?.pendingRead != true,
    updatedAt = updated,
    lastReadAt = lastReadAt?.toEpochMilliseconds(),
    pendingRead = old?.pendingRead ?: false,
    pendingDone = old?.pendingDone ?: false,
    localUnread = old?.localUnread ?: false,
    snoozedUntil = old?.snoozedUntil,
    // Unread again means new activity, which clears the mark.
    readElsewhere = !unread && old?.readElsewhere == true,
    // Hydration describes the thread as of an update. The state is still the best guess after a
    // newer one, but what's new isn't until hydration says again.
    subjectState = old?.subjectState,
    whatsNew = old?.whatsNew?.takeIf { old.updatedAt == updated },
    stateReason = old?.stateReason,
    isDraft = old?.isDraft ?: false,
    checks = old?.checks,
    reviewDecision = old?.reviewDecision,
    hydratedUpdatedAt = old?.hydratedUpdatedAt,
  )
}

/** The forge's fields, back as the forge model, for hydration. */
internal fun InboxThreadEntity.toThread() =
  InboxThread(
    id = id,
    repo = RepoRef(repoOwner, repoName),
    subject = Subject(title, SubjectType.of(subjectType), subjectNumber, webUrl),
    reason = NotificationReason.of(reason),
    unread = unread,
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    lastReadAt = lastReadAt?.let(Instant::fromEpochMilliseconds),
  )
