package dev.sargunv.tongs.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [ReviewRows] backed by the native engine. Rows carry syntax highlights from [highlighter] once
 * they're ready; the review doesn't own it.
 */
class Review private constructor(private var handle: Long, private val highlighter: Highlighter?) :
  ReviewRows, AutoCloseable {
  override val rowCount: Int = NativeEngine.rowCount(handle)
  override val fileCount: Int = NativeEngine.fileCount(handle)

  private val buffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)

  override fun filePath(file: Int): String = NativeEngine.filePath(handle, file)

  override fun fileFirstRow(file: Int): Int = NativeEngine.fileFirstRow(handle, file)

  override fun rows(first: Int, count: Int): List<Row> {
    val out = ArrayList<Row>(count)
    while (out.size < count && first + out.size < rowCount) {
      val written =
        NativeEngine.fillRows(
          handle,
          highlighter?.handle ?: 0L,
          first + out.size,
          count - out.size,
          buffer,
        )
      check(written > 0) { "row ${first + out.size} doesn't fit in the transfer buffer" }
      decode(written, out)
    }
    return out
  }

  private fun decode(count: Int, out: MutableList<Row>) {
    var pos = 0
    repeat(count) {
      val flags = buffer.get(pos + 1).toInt()
      val spanCount = buffer.getShort(pos + 2).toInt() and 0xFFFF
      val textLength = buffer.getInt(pos + 16)
      val text = ByteArray(textLength)
      // Relative get: the absolute bulk `get(index, dst)` needs Android API 35.
      buffer.position(pos + RECORD_HEADER)
      buffer.get(text)
      val spansAt = pos + RECORD_HEADER + textLength
      out +=
        Row(
          kind = KINDS[buffer.get(pos).toInt()],
          file = buffer.getInt(pos + 4),
          oldLine = buffer.getInt(pos + 8),
          newLine = buffer.getInt(pos + 12),
          text = text.decodeToString(),
          spans =
            if (spanCount == 0) Row.NoSpans
            else IntArray(spanCount) { buffer.getInt(spansAt + it * 4) },
          highlightPending = flags and FLAG_HIGHLIGHT_PENDING != 0,
        )
      pos += RECORD_HEADER + textLength + spanCount * 4
    }
  }

  override fun onHighlightsChanged(listener: () -> Unit): AutoCloseable =
    highlighter?.addListener(listener) ?: AutoCloseable {}

  override fun close() {
    if (handle != 0L) {
      NativeEngine.closeReview(handle)
      handle = 0L
    }
  }

  companion object {
    private const val RECORD_HEADER = 20
    private const val FLAG_HIGHLIGHT_PENDING = 1
    private val KINDS = RowKind.entries

    /** Opens a review of two commits whose objects are already in the store at [storeDir]. */
    fun open(
      storeDir: String,
      oldCommit: String,
      newCommit: String,
      highlighter: Highlighter? = null,
    ): Review = Review(NativeEngine.openReview(storeDir, oldCommit, newCommit), highlighter)
  }
}
