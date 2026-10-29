package dev.sargunv.tongs.feature.feed

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sargunv.tongs.core.data.FeedGrouping
import dev.sargunv.tongs.core.data.FeedItem
import dev.sargunv.tongs.core.data.FeedMuteEntity
import dev.sargunv.tongs.core.data.MuteKind
import dev.sargunv.tongs.core.data.groupFeed
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.Avatar
import dev.sargunv.tongs.core.ui.FeedIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.core.ui.isDesktop
import kotlin.time.Instant
import kotlinx.coroutines.launch

/**
 * What the feed can do; the screen wires these to [FeedViewModel]. Each does nothing by default, so
 * screenshot tests implement none.
 */
interface FeedActions {
  /** Opens an issue or PR beside the feed, or anything else in the browser. */
  fun open(item: FeedItem) {}

  fun refresh() {}

  fun loadMore() {}

  fun setGrouping(grouping: FeedGrouping) {}

  fun mute(accountId: Long, kind: MuteKind, value: String) {}

  fun unmute(mute: FeedMuteEntity) {}

  fun undoLast() {}

  fun signIn() {}
}

@Composable
fun FeedScreen(
  viewModel: FeedViewModel,
  selected: (FeedItem) -> Boolean,
  onOpen: (FeedItem) -> Unit,
  onSignIn: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(viewModel) {
    viewModel.notices.collect { notice ->
      snackbar.currentSnackbarData?.dismiss()
      launch {
        val result =
          snackbar.showSnackbar(
            notice.message,
            actionLabel = if (notice.undo != null) "Undo" else null,
            withDismissAction = notice.undo == null,
          )
        if (result == SnackbarResult.ActionPerformed) notice.undo?.undo()
      }
    }
  }
  val actions =
    remember(viewModel, onOpen, onSignIn) {
      object : FeedActions {
        override fun open(item: FeedItem) = onOpen(item)

        override fun refresh() = viewModel.refresh()

        override fun loadMore() = viewModel.loadMore()

        override fun setGrouping(grouping: FeedGrouping) = viewModel.setGrouping(grouping)

        override fun mute(accountId: Long, kind: MuteKind, value: String) =
          viewModel.mute(accountId, kind, value)

        override fun unmute(mute: FeedMuteEntity) = viewModel.unmute(mute)

        override fun undoLast() {
          snackbar.currentSnackbarData?.dismiss()
          viewModel.undoLast()
        }

        override fun signIn() = onSignIn()
      }
    }
  FeedContent(state, viewModel.now(), selected, actions, snackbar)
}

/**
 * The feed, stateless, for the screen and for screenshot tests: one list, newest first, with bursts
 * aggregated, optionally grouped by repo or person.
 *
 * @param mutedOpen starts with the muted list showing, as a screenshot test does.
 */
@Composable
fun FeedContent(
  state: FeedUiState,
  now: Instant,
  selected: (FeedItem) -> Boolean,
  actions: FeedActions,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
  mutedOpen: Boolean = false,
) {
  var showMuted by remember { mutableStateOf(mutedOpen) }
  val rootFocus = remember { FocusRequester() }
  val keys = remember { RowKeys() }
  val data = state.data
  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = "Activity",
        subtitle =
          when (state.grouping) {
            FeedGrouping.None -> null
            FeedGrouping.Repo -> "By repo"
            FeedGrouping.Person -> "By person"
          },
        actions = {
          if (isDesktop) ActionIcon(TongsIcons.Refresh, "Refresh (r)", actions::refresh)
          OverflowMenu(state, actions, onShowMuted = { showMuted = true })
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    LaunchedEffect(Unit) { if (isDesktop) runCatching { rootFocus.requestFocus() } }
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
          val ctrl = event.isCtrlPressed || event.isMetaPressed
          when {
            ctrl && event.key == Key.Z -> actions.undoLast()
            ctrl -> return@onKeyEvent false
            keys.handle?.invoke(event) == true -> {}
            event.key == Key.Z -> actions.undoLast()
            event.key == Key.R -> actions.refresh()
            else -> return@onKeyEvent false
          }
          true
        }
        .focusRequester(rootFocus)
        .focusable(),
    ) {
      FeedBanners(state, now, actions)
      when {
        data == null ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else ->
          PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize(),
          ) {
            val neverSynced =
              state.sync.isNotEmpty() &&
                state.sync.all { it.lastSyncedAt == null && it.problem == null }
            when {
              data.items.isNotEmpty() -> FeedList(state, now, selected, actions, keys)
              neverSynced ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  LoadingIndicator()
                }
              else -> EmptyFeed(state, now, actions, onShowMuted = { showMuted = true })
            }
          }
      }
    }
  }
  if (showMuted) {
    MutedDialog(state.mutes, state, actions, onDismiss = { showMuted = false })
  }
}

