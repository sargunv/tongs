package dev.sargunv.tongs.codeview

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.tongs.engine.ReviewRows
import dev.sargunv.tongs.engine.Row
import dev.sargunv.tongs.engine.RowKind
import dev.sargunv.tongs.engine.TokenStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
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
  /** Digits each line number column fits; drawing grows it. Not state: it never shrinks a frame. */
  internal var gutterDigits = MIN_GUTTER_DIGITS
  internal var viewportHeight by mutableIntStateOf(0)
  /** Bumped from a background thread when highlights land; drawing reads it to repaint. */
  internal var highlightGeneration by mutableIntStateOf(0)
  internal var window by mutableStateOf(RowWindow(rows))
    private set

  val stats = FrameStats()

  /** Lines picked in the gutter, as for a comment. Follows its rows across [swapRows]. */
  var selection by mutableStateOf<LineSelection?>(null)

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

  /** Scrolls [file]'s header to the top. */
  fun scrollToFile(file: Int) {
    if (file in 0 until rows.fileCount) scrollToRow(rows.fileFirstRow(file))
  }

  /** The file whose header is pinned at the top: the one the reader is in. */
  val currentFile: Int
    get() = window.rows(firstVisibleRow, 1).firstOrNull()?.file ?: -1

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
        // The same line if it's still there, as when blocks open above it; otherwise the same
        // distance into the file, as when its placeholder becomes its diff.
        val found = newRows.findRow(file, anchor)
        target =
          if (found >= 0) found
          else (start + first - old.fileFirstRow(anchor.file)).coerceAtMost(end - 1)
      }
    }
    selection = selection?.relocate(old, newRows)
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

/**
 * Code rows picked in the gutter, all in one file and one hunk: [first] and [last] are row indices,
 * and [firstRow] and [lastRow] the rows there, which locate them again in another snapshot.
 */
data class LineSelection(
  val file: Int,
  val first: Int,
  val last: Int,
  val firstRow: Row,
  val lastRow: Row,
) {
  operator fun contains(row: Int) = row in first..last

  /** The same lines in [to], or null if they're gone, as when their file collapsed. */
  internal fun relocate(from: ReviewRows, to: ReviewRows): LineSelection? {
    val file = to.fileIndex(from.filePath(file)).takeIf { it >= 0 } ?: return null
    val first = to.findRow(file, firstRow).takeIf { it >= 0 } ?: return null
    val last = to.findRow(file, lastRow).takeIf { it >= 0 } ?: return null
    return copy(file = file, first = first, last = last)
  }
}

/** What a file's header shows besides its path. */
class FileHeaderInfo(
  val additions: Int,
  val deletions: Int,
  val viewed: Boolean,
  val loaded: Boolean,
)

/**
 * How a [CodeView] reacts to the reader and what it draws beyond the rows. Every member has a
 * default, so a plain viewer needs none of it.
 */
interface CodeViewDelegate {
  /** The header details for [file], read while drawing. */
  fun header(file: Int): FileHeaderInfo? = null

  /** A tap on [file]'s header, away from its viewed box. */
  fun onHeaderClick(file: Int) {}

  /** A tap on [file]'s viewed box. */
  fun onViewedClick(file: Int) {}

  /** Lines picked in the gutter: a tap picks one, a drag (or long-press and drag) a range. */
  fun onLinesSelected(selection: LineSelection) {}

  /** How many rows [ReviewLayout][dev.sargunv.tongs.engine.ReviewLayout] block [id] reserves. */
  fun annotationRows(id: Int): Int = 0

  /** What to draw over block [id]'s rows; it scrolls with them. */
  @Composable fun Annotation(id: Int) {}
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

  private var headerFile = -1
  private var headerRow: Row? = null

