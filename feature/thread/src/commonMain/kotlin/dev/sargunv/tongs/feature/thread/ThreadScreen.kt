package dev.sargunv.tongs.feature.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sargunv.tongs.core.data.ThreadSnapshot
import dev.sargunv.tongs.core.markdown.ForgeLink
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.markdown.ProvideForgeLinks
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.core.ui.isDesktop
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import kotlin.time.Clock
import kotlin.time.Instant

/** The thread's column never gets wider than this, so lines stay readable on desktop. */
private val READABLE_WIDTH = 760.dp

/**
 * An issue or PR conversation, in one scroll: the title and state, a summary card, the primary
 * action, the description, the timeline, and Reply and Quote at the end. Opens from the cache at
 * once and refreshes in the background.
 */
@Composable
fun ThreadScreen(
  viewModel: ThreadViewModel,
  onBack: (() -> Unit)?,
  onOpenChanges: (() -> Unit)?,
  onOpenLink: (ForgeLink.Thread) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(viewModel) { viewModel.failures.collect { snackbar.showSnackbar(it) } }
  val now = remember(state.snapshot?.fetchedAt, state.status) { Clock.System.now() }
  ThreadContent(
    route = viewModel.route,
    state = state,
    now = now,
    actions = if (viewModel.ref == null) ThreadActions.None else viewModel,
    onBack = onBack,
    onOpenChanges = onOpenChanges,
    onOpenLink = onOpenLink,
    snackbar = snackbar,
  )
}

/** [ThreadScreen]'s content, from state, so screenshots can render it. */
@Composable
fun ThreadContent(
  route: Route.Thread,
  state: ThreadUiState,
  now: Instant,
  actions: ThreadActions,
  onBack: (() -> Unit)?,
  onOpenChanges: (() -> Unit)?,
  onOpenLink: (ForgeLink.Thread) -> Unit,
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
  val snapshot = state.snapshot
  val detail = snapshot?.detail
  val browser = LocalUriHandler.current
  val number = route.number?.let { "#$it" }
  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = number ?: route.repo.toString(),
        subtitle = route.repo.toString().takeIf { number != null },
        onBack = onBack,
        actions = {
          if (isDesktop) ActionIcon(TongsIcons.Refresh, "Refresh", actions::refresh)
          val web = detail?.webUrl
          if (web != null)
            ActionIcon(TongsIcons.OpenInNew, "Open in browser", { browser.openUri(web) })
          if (detail != null) OverflowMenu(detail, actions)
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    val context = remember(state.webUrl, route.repo) { MarkdownContext(state.webUrl, route.repo) }
    ProvideForgeLinks(state.webUrl, onOpenLink) {
      PullToRefreshBox(
        isRefreshing = state.status.refreshing && snapshot != null,
        onRefresh = actions::refresh,
        modifier = Modifier.padding(padding).fillMaxSize(),
      ) {
        when {
          snapshot != null ->
            Thread(snapshot, state, context, now, actions, onOpenChanges) { browser.openUri(it) }
          state.status.error != null ->
            EmptyState(
              TongsIcons.Issue,
              "Couldn't load this thread",
              body = state.status.error,
              action = { Button(onClick = actions::refresh) { Text("Try again") } },
            )
          route.number == null ||
            route.subjectType in setOf("Release", "Commit", "Discussion", "CheckSuite") ->
            EmptyState(
              TongsIcons.Notifications,
              state.notificationTitle ?: route.repo.toString(),
              body = "Tongs doesn't show ${route.subjectType.lowercase()}s yet.",
            )
          else -> Loading(state.notificationTitle)
        }
      }
    }
  }
}

@Composable
private fun Loading(title: String?) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    modifier = Modifier.fillMaxSize().padding(32.dp),
  ) {
    if (title != null) {
      Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.widthIn(max = 520.dp),
      )
    }
    LoadingIndicator()
  }
}

