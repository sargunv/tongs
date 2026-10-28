package dev.sargunv.tongs.engine

/** A review snapshot backed by native memory; close it once nothing draws it any more. */
interface ReviewSnapshot : ReviewRows, AutoCloseable {
  /** Whether [file]'s diff is in, rather than a placeholder row. */
  fun fileLoaded(file: Int): Boolean

  /** Whether every changed file is known, though some may still be loading. */
  val isComplete: Boolean
    get() = true

  /** Every file's metadata, in display order, read in one call. */
  fun files(): List<ReviewFile> = emptyList()

  /**
   * The same files laid out for reading, as a new snapshot to close separately. Cheap: files are
   * shared, and only per-file row counts are rebuilt.
   */
  fun withLayout(layout: ReviewLayout): ReviewSnapshot = this

  /** Line [line] of [file] on [side] as it is in the blob, tabs and all; empty if there's none. */
  fun lineText(file: Int, side: DiffSide, line: Int): String = ""
}

/** Why a file is grouped away from the rest, if it is. Mirrors `classify.rs`. */
enum class FileClass {
  Normal,
  Generated,
  Lockfile,
  Vendored,
  /** Moved with identical content. */
  Renamed,
}

enum class ChangeKind {
  Added,
  Deleted,
  Modified,
  Renamed,
}

/** One changed file. Blob ids are hex, null on the side where the file doesn't exist. */
data class ReviewFile(
  val path: String,
  val oldPath: String?,
  val kind: ChangeKind,
  val fileClass: FileClass,
  val additions: Int,
  val deletions: Int,
  val oldBlob: String?,
  val newBlob: String?,
  val loaded: Boolean,
  val binary: Boolean,
) {
  /** The blob a reader sees: the new one, or the old one for a deleted file. */
  val blob: String?
    get() = newBlob ?: oldBlob
}

/**
 * How a snapshot is laid out for reading: [collapsed] files (indexed like [ReviewSnapshot.files])
 * show only their header, and each insert reserves rows under a line for UI drawn over them.
 */
class ReviewLayout(val collapsed: BooleanArray, val inserts: List<LayoutInsert> = emptyList())

/**
 * Reserves [rows] rows under [line] of [file] on [side], as block [id]. Line 0, or a line the diff
 * doesn't show, puts it under the file's header; files still loading get none.
 */
data class LayoutInsert(
  val file: Int,
  val side: DiffSide,
  val line: Int,
  val rows: Int,
  val id: Int,
)

/**
 * How a [FetchSession] plans requests. See `FetchOptions` in `engine-rs/engine/src/fetch.rs`.
 *
 * @property wholeTreesFromDepth fetch whole subtrees from this directory depth on; 0 fetches every
 *   tree with the commits, null walks one level per round trip.
 */
data class FetchOptions(
  val wholeTreesFromDepth: Int? = null,
  val blobBatch: Int = 256,
  val maxBlobRequests: Int = 6,
)

/** An upload-pack request body the engine wants sent. */
class PackRequest(val id: Long, val label: String, val body: ByteArray)

/**
 * Receives one upload-pack response body as it streams in, and indexes it on a native thread. Call
 * from an IO thread: every method may block.
 */
interface PackSink {
  /** Takes the first [length] bytes of [chunk]. */
  fun write(chunk: ByteArray, length: Int)

  /** Waits for the pack to be indexed, then advances the session. */
  fun finish()

  /** Discards the response after a transport error; the session asks for its objects again. */
  fun abort()
}

/**
 * The engine's sans-IO fetch planner for one repo's object store. It decides what to request and
 * ingests responses; the caller does the HTTP. Every method is thread-safe.
 */
interface FetchSession : AutoCloseable {
  /** Starts fetching [commits] before the review is known, e.g. a PR head. */
  fun wantCommits(commits: List<String>)

  /** Loads the diff between two commits, replacing any earlier review. */
  fun startReview(oldCommit: String, newCommit: String)

  /** The next request to send, or null if none is ready until something completes. */
  fun pollRequest(): PackRequest?

  /** A sink for [request]'s response. Call [PackSink.finish] or [PackSink.abort] exactly once. */
  fun openSink(request: PackRequest): PackSink

  /** Fetches the blobs of the files at [paths] before any others. */
  fun prioritize(paths: List<String>)

  /** A new snapshot if the review changed since the last one. */
  fun takeSnapshot(): ReviewSnapshot?

  /**
   * Whether [ancestor] is reachable from [descendant] through commits in the store, which makes it
   * their merge base. False may just mean the history isn't fetched.
   */
  fun isAncestor(ancestor: String, descendant: String): Boolean

  val isDone: Boolean

  val stats: FetchStats
}

class FetchStats(val requests: Int, val objects: Long, val packBytes: Long)
