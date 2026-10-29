package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewColors
import dev.sargunv.tongs.codeview.CodeViewDelegate
import dev.sargunv.tongs.codeview.FileHeaderInfo
import dev.sargunv.tongs.codeview.LineSelection
import dev.sargunv.tongs.codeview.codeRowHeight
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.PullRequestChangesRepository
import dev.sargunv.tongs.core.data.PullRequestDiffs
import dev.sargunv.tongs.core.data.PullRequestKey
import dev.sargunv.tongs.core.markdown.ForgeLink
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.markdown.ProvideForgeLinks
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.engine.ReviewSnapshot
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.sargunv.tongs.forge.api.SubjectType
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads a PR's changes for the app through [PullRequestDiffs], and keeps the screen's state in a
 * [ChangesController], which owns the snapshots for as long as the ViewModel lives. The load starts
 * when the screen opens and stops when it's popped.
 */
@AssistedInject
class PullRequestChangesViewModel(
  @Assisted val route: Route.PullRequestChanges,
  accounts: AccountRepository,
  diffs: PullRequestDiffs,
  repository: PullRequestChangesRepository,
  drafts: DraftRepository,
) : ViewModel() {
  private val key = PullRequestKey(route.accountId, route.repo, route.number)

  val load =
    PullRequestLoad("${route.repo}#${route.number}", log = { println("review: $it") }) { onLoader ->
      diffs.load(key, onLoader)
    }

  val controller = ChangesController(viewModelScope, load, repository, drafts, key)

  /** The instance's web URL, which links in comments are relative to. */
  var webUrl by mutableStateOf<String?>(null)
    private set

  init {
    load.start(viewModelScope)
    viewModelScope.launch {
      accounts.account(route.accountId)?.instance?.webUrl?.let {
        webUrl = it
        controller.markdownContext = MarkdownContext(it, route.repo)
      }
    }
  }

  @AssistedFactory
  fun interface Factory {
    fun create(route: Route.PullRequestChanges): PullRequestChangesViewModel
  }
}

/** The tree goes beside the diff from here up; below, it's a sheet. */
private val TreePaneWidth = 560.dp
/** The review pane joins them from here up, as on desktop. */
private val SidePaneWidth = 1000.dp

/**
 * A PR's changes, for reading first: the diff opens as it loads, and reviewing starts with the
 * first line comment, which creates a pending review. The file tree is a sheet when folded and a
 * pane beside the diff when unfolded or on desktop, where a third pane holds the review and the
 * PR's threads.
 *
 * The composer, for line comments and replies, covers the screen when folded. Unfolded it's a pane
 * beside the diff, in the tree's place, and on desktop it takes the review pane, so the lines it's
 * on stay in sight. A thread opens in a sheet when folded, beside the diff when unfolded, and in
 * the review pane on desktop.
 */
@Composable
fun PullRequestChangesScreen(
  viewModel: PullRequestChangesViewModel,
  onBack: () -> Unit,
  onOpenLink: (ForgeLink.Thread) -> Unit = {},
) {
  val controller = viewModel.controller
  ProvideForgeLinks(viewModel.webUrl ?: "https://github.com", onOpenLink) {
    ChangesScreen(viewModel, controller, onBack)
  }
}

