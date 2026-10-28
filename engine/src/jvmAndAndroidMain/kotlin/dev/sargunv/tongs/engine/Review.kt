package dev.sargunv.tongs.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [ReviewRows] backed by the native engine. Rows carry syntax highlights from [highlighter] once
 * they're ready; the review doesn't own it.
 */
class Review
internal constructor(internal var handle: Long, private val highlighter: Highlighter? = null) :
  ReviewSnapshot {
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

  override val isComplete: Boolean
    get() = NativeEngine.reviewComplete(handle)

  override fun files(): List<ReviewFile> {
    val buffer = ByteBuffer.wrap(NativeEngine.fileInfo(handle)).order(ByteOrder.LITTLE_ENDIAN)
    val out = ArrayList<ReviewFile>(fileCount)
    fun blob(): String? {
      val bytes = ByteArray(20).also(buffer::get)
      return if (bytes.all { it == 0.toByte() }) null else bytes.toHex()
    }
    fun text(): String = ByteArray(buffer.getInt()).also(buffer::get).decodeToString()
    while (buffer.hasRemaining()) {
      val fileClass = FILE_CLASSES[buffer.get().toInt()]
      val kind = CHANGE_KINDS[buffer.get().toInt()]
      val flags = buffer.get().toInt()
      buffer.get()
      val additions = buffer.getInt()
      val deletions = buffer.getInt()
      val oldBlob = blob()
      val newBlob = blob()
      val path = text()
      val oldPath = text().ifEmpty { null }
      out +=
        ReviewFile(
          path = path,
          oldPath = oldPath,
          kind = kind,
          fileClass = fileClass,
          additions = additions,
          deletions = deletions,
          oldBlob = oldBlob,
          newBlob = newBlob,
          loaded = flags and 1 != 0,
          binary = flags and 2 != 0,
        )
    }
    return out
  }

  override fun withLayout(layout: ReviewLayout): ReviewSnapshot {
    val collapsed = ByteArray(fileCount) { if (layout.collapsed.getOrElse(it) { false }) 1 else 0 }
    val inserts = IntArray(layout.inserts.size * 5)
    layout.inserts.forEachIndexed { i, insert ->
      inserts[i * 5] = insert.file
      inserts[i * 5 + 1] = insert.side.ordinal
      inserts[i * 5 + 2] = insert.line
      inserts[i * 5 + 3] = insert.rows
      inserts[i * 5 + 4] = insert.id
    }
    return Review(NativeEngine.withLayout(handle, collapsed, inserts), highlighter)
  }

  override fun findRow(file: Int, row: Row): Int =
    NativeEngine.findRow(handle, file, row.kind.ordinal, row.oldLine, row.newLine)

  override fun lineText(file: Int, side: DiffSide, line: Int): String =
    NativeEngine.lineText(handle, file, side.ordinal, line)

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
          collapsed = flags and FLAG_COLLAPSED != 0,
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
    private const val FLAG_COLLAPSED = 2
    private val KINDS = RowKind.entries
    private val FILE_CLASSES = FileClass.entries
    private val CHANGE_KINDS = ChangeKind.entries

    private fun ByteArray.toHex(): String =
      joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Opens a review of two commits whose objects are already in the store at [storeDir]. */
    fun open(
      storeDir: String,
      oldCommit: String,
      newCommit: String,
      highlighter: Highlighter? = null,
    ): Review = Review(NativeEngine.openReview(storeDir, oldCommit, newCommit), highlighter)
  }
}