  /** [file]'s header row, cached apart from the window so the pinned header doesn't move it. */
  fun header(file: Int): Row? {
    if (file != headerFile) {
      headerRow = source.rows(source.fileFirstRow(file), 1).firstOrNull()
      headerFile = file
    }
    return headerRow
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
  val selection: Color = Color(0x553D7BFD),
  val selectionGutter: Color = Color(0xAA3D7BFD),
  val annotation: Color = Color(0xFF1B1E24),
  val viewedBox: Color = Color(0xFF8A93A3),
  val viewedCheck: Color = Color(0xFF6CC48A),
  val additions: Color = Color(0xFF6CC48A),
  val deletions: Color = Color(0xFFE27A7A),
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

/** Each line number column starts this wide, and grows to fit the numbers shown. */
private const val MIN_GUTTER_DIGITS = 3
private val ScrollbarWidth = 12.dp

/** Rows the viewed box and counts take at a header's right end, in columns. */
private const val HEADER_BOX_COLUMNS = 4

private val NoDelegate = object : CodeViewDelegate {}

/** Code text size; rows are 1.5 times it, rounded up to whole pixels. */
private val CodeFontSize = 13.sp

/**
 * A [CodeView] row's height in pixels, so UI drawn over annotation rows can size itself to whole
 * rows.
 */
fun Density.codeRowHeight(): Float = ceil(CodeFontSize.toPx() * 1.5f)

@Composable
fun rememberCodeViewState(rows: ReviewRows): CodeViewState = remember(rows) { CodeViewState(rows) }

/**
 * A virtualized, fixed-row-height diff view. Visible rows are drawn straight onto the canvas;
 * nothing is composed per row, so cost scales with the viewport, not the review. Only annotation
 * blocks, which [delegate] supplies, are composed, and only while on screen.
 */
@Composable
fun CodeView(
  state: CodeViewState,
  modifier: Modifier = Modifier,
  colors: CodeViewColors = remember { CodeViewColors() },
  delegate: CodeViewDelegate = NoDelegate,
) {
  val density = LocalDensity.current
  val painter = remember(density) { TextPainter(with(density) { CodeFontSize.toPx() }) }
  state.lineHeight = painter.lineHeight
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  val maxLineWidth = painter.advance * 4096
  val haptics = LocalHapticFeedback.current
  // Reused for the header's shapes, so drawing allocates nothing.
  val scratch = remember { Path() }
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
      // After the scrollables, so it sees pointer events first and can keep a gutter drag from
      // scrolling.
      .pointerInput(state, delegate) {
        gestures(state, delegate, painter, ScrollbarWidth.toPx()) {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
      }
      .onFocusChanged { focused = it.isFocused }
      .focusRequester(focus)
      .focusable()
      .onKeyEvent { event ->
        // Keys typed into a composer in an annotation bubble up here; leave them be.
        if (!focused || event.type != KeyEventType.KeyDown) return@onKeyEvent false
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
          Key.V -> state.currentFile.takeIf { it >= 0 }?.let(delegate::onViewedClick)
          else -> return@onKeyEvent false
        }
        true
      }
      .drawBehind { drawRows(state, painter, colors, delegate, scratch) },
  ) {
    if (delegate !== NoDelegate) Annotations(state, delegate, painter.lineHeight)
    Scrollbar(state, colors, Modifier.align(Alignment.CenterEnd))
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A block of annotation rows on screen: its id and first row. */
private data class AnnotationBlock(val id: Int, val firstRow: Int)

/** Composes the annotation blocks on screen over their rows, below the pinned header. */
@Composable
private fun Annotations(state: CodeViewState, delegate: CodeViewDelegate, lineHeight: Float) {
  val blocks by
    remember(state) {
      derivedStateOf {
        val first = state.firstVisibleRow
        val visible = ceil(state.viewportHeight / lineHeight).toInt() + 1
        val out = ArrayList<AnnotationBlock>(2)
        state.window.rows(first, visible).forEachIndexed { i, row ->
          if (row.kind == RowKind.Annotation && out.none { it.id == row.oldLine }) {
            out += AnnotationBlock(row.oldLine, first + i - row.newLine)
          }
        }
        out
      }
    }
  if (blocks.isEmpty()) return
  val density = LocalDensity.current
  Box(
    Modifier.fillMaxSize()
      .padding(top = with(density) { lineHeight.toDp() }, end = ScrollbarWidth)
      .clipToBounds(),
  ) {
    for (block in blocks) {
      key(block.id) {
        val rows = delegate.annotationRows(block.id)
        Box(
          Modifier.offset {
              IntOffset(0, (block.firstRow * lineHeight - state.offsetY - lineHeight).roundToInt())
            }
            .fillMaxWidth()
            .height(with(density) { (rows * lineHeight).toDp() }),
        ) {
          delegate.Annotation(block.id)
        }
      }
    }
  }
}

/**
 * Taps on headers and in the gutter, and gutter selection: a mouse drags across line numbers at
 * once, while touch long-presses first, so a swipe over the gutter still scrolls.
 */
private suspend fun PointerInputScope.gestures(
  state: CodeViewState,
  delegate: CodeViewDelegate,
  painter: TextPainter,
  scrollbarWidth: Float,
  onLongPress: () -> Unit,
) = awaitEachGesture {
  val textLeft = state.textLeft(painter.advance)
  val down = awaitFirstDown(requireUnconsumed = false)
  if (down.position.x > size.width - scrollbarWidth) return@awaitEachGesture
  val hit = state.hit(down.position.y) ?: return@awaitEachGesture
  val (index, row) = hit
  val mouse = down.type == PointerType.Mouse
  if (row.kind == RowKind.FileHeader) {
    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
      return@awaitEachGesture
    }
    val boxLeft = size.width - scrollbarWidth - painter.advance * HEADER_BOX_COLUMNS
    if (up.position.x >= boxLeft - painter.advance * 2) delegate.onViewedClick(row.file)
    else delegate.onHeaderClick(row.file)
    return@awaitEachGesture
  }
  if (row.side == null || down.position.x >= textLeft) return@awaitEachGesture
  var selecting = mouse
  if (mouse) {
    down.consume()
    state.selection = state.selectRange(index, index)
  } else {
    // A tap picks the line; a long press starts a range; moving first is a scroll.
    val outcome =
      withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
          val event = awaitPointerEvent()
          val change =
            event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
          if (!change.pressed) return@withTimeoutOrNull true
          if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            return@withTimeoutOrNull false
          }
        }
        @Suppress("UNREACHABLE_CODE") false
      }
    when (outcome) {
      true -> {
        state.selectRange(index, index)?.let {
          state.selection = it
          delegate.onLinesSelected(it)
        }
        return@awaitEachGesture
      }
      false -> return@awaitEachGesture
      null -> {
        selecting = true
        onLongPress()
        state.selection = state.selectRange(index, index)
      }
    }
  }
  while (selecting) {
    val event = awaitPointerEvent()
    val change = event.changes.firstOrNull { it.id == down.id } ?: break
    change.consume()
    if (!change.pressed) break
    // Near an edge, scroll so the range can grow past the screen.
    val edge = painter.lineHeight * 2
    when {
      change.position.y < edge -> state.scrollBy(-painter.lineHeight.toDouble())
      change.position.y > size.height - edge -> state.scrollBy(painter.lineHeight.toDouble())
    }
    val to = state.rowIndexAt(change.position.y.coerceIn(painter.lineHeight, size.height - 1f))
    state.selectRange(index, to)?.let { state.selection = it }
  }
  state.selection?.let(delegate::onLinesSelected)
}

