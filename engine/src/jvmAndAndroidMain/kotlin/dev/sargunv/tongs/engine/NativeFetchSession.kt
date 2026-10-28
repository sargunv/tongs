package dev.sargunv.tongs.engine

/**
 * A [FetchSession] backed by the native engine, over the object store at a directory. Snapshots
 * carry syntax highlights from [highlighter]; the session doesn't own it.
 */
class NativeFetchSession
private constructor(private val handle: Long, private val highlighter: Highlighter?) :
  FetchSession {
  override fun wantCommits(commits: List<String>) =
    NativeFetch.wantCommits(handle, commits.joinToString(" "))

  override fun startReview(oldCommit: String, newCommit: String) =
    NativeFetch.startReview(handle, oldCommit, newCommit)

  override fun pollRequest(): PackRequest? {
    val id = NativeFetch.pollRequest(handle)
    if (id == 0L) return null
    return PackRequest(
      id,
      NativeFetch.requestLabel(handle, id),
      NativeFetch.takeRequestBody(handle, id),
    )
  }

  override fun openSink(request: PackRequest): PackSink =
    object : PackSink {
      private var sink = NativeFetch.openSink(handle)

      override fun write(chunk: ByteArray, length: Int) =
        NativeFetch.sinkWrite(sink, chunk, 0, length)

      override fun finish() {
        val sink = sink.also { sink = 0 }
        NativeFetch.finishResponse(handle, sink, request.id)
      }

      override fun abort() {
        val sink = sink.also { sink = 0 }
        NativeFetch.abortResponse(handle, sink, request.id)
      }
    }

  override fun prioritize(paths: List<String>) =
    NativeFetch.prioritize(handle, paths.joinToString("\n"))

  override fun takeSnapshot(): ReviewSnapshot? =
    NativeFetch.takeSnapshot(handle).takeIf { it != 0L }?.let { Review(it, highlighter) }

  override fun isAncestor(ancestor: String, descendant: String): Boolean =
    NativeFetch.isAncestor(handle, ancestor, descendant)

  override val isDone: Boolean
    get() = NativeFetch.isDone(handle)

  override val stats: FetchStats
    get() {
      val (requests, objects, bytes) = NativeFetch.stats(handle).split(" ")
      return FetchStats(requests.toInt(), objects.toLong(), bytes.toLong())
    }

  override fun close() = NativeFetch.closeSession(handle)

  companion object {
    /** Opens a session over the object store at [storeDir], creating it if needed. */
    fun open(
      storeDir: String,
      options: FetchOptions = FetchOptions(),
      highlighter: Highlighter? = null,
    ): NativeFetchSession =
      NativeFetchSession(
        NativeFetch.openSession(
          storeDir,
          options.wholeTreesFromDepth ?: -1,
          options.blobBatch,
          options.maxBlobRequests,
        ),
        highlighter,
      )
  }
}