@Composable
private fun ChangesScreen(
  viewModel: PullRequestChangesViewModel,
  controller: ChangesController,
  onBack: () -> Unit,
) {
  val route = viewModel.route
  val snackbars = remember { SnackbarHostState() }
  LaunchedEffect(controller) { controller.messages.collect { snackbars.showSnackbar(it) } }
  val treeState = remember { FileTreeState() }
  var treeSheet by rememberSaveable { mutableStateOf(false) }
  var showTree by rememberSaveable { mutableStateOf(true) }
  var showSide by rememberSaveable { mutableStateOf(true) }
  var finishing by remember { mutableStateOf<ReviewEvent?>(null) }
  var selectedThread by rememberSaveable { mutableStateOf<String?>(null) }
  val composer = controller.composer

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val width = maxWidth
    val wide = width >= TreePaneWidth
    val threePane = maxWidth >= SidePaneWidth
    // The review pane shows itself for the composer.
    val sidePane = threePane && (showSide || composer != null)
    val thread = selectedThread?.let { id -> controller.threads.firstOrNull { it.thread.id == id } }
    // Unfolded, the composer or a thread goes beside the diff, in the tree's place.
    val extraPane = wide && !threePane && (composer != null || thread != null)
    val overview = controller.overview
    val canVerdict = overview?.viewerReviewRequested == true && !overview.viewerDidAuthor
    val pending = controller.pendingCount
    val bottomBar = !wide && (canVerdict || pending > 0)
    Scaffold(
      snackbarHost = { SnackbarHost(snackbars) },
      topBar = {
        TongsTopAppBar(
          title = overview?.title ?: "#${route.number}",
          subtitle = "${route.repo}#${route.number}" + filesSummary(controller),
          onBack = onBack,
          actions = {
            if (wide) {
              ActionIcon(
                ReviewIcons.TreePane,
                if (showTree) "Hide files" else "Show files",
                { showTree = !showTree },
              )
              ReviewActions(
                canVerdict = canVerdict,
                pending = pending,
                // The side pane has the review form.
                offerReview = !sidePane,
                onFinish = { finishing = it },
              )
              if (threePane) {
                ActionIcon(
                  ReviewIcons.SidePane,
                  if (showSide) "Hide review" else "Show review",
                  { showSide = !showSide },
                )
              }
            } else {
              ActionIcon(ReviewIcons.FileTree, "Files", { treeSheet = true })
            }
          },
        )
      },
      bottomBar = {
        if (bottomBar) {
          Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier.fillMaxWidth()
                  .navigationBarsPadding()
                  .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
              ReviewActions(canVerdict, pending, offerReview = true, onFinish = { finishing = it })
            }
          }
        }
      },
      contentWindowInsets = WindowInsets(0),
    ) { padding ->
      // Edge to edge; the bottom bar keeps clear of the navigation bar when it shows.
      val insets = if (bottomBar) Modifier else Modifier.navigationBarsPadding()
      Row(Modifier.padding(padding).fillMaxSize().then(insets).imePadding()) {
        if (wide && showTree && !extraPane) {
          FileTreePane(
            controller,
            treeState,
            onPick = controller::jumpTo,
            modifier = Modifier.width(if (width < 840.dp) 248.dp else 288.dp).fillMaxHeight(),
          )
          VerticalDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
          PendingReviewBanner(controller)
          DiffPane(
            controller,
            onOpenThread = {
              selectedThread = it
              if (threePane) showSide = true
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
          )
        }
        if (sidePane) {
          VerticalDivider()
          ReviewSidePane(
            controller,
            selectedThread,
            onSelectThread = { selectedThread = it },
            modifier = Modifier.width(360.dp).fillMaxHeight(),
          )
        }
        if (extraPane) {
          VerticalDivider()
          val paneWidth = (width * 0.45f).coerceIn(300.dp, 400.dp)
          val paneModifier = Modifier.width(paneWidth).fillMaxHeight()
          if (composer != null) {
            key(composer) {
              LocalCommentComposer.current.Content(
                controller.commentRequest(composer, quote = false),
                paneModifier,
              )
            }
          } else if (thread != null) {
            ThreadPane(thread, controller, onClose = { selectedThread = null }, paneModifier)
          }
        }
      }
    }

    if (!wide && treeSheet) {
      ModalBottomSheet(
        onDismissRequest = { treeSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      ) {
        FileTreePane(
          controller,
          treeState,
          onPick = {
            controller.jumpTo(it)
            treeSheet = false
          },
          modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        )
      }
    }
    // Folded, a thread is a sheet; replying from it closes it for the composer.
    if (!wide && thread != null && composer == null) {
      ModalBottomSheet(onDismissRequest = { selectedThread = null }) {
        ThreadDetail(
          thread,
          controller,
          Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        )
      }
    }
    // Folded, the composer covers the screen, with the lines it's on above the text.
    if (!wide && composer != null) {
      NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = controller::closeComposer,
      )
      key(composer) {
        LocalCommentComposer.current.Content(
          controller.commentRequest(composer, quote = true),
          Modifier.fillMaxSize(),
        )
      }
    }
    finishing?.let { event ->
      FinishReviewDialog(controller, event, onDismiss = { finishing = null })
    }
  }
}

