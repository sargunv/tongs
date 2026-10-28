package dev.sargunv.tongs.feature.thread

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.isBesideList
import dev.sargunv.tongs.core.navigation.route
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

@ContributesIntoSet(AppScope::class)
@Inject
class ThreadEntries(private val viewModels: ThreadViewModel.Factory) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Thread> { route ->
      val navigator = LocalNavigator.current
      ThreadScreen(
        viewModel = viewModel(key = route.toString()) { viewModels.create(route) },
        onBack = if (isBesideList()) null else ({ navigator.back() }),
        onOpenChanges =
          if (route.isPullRequest) {
            {
              navigator.open(Route.PullRequestChanges(route.accountId, route.repo, route.number!!))
            }
          } else null,
      )
    }
  }
}
