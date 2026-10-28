package dev.sargunv.tongs.codeview

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.sargunv.tongs.engine.ReviewRows
import dev.sargunv.tongs.engine.Row
import dev.sargunv.tongs.engine.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One file: a header, a hunk, then [code] rows alternating context, removed, and added. */
private class CodeRows(private val code: Int) : ReviewRows {
  override val rowCount = code + 2
  override val fileCount = 1

  override fun filePath(file: Int) = "src/Main.kt"

  override fun fileFirstRow(file: Int) = 0

  override fun fileIndex(path: String) = if (path == "src/Main.kt") 0 else -1

  override fun rows(first: Int, count: Int) =
    (first until (first + count).coerceAtMost(rowCount)).map(::row)

  override fun findRow(file: Int, row: Row) =
    (0 until rowCount).firstOrNull {
      val r = row(it)
      r.kind == row.kind && r.oldLine == row.oldLine && r.newLine == row.newLine
    } ?: -1

  private fun row(i: Int): Row =
    when (i) {
      0 -> Row(RowKind.FileHeader, 0, 0, 0, "src/Main.kt")
      1 -> Row(RowKind.Hunk, 0, 1, 1, "")
      else ->
        when ((i - 2) % 3) {
          0 -> Row(RowKind.Context, 0, i, i, "val x = $i")
          1 -> Row(RowKind.Removed, 0, i, 0, "old $i")
          else -> Row(RowKind.Added, 0, 0, i, "new $i")
        }
    }
}

private class Recorder : CodeViewDelegate {
  val selections = mutableListOf<LineSelection>()
  val events = mutableListOf<String>()

  override fun onLinesSelected(selection: LineSelection) {
    selections += selection
  }

  override fun onHeaderClick(file: Int) {
    events += "header $file"
  }

  override fun onViewedClick(file: Int) {
    events += "viewed $file"
  }
}

@OptIn(ExperimentalTestApi::class)
class CodeViewGestureTest {
  private val state = CodeViewState(CodeRows(200))
  private val recorder = Recorder()
  private val row = Density(1f).codeRowHeight()

  /** The middle of row [i] of the screen, over the old line numbers. */
  private fun gutter(i: Int) = Offset(row, row * i + row / 2)

  private fun test(block: ComposeUiTest.() -> Unit) =
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { CodeView(state, Modifier.testTag("code"), delegate = recorder) }
      waitForIdle()
      block()
    }

  @Test
  fun aMouseDragAcrossLineNumbersSelectsARange() = test {
    // Rows 2 to 5 of the screen: code rows 2 to 5 of the file.
    onNodeWithTag("code").performMouseInput {
      moveTo(gutter(2))
      press()
      moveTo(gutter(4))
      moveTo(gutter(5))
      release()
    }
    waitForIdle()
    val selection = recorder.selections.single()
    assertEquals(2..5, selection.first..selection.last)
    assertEquals(RowKind.Context, selection.firstRow.kind)
    assertEquals(0.0, state.offsetY, "a gutter drag doesn't scroll")
  }

  @Test
  fun aDragStopsAtTheHunk() = test {
    onNodeWithTag("code").performMouseInput {
      moveTo(gutter(4))
      press()
      moveTo(gutter(1))
      release()
    }
    waitForIdle()
    val selection = recorder.selections.single()
    assertEquals(2..4, selection.first..selection.last)
  }

  @Test
  fun aTapOnALineNumberSelectsThatLine() = test {
    onNodeWithTag("code").performTouchInput { click(gutter(3)) }
    waitForIdle()
    val selection = recorder.selections.single()
    assertEquals(3..3, selection.first..selection.last)
  }

  @Test
  fun aLongPressThenDragSelectsOnTouch() = test {
    onNodeWithTag("code").performTouchInput {
      down(gutter(2))
      advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
      moveTo(gutter(4))
      moveTo(gutter(6))
      up()
    }
    waitForIdle()
    val selection = recorder.selections.last()
    assertEquals(2..6, selection.first..selection.last)
  }

  @Test
  fun aSwipeOverTheGutterStillScrolls() = test {
    onNodeWithTag("code").performTouchInput {
      down(gutter(10))
      moveTo(gutter(8))
      moveTo(gutter(2))
      up()
    }
    waitForIdle()
    assertTrue(recorder.selections.isEmpty(), "${recorder.selections}")
    assertTrue(state.offsetY > 0.0)
  }

  @Test
  fun headerClicksToggleCollapseOrViewed() = test {
    onNodeWithTag("code").performMouseInput { click(Offset(100f, row / 2)) }
    onNodeWithTag("code").performMouseInput { click(Offset(600f - 12f - row, row / 2)) }
    waitForIdle()
    assertEquals(listOf("header 0", "viewed 0"), recorder.events)
    assertNull(state.selection)
  }
}