@Composable
private fun Thread(
  snapshot: ThreadSnapshot,
  state: ThreadUiState,
  context: MarkdownContext,
  now: Instant,
  actions: ThreadActions,
  onOpenChanges: (() -> Unit)?,
  openInBrowser: (String) -> Unit,
) {
  val detail = snapshot.detail
  val env =
    remember(context, now, actions, detail.viewer) {
      TimelineEnv(
        context = context,
        now = now,
        actions = actions,
        canReact = detail.viewer.canReact,
        canComment = detail.viewer.canComment,
        isPullRequest = detail.kind == ThreadKind.PullRequest,
        openInBrowser = openInBrowser,
      )
    }
  val rows = remember(snapshot) { timelineRows(snapshot) }
  val list = rememberLazyListState()
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      state = list,
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
      modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxSize(),
    ) {
      item(key = "header") { Header(detail, now) }
      item(key = "summary") {
        Box(Modifier.padding(top = 16.dp)) {
          SummaryCard(detail, onOpenChanges.takeIf { detail.pullRequest != null }, openInBrowser)
        }
      }
      item(key = "primary") { PrimaryActions(detail, state, actions, onOpenChanges) }
      item(key = "description") {
        Post(
          author = detail.author,
          meta = if (detail.editedAt != null) "edited" else null,
          at = detail.createdAt,
          body = detail.body,
          reactions = detail.reactions,
          subjectId = detail.id,
          webUrl = detail.webUrl,
          env = env,
          verb = { Verb("opened this") },
          emptyBody = "No description provided.",
          modifier = Modifier.padding(top = 20.dp),
        )
      }
      timeline(rows, state, env, actions)
      item(key = "end") { End(detail, snapshot, state, actions) }
    }
  }
}

