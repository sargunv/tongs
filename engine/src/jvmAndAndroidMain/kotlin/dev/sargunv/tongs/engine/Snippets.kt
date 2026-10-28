package dev.sargunv.tongs.engine

/**
 * Highlights a short snippet, such as a Markdown code fence, all at once on the calling thread:
 * call it off the main thread. Returns style changes packed like [Row.spans], `offset shl 8 or
 * styleId` with UTF-16 offsets into all of [code], or null when [language] (a fence's info string)
 * isn't one the engine knows, the snippet is over 256 KiB, it doesn't parse in time, or the native
 * library isn't loadable, as in unit tests.
 */
fun highlightSnippet(language: String, code: String): IntArray? {
  if (!NativeSnippets.available) return null
  return runCatching { NativeSnippets.highlight(language, code) }.getOrNull()
}

/** JNI entry points for snippets. Mirrors `engine-rs/jni/src/snippet.rs`. */
internal object NativeSnippets {
  /** Whether `libtongs_jni` loaded. Android needs to know where to extract grammars first. */
  val available: Boolean by lazy {
    runCatching {
      System.getProperty("java.io.tmpdir")?.let { NativeEngine.setGrammarDir("$it/tongs-grammars") }
        ?: System.loadLibrary("tongs_jni")
      true
    }
      .getOrElse { false }
  }

  @JvmStatic external fun highlight(language: String, text: String): IntArray?
}
