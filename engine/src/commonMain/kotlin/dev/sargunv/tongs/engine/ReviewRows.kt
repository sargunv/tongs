package dev.sargunv.tongs.engine

enum class RowKind {
  FileHeader,
  Hunk,
  Context,
  Added,
  Removed,
  Binary,
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

  /** Rows `first until first + count`, clamped to the review. */
  fun rows(first: Int, count: Int): List<Row>
}
