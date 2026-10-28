package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.SwipeAction
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.SwipeAction as SwipeActionUi
import dev.sargunv.tongs.core.ui.SwipeActionsBox
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.core.ui.isDesktop
import kotlin.time.Instant
import kotlinx.coroutines.launch

/**
 * What the inbox can do; the screen wires these to [InboxViewModel]. Each does nothing by default,
 * so previews and screenshot tests implement none.
 */
interface InboxActions {
  fun open(row: InboxRow) {}

  fun refresh() {}

  fun markDone(row: InboxRow) {}

  fun markAllDone(rows: List<InboxRow>) {}

  fun toggleRead(row: InboxRow) {}

  fun markRead(row: InboxRow) {}

  fun snooze(row: InboxRow, until: Instant) {}

  fun unsnooze(row: InboxRow) {}

  fun undoLast() {}

  fun setQuery(text: String) {}

  fun selectView(id: Long?) {}

  fun saveView(id: Long?, name: String, query: String, groupByRepo: Boolean) {}

  fun deleteView(id: Long) {}

  fun toggleGroupByRepo() {}

  /** Signs in again, to replace a token the forge rejects. */
  fun signIn() {}
}

@Composable
fun InboxScreen(
  viewModel: InboxViewModel,
  selected: (InboxRow) -> Boolean,
  onOpen: (InboxRow) -> Unit,
  onSignIn: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(viewModel) {
    viewModel.notices.collect { notice ->
      // A new notice replaces the one showing; its undo stays available through `z`.
      snackbar.currentSnackbarData?.dismiss()
      launch {
        val result =
          snackbar.showSnackbar(
            notice.message,
            actionLabel = if (notice.undo != null) "Undo" else null,
            withDismissAction = notice.undo == null,
            duration = if (notice.long) SnackbarDuration.Long else SnackbarDuration.Short,
          )
        if (result == SnackbarResult.ActionPerformed) notice.undo?.undo()
      }
    }
  }
  val actions =
    remember(viewModel, onOpen, onSignIn, snackbar) {
      object : InboxActions {
        override fun open(row: InboxRow) {
          viewModel.markRead(row)
          onOpen(row)
        }

        override fun refresh() = viewModel.refresh()

        override fun markDone(row: InboxRow) = viewModel.markDone(row)

        override fun markAllDone(rows: List<InboxRow>) = viewModel.markAllDone(rows)

        override fun toggleRead(row: InboxRow) = viewModel.toggleRead(row)

        override fun markRead(row: InboxRow) = viewModel.markRead(row)

        override fun snooze(row: InboxRow, until: Instant) = viewModel.snooze(row, until)

        override fun unsnooze(row: InboxRow) = viewModel.unsnooze(row)

        override fun undoLast() {
          snackbar.currentSnackbarData?.dismiss()
          viewModel.undoLast()
        }

        override fun setQuery(text: String) = viewModel.setQuery(text)

        override fun selectView(id: Long?) = viewModel.selectView(id)

        override fun saveView(id: Long?, name: String, query: String, groupByRepo: Boolean) =
          viewModel.saveView(id, name, query, groupByRepo)

        override fun deleteView(id: Long) = viewModel.deleteView(id)

        override fun toggleGroupByRepo() = viewModel.toggleGroupByRepo()

        override fun signIn() = onSignIn()
      }
    }
  InboxContent(state, viewModel.now(), selected, actions, snackbar)
}

/**
 * The inbox, stateless, for the screen and for screenshot tests: one list, latest first, with
 * GitHub's reason and what's new on each row, under the saved views and an optional filter.
 *
 * @param filterOpen starts with the filter showing, as the screenshot tests do.
 */
