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

  @JvmStatic external fun fillRows(handle: Long, first: Int, count: Int, out: ByteBuffer): Int
}