private fun filesSummary(controller: ChangesController): String {
  val files = controller.files
  if (files.isEmpty()) return ""
  val added = files.sumOf { it.additions }
  val removed = files.sumOf { it.deletions }
  return " · ${files.size} files · +$added −$removed"
}

/**
 * Approve and Request changes when the reader's review is requested, and Finish review once there's
 * a pending review.
 */
@Composable
private fun ReviewActions(
  canVerdict: Boolean,
  pending: Int,
  offerReview: Boolean,
  onFinish: (ReviewEvent) -> Unit,
) {
  if (canVerdict) {
    OutlinedButton(
      onClick = { onFinish(ReviewEvent.RequestChanges) },
      contentPadding = ButtonDefaults.SmallContentPadding,
    ) {
      Text("Request changes", maxLines = 1)
    }
    FilledTonalButton(
      onClick = { onFinish(ReviewEvent.Approve) },
      contentPadding = ButtonDefaults.SmallContentPadding,
    ) {
      Icon(ReviewIcons.Approve, null, Modifier.size(ButtonDefaults.IconSize))
      Text("Approve", Modifier.padding(start = 6.dp), maxLines = 1)
    }
  }
  if (pending > 0) {
    BadgedBox(badge = { Badge { Text("$pending") } }) {
      Button(
        onClick = { onFinish(ReviewEvent.Comment) },
        contentPadding = ButtonDefaults.SmallContentPadding,
      ) {
        Text("Finish review", maxLines = 1)
      }
    }
  } else if (!canVerdict && offerReview) {
    TextButton(onClick = { onFinish(ReviewEvent.Comment) }) { Text("Review") }
  }
  Spacer(Modifier.width(4.dp))
}

/** The diff, as it loads, with threads, pending comments, and the composer inline. */
@Composable
private fun DiffPane(
  controller: ChangesController,
  onOpenThread: (String) -> Unit,
  modifier: Modifier,
) {
  val colors = rememberDiffColors()
  val view = controller.codeView
  if (view == null) {
    val status by controller.load.status.collectAsState()
    Box(modifier.background(colors.background).padding(24.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.text)
        if (!status.startsWith("Failed")) LinearProgressIndicator(Modifier.width(160.dp))
      }
    }
    return
  }
  // Fetch what's on screen first. Paths are read here, while the snapshot is surely open.
  LaunchedEffect(view) {
    val loader = controller.load.loader.filterNotNull().first()
    snapshotFlow {
      val rows = view.rows as ReviewSnapshot
      val files = view.visibleFiles()
      loader.prioritize(rows, files)
      files
    }
      .collect {}
  }
  BoxWithConstraints(modifier.background(colors.background)) {
    val density = LocalDensity.current
    val metrics = rememberCardMetrics(density.codeRowHeight())
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val widthPx = constraints.maxWidth - with(density) { 12.dp.roundToPx() }
    LaunchedEffect(metrics, widthPx) {
      controller.rowPx = metrics.rowPx
      controller.cardRows = { item -> metrics.rows(item, measurer, widthPx) }
    }
    val delegate =
      remember(controller, metrics) {
        object : CodeViewDelegate {
          override fun header(file: Int): FileHeaderInfo? =
            controller.files.getOrNull(file)?.let {
              FileHeaderInfo(
                it.additions,
                it.deletions,
                controller.isViewed(file),
                it.loaded && !it.binary,
              )
            }

          override fun onHeaderClick(file: Int) = controller.toggleCollapsed(file)

          override fun onViewedClick(file: Int) = controller.toggleViewed(file)

          override fun onLinesSelected(selection: LineSelection) =
            controller.startComment(selection)

          override fun annotationRows(id: Int) = controller.itemRows[id] ?: 0

          @Composable
          override fun Annotation(id: Int) {
            InlineCard(controller.items[id], controller, onOpenThread)
          }
        }
      }
    CodeView(view, Modifier.fillMaxSize().testTag("diff"), colors, delegate)
  }
}

