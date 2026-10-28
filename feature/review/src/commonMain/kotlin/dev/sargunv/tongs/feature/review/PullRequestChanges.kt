package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.data.ForgeClients
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.engine.Highlighter
import dev.sargunv.tongs.engine.NativeFetchSession
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Loads a PR's changes for the app, through the account's
 * [dev.sargunv.tongs.forge.api.ReviewSource], into the same per-repo object store the spike entry
 * points use. The load starts when the screen opens and stops when it's popped.
 */
@AssistedInject
class PullRequestChangesViewModel(
  @Assisted val route: Route.PullRequestChanges,
  accounts: AccountRepository,
  clients: ForgeClients,
  environment: AppEnvironment,
  highlighter: Highlighter,
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

  init {
    load.start(viewModelScope)
  }

  @AssistedFactory
  fun interface Factory {
    fun create(route: Route.PullRequestChanges): PullRequestChangesViewModel
  }
}

/**
 * A PR's changes: one virtual surface over every file, shown as it loads. The file tree and the
 * conversation pane come later, as the design's three panes.
 */
@Composable
fun PullRequestChangesScreen(viewModel: PullRequestChangesViewModel, onBack: () -> Unit) {
  val route = viewModel.route
  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = "Changes in #${route.number}",
        subtitle = "${route.repo}",
        onBack = onBack,
      )
    },
  ) { padding ->
    PullRequestReview(viewModel.load, Modifier.padding(padding).fillMaxSize())
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