/** The row under [y] in the view, with the pinned header standing in for the top row. */
private fun CodeViewState.hit(y: Float): Pair<Int, Row>? {
  if (y < lineHeight) {
    val top = window.rows(firstVisibleRow, 1).firstOrNull() ?: return null
    return window.header(top.file)?.let { rows.fileFirstRow(top.file) to it }
  }
  val index = rowIndexAt(y)
  return window.rows(index, 1).firstOrNull()?.let { index to it }
}

private fun pow10(n: Int): Int {
  var out = 1
  repeat(n) { out *= 10 }
  return out
}

/** Where code text starts: two line number columns, then a column for the sign. */
private fun CodeViewState.textLeft(advance: Float) = advance * (gutterDigits * 2 + 3)

private fun CodeViewState.rowIndexAt(y: Float): Int =
  floor((offsetY + y) / lineHeight).toInt().coerceIn(0, (rows.rowCount - 1).coerceAtLeast(0))

/**
 * The code rows from [anchor] toward [to], stopping at the edge of [anchor]'s hunk: a comment can
 * only cover lines the forge shows together.
 */
internal fun CodeViewState.selectRange(anchor: Int, to: Int): LineSelection? {
  val anchorRow = rows.rows(anchor, 1).firstOrNull()?.takeIf { it.side != null } ?: return null
  val step = if (to >= anchor) 1 else -1
  var end = anchor
  var endRow = anchorRow
  val span = kotlin.math.abs(to - anchor)
  if (span > 0) {
    val from = if (step > 0) anchor + 1 else to
    val between = rows.rows(from, span)
    val ordered = if (step > 0) between else between.asReversed()
    for ((i, row) in ordered.withIndex()) {
      if (row.file != anchorRow.file) break
      if (row.kind == RowKind.Annotation) continue
      if (row.side == null) break
      end = anchor + step * (i + 1)
      endRow = row
    }
  }
  return if (step > 0) LineSelection(anchorRow.file, anchor, end, anchorRow, endRow)
  else LineSelection(anchorRow.file, end, anchor, endRow, anchorRow)
}

private fun DrawScope.drawRows(
  state: CodeViewState,
  painter: TextPainter,
  colors: CodeViewColors,
  delegate: CodeViewDelegate,
  scratch: Path,
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
  // Grow the line number columns to fit what's shown; never shrink, so text doesn't shift back.
  var widest = 0
  for (row in rows) if (row.side != null) widest = maxOf(widest, row.oldLine, row.newLine)
  while (widest >= pow10(state.gutterDigits)) state.gutterDigits++
  val textLeft = state.textLeft(painter.advance)
  val firstColumn = (state.offsetX / painter.advance).toInt()
  val columns = ceil((size.width - textLeft) / painter.advance).toInt() + 1
  val gutterWidth = painter.advance * (state.gutterDigits + 1)
  val selection = state.selection

  drawRect(colors.background)
  rows.forEachIndexed { i, row ->
    val y = top + i * lineHeight
    if (row.kind == RowKind.FileHeader) {
      drawHeader(row, row.text, y, painter, colors, delegate, scratch)
      return@forEachIndexed
    }
    val selected = selection != null && first + i in selection
    drawRow(
      row,
      y,
      painter,
      colors,
      textLeft,
      gutterWidth,
      state.gutterDigits,
      firstColumn,
      columns,
      state.offsetX,
      selected,
    )
  }

  // Sticky header for the file at the top of the viewport.
  rows.firstOrNull()?.let { row ->
    if (row.kind != RowKind.FileHeader || top < 0f) {
      val header = if (row.kind == RowKind.FileHeader) row else state.window.header(row.file)
      if (header != null) drawHeader(header, header.text, 0f, painter, colors, delegate, scratch)
    }
  }

  state.stats.drawNanos += mark.elapsedNow().inWholeNanoseconds
  state.stats.fetchNanos += state.window.lastFetchNanos
  state.stats.rowsDrawn += rows.size
  state.stats.rowsPending += rows.count { it.highlightPending }
}

