package dev.sargunv.tongs.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.InboxQuery
import dev.sargunv.tongs.core.data.InboxRepository
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.InboxSettings
import dev.sargunv.tongs.core.data.InboxSync
import dev.sargunv.tongs.core.data.InboxViewEntity
import dev.sargunv.tongs.core.data.InboxViewRepository
import dev.sargunv.tongs.core.data.SettingsRepository
import dev.sargunv.tongs.core.data.ThreadKey
import dev.sargunv.tongs.core.data.Undo
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the inbox shows: the current view's rows, and everything around them. */
data class InboxUiState(
  /** The current view's rows, latest first; null until the first read from the database. */
  val rows: List<InboxRow>?,
  val settings: InboxSettings,
  val refreshing: Boolean,
  /** Each account's sync status. */
  val sync: List<AccountSyncStatus> = emptyList(),
  /** Whether rows come from more than one account, so each should say which. */
  val multipleAccounts: Boolean = false,
  val views: List<InboxViewEntity> = emptyList(),
  /** The saved view on show, or null for the whole inbox. */
  val viewId: Long? = null,
  /** The filter on show: the view's, or one being typed. */
  val query: InboxQuery = InboxQuery.Empty,
  val groupByRepo: Boolean = false,
  /** Where each account makes a new token, for an account whose token can't read the inbox. */
  val tokenUrls: Map<Long, String> = emptyMap(),
) {
  val view: InboxViewEntity?
    get() = views.find { it.id == viewId }

  /** The view's name, or "Inbox". */
  val title: String
    get() = view?.name ?: "Inbox"

  /** Whether the filter differs from the saved view's, or is set on the whole inbox. */
  val queryEdited: Boolean
    get() = query.text.trim() != (view?.query ?: "")
}

/** A message for the snackbar, which offers [undo] if it's set. */
data class InboxNotice(val message: String, val undo: Undo? = null, val long: Boolean = false)

