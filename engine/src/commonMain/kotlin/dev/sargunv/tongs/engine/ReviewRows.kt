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

/** One fixed-height row of a review. Line numbers are 1-based; 0 means absent on that side. */
class Row(
  val kind: RowKind,
  val file: Int,
  val oldLine: Int,
  val newLine: Int,
  val text: String,
)

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
}