/**
 * A file's header: a chevron for whether it's collapsed, its path, and, when the [delegate] knows
 * them, its line counts and a viewed box at the right end.
 */
private fun DrawScope.drawHeader(
  row: Row,
  text: String,
  y: Float,
  painter: TextPainter,
  colors: CodeViewColors,
  delegate: CodeViewDelegate,
  path: Path,
) =
  with(painter) {
    val lineHeight = painter.lineHeight
    val advance = painter.advance
    drawRect(colors.header, Offset(0f, y), Size(size.width, lineHeight))
    drawChevron(path, advance * 0.6f, y, lineHeight, advance, !row.collapsed, colors.headerText)
    val info = delegate.header(row.file)
    var right = size.width - ScrollbarWidth.toPx()
    if (info != null) {
      val box = lineHeight * 0.55f
      val boxLeft = right - advance - box
      val boxTop = y + (lineHeight - box) / 2
      if (info.viewed) {
        drawRect(colors.viewedCheck, Offset(boxLeft, boxTop), Size(box, box))
        path.reset()
        path.moveTo(boxLeft + box * 0.22f, boxTop + box * 0.52f)
        path.lineTo(boxLeft + box * 0.42f, boxTop + box * 0.72f)
        path.lineTo(boxLeft + box * 0.78f, boxTop + box * 0.3f)
        drawPath(path, colors.header, style = Stroke(width = box * 0.14f))
      } else {
        drawRect(colors.viewedBox, Offset(boxLeft, boxTop), Size(box, box), style = Stroke(1.5f))
      }
      right = boxLeft - advance
      if (info.loaded && (info.additions > 0 || info.deletions > 0)) {
        val deletions = "-${info.deletions}"
        val additions = "+${info.additions}"
        right -= deletions.length * advance
        drawText(deletions, right, y, colors.deletions)
        right -= (additions.length + 1) * advance
        drawText(additions, right, y, colors.additions)
        right -= advance
      }
    }
    clipRect(left = 0f, right = right) {
      drawText(text, advance * 2.5f, y, colors.headerText)
    }
  }

/** A small triangle pointing down when [open], right otherwise. */
private fun DrawScope.drawChevron(
  path: Path,
  x: Float,
  y: Float,
  lineHeight: Float,
  advance: Float,
  open: Boolean,
  color: Color,
) {
  val w = advance * 0.9f
  val cy = y + lineHeight / 2
  path.reset()
  if (open) {
    path.moveTo(x, cy - w * 0.35f)
    path.lineTo(x + w, cy - w * 0.35f)
    path.lineTo(x + w / 2, cy + w * 0.4f)
  } else {
    path.moveTo(x + w * 0.2f, cy - w / 2)
    path.lineTo(x + w * 0.95f, cy)
    path.lineTo(x + w * 0.2f, cy + w / 2)
  }
  path.close()
  drawPath(path, color, alpha = 0.7f)
}

private fun DrawScope.drawRow(
  row: Row,
  y: Float,
  painter: TextPainter,
  colors: CodeViewColors,
  textLeft: Float,
  gutterWidth: Float,
  gutterDigits: Int,
  firstColumn: Int,
  columns: Int,
  scrollX: Float,
  selected: Boolean,
) =
  with(painter) {
    val lineHeight = painter.lineHeight
    when (row.kind) {
      RowKind.FileHeader -> return
      RowKind.Annotation -> {
        drawRect(colors.annotation, Offset(0f, y), Size(size.width, lineHeight))
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
      RowKind.Loading,
      RowKind.Renamed -> Unit
    }
    // Under the text, so selected lines keep their numbers.
    if (selected) {
      drawRect(colors.selection, Offset(0f, y), Size(size.width, lineHeight))
      drawRect(colors.selectionGutter, Offset(0f, y), Size(textLeft - painter.advance, lineHeight))
    }
    val digits = painter.advance * gutterDigits
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
