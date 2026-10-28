package dev.sargunv.tongs.engine

/** A review snapshot backed by native memory; close it once nothing draws it any more. */
interface ReviewSnapshot : ReviewRows, AutoCloseable {
  /** Whether [file]'s diff is in, rather than a placeholder row. */
  fun fileLoaded(file: Int): Boolean
}

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
