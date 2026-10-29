package dev.sargunv.tongs.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** What the feed shows: its items, and what it left out. */
data class FeedData(
  val items: List<FeedItem>,
  /** Events stored for every account, whether loaded or not. */
  val storedEvents: Int,
  /** Events loaded for [items], muted ones included. */
  val loadedEvents: Int,
  /** Loaded events hidden because their repo or person is muted. */
  val mutedEvents: Int,
) {
  /** Whether there are older stored events than were loaded. */
  val canLoadMore: Boolean
    get() = storedEvents > loadedEvents
}

/**
 * The activity feed, from the database, aggregated into items (see [aggregateFeed]), and the local
 * state around it: mutes and how it's grouped. Mutes are local, since GitHub's feed has none.
 */
@SingleIn(AppScope::class)
@Inject
class FeedRepository(
  private val db: TongsDatabase,
  private val store: DataStore<Preferences>,
  private val clock: Clock,
) {
  private val dao = db.feed()

  /** The newest [limit] stored events across accounts, as items, newest first. */
  fun feed(limit: Int): Flow<FeedData> {
    val inputs =
      combine(
        dao.observeEvents(limit),
        dao.observeSubjects(),
        mutes,
        db.accounts().observeAll(),
      ) { events, subjects, mutes, accounts ->
        Inputs(events, subjects, mutes, accounts)
      }
    return combine(inputs, dao.observeEventCount()) { input, stored ->
        val byAccount =
          input.mutes
            .groupBy { it.accountId }
            .mapValues { (_, list) ->
              FeedMutes(
                repos = list.filter { it.kind == MuteKind.Repo.name }.mapTo(HashSet()) { it.value },
                actors =
                  list.filter { it.kind == MuteKind.Actor.name }.mapTo(HashSet()) { it.value },
              )
            }
        val muted = input.events.count { byAccount[it.accountId]?.mutes(it) == true }
        FeedData(
          items =
            aggregateFeed(
              events = input.events,
              subjects =
                input.subjects.associateBy {
                  subjectKey(it.accountId, it.repoOwner, it.repoName, it.number)
                },
              mutes = byAccount,
              webUrls = input.accounts.associate { it.id to it.webUrl },
            ),
          storedEvents = stored,
          loadedEvents = input.events.size,
          mutedEvents = muted,
        )
      }
      // Aggregating a few thousand events takes milliseconds, but not on the main thread.
      .flowOn(Dispatchers.Default)
      .distinctUntilChanged()
  }

  private class Inputs(
    val events: List<FeedEventEntity>,
    val subjects: List<FeedSubjectEntity>,
    val mutes: List<FeedMuteEntity>,
    val accounts: List<AccountEntity>,
  )

  /** Each account's feed sync status, as the inbox's. */
  fun syncStatus(): Flow<List<AccountSyncStatus>> =
    combine(db.accounts().observeAll(), dao.observeSyncStates()) { accounts, states ->
        val byAccount = states.associateBy { it.accountId }
        accounts.map { account ->
          byAccount[account.id]?.status(account.login)
            ?: AccountSyncStatus(account.id, account.login, null, emptyList(), null)
        }
      }
      .distinctUntilChanged()

  val mutes: Flow<List<FeedMuteEntity>> = dao.observeMutes()

  /** Hides [value]'s activity, a repo's `owner/name` or a login, from the account's feed. */
  suspend fun mute(accountId: Long, kind: MuteKind, value: String): Undo {
    val mute =
      FeedMuteEntity(accountId, kind.name, value.lowercase(), clock.now().toEpochMilliseconds())
    dao.upsertMute(mute)
    return Undo { unmute(mute) }
  }

  suspend fun unmute(mute: FeedMuteEntity) = dao.deleteMute(mute.accountId, mute.kind, mute.value)

  val grouping: Flow<FeedGrouping> =
    store.data.map { prefs ->
      FeedGrouping.entries.find { it.name == prefs[GROUPING] } ?: FeedGrouping.None
    }

  suspend fun setGrouping(grouping: FeedGrouping) {
    store.edit { it[GROUPING] = grouping.name }
  }

  private companion object {
    val GROUPING = stringPreferencesKey("feed.grouping")
  }
}