/**
 * Diff colors from the app's theme: its surfaces for the background and headers, and a syntax
 * palette for light or dark (One Dark and One Light, as the spike used).
 */
@Composable
private fun rememberDiffColors(): CodeViewColors {
  val scheme = MaterialTheme.colorScheme
  val dark = scheme.surface.luminance() < 0.5f
  return remember(scheme, dark) {
    if (dark) {
      CodeViewColors(
        background = scheme.surface,
        text = scheme.onSurface,
        gutterText = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        header = scheme.surfaceContainerHighest,
        headerText = scheme.onSurface,
        hunk = scheme.surfaceContainer,
        hunkText = scheme.onSurfaceVariant,
        annotation = scheme.surface,
        selection = Color(0x30F2CC60),
        selectionGutter = Color(0x90F2CC60),
        viewedBox = scheme.onSurfaceVariant,
        viewedCheck = scheme.primary,
      )
    } else {
      CodeViewColors(
        background = scheme.surface,
        text = Color(0xFF24292F),
        gutterText = Color(0xFF8C959F),
        added = Color(0xFFE6FFEC),
        addedGutter = Color(0xFFCCFFD8),
        removed = Color(0xFFFFEBE9),
        removedGutter = Color(0xFFFFD7D5),
        hunk = scheme.surfaceContainer,
        hunkText = scheme.onSurfaceVariant,
        header = scheme.surfaceContainerHigh,
        headerText = scheme.onSurface,
        scrollThumb = Color(0x55000000),
        // GitHub's highlight for selected lines.
        selection = Color(0x66FFF0A0),
        selectionGutter = Color(0xFFF5D86A),
        annotation = scheme.surface,
        viewedBox = scheme.onSurfaceVariant,
        viewedCheck = scheme.primary,
        additions = Color(0xFF1A7F37),
        deletions = Color(0xFFCF222E),
        comment = Color(0xFFA0A1A7),
        keyword = Color(0xFFA626A4),
        operator = Color(0xFF0184BC),
        punctuation = Color(0xFF383A42),
        string = Color(0xFF50A14F),
        stringSpecial = Color(0xFF0184BC),
        number = Color(0xFF986801),
        constant = Color(0xFF986801),
        function = Color(0xFF4078F2),
        type = Color(0xFFC18401),
        property = Color(0xFFE45649),
        variableBuiltin = Color(0xFFE45649),
        attribute = Color(0xFF986801),
        namespace = Color(0xFFC18401),
        label = Color(0xFFE45649),
        tag = Color(0xFFE45649),
        heading = Color(0xFFE45649),
        link = Color(0xFF4078F2),
        emphasis = Color(0xFF986801),
      )
    }
  }
}

@ContributesIntoSet(AppScope::class)
@Inject
class ReviewEntries(private val viewModels: PullRequestChangesViewModel.Factory) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.PullRequestChanges> { route ->
      val navigator = LocalNavigator.current
      PullRequestChangesScreen(
        viewModel = viewModel(key = route.toString()) { viewModels.create(route) },
        onBack = { navigator.back() },
        onOpenLink = { link ->
          val type =
            if (link.kind == ThreadKind.PullRequest) SubjectType.PullRequest else SubjectType.Issue
          navigator.push(Route.Thread(route.accountId, link.repo, type.raw, link.number))
        },
      )
    }
  }
}
