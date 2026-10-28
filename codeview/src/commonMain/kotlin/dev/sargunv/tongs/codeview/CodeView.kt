package dev.sargunv.tongs.codeview

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.engine.ReviewRows
import dev.sargunv.tongs.engine.Row
import dev.sargunv.tongs.engine.RowKind
import dev.sargunv.tongs.engine.TokenStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.TimeSource

/**
 * Scroll position and frame statistics for a [CodeView].
 *
 * The vertical offset is a `Double`: at 20px per row, a 700k-row review is 14M px tall, past where
 * `Float` can still address single pixels.
 */
class CodeViewState(rows: ReviewRows) {
  var rows by mutableStateOf(rows)
    private set

  var offsetY by mutableDoubleStateOf(0.0)
    private set

  var offsetX by mutableFloatStateOf(0f)
    private set

  internal var lineHeight by mutableFloatStateOf(1f)
  internal var viewportHeight by mutableIntStateOf(0)
  /** Bumped from a background thread when highlights land; drawing reads it to repaint. */
  internal var highlightGeneration by mutableIntStateOf(0)
  internal var window by mutableStateOf(RowWindow(rows))
    private set

  val stats = FrameStats()

  val maxOffsetY: Double
    get() = (rows.rowCount * lineHeight.toDouble() - viewportHeight).coerceAtLeast(0.0)

  val firstVisibleRow: Int
    get() = floor(offsetY / lineHeight).toInt()

  /** Scrolls by [delta] pixels and returns the distance actually moved. */
  fun scrollBy(delta: Double): Double {
    val old = offsetY
    offsetY = (offsetY + delta).coerceIn(0.0, maxOffsetY)
    return offsetY - old
  }

  fun scrollToRow(row: Int) {
    offsetY = (row * lineHeight.toDouble()).coerceIn(0.0, maxOffsetY)
  }

  internal fun scrollXBy(delta: Float, maxWidth: Float) {
    offsetX = (offsetX + delta).coerceIn(0f, maxWidth)
  }

  /**
   * Swaps in a newer snapshot of the same review and returns the old one. The top row stays put:
   * same file, same row within it. A loaded file's rows never change, so that's the same line; a
   * placeholder row maps to the start of the file's diff. At the very top, the view stays at the
   * top, so files that sort earlier come into view as they're found.
   */
  fun swapRows(newRows: ReviewRows): ReviewRows {
    val old = rows
    val first = firstVisibleRow
    val withinRow = offsetY - first * lineHeight.toDouble()
    var target = first
    val anchor = if (offsetY > 0.0) old.rows(first, 1).firstOrNull() else null
    if (anchor != null) {
      val file = newRows.fileIndex(old.filePath(anchor.file))
      if (file >= 0) {
        val start = newRows.fileFirstRow(file)
        val end =
          if (file + 1 < newRows.fileCount) newRows.fileFirstRow(file + 1) else newRows.rowCount
        target = (start + first - old.fileFirstRow(anchor.file)).coerceAtMost(end - 1)
      }
    }
    rows = newRows
    window = RowWindow(newRows)
    offsetY =
      if (anchor == null) 0.0
      else (target * lineHeight.toDouble() + withinRow).coerceIn(0.0, maxOffsetY)
    return old
  }

  /** The files on screen and in the next [ahead] screens, for fetching their content first. */
  fun visibleFiles(ahead: Int = 2): IntRange {
    if (rows.rowCount == 0) return IntRange.EMPTY
    val perScreen = ceil(viewportHeight / lineHeight).toInt() + 1
    val last = (firstVisibleRow + perScreen * (ahead + 1)).coerceAtMost(rows.rowCount - 1)
    val first = firstVisibleRow.coerceAtMost(last)
    val firstFile = rows.rows(first, 1).firstOrNull()?.file ?: return IntRange.EMPTY
    val lastFile = rows.rows(last, 1).firstOrNull()?.file ?: return IntRange.EMPTY
    return firstFile..lastFile
  }

  /** Jumps to the next (or previous) file's header. */
  fun jumpFile(forward: Boolean) {
    val current = window.rows(firstVisibleRow, 1).firstOrNull()?.file ?: return
    val atHeader = rows.fileFirstRow(current) == firstVisibleRow
    val target = if (forward) current + 1 else if (atHeader) current - 1 else current
    if (target in 0 until rows.fileCount) scrollToRow(rows.fileFirstRow(target))
  }
}