@Composable
fun InboxContent(
  state: InboxUiState,
  now: Instant,
  selected: (InboxRow) -> Boolean,
  actions: InboxActions,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
  filterOpen: Boolean = false,
) {
  var snoozing by remember { mutableStateOf<InboxRow?>(null) }
  var editingView by remember { mutableStateOf<Boolean?>(null) } // true: edit, false: new
  var showFilter by remember { mutableStateOf(filterOpen) }
  var showShortcuts by remember { mutableStateOf(false) }
  val listFocus = remember { FocusRequester() }
  val filterFocus = remember { FocusRequester() }
  val rows = state.rows
  val unread = rows?.count { it.isUnread } ?: 0
  val filterShowing = showFilter || state.queryEdited
  val focusFilter = remember { mutableIntStateOf(0) }
  val rowKeys = remember { RowKeys() }
  val rootFocus = remember { FocusRequester() }
  // The keyboard goes back to the inbox whenever the view changes or the filter closes, so
  // shortcuts keep working after a click elsewhere. The inbox's key handler drives the list's
  // cursor too, so the list itself needn't have the focus.
  fun focusInbox() {
    if (isDesktop) runCatching { rootFocus.requestFocus() }
  }

  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = state.title,
        subtitle =
          when {
            rows == null -> null
            rows.isEmpty() -> null
            unread > 0 -> "$unread unread of ${rows.size}"
            else -> "${rows.size} read"
          },
        actions = {
          ActionIcon(
            TongsIcons.Search,
            "Filter (/)",
            {
              showFilter = !filterShowing
              if (showFilter) focusFilter.intValue++ else actions.setQuery(state.view?.query ?: "")
            },
          )
          if (isDesktop) ActionIcon(TongsIcons.Refresh, "Refresh (r)", actions::refresh)
          OverflowMenu(
            state = state,
            onGroup = actions::toggleGroupByRepo,
            onMarkAllDone = { rows?.let(actions::markAllDone) },
            onEditView = { editingView = true },
            onShortcuts = { showShortcuts = true },
          )
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    // Shortcuts work wherever the keyboard is in the inbox, short of a text field, which keeps
    // the keys it types: events bubble up here from the list, the chips, and the buttons.
    LaunchedEffect(filterShowing, state.viewId) { if (!filterShowing) focusInbox() }
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
          val ctrl = event.isCtrlPressed || event.isMetaPressed
          when {
            ctrl && event.key == Key.Z -> actions.undoLast()
            ctrl -> return@onKeyEvent false
            rowKeys.handle?.invoke(event) == true -> {}
            event.key == Key.Slash && event.isShiftPressed -> showShortcuts = true
            event.key == Key.Slash -> {
              showFilter = true
              focusFilter.intValue++
            }
            event.key == Key.Z -> actions.undoLast()
            event.key == Key.R -> actions.refresh()
            event.key == Key.Escape -> {
              if (state.view == null && !state.query.isEmpty) actions.setQuery("")
              showFilter = false
            }
            else -> return@onKeyEvent false
          }
          true
        }
        // After the key handler, so it hears keys while it has the focus itself.
        .focusRequester(rootFocus)
        .focusable(),
    ) {
      ViewChips(
        state,
        object : InboxActions by actions {
          override fun selectView(id: Long?) {
            actions.selectView(id)
            focusInbox()
          }
        },
        onNewView = { editingView = false },
      )
      if (filterShowing) {
        FilterBar(
          query = state.query,
          rows = rows.orEmpty(),
          focus = filterFocus,
          focusRequests = focusFilter.intValue,
          onChange = actions::setQuery,
          onDone = { focusInbox() },
        )
      }
      Box(Modifier.padding(top = 4.dp))
      SyncBanners(state, now, actions)
      when {
        rows == null ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else ->
          PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize(),
          ) {
            if (rows.isEmpty()) {
              EmptyInbox(state, now, actions)
            } else {
              InboxList(
                rows = rows,
                state = state,
                now = now,
                selected = selected,
                actions = actions,
                focus = listFocus,
                keys = rowKeys,
                onSnooze = { snoozing = it },
              )
            }
          }
      }
    }
  }
  // Dialogs hand the keyboard back to the list when they close.
  fun backToList() = focusInbox()
  snoozing?.let { row ->
    SnoozeDialog(
      now = now,
      onPick = {
        actions.snooze(row, it)
        snoozing = null
        backToList()
      },
      onDismiss = {
        snoozing = null
        backToList()
      },
    )
  }
  editingView?.let { edit ->
    val view = state.view?.takeIf { edit }
    ViewDialog(
      editing = view,
      query = state.query.text,
      groupByRepo = state.groupByRepo,
      onSave = { name, query, group ->
        actions.saveView(view?.id, name, query, group)
        editingView = null
        showFilter = false
        backToList()
      },
      onDelete =
        view?.let {
          {
            actions.deleteView(it.id)
            editingView = null
          }
        },
      onDismiss = {
        editingView = null
        backToList()
      },
    )
  }
  if (showShortcuts) {
    ShortcutsDialog(
      onDismiss = {
        showShortcuts = false
        backToList()
      },
    )
  }
}

