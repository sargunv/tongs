package dev.sargunv.tongs.engine

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Background syntax highlighting with a cache keyed by blob, shared by every [Review] opened with
 * it. It outlives reviews: keep one per app and close it last.
 *
 * @param cacheBytes budget for cached highlights; all of a 600k-line review takes about 20 MiB.
 * @param threads highlighting threads. The default leaves most cores to drawing and fetching.
 * @param grammarDir where Android extracts each grammar the first time a file needs it, since it
 *   bundles them compressed rather than linked; all of them take 30 MB there. The default is in the
 *   app's cache dir, which Android sets as `java.io.tmpdir`. Desktop links grammars in and ignores
 *   this.
 */
class Highlighter(
  cacheBytes: Long = 64L shl 20,
  threads: Int = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4),
  grammarDir: String? = System.getProperty("java.io.tmpdir")?.let { "$it/tongs-grammars" },
) : AutoCloseable {
  init {
    grammarDir?.let(NativeEngine::setGrammarDir)
  }

  internal var handle: Long = NativeEngine.newHighlighter(cacheBytes, threads)
    private set

  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  @Volatile private var open = true

  /** Waits on the engine so listeners hear about new highlights without polling. */
  private val notifier =
    thread(isDaemon = true, name = "tongs-highlight-notify") {
      var epoch = 0L
      while (open) {
        val next = NativeEngine.awaitHighlights(handle, epoch, 1000)
        if (next != epoch) {
          epoch = next
          listeners.forEach { it() }
        }
      }
    }

  /** Bytes of highlights currently cached. */
  val cacheBytes: Long
    get() = NativeEngine.highlighterCacheBytes(handle)

  /** Calls [listener], on a background thread, whenever new highlights land. */
  fun addListener(listener: () -> Unit): AutoCloseable {
    listeners += listener
    return AutoCloseable { listeners -= listener }
  }

  override fun close() {
    if (!open) return
    open = false
    NativeEngine.shutdownHighlighter(handle)
    notifier.join()
    NativeEngine.closeHighlighter(handle)
    handle = 0L
  }
}
