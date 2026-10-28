package dev.sargunv.tongs.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.LocalTongsDensity
import dev.sargunv.tongs.core.ui.ReviewIcons
import dev.sargunv.tongs.engine.FileClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/** Remembered across the sheet opening and closing, and the pane hiding. */
class FileTreeState {
  var expandedSections by mutableStateOf(emptySet<FileClass>())
  var collapsedDirs by mutableStateOf(emptySet<String>())
  var filter by mutableStateOf("")
}

/**
 * The PR's files as a tree: a filter, then directories, files with their line counts and viewed
 * boxes, and the generated, lockfile, vendored, and renamed groups, folded. The file the diff is on
 * is highlighted and kept in view. Picking a file jumps the diff to it.
 */
@Composable
fun FileTreePane(
  controller: ChangesController,
  state: FileTreeState,
  onPick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val tree = controller.tree
  val viewedSet = controller.viewed
  val files = controller.files
  val filterText = rememberTextFieldState(state.filter)
  LaunchedEffect(filterText) {
    snapshotFlow { filterText.text.toString() }.debounce(80).collect { state.filter = it }
  }
  // Flattening is cheap, but off the main thread it never costs a frame, even at thousands.
  val rows by
    produceState(
      emptyList<TreeRow>(),
      tree,
      viewedSet,
      state.expandedSections,
      state.collapsedDirs,
      state.filter,
    ) {
      tree ?: return@produceState
      val viewed = BooleanArray(files.size) { controller.isViewed(it) }
      value =
        withContext(Dispatchers.Default) {
          tree.rows(state.expandedSections, state.collapsedDirs, { viewed[it] }, state.filter)
        }
    }
  val current = controller.codeView?.currentFile ?: -1
  val list = rememberLazyListState()
  val dragged by list.interactionSource.collectIsDraggedAsState()
  // Follow the diff, unless the reader is scrolling the tree.
  LaunchedEffect(current, rows) {
    if (dragged || list.isScrollInProgress) return@LaunchedEffect
    val at = rows.indexOfFirst { it is TreeRow.File && it.index == current }
    if (at < 0) return@LaunchedEffect
    val visible = list.layoutInfo.visibleItemsInfo
    val shown = visible.size > 2 && at > visible.first().index && at < visible.last().index
    if (!shown) list.animateScrollToItem((at - 3).coerceAtLeast(0))
  }

  Column(modifier) {
    OutlinedTextField(
      state = filterText,
      placeholder = { Text("Filter files") },
      leadingIcon = { Icon(ReviewIcons.Search, null, Modifier.size(20.dp)) },
      lineLimits = TextFieldLineLimits.SingleLine,
      textStyle = MaterialTheme.typography.bodyMedium,
      shape = RoundedCornerShape(50),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
    val viewedCount = files.indices.count(controller::isViewed)
    Column(Modifier.padding(horizontal = 16.dp)) {
      Text(
        if (files.isEmpty()) "Loading files…"
        else "$viewedCount of ${files.size} files viewed" + if (controller.complete) "" else "…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      LinearProgressIndicator(
        progress = { if (files.isEmpty()) 0f else viewedCount.toFloat() / files.size },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        drawStopIndicator = {},
      )
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("file-tree")) {
      items(rows, key = { it.key }, contentType = { it::class }) { row ->
        when (row) {
          is TreeRow.Section ->
            SectionRow(row) {
              state.expandedSections =
                if (row.expanded) state.expandedSections - row.fileClass
                else state.expandedSections + row.fileClass
            }
          is TreeRow.Directory ->
            DirectoryRow(row) {
              state.collapsedDirs =
                if (row.expanded) state.collapsedDirs + row.key else state.collapsedDirs - row.key
            }
          is TreeRow.File ->
            FileRow(
              row,
              current = row.index == current,
              onClick = { onPick(row.index) },
              onToggleViewed = { controller.toggleViewed(row.index) },
            )
        }
      }
    }
  }
}

private val Indent = 14.dp

@Composable
private fun TreeRowFrame(
  depth: Int,
  selected: Boolean = false,
  onClick: () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  val height = if (LocalTongsDensity.current.minTouchTarget < 40.dp) 30.dp else 40.dp
  val colors = MaterialTheme.colorScheme
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .height(height)
        .padding(horizontal = 6.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(if (selected) colors.secondaryContainer else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(start = 8.dp + Indent * depth, end = 4.dp),
  ) {
    content()
  }
}

@Composable
private fun Chevron(expanded: Boolean) {
  Icon(
    if (expanded) ReviewIcons.ExpandMore else ReviewIcons.ChevronRight,
    contentDescription = if (expanded) "Collapse" else "Expand",
    modifier = Modifier.size(18.dp),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SectionRow(row: TreeRow.Section, onClick: () -> Unit) {
  TreeRowFrame(0, onClick = onClick) {
    Chevron(row.expanded)
    Text(
      when (row.fileClass) {
        FileClass.Generated -> "Generated"
        FileClass.Lockfile -> "Lockfiles"
        FileClass.Vendored -> "Vendored"
        FileClass.Renamed -> "Renamed without changes"
        FileClass.Normal -> "Files"
      },
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = 4.dp).weight(1f),
      maxLines = 1,
    )
    Count(row.viewed, row.files)
  }
}

@Composable
private fun DirectoryRow(row: TreeRow.Directory, onClick: () -> Unit) {
  TreeRowFrame(row.depth, onClick = onClick) {
    Chevron(row.expanded)
    Icon(
      ReviewIcons.Folder,
      null,
      Modifier.padding(start = 2.dp).size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      row.label,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
      modifier = Modifier.padding(start = 6.dp).weight(1f),
    )
    Count(row.viewed, row.files)
  }
}

/** `3/10`, or a check once all are viewed. */
@Composable
private fun Count(viewed: Int, total: Int) {
  val colors = MaterialTheme.colorScheme
  Spacer(Modifier.width(8.dp))
  if (viewed == total && total > 0) {
    Icon(ReviewIcons.Check, "All viewed", Modifier.size(16.dp), tint = colors.primary)
  } else {
    Text(
      "$viewed/$total",
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant,
    )
  }
  Spacer(Modifier.width(6.dp))
}

@Composable
private fun FileRow(
  row: TreeRow.File,
  current: Boolean,
  onClick: () -> Unit,
  onToggleViewed: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  TreeRowFrame(row.depth, selected = current, onClick = onClick) {
    // Lines up with a sibling directory's chevron.
    Spacer(Modifier.width(18.dp))
    Text(
      row.name,
      style = MaterialTheme.typography.bodyMedium,
      color = if (row.viewed) colors.onSurfaceVariant else colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
      modifier = Modifier.weight(1f),
    )
    val file = row.file
    if (file.loaded && !file.binary && !row.renamed) {
      Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = AddedColor)
      Text(
        " −${file.deletions}",
        style = MaterialTheme.typography.labelSmall,
        color = RemovedColor,
        modifier = Modifier.padding(end = 2.dp),
      )
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
      Checkbox(
        checked = row.viewed,
        onCheckedChange = { onToggleViewed() },
        colors = CheckboxDefaults.colors(),
      )
    }
  }
}

internal val AddedColor = Color(0xFF3FA45B)
internal val RemovedColor = Color(0xFFD9534F)
