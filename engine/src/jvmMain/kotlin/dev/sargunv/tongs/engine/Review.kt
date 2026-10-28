package dev.sargunv.tongs.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A [ReviewRows] backed by the native engine. */
class Review internal constructor(internal var handle: Long) : ReviewSnapshot {
  override val rowCount: Int = NativeEngine.rowCount(handle)
  override val fileCount: Int = NativeEngine.fileCount(handle)

  // Lazy: a fetch makes many snapshots, and the UI only ever reads rows from some of them.
  private val buffer by lazy {
    ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
  }

  override fun filePath(file: Int): String = NativeEngine.filePath(handle, file)

  override fun fileFirstRow(file: Int): Int = NativeEngine.fileFirstRow(handle, file)

  override fun fileIndex(path: String): Int = NativeFetch.fileIndex(handle, path)

  override fun fileLoaded(file: Int): Boolean = NativeFetch.fileLoaded(handle, file)

  override fun rows(first: Int, count: Int): List<Row> {
    val out = ArrayList<Row>(count)
    while (out.size < count && first + out.size < rowCount) {
      val written = NativeEngine.fillRows(handle, first + out.size, count - out.size, buffer)
      check(written > 0) { "row ${first + out.size} doesn't fit in the transfer buffer" }
      decode(written, out)
    }
    return out
  }

  private fun decode(count: Int, out: MutableList<Row>) {
    var pos = 0
    repeat(count) {
      val textLength = buffer.getInt(pos + 16)
      val text = ByteArray(textLength)
      buffer.get(pos + RECORD_HEADER, text)
      out +=
        Row(
          kind = KINDS[buffer.get(pos).toInt()],
          file = buffer.getInt(pos + 4),
          oldLine = buffer.getInt(pos + 8),
          newLine = buffer.getInt(pos + 12),
          text = text.decodeToString(),
        )
      pos += RECORD_HEADER + textLength
    }
  }

  override fun close() {
    if (handle != 0L) {
      NativeEngine.closeReview(handle)
      handle = 0L
    }
  }

  companion object {
    private const val RECORD_HEADER = 20
    private val KINDS = RowKind.entries

    /** Opens a review of two commits whose objects are already in the store at [storeDir]. */
    fun open(storeDir: String, oldCommit: String, newCommit: String): Review =
      Review(NativeEngine.openReview(storeDir, oldCommit, newCommit))
  }
}
