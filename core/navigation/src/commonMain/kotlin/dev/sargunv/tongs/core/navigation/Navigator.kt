package dev.sargunv.tongs.core.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldScope
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * Moves around the back stack. Screens reach it through [LocalNavigator] rather than editing the
 * stack themselves, so the rules for list and detail panes live in one place.
 */
class Navigator(private val backStack: MutableList<NavKey>) {
  val current: Route
    get() = backStack.last() as Route

  /** The top-level destination at the bottom of the stack. */
  val topLevel: Route.TopLevel?
    get() = backStack.firstOrNull() as? Route.TopLevel

  /** Switches to a top-level destination, dropping whatever was open in the previous one. */
  fun selectTopLevel(destination: Route.TopLevel) {
    if (backStack.size == 1 && backStack[0] == destination) return
    backStack.clear()
    backStack.add(destination)
  }

  /**
   * Opens [route]. A detail replaces the detail already open, so opening another thread from the
   * inbox switches threads rather than stacking them.
   */
  fun open(route: Route) {
    if (route == current) return
    if (route.pane == Pane.Detail && current.pane == Pane.Detail) {
      backStack[backStack.lastIndex] = route
    } else {
      backStack.add(route)
    }
  }

  /** Goes back one screen. Returns false at the bottom of the stack. */
  fun back(): Boolean {
    if (backStack.size <= 1) return false
    backStack.removeAt(backStack.lastIndex)
    return true
  }

  /** Replaces the whole stack, as after signing in or out. */
  fun reset(route: Route) {
    backStack.clear()
    backStack.add(route)
  }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }

/**
 * A feature's screens. Each feature contributes one to the app graph with
 * `@ContributesIntoSet(AppScope::class)`, and the app installs them all into its `NavDisplay`.
 */
interface RouteEntries {
  fun EntryProviderScope<NavKey>.install()
}

/**
 * Declares [K]'s screen, placed by its [Route.pane]. [detailPlaceholder] fills the detail pane
 * beside a [Pane.List] screen when nothing is open in it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
inline fun <reified K : Route> EntryProviderScope<NavKey>.route(
  noinline detailPlaceholder: @Composable ThreePaneScaffoldScope.() -> Unit = {},
  noinline content: @Composable (K) -> Unit,
) {
  entry<K>(
    metadata = { key ->
      when (key.pane) {
        Pane.List ->
          ListDetailSceneStrategy.listPane(
            detailPlaceholder = { DetailPaneFrame { detailPlaceholder() } },
          )
        Pane.Detail -> ListDetailSceneStrategy.detailPane()
        Pane.Full -> emptyMap()
      }
    },
    content = { key ->
      if (key.pane == Pane.Detail) DetailPaneFrame { content(key) } else content(key)
    },
  )
}

/**
 * Sets a detail pane apart from the list beside it: a rounded card a tone off the background.
 * Beside nothing, it's just [content].
 */
@Composable
fun DetailPaneFrame(content: @Composable () -> Unit) {
  if (!isBesideList()) return content()
  val colors = MaterialTheme.colorScheme
  val card = colors.surfaceContainerLow
  Surface(
    color = card,
    shape = MaterialTheme.shapes.extraLarge,
    modifier =
      Modifier.fillMaxSize()
        // The card keeps clear of the system bars; the screen inside then needn't.
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom,
          ),
        )
        .padding(top = 8.dp, end = 8.dp, bottom = 8.dp),
  ) {
    // Screens draw on the background and surface colors; make those the card's.
    MaterialTheme(colorScheme = colors.copy(background = card, surface = card), content = content)
  }
}

/**
 * True when this screen shows beside its list, as a detail pane on a wide window. Detail screens
 * use it to drop their back arrow.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun isBesideList(): Boolean = LocalListDetailSceneScope.current != null
