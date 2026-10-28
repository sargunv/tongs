package dev.sargunv.tongs.feature.inbox

import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.RepoRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

@ContributesIntoSet(AppScope::class)
@Inject
class InboxEntries(private val viewModel: Provider<InboxViewModel>) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Inbox>(
      detailPlaceholder = {
        EmptyState(TongsIcons.Inbox, "Nothing open", body = "Pick a notification to read it here.")
      },
    ) {
      val navigator = LocalNavigator.current
      InboxScreen(
        viewModel = viewModel { viewModel() },
        selected = { row ->
          val open = navigator.current as? Route.Thread
          open?.accountId == row.accountId && open.notificationId == row.id
        },
        onOpen = { row ->
          navigator.open(
            Route.Thread(
              accountId = row.accountId,
              repo = RepoRef(row.repoOwner, row.repoName),
              subjectType = row.subjectType,
              number = row.subjectNumber,
              notificationId = row.id,
            ),
          )
        },
      )
    }
  }
}
