package dev.sargunv.tongs.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.tongs.core.data.InboxRepository
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.InboxSettings
import dev.sargunv.tongs.core.data.InboxSync
import dev.sargunv.tongs.core.data.SettingsRepository
import dev.sargunv.tongs.core.data.ThreadKey
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InboxUiState(
  /** Null until the first read from the database. */
  val rows: List<InboxRow>?,
  val settings: InboxSettings,
  val refreshing: Boolean,
  /** Why the last sync failed, if it did. */
  val syncError: String?,
  /** Whether rows come from more than one account, so each should say which. */
  val multipleAccounts: Boolean,
)

@Inject
class InboxViewModel(
  private val inbox: InboxRepository,
  private val sync: InboxSync,
  settings: SettingsRepository,
  private val clock: Clock,
) : ViewModel() {
  val state: StateFlow<InboxUiState> =
    combine(inbox.inbox(), settings.inbox, sync.refreshing, inbox.syncErrors()) {
        rows,
        settings,
        refreshing,
        errors ->
        InboxUiState(
          rows = rows,
          settings = settings,
          refreshing = refreshing,
          syncError = errors.firstOrNull(),
          multipleAccounts = rows.distinctBy { it.accountId }.size > 1,
        )
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        InboxUiState(null, InboxSettings(), refreshing = false, null, multipleAccounts = false),
      )

  /** Actions the forge refused, to show once each. */
  val failures: Flow<String> = inbox.failures

  fun now(): Instant = clock.now()

  fun refresh() {
    viewModelScope.launch { sync.refresh() }
  }

  fun markDone(row: InboxRow) {
    viewModelScope.launch { inbox.markDone(row.key) }
  }

  fun markRead(row: InboxRow) {
    if (!row.isUnread) return
    viewModelScope.launch { inbox.markRead(row.key) }
  }

  fun toggleRead(row: InboxRow) {
    viewModelScope.launch {
      if (row.isUnread) inbox.markRead(row.key) else inbox.markUnread(row.key)
    }
  }

  fun snooze(row: InboxRow, until: Instant) {
    viewModelScope.launch { inbox.snooze(row.key, until) }
  }
}

val InboxRow.key: ThreadKey
  get() = ThreadKey(accountId, id)
