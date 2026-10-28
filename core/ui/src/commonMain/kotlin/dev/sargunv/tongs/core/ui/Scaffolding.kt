package dev.sargunv.tongs.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * The top app bar every screen uses. [onBack] shows a back arrow; screens in a detail pane pass
 * null when the list is beside them.
 */
@Composable
fun TongsTopAppBar(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  TopAppBar(
    title = {
      Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
          Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    },
    navigationIcon = { if (onBack != null) ActionIcon(TongsIcons.Back, "Back", onBack) },
    actions = actions,
    expandedHeight = LocalTongsDensity.current.appBarHeight,
    colors = TopAppBarDefaults.topAppBarColors(),
    modifier = modifier,
  )
}

/** A top-level destination in the navigation bar or rail. */
data class NavigationItem(
  val label: String,
  val icon: ImageVector,
  val selectedIcon: ImageVector,
  val selected: Boolean,
  val badge: Int? = null,
  val onClick: () -> Unit,
)

/**
 * The app's frame: a bottom bar on narrow windows (a folded phone), and a rail beside the content
 * on wider ones (unfolded, tablets, desktop). [showNavigation] false hides the bottom bar, for
 * full-screen detail screens on a phone; the rail stays, since it costs little width.
 */
@Composable
fun TongsNavigationSuite(
  items: List<NavigationItem>,
  showNavigation: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val compact = isCompactWidth()
  val type =
    when {
      !compact -> NavigationSuiteType.WideNavigationRailCollapsed
      showNavigation -> NavigationSuiteType.ShortNavigationBarCompact
      else -> NavigationSuiteType.None
    }
  NavigationSuiteScaffold(
    navigationSuiteType = type,
    modifier = modifier,
    navigationItems = {
      for (item in items) {
        NavigationSuiteItem(
          selected = item.selected,
          onClick = item.onClick,
          icon = { Icon(if (item.selected) item.selectedIcon else item.icon, null) },
          label = { Text(item.label) },
          navigationSuiteType = type,
          badge = item.badge?.takeIf { it > 0 }?.let { count -> { Text(count.toString()) } },
        )
      }
    },
    content = content,
  )
}

/** True when the window is phone-width, as when a foldable is folded. */
@Composable
fun isCompactWidth(): Boolean =
  !currentWindowAdaptiveInfoV2()
    .windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/** A centered message for an empty or not-yet-built screen. */
@Composable
fun EmptyState(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  body: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (body != null) {
      Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 420.dp),
      )
    }
    action?.invoke()
  }
}