/** Per-frame costs, so benchmarks can see what the renderer spends. */
class FrameStats {
  val drawNanos = ArrayList<Long>()
  val fetchNanos = ArrayList<Long>()
  /** Rows drawn, and how many of those were still plain while waiting on highlights. */
  var rowsDrawn = 0L
  var rowsPending = 0L

  fun clear() {
    drawNanos.clear()
    fetchNanos.clear()
    rowsDrawn = 0
    rowsPending = 0
  }
}

/** Caches decoded rows around the viewport so steady scrolling rarely crosses into the engine. */
internal class RowWindow(private val source: ReviewRows) {
  private var start = 0
  private var cached: List<Row> = emptyList()
  var lastFetchNanos = 0L
    private set

  /** The [CodeViewState.highlightGeneration] these rows reflect. */
  var highlightGeneration = 0

  fun rows(first: Int, count: Int): List<Row> {
    lastFetchNanos = 0
    val end = (first + count).coerceAtMost(source.rowCount)
    if (first < start || end > start + cached.size) {
      val mark = TimeSource.Monotonic.markNow()
      start = (first - count).coerceAtLeast(0)
      cached = source.rows(start, count * 3)
      lastFetchNanos = mark.elapsedNow().inWholeNanoseconds
    }
    val from = (first - start).coerceIn(0, cached.size)
    return cached.subList(from, (end - start).coerceIn(from, cached.size))
  }

  /** Drops the cached rows if any were waiting on highlights, so the next call refetches. */
  fun invalidatePending() {
    if (cached.any { it.highlightPending }) cached = emptyList()
  }
}

class CodeViewColors(
  val background: Color = Color(0xFF16181D),
  val text: Color = Color(0xFFD5D8DE),
  val gutterText: Color = Color(0xFF5F6673),
  val added: Color = Color(0xFF1C3326),
  val addedGutter: Color = Color(0xFF234530),
  val removed: Color = Color(0xFF3A1F24),
  val removedGutter: Color = Color(0xFF4D262D),
  val hunk: Color = Color(0xFF1E2A3D),
  val hunkText: Color = Color(0xFF7D93B5),
  val header: Color = Color(0xFF232731),
  val headerText: Color = Color(0xFFE8EAEE),
  val scrollThumb: Color = Color(0x66A0A8B8),
  val comment: Color = Color(0xFF7F848E),
  val keyword: Color = Color(0xFFC678DD),
  val operator: Color = Color(0xFF56B6C2),
  val punctuation: Color = Color(0xFFA0A8B7),
  val string: Color = Color(0xFF98C379),
  val stringSpecial: Color = Color(0xFF56B6C2),
  val number: Color = Color(0xFFD19A66),
  val constant: Color = Color(0xFFD19A66),
  val function: Color = Color(0xFF61AFEF),
  val type: Color = Color(0xFFE5C07B),
  val property: Color = Color(0xFFE06C75),
  val variableBuiltin: Color = Color(0xFFE06C75),
  val attribute: Color = Color(0xFFD19A66),
  val namespace: Color = Color(0xFFE5C07B),
  val label: Color = Color(0xFFE06C75),
  val tag: Color = Color(0xFFE06C75),
  val heading: Color = Color(0xFFE06C75),
  val link: Color = Color(0xFF61AFEF),
  val emphasis: Color = Color(0xFFD19A66),
) {
  private val styles =
    TokenStyle.entries
      .map {
        when (it) {
          TokenStyle.Plain -> text
          TokenStyle.Comment -> comment
          TokenStyle.Keyword -> keyword
          TokenStyle.Operator -> operator
          TokenStyle.Punctuation -> punctuation
          TokenStyle.String -> string
          TokenStyle.StringSpecial -> stringSpecial
          TokenStyle.Number -> number
          TokenStyle.Constant -> constant
          TokenStyle.Function -> function
          TokenStyle.Type -> type
          TokenStyle.Property -> property
          TokenStyle.VariableBuiltin -> variableBuiltin
          TokenStyle.Attribute -> attribute
          TokenStyle.Namespace -> namespace
          TokenStyle.Label -> label
          TokenStyle.Tag -> tag
          TokenStyle.Heading -> heading
          TokenStyle.Link -> link
          TokenStyle.Emphasis -> emphasis
        }
      }
      .toTypedArray()

  /** The color for a style ID from [Row.spans]; unknown IDs draw as plain text. */
  fun style(id: Int): Color = styles.getOrElse(id) { text }
}

