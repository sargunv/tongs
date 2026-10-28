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
import dev.sargunv.tongs.codeview.CodeView
import dev.sargunv.tongs.codeview.CodeViewColors
import dev.sargunv.tongs.codeview.CodeViewDelegate
import dev.sargunv.tongs.codeview.FileHeaderInfo
import dev.sargunv.tongs.codeview.LineSelection
import dev.sargunv.tongs.codeview.codeRowHeight
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.ForgeClients
import dev.sargunv.tongs.core.data.PullRequestChangesRepository
import dev.sargunv.tongs.core.data.PullRequestKey
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.sargunv.tongs.engine.ReviewSnapshot
import dev.sargunv.tongs.forge.api.ReviewEvent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Loads a PR's changes for the app, through the account's
 * [dev.sargunv.tongs.forge.api.ReviewSource], into the same per-repo object store the spike entry
 * points use, and keeps the screen's state in a [ChangesController]. The load starts when the
 * screen opens and stops when it's popped.
 */
@AssistedInject
class PullRequestChangesViewModel(
  @Assisted val route: Route.PullRequestChanges,
  accounts: AccountRepository,
  clients: ForgeClients,
  environment: AppEnvironment,
  highlighter: Highlighter,
  repository: PullRequestChangesRepository,
) : ViewModel() {
  val load =
    PullRequestLoad("${route.repo}#${route.number}", log = { println("review: $it") }) { onLoader ->
      val account = accounts.account(route.accountId) ?: error("This account is signed out")
      val reviews =
        clients.client(route.accountId)?.reviews ?: error("This forge can't load reviews")
      val store =
        "${environment.cacheDir}/${account.instance.host}/${route.repo.owner}/${route.repo.name}/objects"
      reviews.loadPullRequest(
        route.repo,
        route.number,
        openSession = { NativeFetchSession.open(store, it, highlighter) },
        onLoader = onLoader,
      )
    }

  val controller =
    ChangesController(
      viewModelScope,
      load,
      repository,
      PullRequestKey(route.accountId, route.repo, route.number),
    )

  init {
    load.start(viewModelScope)
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
 */
@Composable
fun PullRequestChangesScreen(viewModel: PullRequestChangesViewModel, onBack: () -> Unit) {
  val controller = viewModel.controller
  val route = viewModel.route
  val snackbars = remember { SnackbarHostState() }
  LaunchedEffect(controller) { controller.messages.collect { snackbars.showSnackbar(it) } }
  val treeState = remember { FileTreeState() }
  var treeSheet by rememberSaveable { mutableStateOf(false) }
  var showTree by rememberSaveable { mutableStateOf(true) }
  var showSide by rememberSaveable { mutableStateOf(true) }
  var finishing by remember { mutableStateOf<ReviewEvent?>(null) }
  var selectedThread by rememberSaveable { mutableStateOf<String?>(null) }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val width = maxWidth
    val wide = width >= TreePaneWidth
    val threePane = maxWidth >= SidePaneWidth
    val overview = controller.overview
    val canVerdict = overview?.viewerReviewRequested == true && !overview.viewerDidAuthor
    val pending = controller.drafts.size
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
                offerReview = !threePane || !showSide,
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
        if (!wide && (canVerdict || pending > 0)) {
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
      Row(Modifier.padding(padding).fillMaxSize().imePadding()) {
        if (wide && showTree) {
          FileTreePane(
            controller,
            treeState,
            onPick = controller::jumpTo,
            modifier = Modifier.width(if (width < 840.dp) 248.dp else 288.dp).fillMaxHeight(),
          )
          VerticalDivider()
        }
        DiffPane(
          controller,
          onOpenThread = {
            selectedThread = it
            if (threePane) showSide = true
          },
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (threePane && showSide) {
          VerticalDivider()
          ReviewSidePane(
            controller,
            selectedThread,
            onSelectThread = { selectedThread = it },
            modifier = Modifier.width(340.dp).fillMaxHeight(),
          )
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
    if (!threePane || !showSide) {
      selectedThread
        ?.let { id -> controller.threads.firstOrNull { it.thread.id == id } }
        ?.let { thread ->
          ModalBottomSheet(onDismissRequest = { selectedThread = null }) {
            ThreadDetail(
              thread,
              Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            )
          }
        }
    }
    // A composer that doesn't sit in the diff shows here when there's no side pane for it.
    val composer = controller.composer
    if (composer != null && controller.composerRows == 0 && !(threePane && showSide)) {
      ModalBottomSheet(onDismissRequest = controller::cancelComment) {
        LocalCommentComposer.current.Content(
          controller.commentRequest(composer),
          Modifier.fillMaxWidth().padding(16.dp),
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
    val composer = LocalCommentComposer.current
    LaunchedEffect(metrics, widthPx, composer) {
      controller.composerRows = composer.inlineRows
      controller.cardRows = { item -> metrics.rows(item, measurer, widthPx, density.density) }
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
            InlineCard(controller.items[id], controller, metrics, onOpenThread)
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
      )
    }
  }
}

@ContributesTo(AppScope::class)
interface ReviewBindings {
  /** One per process: its cache is keyed by blob, so it serves every PR. */
  @Provides @SingleIn(AppScope::class) fun highlighter(): Highlighter = Highlighter()
}