@Composable
private fun OverflowMenu(
  state: InboxUiState,
  onGroup: () -> Unit,
  onMarkAllDone: () -> Unit,
  onEditView: () -> Unit,
  onShortcuts: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    ActionIcon(TongsIcons.MoreVert, "More", { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      val count = state.rows?.size ?: 0
      DropdownMenuItem(
        text = { Text(if (count > 0) "Mark all $count as done" else "Mark all as done") },
        leadingIcon = { Icon(TongsIcons.DoneAll, null) },
        enabled = count > 0 && !state.query.wantsSnoozed,
        onClick = {
          open = false
          onMarkAllDone()
        },
      )
      DropdownMenuItem(
        text = { Text("Group by repo") },
        leadingIcon = { Icon(TongsIcons.Folder, null) },
        trailingIcon = { if (state.groupByRepo) Icon(TongsIcons.Check, "On") },
        onClick = {
          open = false
          onGroup()
        },
      )
      if (state.view != null) {
        DropdownMenuItem(
          text = { Text("Edit view") },
          leadingIcon = { Icon(TongsIcons.Edit, null) },
          onClick = {
            open = false
            onEditView()
          },
        )
      }
      if (isDesktop) {
        HorizontalDivider()
        DropdownMenuItem(
          text = { Text("Keyboard shortcuts") },
          leadingIcon = { Icon(TongsIcons.Keyboard, null) },
          trailingIcon = { Text("?") },
          onClick = {
            open = false
            onShortcuts()
          },
        )
      }
    }
  }
}

