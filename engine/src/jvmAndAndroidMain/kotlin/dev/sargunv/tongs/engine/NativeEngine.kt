package dev.sargunv.tongs.engine

import java.nio.ByteBuffer

/** JNI entry points into `libtongs_jni`. Mirrors `engine-rs/jni/src/lib.rs`. */
internal object NativeEngine {
  init {
    System.loadLibrary("tongs_jni")
  }

  @JvmStatic external fun openReview(storeDir: String, oldCommit: String, newCommit: String): Long

  @JvmStatic external fun closeReview(handle: Long)

  @JvmStatic external fun rowCount(handle: Long): Int

  @JvmStatic external fun fileCount(handle: Long): Int

  @JvmStatic external fun fileFirstRow(handle: Long, file: Int): Int

  @JvmStatic external fun filePath(handle: Long, file: Int): String

  @JvmStatic
  external fun fillRows(
    handle: Long,
    highlighter: Long,
    first: Int,
    count: Int,
    out: ByteBuffer,
  ): Int

  @JvmStatic external fun newHighlighter(cacheBytes: Long, threads: Int): Long

  @JvmStatic external fun shutdownHighlighter(handle: Long)

  @JvmStatic external fun closeHighlighter(handle: Long)

  @JvmStatic external fun awaitHighlights(handle: Long, after: Long, timeoutMillis: Int): Long

  @JvmStatic external fun highlighterCacheBytes(handle: Long): Long
}