@Composable
private fun OverflowMenu(state: FeedUiState, actions: FeedActions, onShowMuted: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    ActionIcon(TongsIcons.MoreVert, "More", { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for ((grouping, label) in
        listOf(
          FeedGrouping.None to "One list",
          FeedGrouping.Repo to "Group by repo",
          FeedGrouping.Person to "Group by person",
        )) {
        DropdownMenuItem(
          text = { Text(label) },
          leadingIcon = {
            Icon(
              when (grouping) {
                FeedGrouping.None -> TongsIcons.Activity
                FeedGrouping.Repo -> TongsIcons.Folder
                FeedGrouping.Person -> TongsIcons.Account
              },
              null,
            )
          },
          trailingIcon = { if (state.grouping == grouping) Icon(TongsIcons.Check, "On") },
          onClick = {
            open = false
            actions.setGrouping(grouping)
          },
        )
      }
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text(if (state.mutes.isEmpty()) "Muted" else "Muted (${state.mutes.size})") },
        leadingIcon = { Icon(FeedIcons.Mute, null) },
        onClick = {
          open = false
          onShowMuted()
        },
      )
    }
  }
}

/** The muted repos and people, each with Unmute. */
@Composable
private fun MutedDialog(
  mutes: List<FeedMuteEntity>,
  state: FeedUiState,
  actions: FeedActions,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Muted") },
    text = {
      if (mutes.isEmpty()) {
        Text(
          "Nothing muted. " +
            (if (isDesktop) "Hover over an item" else "Long-press an item") +
            " to mute its repo or the people in it; their activity stays out of the feed on " +
            "this device.",
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          for (mute in mutes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                if (mute.kind == MuteKind.Repo.name) TongsIcons.Folder else TongsIcons.Account,
                null,
                Modifier.size(18.dp),
              )
              Text(
                mute.value +
                  if (state.multipleAccounts) " · ${state.viewer(mute.accountId)}" else "",
                modifier = Modifier.padding(start = 12.dp).weight(1f),
              )
              TextButton(onClick = { actions.unmute(mute) }) { Text("Unmute") }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedList(
  state: FeedUiState,
  now: Instant,
  selected: (FeedItem) -> Boolean,
  actions: FeedActions,
  keys: RowKeys,
) {
  val data = state.data ?: return
  val sections = remember(data.items, state.grouping) { groupFeed(data.items, state.grouping) }
  val ordered = remember(sections) { sections.flatMap { it.items } }
  val grouped = state.grouping != FeedGrouping.None
  val listState = rememberLazyListState()
  var cursor by remember { mutableIntStateOf(-1) }
  if (cursor > ordered.lastIndex) cursor = ordered.lastIndex
  LaunchedEffect(state.grouping) {
    listState.scrollToItem(0)
    cursor = -1
  }
  fun listIndex(i: Int): Int {
    if (!grouped) return i
    var seen = 0
    var index = 0
    for (section in sections) {
      index++
      if (i < seen + section.items.size) return index + (i - seen)
      seen += section.items.size
      index += section.items.size
    }
    return index
  }
  LaunchedEffect(cursor, sections) { if (cursor >= 0) listState.scrollToShow(listIndex(cursor)) }
  keys.handle = handle@{ event ->
    val item = ordered.getOrNull(cursor)
    when {
      event.key == Key.J || event.key == Key.DirectionDown ->
        cursor = (cursor + 1).coerceAtMost(ordered.lastIndex)
      event.key == Key.K || event.key == Key.DirectionUp -> cursor = (cursor - 1).coerceAtLeast(0)
      item == null -> return@handle false
      event.key == Key.Enter || event.key == Key.O -> actions.open(item)
      else -> return@handle false
    }
    true
  }

  @Composable
  fun Item(index: Int, item: FeedItem, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val accountId = item.accountId
    val primary = item.actors.first()
    Box(modifier) {
      FeedRowItem(
        item = item,
        now = now,
        viewer = state.viewer(accountId),
        account = state.viewer(accountId).takeIf { state.multipleAccounts },
        groupedByRepo = state.grouping == FeedGrouping.Repo,
        selected = selected(item),
        cursor = isDesktop && index == cursor,
        onClick = {
          cursor = index
          actions.open(item)
        },
        onLongClick = { menu = true },
        hoverActions = {
          if (item.repos.size == 1) {
            ActionIcon(
              FeedIcons.Mute,
              "Mute ${item.repos.first()}",
              {
                actions.mute(accountId, MuteKind.Repo, item.repos.first().toString())
              },
            )
          }
          ActionIcon(
            FeedIcons.MutePerson,
            "Mute ${primary.login}",
            {
              actions.mute(accountId, MuteKind.Actor, primary.login)
            },
          )
          ActionIcon(TongsIcons.MoreVert, "More", { menu = true })
        },
      )
      ItemMenu(
        item,
        open = menu,
        webUrl = state.accounts.find { it.id == accountId }?.webUrl,
        actions = actions,
        onDismiss = { menu = false },
      )
    }
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    var index = 0
    for (section in sections) {
      if (grouped) {
        stickyHeader(key = section.key) {
          SectionHeader(section.title, section.avatarUrl, section.items.size, state.grouping) {
            val accountId = section.items.first().accountId
            val kind = if (state.grouping == FeedGrouping.Repo) MuteKind.Repo else MuteKind.Actor
            actions.mute(accountId, kind, section.title)
          }
        }
      }
      for (item in section.items) {
        val i = index++
        item(key = item.key) { Item(i, item, Modifier.animateItem()) }
      }
    }
    if (data.canLoadMore) {
      item(key = "more") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
          TextButton(onClick = actions::loadMore) { Text("Show older activity") }
        }
      }
    }
  }
}

/** Long-press (or the hover "More"): open in the browser, mute the repo or anyone in the item. */
@Composable
private fun ItemMenu(
  item: FeedItem,
  open: Boolean,
  webUrl: String?,
  actions: FeedActions,
  onDismiss: () -> Unit,
) {
  val uri = LocalUriHandler.current
  DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
    item.webUrl?.let { url ->
      DropdownMenuItem(
        text = { Text("Open in browser") },
        leadingIcon = { Icon(TongsIcons.OpenInNew, null) },
        onClick = {
          onDismiss()
          uri.openUri(url)
        },
      )
      HorizontalDivider()
    }
    if (webUrl != null) {
      for (repo in item.repos.take(3)) {
        DropdownMenuItem(
          text = { Text("Open $repo") },
          onClick = {
            onDismiss()
            uri.openUri("$webUrl/$repo")
          },
        )
      }
      for (person in item.actors.take(3)) {
        DropdownMenuItem(
          text = { Text("Open ${person.login}") },
          onClick = {
            onDismiss()
            uri.openUri("$webUrl/${person.login}")
          },
        )
      }
      HorizontalDivider()
    }
    for (repo in item.repos.take(3)) {
      DropdownMenuItem(
        text = { Text("Mute $repo") },
        leadingIcon = { Icon(FeedIcons.Mute, null) },
        onClick = {
          onDismiss()
          actions.mute(item.accountId, MuteKind.Repo, repo.toString())
        },
      )
    }
    for (person in item.actors.take(3)) {
      DropdownMenuItem(
        text = { Text("Mute ${person.login}") },
        leadingIcon = { Icon(FeedIcons.MutePerson, null) },
        onClick = {
          onDismiss()
          actions.mute(item.accountId, MuteKind.Actor, person.login)
        },
      )
    }
  }
}

/** A repo's or person's header when grouped: its name, how many items, and mute. */
@Composable
private fun SectionHeader(
  title: String,
  avatarUrl: String?,
  count: Int,
  grouping: FeedGrouping,
  onMute: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp),
    ) {
      if (grouping == FeedGrouping.Person) {
        Avatar(avatarUrl, title, 16.dp)
      } else {
        Icon(
          TongsIcons.Folder,
          null,
          Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      Text(
        "$count",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ActionIcon(
        if (grouping == FeedGrouping.Person) FeedIcons.MutePerson else FeedIcons.Mute,
        "Mute $title",
        onMute,
      )
    }
  }
}

/** The list's handler for keys on the item under the cursor. */
private class RowKeys {
  var handle: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
}

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
