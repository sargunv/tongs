package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.SwipeAction
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.ListRow
import dev.sargunv.tongs.core.ui.SwipeAction as SwipeActionUi
import dev.sargunv.tongs.core.ui.SwipeActionsBox
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.core.ui.isDesktop
import dev.sargunv.tongs.core.ui.shortAge
import kotlin.time.Instant

/** Triage callbacks for [InboxContent]; the screen wires them to [InboxViewModel]. */
interface InboxActions {
  fun open(row: InboxRow)

  fun refresh()

  fun markDone(row: InboxRow)

  fun toggleRead(row: InboxRow)

  fun snooze(row: InboxRow, until: Instant)
}

@Composable
fun InboxScreen(
  viewModel: InboxViewModel,
  selected: (InboxRow) -> Boolean,
  onOpen: (InboxRow) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(viewModel) { viewModel.failures.collect { snackbar.showSnackbar(it) } }
  val actions =
    remember(viewModel, onOpen) {
      object : InboxActions {
        override fun open(row: InboxRow) {
          viewModel.markRead(row)
          onOpen(row)
        }

        override fun refresh() = viewModel.refresh()

        override fun markDone(row: InboxRow) = viewModel.markDone(row)

        override fun toggleRead(row: InboxRow) = viewModel.toggleRead(row)

        override fun snooze(row: InboxRow, until: Instant) = viewModel.snooze(row, until)
      }
    }
  InboxContent(state, viewModel.now(), selected, actions, snackbar)
}

/**
 * The inbox list, stateless, for the screen and for screenshot tests. One list, latest first, with
 * GitHub's reason on each row.
 */
@Composable
fun InboxContent(
  state: InboxUiState,
  now: Instant,
  selected: (InboxRow) -> Boolean,
  actions: InboxActions,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
  var snoozing by remember { mutableStateOf<InboxRow?>(null) }
  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = "Inbox",
        actions = { ActionIcon(TongsIcons.Refresh, "Refresh", actions::refresh) },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      state.syncError?.let { SyncErrorBanner(it) }
      val rows = state.rows
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
              EmptyState(
                TongsIcons.DoneAll,
                "All caught up",
                body = "New notifications show up here while Tongs is open.",
              )
            } else {
              InboxList(rows, state, now, selected, actions, onSnooze = { snoozing = it })
            }
          }
      }
    }
  }
  snoozing?.let { row ->
    SnoozeDialog(
      now = now,
      onPick = {
        actions.snooze(row, it)
        snoozing = null
      },
      onDismiss = { snoozing = null },
    )
  }
}

@Composable
private fun InboxList(
  rows: List<InboxRow>,
  state: InboxUiState,
  now: Instant,
  selected: (InboxRow) -> Boolean,
  actions: InboxActions,
  onSnooze: (InboxRow) -> Unit,
) {
  val listState = rememberLazyListState()
  // The keyboard cursor, on desktop: j and k move it, like a mail client.
  var cursor by remember { mutableIntStateOf(-1) }
  val focus = remember { FocusRequester() }
  if (isDesktop) LaunchedEffect(Unit) { focus.requestFocus() }
  LaunchedEffect(cursor) { if (cursor >= 0) listState.animateScrollToItem(cursor) }
  val colors = MaterialTheme.colorScheme
  fun swipe(action: SwipeAction, row: InboxRow): SwipeActionUi? {
    return when (action) {
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
  }

  LazyColumn(
    state = listState,
    modifier =
      Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val row = rows.getOrNull(cursor)
        when {
          event.key == Key.J || event.key == Key.DirectionDown ->
            cursor = (cursor + 1).coerceAtMost(rows.lastIndex)
          event.key == Key.K || event.key == Key.DirectionUp ->
            cursor = (cursor - 1).coerceAtLeast(0)
          row == null -> return@onPreviewKeyEvent false
          event.key == Key.Enter || event.key == Key.O -> actions.open(row)
          event.key == Key.E -> actions.markDone(row)
          event.key == Key.S -> onSnooze(row)
          event.key == Key.U && event.isShiftPressed -> actions.toggleRead(row)
          event.key == Key.I && event.isShiftPressed -> actions.toggleRead(row)
          else -> return@onPreviewKeyEvent false
        }
        true
      },
  ) {
    itemsIndexed(rows, key = { _, row -> "${row.accountId}/${row.id}" }) { index, row ->
      SwipeActionsBox(
        left = swipe(state.settings.swipeLeft, row),
        right = swipe(state.settings.swipeRight, row),
        modifier = Modifier.animateItem(),
      ) {
        InboxRowItem(
          row = row,
          now = now,
          showAccount = state.multipleAccounts,
          selected = selected(row) || index == cursor,
          onClick = {
            cursor = index
            actions.open(row)
          },
          hoverActions = {
            ActionIcon(TongsIcons.Done, "Done (e)", { actions.markDone(row) })
            ActionIcon(
              if (row.isUnread) TongsIcons.MarkRead else TongsIcons.MarkUnread,
              if (row.isUnread) "Mark read" else "Mark unread",
              { actions.toggleRead(row) },
            )
            ActionIcon(TongsIcons.Snooze, "Snooze (s)", { onSnooze(row) })
          },
        )
      }
    }
  }
}

@Composable
private fun InboxRowItem(
  row: InboxRow,
  now: Instant,
  showAccount: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  hoverActions: @Composable RowScope.() -> Unit,
) {
  val number = row.subjectNumber?.let { " #$it" }.orEmpty()
  val account = if (showAccount) " · ${row.accountLogin}" else ""
  ListRow(
    overline = "${row.repoOwner}/${row.repoName}$number$account",
    headline = row.title,
    // GitHub's reason, and what's new since you last read it once hydration says.
    supporting = listOfNotNull(reasonLabel(row.reason), row.whatsNew).joinToString(" · "),
    leading = {
      Icon(
        subjectIcon(row.subjectType),
        contentDescription = row.subjectType,
        tint =
          if (row.isUnread) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    trailing = {
      Text(
        shortAge(row.sortAt, now.toEpochMilliseconds()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    hoverActions = hoverActions,
    emphasized = row.isUnread,
    selected = selected,
    onClick = onClick,
  )
}

@Composable
private fun SyncErrorBanner(message: String) {
  Surface(color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      "Couldn't sync: $message",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onErrorContainer,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

@Composable
private fun SnoozeDialog(now: Instant, onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Snooze until") },
    text = {
      Column {
        for (option in snoozeOptions(now)) {
          TextButton(onClick = { onPick(option.until) }) { Text(option.label) }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