private const val GUTTER_DIGITS = 6
private val ScrollbarWidth = 12.dp

@Composable
fun rememberCodeViewState(rows: ReviewRows): CodeViewState = remember(rows) { CodeViewState(rows) }

/**
 * A virtualized, fixed-row-height diff view. Visible rows are drawn straight onto the canvas;
 * nothing is composed per row, so cost scales with the viewport, not the review.
 */
@Composable
fun CodeView(
  state: CodeViewState,
  modifier: Modifier = Modifier,
  colors: CodeViewColors = remember { CodeViewColors() },
) {
  val density = LocalDensity.current
  val painter = remember(density) { TextPainter(with(density) { 13.sp.toPx() }) }
  state.lineHeight = painter.lineHeight
  val focus = remember { FocusRequester() }
  val textLeft = painter.advance * (GUTTER_DIGITS * 2 + 3)
  val maxLineWidth = painter.advance * 4096
  DisposableEffect(state.rows) {
    val subscription = state.rows.onHighlightsChanged { state.highlightGeneration++ }
    onDispose { subscription.close() }
  }

  Box(
    modifier
      .fillMaxSize()
      .clipToBounds()
      .onSizeChanged { state.viewportHeight = it.height }
      .scrollable(
        orientation = Orientation.Vertical,
        state = rememberScrollableState { -state.scrollBy(-it.toDouble()).toFloat() },
      )
      .scrollable(
        orientation = Orientation.Horizontal,
        state =
          rememberScrollableState {
            state.scrollXBy(-it, maxLineWidth)
            it
          },
      )
      .focusRequester(focus)
      .focusable()
      .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val page = state.viewportHeight - state.lineHeight * 2.0
        when (event.key) {
          Key.PageDown,
          Key.Spacebar -> state.scrollBy(page)
          Key.PageUp -> state.scrollBy(-page)
          Key.MoveHome -> state.scrollToRow(0)
          Key.MoveEnd -> state.scrollToRow(state.rows.rowCount)
          Key.J,
          Key.DirectionDown -> state.scrollBy(state.lineHeight * 3.0)
          Key.K,
          Key.DirectionUp -> state.scrollBy(-state.lineHeight * 3.0)
          Key.N -> state.jumpFile(forward = true)
          Key.P -> state.jumpFile(forward = false)
          else -> return@onKeyEvent false
        }
        true
      }
      .drawBehind { drawRows(state, painter, colors, textLeft) },
  ) {
    Scrollbar(state, colors, Modifier.align(Alignment.CenterEnd))
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun DrawScope.drawRows(
  state: CodeViewState,
  painter: TextPainter,
  colors: CodeViewColors,
  textLeft: Float,
) {
  val mark = TimeSource.Monotonic.markNow()
  if (state.highlightGeneration != state.window.highlightGeneration) {
    state.window.highlightGeneration = state.highlightGeneration
    state.window.invalidatePending()
  }
  val lineHeight = painter.lineHeight
  val first = state.firstVisibleRow
  val top = -(state.offsetY - first * lineHeight.toDouble()).toFloat()
  val visible = ceil(size.height / lineHeight).toInt() + 1
  val rows = state.window.rows(first, visible)
  val firstColumn = (state.offsetX / painter.advance).toInt()
  val columns = ceil((size.width - textLeft) / painter.advance).toInt() + 1
  val gutterWidth = painter.advance * (GUTTER_DIGITS + 1)

  drawRect(colors.background)
  rows.forEachIndexed { i, row ->
    val y = top + i * lineHeight
    drawRow(row, y, painter, colors, textLeft, gutterWidth, firstColumn, columns, state.offsetX)
  }

  // Sticky header for the file at the top of the viewport.
  rows.firstOrNull()?.let { row ->
    if (row.kind != RowKind.FileHeader || top < 0f) {
      drawRect(colors.header, size = Size(size.width, lineHeight))
      with(painter) {
        drawText(state.rows.filePath(row.file), painter.advance, 0f, colors.headerText)
      }
    }
  }

  state.stats.drawNanos += mark.elapsedNow().inWholeNanoseconds
  state.stats.fetchNanos += state.window.lastFetchNanos
  state.stats.rowsDrawn += rows.size
  state.stats.rowsPending += rows.count { it.highlightPending }
}

private fun DrawScope.drawRow(
  row: Row,
  y: Float,
  painter: TextPainter,
  colors: CodeViewColors,
  textLeft: Float,
  gutterWidth: Float,
  firstColumn: Int,
  columns: Int,
  scrollX: Float,
) =
  with(painter) {
    val lineHeight = painter.lineHeight
    when (row.kind) {
      RowKind.FileHeader -> {
        drawRect(colors.header, Offset(0f, y), Size(size.width, lineHeight))
        drawText(row.text, painter.advance, y, colors.headerText)
        return
      }
      RowKind.Hunk -> {
        drawRect(colors.hunk, Offset(0f, y), Size(size.width, lineHeight))
        drawText(row.text, textLeft, y, colors.hunkText)
        return
      }
      RowKind.Added -> {
        drawRect(colors.added, Offset(0f, y), Size(size.width, lineHeight))
        drawRect(colors.addedGutter, Offset(0f, y), Size(textLeft - painter.advance, lineHeight))
      }
      RowKind.Removed -> {
        drawRect(colors.removed, Offset(0f, y), Size(size.width, lineHeight))
        drawRect(colors.removedGutter, Offset(0f, y), Size(textLeft - painter.advance, lineHeight))
      }
      RowKind.Context,
      RowKind.Binary,
      RowKind.Loading -> Unit
    }
    val digits = painter.advance * GUTTER_DIGITS
    if (row.oldLine > 0) drawLineNumber(painter, row.oldLine, digits, y, colors.gutterText)
    if (row.newLine > 0) {
      drawLineNumber(painter, row.newLine, gutterWidth + digits, y, colors.gutterText)
    }
    val sign =
      when (row.kind) {
        RowKind.Added -> "+"
        RowKind.Removed -> "-"
        else -> null
      }
    if (sign != null) drawText(sign, textLeft - painter.advance * 1.5f, y, colors.gutterText)

    // Shape only the visible columns, so long lines cost no more than short ones.
    val text = row.text
    if (firstColumn < text.length) {
      val end = (firstColumn + columns).coerceAtMost(text.length)
      val left = textLeft - scrollX
      clipRect(left = textLeft, right = size.width) {
        // One draw per style run, each at its own column.
        var runStart = firstColumn
        var style = 0
        for (span in row.spans) {
          val column = Row.spanColumn(span)
          if (column > runStart) {
            drawRun(painter, text, runStart, column.coerceAtMost(end), left, y, colors.style(style))
            runStart = column
          }
          if (runStart >= end) break
          style = Row.spanStyle(span)
        }
        drawRun(painter, text, runStart, end, left, y, colors.style(style))
      }
    }
  }

/** Draws `text[start until end]` at its column, skipping runs of spaces. */
private fun DrawScope.drawRun(
  painter: TextPainter,
  text: String,
  start: Int,
  end: Int,
  left: Float,
  y: Float,
  color: Color,
) {
  var from = start
  while (from < end && text[from] == ' ') from++
  if (from >= end) return
  with(painter) { drawText(text.substring(from, end), left + from * painter.advance, y, color) }
}

/** Draws [number] right-aligned to [right]. */
private fun DrawScope.drawLineNumber(
  painter: TextPainter,
  number: Int,
  right: Float,
  y: Float,
  color: Color,
) =
  with(painter) {
    val text = number.toString()
    drawText(text, right - text.length * painter.advance, y, color)
  }

@Composable
private fun Scrollbar(state: CodeViewState, colors: CodeViewColors, modifier: Modifier) {
  val jumpTo = { y: Float, height: Int ->
    state.scrollToRow(
      ((y / height) * state.rows.rowCount).toInt() -
        (state.viewportHeight / state.lineHeight / 2).toInt(),
    )
  }
  Box(
    modifier
      .width(ScrollbarWidth)
      .fillMaxHeight()
      .pointerInput(state) { detectTapGestures { jumpTo(it.y, size.height) } }
      .pointerInput(state) {
        detectVerticalDragGestures { change, _ -> jumpTo(change.position.y, size.height) }
      }
      .drawBehind {
        val total = state.rows.rowCount * state.lineHeight.toDouble()
        if (total <= size.height) return@drawBehind
        val thumbHeight = (size.height * size.height / total).toFloat().coerceAtLeast(24f)
        val thumbTop = ((state.offsetY / state.maxOffsetY) * (size.height - thumbHeight)).toFloat()
        drawRect(colors.scrollThumb, Offset(2f, thumbTop), Size(size.width - 4f, thumbHeight))
      },
  )
}