@Composable
internal fun Verb(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun LazyListScope.timeline(
  rows: List<TimelineRow>,
  state: ThreadUiState,
  env: TimelineEnv,
  actions: ThreadActions,
) {
  items(rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
    val spacing =
      when (row) {
        is TimelineRow.Comment,
        is TimelineRow.Review -> if (row is TimelineRow.Review && row.isReplyOnly) 10.dp else 22.dp
        is TimelineRow.Gap -> 16.dp
        else -> 10.dp
      }
    Box(Modifier.padding(top = spacing)) {
      if (row is TimelineRow.Gap) Gap(row.count, state.status.loadingHidden, actions::loadHidden)
      else TimelineRowView(row, env)
    }
  }
}

@Composable
private fun Header(detail: ThreadDetail, now: Instant) {
  BoxWithConstraints(Modifier.padding(top = 8.dp)) {
    // A narrow pane, like the thread beside the inbox on an unfolded phone, gets a smaller title.
    val title =
      if (maxWidth < 440.dp) MaterialTheme.typography.titleLarge
      else MaterialTheme.typography.headlineSmall
    Column {
      Text(detail.title, style = title.copy(fontWeight = FontWeight.Medium))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
      ) {
        StatePill(stateLook(detail))
        val by = detail.author?.login ?: "ghost"
        val pr = detail.pullRequest
        val what =
          when {
            detail.state == ThreadState.Merged && pr?.mergedBy != null ->
              "${pr.mergedBy!!.login} merged ${pr.mergedAt?.let { ago(it, now) } ?: ""}".trim()
            else -> "$by opened ${ago(detail.createdAt, now)}"
          }
        val comments = detail.commentCount?.takeIf { it > 0 }?.let { plural(it, "comment") }
        Text(
          listOfNotNull(what, comments).joinToString(" · "),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The one or two things you'd do next. For a PR, read its changes, or merge it when it's approved
 * and clean. Replying lives at the end of the timeline, after you've read it.
 */
@Composable
private fun PrimaryActions(
  detail: ThreadDetail,
  state: ThreadUiState,
  actions: ThreadActions,
  onOpenChanges: (() -> Unit)?,
) {
  val pr = detail.pullRequest ?: return
  var merging by rememberSaveable { mutableStateOf(false) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  ) {
    val ready = detail.readyToMerge()
    val changes: @Composable (Modifier) -> Unit = { modifier ->
      if (onOpenChanges != null) {
        val content: @Composable () -> Unit = {
          Icon(TongsIcons.Changes, null, Modifier.size(ButtonDefaults.IconSize))
          Text("Changes", Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        if (ready) FilledTonalButton(onClick = onOpenChanges, modifier = modifier) { content() }
        else Button(onClick = onOpenChanges, modifier = modifier) { content() }
      }
    }
    changes(Modifier.weight(1f))
    if (ready) {
      Button(
        onClick = { merging = true },
        enabled = !state.status.merging,
        modifier = Modifier.weight(1f),
      ) {
        if (state.status.merging) {
          CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
        } else {
          Icon(TongsIcons.PullRequest, null, Modifier.size(ButtonDefaults.IconSize))
        }
        Text("Merge", Modifier.padding(start = ButtonDefaults.IconSpacing))
      }
    }
  }
  if (merging) {
    MergeDialog(
      detail,
      pr.headRef,
      onMerge = {
        merging = false
        actions.merge(it)
      },
      onDismiss = { merging = false },
    )
  }
}

@Composable
private fun MergeDialog(
  detail: ThreadDetail,
  head: String,
  onMerge: (MergeMethod) -> Unit,
  onDismiss: () -> Unit,
) {
  val methods = detail.viewer.mergeMethods
  var method by remember {
    mutableStateOf(detail.viewer.defaultMergeMethod?.takeIf { it in methods } ?: methods.first())
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(TongsIcons.PullRequest, null) },
    title = { Text("Merge #${detail.number}?") },
    text = {
      Column {
        Text(
          "Merges $head into ${detail.pullRequest?.baseRef}, if no one has pushed to it since you loaded it.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.padding(top = 12.dp)) {
          for (option in methods) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier.fillMaxWidth()
                  .selectable(selected = method == option, onClick = { method = option })
                  .padding(vertical = 2.dp),
            ) {
              RadioButton(selected = method == option, onClick = null)
              Text(mergeLabel(option), Modifier.padding(start = 12.dp))
            }
          }
        }
      }
    },
    confirmButton = { Button(onClick = { onMerge(method) }) { Text(mergeLabel(method)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun mergeLabel(method: MergeMethod) =
  when (method) {
    MergeMethod.Merge -> "Create a merge commit"
    MergeMethod.Squash -> "Squash and merge"
    MergeMethod.Rebase -> "Rebase and merge"
    is MergeMethod.Other -> method.raw.lowercase()
  }

@Composable
private fun Gap(count: Int, loading: Boolean, onLoad: () -> Unit) {
  Surface(
    onClick = onLoad,
    enabled = !loading,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
      Text(
        if (loading) "Loading…" else "${plural(count, "hidden item")} · Load more",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      if (loading) LinearProgressIndicator(Modifier.padding(top = 8.dp).fillMaxWidth(0.4f))
    }
  }
}

/**
 * The end of the timeline: Reply and Quote, like an email. An unsent reply shows here as a draft to
 * pick up again.
 */
@Composable
private fun End(
  detail: ThreadDetail,
  snapshot: ThreadSnapshot,
  state: ThreadUiState,
  actions: ThreadActions,
) {
  Column(Modifier.padding(top = 28.dp)) {
    if (!detail.viewer.canComment) {
      Text(
        if (detail.locked) "This conversation is locked." else "You can't comment here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = GUTTER),
      )
      return
    }
    val last =
      snapshot.items.lastOrNull { it.item is TimelineItem.Comment && !it.pending }?.item
        as? TimelineItem.Comment
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = actions::reply, modifier = Modifier.weight(1f)) {
        Icon(
          if (state.hasDraft) TongsIcons.Edit else ConversationIcons.Reply,
          null,
          Modifier.size(ButtonDefaults.IconSize),
        )
        Text(
          if (state.hasDraft) "Draft" else "Reply",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
        )
      }
      OutlinedButton(
        onClick = {
          if (last != null) actions.quote(last.body, last.author?.login)
          else actions.quote(detail.body, detail.author?.login)
        },
        // Shares the row evenly, so a long name ellipsizes rather than squeezing Reply.
        modifier = Modifier.weight(1f),
      ) {
        // Quotes the last comment, or the description when there's none.
        Icon(ConversationIcons.Quote, null, Modifier.size(ButtonDefaults.IconSize))
        Text("Quote", Modifier.padding(start = ButtonDefaults.IconSpacing))
      }
    }
  }
}

@Composable
private fun OverflowMenu(detail: ThreadDetail, actions: ThreadActions) {
  var open by remember { mutableStateOf(false) }
  Box {
    ActionIcon(TongsIcons.MoreVert, "More", { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (!isDesktop) {
        DropdownMenuItem(
          text = { Text("Refresh") },
          leadingIcon = { Icon(TongsIcons.Refresh, null) },
          onClick = {
            open = false
            actions.refresh()
          },
        )
      }
      val isIssue = detail.kind == ThreadKind.Issue
      if (detail.state == ThreadState.Open && detail.viewer.canClose) {
        DropdownMenuItem(
          text = { Text(if (isIssue) "Close as completed" else "Close pull request") },
          leadingIcon = { Icon(ConversationIcons.CheckCircle, null) },
          onClick = {
            open = false
            actions.setState(close = true, reason = StateReason.Completed.takeIf { isIssue })
          },
        )
        if (isIssue) {
          DropdownMenuItem(
            text = { Text("Close as not planned") },
            leadingIcon = { Icon(ConversationIcons.Skipped, null) },
            onClick = {
              open = false
              actions.setState(close = true, reason = StateReason.NotPlanned)
            },
          )
        }
      }
      if (detail.state == ThreadState.Closed && detail.viewer.canReopen) {
        DropdownMenuItem(
          text = { Text("Reopen") },
          leadingIcon = { Icon(TongsIcons.Undo, null) },
          onClick = {
            open = false
            actions.setState(close = false)
          },
        )
      }
    }
  }
}
