package dev.sargunv.tongs.engine

enum class RowKind {
  FileHeader,
  Hunk,
  Context,
  Added,
  Removed,
  Binary,
  /** Stands in for a file's diff until its blobs arrive. */
  Loading,
}

/** Token styles the engine assigns. Mirrors `Style` in `engine-rs/highlight/src/style.rs`. */
enum class TokenStyle {
  Plain,
  Comment,
  Keyword,
  Operator,
  Punctuation,
  String,
  StringSpecial,
  Number,
  Constant,
  Function,
  Type,
  Property,
  VariableBuiltin,
  Attribute,
  Namespace,
  Label,
  Tag,
  Heading,
  Link,
  Emphasis,
}

/**
 * One fixed-height row of a review. Line numbers are 1-based; 0 means absent on that side.
 *
 * [spans] are style changes, each packed as `column shl 8 or styleId`: the style runs from that
 * UTF-16 column of [text] to the next span or the end. Text before the first span is plain.
 */
class Row(
  val kind: RowKind,
  val file: Int,
  val oldLine: Int,
  val newLine: Int,
  val text: String,
  val spans: IntArray = NoSpans,
  /** Highlights for this row are still being computed. */
  val highlightPending: Boolean = false,
) {
  companion object {
    val NoSpans = IntArray(0)

    fun spanColumn(span: Int): Int = span ushr 8

    fun spanStyle(span: Int): Int = span and 0xFF
  }
}

/** Every changed file in a review, flattened into fixed-height rows. */
interface ReviewRows {
  val rowCount: Int
  val fileCount: Int

  fun filePath(file: Int): String

  fun fileFirstRow(file: Int): Int

  /** The index of the file at [path], or -1. Files are sorted by path. */
  fun fileIndex(path: String): Int

  /** Rows `first until first + count`, clamped to the review. */
  fun rows(first: Int, count: Int): List<Row>

  /**
   * Calls [listener], on any thread, whenever highlights land that rows already returned with
   * [Row.highlightPending] may now have. Close the result to stop.
   */
  fun onHighlightsChanged(listener: () -> Unit): AutoCloseable = AutoCloseable {}
}
