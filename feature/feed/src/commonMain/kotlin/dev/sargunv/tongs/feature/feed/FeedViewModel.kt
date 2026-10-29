package dev.sargunv.tongs.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.FeedData
import dev.sargunv.tongs.core.data.FeedGrouping
import dev.sargunv.tongs.core.data.FeedMuteEntity
import dev.sargunv.tongs.core.data.FeedRepository
import dev.sargunv.tongs.core.data.FeedSync
import dev.sargunv.tongs.core.data.MuteKind
import dev.sargunv.tongs.core.data.Undo
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An account as the feed needs it: who "You" is, and what its token can't see. */
data class FeedAccount(
  val id: Long,
  val login: String,
  /** Requested scopes the token lacks: without `repo`, private repos' activity is hidden. */
  val missingScopes: Set<String> = emptySet(),
  /** Where to make a new token. */
  val tokenUrl: String? = null,
  val webUrl: String? = null,
)

/** What the feed shows. */
data class FeedUiState(
  /** Null until the first read from the database. */
  val data: FeedData?,
  val grouping: FeedGrouping = FeedGrouping.None,
  val refreshing: Boolean = false,
  val sync: List<AccountSyncStatus> = emptyList(),
  val accounts: List<FeedAccount> = emptyList(),
  val mutes: List<FeedMuteEntity> = emptyList(),
) {
  val multipleAccounts: Boolean
    get() = accounts.size > 1

  /** The account's login, so its own activity reads "You". */
  fun viewer(accountId: Long): String? = accounts.find { it.id == accountId }?.login
}

/** A message for the snackbar, which offers [undo] if it's set. */
data class FeedNotice(val message: String, val undo: Undo? = null)

@Inject
class FeedViewModel(
  private val feed: FeedRepository,
  private val sync: FeedSync,
  accounts: AccountRepository,
  private val clock: Clock,
) : ViewModel() {
  private val limit = MutableStateFlow(PAGE)
  private val _notices = MutableSharedFlow<FeedNotice>(extraBufferCapacity = 8)
  private var lastUndo: Undo? = null

  @OptIn(ExperimentalCoroutinesApi::class)
  private val data: Flow<FeedData> = limit.flatMapLatest { feed.feed(it) }

  private val feedAccounts =
    accounts.accounts.map { list ->
      list.map {
        FeedAccount(
          id = it.account.id.value,
          login = it.account.login,
          webUrl = it.account.instance.webUrl,
          missingScopes = it.missingScopes,
          tokenUrl = accounts.auth(it.account.instance.kind).newTokenUrl(it.account.instance),
        )
      }
    }

  private val chrome =
    combine(feed.grouping, sync.refreshing, feed.syncStatus(), feedAccounts, feed.mutes) {
      grouping,
      refreshing,
      status,
      accounts,
      mutes ->
      FeedUiState(null, grouping, refreshing, status, accounts, mutes)
    }

  val state: StateFlow<FeedUiState> =
    combine(chrome, data) { chrome, data -> chrome.copy(data = data) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState(null))

  val notices: Flow<FeedNotice> = _notices

  fun now(): Instant = clock.now()

  fun refresh() {
    viewModelScope.launch { sync.refresh() }
  }

  /** Shows older stored activity. */
  fun loadMore() {
    limit.value += PAGE
  }

  fun setGrouping(grouping: FeedGrouping) {
    viewModelScope.launch { feed.setGrouping(grouping) }
  }

  fun mute(accountId: Long, kind: MuteKind, value: String) {
    viewModelScope.launch {
      val undo = feed.mute(accountId, kind, value)
      lastUndo = undo
      _notices.tryEmit(FeedNotice("Muted $value", undo))
    }
  }

  fun unmute(mute: FeedMuteEntity) {
    viewModelScope.launch { feed.unmute(mute) }
  }

  fun undoLast() {
    val undo = lastUndo ?: return
    lastUndo = null
    viewModelScope.launch { undo.undo() }
  }

  companion object {
    /** Events loaded at a time; aggregated, a few hundred rows. */
    const val PAGE = 1_500
  }
}
