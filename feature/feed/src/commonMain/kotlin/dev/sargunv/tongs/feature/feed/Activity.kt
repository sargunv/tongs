package dev.sargunv.tongs.feature.feed

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/** The activity feed: a placeholder until it's built (see "Activity feed" in the design). */
@Composable
fun ActivityScreen() {
  Scaffold(topBar = { TongsTopAppBar(title = "Activity") }) { padding ->
    EmptyState(
      TongsIcons.Activity,
      "Activity is on its way",
      body =
        "What's happening across the repos and people you follow will show here, grouped by " +
          "repo or person.",
      modifier = Modifier.padding(padding),
    )
  }
}

@ContributesIntoSet(AppScope::class)
@Inject
class FeedEntries : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Activity> { ActivityScreen() }
  }
}
