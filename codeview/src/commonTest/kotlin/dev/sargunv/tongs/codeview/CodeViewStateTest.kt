package dev.sargunv.tongs.codeview

import dev.sargunv.tongs.engine.ReviewRows
import dev.sargunv.tongs.engine.Row
import dev.sargunv.tongs.engine.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Files as `(path, rows)`, sorted by path; every row but the first is context. */
private class FakeRows(private val files: List<Pair<String, Int>>) : ReviewRows {
  private val starts = files.runningFold(0) { total, (_, rows) -> total + rows }

  override val rowCount = starts.last()
  override val fileCount = files.size

  override fun filePath(file: Int) = files[file].first

  override fun fileFirstRow(file: Int) = starts[file]

  override fun fileIndex(path: String) = files.indexOfFirst { it.first == path }

  override fun rows(first: Int, count: Int) =
    (first until (first + count).coerceAtMost(rowCount)).map { row ->
      val file = starts.indexOfLast { it <= row }
      val kind = if (row == starts[file]) RowKind.FileHeader else RowKind.Context
      Row(kind, file, 0, row - starts[file], "")
    }
}

class CodeViewStateTest {
  private fun state(rows: ReviewRows) = CodeViewState(rows).apply { viewportHeight = 1 }

  @Test
  fun keepsTopRowOnItsFileAcrossSwaps() {
    val state = state(FakeRows(listOf("a" to 2, "b" to 2, "c" to 2)))
    state.scrollBy(3.5) // Half a row into b's placeholder.
    state.swapRows(FakeRows(listOf("a" to 10, "aa" to 2, "b" to 5, "c" to 2)))
    assertEquals(13.5, state.offsetY)
  }

  @Test
  fun clampsToTheFileWhenItShrinks() {
    val state = state(FakeRows(listOf("a" to 2, "b" to 9, "c" to 2)))
    state.scrollBy(8.0)
    state.swapRows(FakeRows(listOf("a" to 2, "b" to 3, "c" to 2)))
    assertEquals(4.0, state.offsetY)
  }

  @Test
  fun staysAtTheTopAsEarlierFilesAppear() {
    val state = state(FakeRows(listOf("b" to 2)))
    state.swapRows(FakeRows(listOf("a" to 5, "b" to 2)))
    assertEquals(0.0, state.offsetY)
  }

  @Test
  fun reportsFilesOnScreenAndAhead() {
    val state = state(FakeRows(List(20) { "f$it" to 2 }))
    state.scrollBy(4.0)
    // A 1px viewport of 1px rows shows parts of 2 rows; with two screens ahead, rows 4 to 10.
    assertEquals(2..5, state.visibleFiles())
  }
}
