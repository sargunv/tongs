package dev.sargunv.tongs.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import coil3.compose.setSingletonImageLoaderFactory
import dev.sargunv.tongs.core.data.InboxSync
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Navigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteSerializers
import dev.sargunv.tongs.core.ui.NavigationItem
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsNavigationSuite
import dev.sargunv.tongs.core.ui.TongsTheme
import dev.sargunv.tongs.core.ui.isCompactWidth

/**
 * The whole app: Android's activity and desktop's window each show one of these.
 *
 * @param initialBackStack where to start once signed in, as from a deep link; the inbox otherwise.
 */
@Composable
fun TongsApp(graph: AppGraph, initialBackStack: List<Route>? = null) {
  setSingletonImageLoaderFactory {
    tongsImageLoader(it, graph.httpClient, graph.environment.cacheDir)
  }
  TongsTheme {
    Surface(Modifier.fillMaxSize()) {
      val accounts by graph.accounts.accounts.collectAsState(initial = null)
      val signedIn = accounts?.isNotEmpty() ?: return@Surface
      val start = if (!signedIn) listOf(Route.SignIn) else (initialBackStack ?: listOf(Route.Inbox))
      val backStack =
        rememberNavBackStack(
          SavedStateConfiguration { serializersModule = RouteSerializers },
          *start.toTypedArray(),
        )
      val navigator = remember(backStack) { Navigator(backStack) }
      // Signing out of the last account goes back to sign-in.
      LaunchedEffect(signedIn) { if (!signedIn) navigator.reset(Route.SignIn) }
      if (signedIn) ForegroundSync(graph.inboxSync)
      // Screens' view models live in their back stack entries, under the window's owner.
      val viewModels =
        LocalViewModelStoreOwner.current
          ?: remember {
            object : ViewModelStoreOwner {
              override val viewModelStore = ViewModelStore()
            }
          }
      CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalViewModelStoreOwner provides viewModels,
      ) {
        AppFrame(graph, navigator, showNavigation = signedIn, backStack = backStack)
      }
    }
  }
}

@Composable
private fun AppFrame(
  graph: AppGraph,
  navigator: Navigator,
  showNavigation: Boolean,
  backStack: MutableList<NavKey>,
) {
  val top = navigator.topLevel
  val items =
    listOf(
      NavigationItem("Inbox", TongsIcons.Inbox, TongsIcons.InboxFilled, top == Route.Inbox) {
        navigator.selectTopLevel(Route.Inbox)
      },
      NavigationItem(
        "Activity",
        TongsIcons.Activity,
        TongsIcons.ActivityFilled,
        top == Route.Activity,
      ) {
        navigator.selectTopLevel(Route.Activity)
      },
      NavigationItem(
        "Settings",
        TongsIcons.Settings,
        TongsIcons.SettingsFilled,
        top == Route.Settings,
      ) {
        navigator.selectTopLevel(Route.Settings)
      },
    )
  // On a phone, screens opened from a top-level one cover the bottom bar, as in a mail client.
  val atTopLevel = navigator.current is Route.TopLevel
  val compact = isCompactWidth()
  val entries = remember(graph) { graph.routeEntries }
  TongsNavigationSuite(
    items = items,
    showNavigation = showNavigation && top != null && (atTopLevel || !compact),
  ) {
    BoxWithConstraints {
      // List and detail side by side from medium width up, so an unfolded foldable (about 700 dp)
      // shows both. The list starts at half of a medium window and 40% of a wider one, and the
      // handle between them resizes it.
      val directive =
        calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2())
          .copy(horizontalPartitionSpacerSize = 0.dp)
      val anchors = remember {
        listOf(0.3f, 0.4f, 0.5f, 0.6f).map { PaneExpansionAnchor.Proportion(it) }
      }
      val expansion =
        rememberPaneExpansionState(
          anchors = anchors,
          initialAnchoredIndex = if (maxWidth < 760.dp) 2 else 1,
        )
      val listDetail =
        rememberListDetailSceneStrategy<NavKey>(
          directive = directive,
          paneExpansionState = expansion,
          paneExpansionDragHandle = { state ->
            val interactions = remember { MutableInteractionSource() }
            VerticalDragHandle(
              modifier =
                Modifier.paneExpansionDraggable(
                  state,
                  LocalMinimumInteractiveComponentSize.current,
                  interactions,
                ),
              interactionSource = interactions,
            )
          },
        )
      NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators =
          listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
          ),
        sceneStrategies = listOf(listDetail),
        entryProvider = entryProvider { for (entry in entries) with(entry) { install() } },
      )
    }
  }
}

/** Polls the inbox while the app is in the foreground, and again on each return to it. */
@Composable
private fun ForegroundSync(sync: InboxSync) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle, sync) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { sync.pollWhileActive() }
  }
}