/** The rows in display order, with the list index of each: grouped by repo, headers between. */
private class Layout(val rows: List<InboxRow>, val groups: List<Pair<String, List<InboxRow>>>?) {
  /** The [LazyColumn] index of row [i], counting group headers. */
  fun listIndex(i: Int): Int {
    if (groups == null) return i
    var seen = 0
    var index = 0
    for ((_, group) in groups) {
      index++ // header
      if (i < seen + group.size) return index + (i - seen)
      seen += group.size
      index += group.size
    }
    return index
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxList(
  rows: List<InboxRow>,
  state: InboxUiState,
  now: Instant,
  selected: (InboxRow) -> Boolean,
  actions: InboxActions,
  focus: FocusRequester,
  keys: RowKeys,
  onSnooze: (InboxRow) -> Unit,
) {
  val layout =
    remember(rows, state.groupByRepo) {
      if (state.groupByRepo) {
        val groups = rows.groupBy { "${it.repoOwner}/${it.repoName}" }.toList()
        Layout(groups.flatMap { it.second }, groups)
      } else {
        Layout(rows, null)
      }
    }
  val ordered = layout.rows
  val listState = rememberLazyListState()
  // The keyboard cursor, on desktop: j and k move it, like a mail client. It stays at its index
  // when the row under it goes, so the next row takes its place.
  var cursor by remember { mutableIntStateOf(-1) }
  if (cursor > ordered.lastIndex) cursor = ordered.lastIndex
  // Another view or filter starts at the top. Without this, the list would hold on to the row it
  // showed first, wherever that row is in the new list.
  LaunchedEffect(state.viewId, state.query.text) {
    listState.scrollToItem(0)
    cursor = -1
  }
  LaunchedEffect(cursor, layout) {
    if (cursor >= 0) listState.scrollToShow(layout.listIndex(cursor))
  }
  val scope = rememberCoroutineScope()
  // The keys that act on the row under the cursor; the inbox's own handler asks these first.
  keys.handle = handle@{ event ->
    val row = ordered.getOrNull(cursor)
    when {
      event.key == Key.J || event.key == Key.DirectionDown ->
        cursor = (cursor + 1).coerceAtMost(ordered.lastIndex)
      event.key == Key.K || event.key == Key.DirectionUp -> cursor = (cursor - 1).coerceAtLeast(0)
      row == null -> return@handle false
      event.key == Key.Enter || event.key == Key.O -> actions.open(row)
      event.key == Key.E -> actions.markDone(row)
      event.key == Key.S -> onSnooze(row)
      event.key == Key.U && event.isShiftPressed -> actions.toggleRead(row)
      event.key == Key.I && event.isShiftPressed -> actions.markRead(row)
      else -> return@handle false
    }
    true
  }
  DisposableEffect(keys) { onDispose { keys.handle = null } }
  val colors = MaterialTheme.colorScheme
  fun swipe(action: SwipeAction, row: InboxRow): SwipeActionUi? =
    when (action) {
      SwipeAction.Done ->
        SwipeActionUi("Done", TongsIcons.Done, colors.primary, colors.onPrimary) {
          actions.markDone(row)
        }
      SwipeAction.Snooze ->
        SwipeActionUi("Snooze", TongsIcons.Snooze, colors.tertiary, colors.onTertiary) {
          onSnooze(row)
        }
      SwipeAction.ToggleRead ->
        SwipeActionUi(
          if (row.isUnread) "Read" else "Unread",
          if (row.isUnread) TongsIcons.MarkRead else TongsIcons.MarkUnread,
          colors.secondary,
          colors.onSecondary,
        ) {
          actions.toggleRead(row)
        }
      SwipeAction.None -> null
    }

  @Composable
  fun Item(index: Int, row: InboxRow, modifier: Modifier) {
    SwipeActionsBox(
      left = swipe(state.settings.swipeLeft, row),
      right = swipe(state.settings.swipeRight, row),
      modifier = modifier,
    ) {
      InboxRowItem(
        row = row,
        now = now,
        showAccount = state.multipleAccounts,
        grouped = layout.groups != null,
        selected = selected(row),
        cursor = isDesktop && index == cursor,
        onClick = {
          cursor = index
          runCatching { focus.requestFocus() }
          actions.open(row)
        },
        hoverActions = {
          ActionIcon(TongsIcons.Done, "Done (e)", { actions.markDone(row) })
          ActionIcon(
            if (row.isUnread) TongsIcons.MarkRead else TongsIcons.MarkUnread,
            if (row.isUnread) "Mark read (Shift+I)" else "Mark unread (Shift+U)",
            { actions.toggleRead(row) },
          )
          if (row.snoozedUntil != null && row.snoozedUntil!! > now.toEpochMilliseconds()) {
            ActionIcon(TongsIcons.Inbox, "Back to inbox", { actions.unsnooze(row) })
          } else {
            ActionIcon(TongsIcons.Snooze, "Snooze (s)", { onSnooze(row) })
          }
        },
      )
    }
  }

  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().focusRequester(focus).focusable(),
  ) {
    val groups = layout.groups
    if (groups == null) {
      for ((index, row) in ordered.withIndex()) {
        item(key = row.listKey) { Item(index, row, Modifier.animateItem()) }
      }
    } else {
      var index = 0
      for ((repo, group) in groups) {
        stickyHeader(key = "repo:$repo") {
          RepoHeader(
            repo,
            group,
            onDone = { scope.launch { actions.markAllDone(group) } },
          )
        }
        for (row in group) {
          val i = index++
          item(key = row.listKey) { Item(i, row, Modifier.animateItem()) }
        }
      }
    }
  }
}

/** The list's handler for keys on the row under the cursor, for the inbox's key handler. */
private class RowKeys {
  var handle: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
}

private val InboxRow.listKey
  get() = "$accountId/$id"

/** Scrolls just enough to show item [index], as a keyboard cursor needs. */
private suspend fun LazyListState.scrollToShow(index: Int) {
  val visible = layoutInfo.visibleItemsInfo
  val item = visible.find { it.index == index }
  val end = layoutInfo.viewportEndOffset
  when {
    item == null -> animateScrollToItem(index)
    item.offset < layoutInfo.viewportStartOffset -> animateScrollToItem(index)
    item.offset + item.size > end -> animateScrollBy((item.offset + item.size - end).toFloat())
  }
}

/** A repo's header when grouped: its name, how many threads, and done for all of them. */
@Composable
private fun RepoHeader(repo: String, rows: List<InboxRow>, onDone: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp),
    ) {
      Icon(TongsIcons.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
      Text(
        repo,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      val unread = rows.count { it.isUnread }
      Text(
        if (unread > 0) "$unread new · ${rows.size}" else "${rows.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ActionIcon(TongsIcons.DoneAll, "Mark ${rows.size} as done", onDone)
    }
  }
}
