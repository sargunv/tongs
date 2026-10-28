package dev.sargunv.tongs.engine

/** JNI entry points for fetch sessions. Mirrors `engine-rs/jni/src/fetch.rs`. */
internal object NativeFetch {
  init {
    System.loadLibrary("tongs_jni")
  }

  @JvmStatic
  external fun openSession(
    storeDir: String,
    wholeTreesFromDepth: Int,
    blobBatch: Int,
    maxBlobRequests: Int,
  ): Long

  @JvmStatic external fun closeSession(session: Long)

  @JvmStatic external fun wantCommits(session: Long, commits: String)

  @JvmStatic external fun startReview(session: Long, oldCommit: String, newCommit: String)

  @JvmStatic external fun pollRequest(session: Long): Long

  @JvmStatic external fun requestLabel(session: Long, id: Long): String

  @JvmStatic external fun takeRequestBody(session: Long, id: Long): ByteArray

  @JvmStatic external fun openSink(session: Long): Long

  @JvmStatic external fun sinkWrite(sink: Long, chunk: ByteArray, offset: Int, length: Int)

  @JvmStatic external fun finishResponse(session: Long, sink: Long, id: Long)

  @JvmStatic external fun abortResponse(session: Long, sink: Long, id: Long)

  @JvmStatic external fun prioritize(session: Long, paths: String)

  @JvmStatic external fun takeSnapshot(session: Long): Long

  @JvmStatic external fun isDone(session: Long): Boolean

  @JvmStatic external fun isAncestor(session: Long, ancestor: String, descendant: String): Boolean

  @JvmStatic external fun stats(session: Long): String

  @JvmStatic external fun fileIndex(review: Long, path: String): Int

  @JvmStatic external fun fileLoaded(review: Long, file: Int): Boolean
}