@Inject
class InboxViewModel(
  private val inbox: InboxRepository,
  private val sync: InboxSync,
  private val settings: SettingsRepository,
  private val viewsRepository: InboxViewRepository,
  accounts: AccountRepository,
  private val clock: Clock,
) : ViewModel() {
  private val queryText = MutableStateFlow("")
  private val viewId = MutableStateFlow<Long?>(null)
  private val _notices = MutableSharedFlow<InboxNotice>(extraBufferCapacity = 8)
  private var lastUndo: Undo? = null

  private val query = queryText.map { InboxQuery.parse(it) }.distinctUntilChanged()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val rows: Flow<List<InboxRow>> =
    query
      .map { it.wantsSnoozed }
      .distinctUntilChanged()
      .flatMapLatest { snoozed -> if (snoozed) inbox.snoozed() else inbox.inbox() }

  private val tokenUrls =
    accounts.accounts.map { list ->
      list.associate {
        it.account.id.value to
          accounts.auth(it.account.instance.kind).newTokenUrl(it.account.instance)
      }
    }

  private val filtered = combine(rows, query) { rows, query -> rows to query }

  private val chrome =
    combine(settings.inbox, sync.refreshing, inbox.syncStatus(), viewsRepository.views, viewId) {
      settings,
      refreshing,
      status,
      views,
      viewId ->
      InboxUiState(
        rows = null,
        settings = settings,
        refreshing = refreshing,
        sync = status,
        multipleAccounts = status.size > 1,
        views = views,
        viewId = viewId?.takeIf { id -> views.any { it.id == id } },
      )
    }

  val state: StateFlow<InboxUiState> =
    combine(chrome, filtered, tokenUrls) { chrome, (rows, query), urls ->
        val view = chrome.view
        chrome.copy(
          rows = rows.filter(query::matches),
          query = query,
          groupByRepo = view?.groupByRepo ?: chrome.settings.groupByRepo,
          tokenUrls = urls,
        )
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        InboxUiState(null, InboxSettings(), refreshing = false),
      )

  /** Snackbar messages: what was done, with undo, and actions the forge refused. */
  val notices: Flow<InboxNotice> =
    kotlinx.coroutines.flow.merge(_notices, inbox.failures.map { InboxNotice(it) })

  fun now(): Instant = clock.now()

  fun refresh() {
    viewModelScope.launch { sync.refresh() }
  }

  fun setQuery(text: String) {
    queryText.value = text
  }

  /** Shows a saved view, or the whole inbox for null. */
  fun selectView(id: Long?) {
    viewModelScope.launch {
      val view = id?.let { viewsRepository.views.first().find { it.id == id } }
      viewId.value = view?.id
      queryText.value = view?.query.orEmpty()
    }
  }

  fun saveView(id: Long?, name: String, query: String, groupByRepo: Boolean) {
    viewModelScope.launch {
      val saved = viewsRepository.save(id, name, query, groupByRepo)
      viewId.value = saved
      queryText.value = query.trim()
    }
  }

  fun deleteView(id: Long) {
    viewModelScope.launch {
      viewsRepository.delete(id)
      if (viewId.value == id) selectView(null)
    }
  }

  fun toggleGroupByRepo() {
    viewModelScope.launch {
      val current = state.value
      val view = current.view
      if (view != null) {
        viewsRepository.save(view.id, view.name, view.query, !view.groupByRepo)
      } else {
        settings.setGroupByRepo(!current.settings.groupByRepo)
      }
    }
  }

  fun markDone(row: InboxRow) {
    viewModelScope.launch { notify("Marked done", inbox.markDone(row.key)) }
  }

  /** Marks every row in the current view done, with a longer window to undo it. */
  fun markAllDone(rows: List<InboxRow>) {
    if (rows.isEmpty()) return
    viewModelScope.launch {
      val undo = inbox.markDone(rows.map { it.key }, BULK_UNDO_WINDOW)
      notify("${rows.size} marked done", undo, long = true)
    }
  }

  /**
   * Marks a row read, as opening it does. A row read elsewhere is read already; this clears its
   * mark instead.
   */
  fun markRead(row: InboxRow) {
    when {
      row.isUnread -> viewModelScope.launch { inbox.markRead(row.key) }
      row.readElsewhere -> viewModelScope.launch { inbox.acknowledgeReadElsewhere(row.key) }
    }
  }

  /**
   * Marks done the rows in [rows] that were read or done elsewhere, which GitHub can't tell apart.
   * Sending done for one that's already done there is harmless.
   */
  fun markReadElsewhereDone(rows: List<InboxRow>) = markAllDone(rows.filter { it.readElsewhere })

  fun toggleRead(row: InboxRow) {
    viewModelScope.launch {
      if (row.isUnread) inbox.markRead(row.key)
      else notify("Marked unread", inbox.markUnread(row.key))
    }
  }

  fun snooze(row: InboxRow, until: Instant) {
    viewModelScope.launch {
      notify("Snoozed until ${snoozeTime(until, clock.now())}", inbox.snooze(row.key, until))
    }
  }

  fun unsnooze(row: InboxRow) {
    viewModelScope.launch { inbox.unsnooze(row.key) }
  }

  /** Undoes the last action that can be, as the keyboard's `z` does. */
  fun undoLast() {
    val undo = lastUndo ?: return
    lastUndo = null
    viewModelScope.launch { undo.undo() }
  }

  private fun notify(message: String, undo: Undo, long: Boolean = false) {
    lastUndo = undo
    _notices.tryEmit(InboxNotice(message, undo, long))
  }

  companion object {
    /** Marking a whole view done gets a longer snackbar, and as long to take it back. */
    val BULK_UNDO_WINDOW = 11.seconds
  }
}

val InboxRow.key: ThreadKey
  get() = ThreadKey(accountId, id)
