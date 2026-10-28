package dev.sargunv.tongs.engine

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Posts upload-pack requests to one repo and streams the responses back. */
fun interface GitTransport {
  /**
   * POSTs [body] to `git-upload-pack`, passing each response chunk to [onChunk] as it arrives.
   * [onChunk] may block, so call it from an IO thread.
   */
  suspend fun uploadPack(body: ByteArray, onChunk: (ByteArray, Int) -> Unit)
}

/**
 * Drives a [FetchSession] over a [GitTransport]: sends every request the engine is ready for at
 * once, and publishes review snapshots to [snapshots] as responses land.
 *
 * Snapshots go out through a conflated channel. One the UI never received is closed when a newer
 * one replaces it; the UI closes the ones it took once it swaps in the next.
 */
class ReviewLoader(
  private val session: FetchSession,
  private val transport: GitTransport,
  private val log: (String) -> Unit = {},
) : AutoCloseable {
  val snapshots = Channel<ReviewSnapshot>(Channel.CONFLATED, onUndeliveredElement = { it.close() })

  /** Wakes [run]: null for "something changed", or a request's outcome. */
  private val events = Channel<Result<Unit>?>(Channel.UNLIMITED)
  private val publishLock = Mutex()
  @Volatile private var closed = false

  /** While set, snapshots are held back; see [startReview]. */
  @Volatile private var speculative: Pair<String, String>? = null
  @Volatile private var visible: List<String>? = null
  private var prioritized: List<String>? = null
  private var held: ReviewSnapshot? = null
  private var sawHeaders = false

  /** The commit the latest review shows the changes up to, as for anchoring comments. */
  @Volatile
  var newCommit: String? = null
    private set

  private var sawBody = false

  /**
   * Loads the diff between two commits. A [speculative] review loads but isn't shown until
   * [confirm]: a PR's base is usually its merge base, so the fetch needn't wait for the forge to
   * say so. The loader confirms by itself once the fetched history shows [oldCommit] is an ancestor
   * of [newCommit].
   */
  suspend fun startReview(oldCommit: String, newCommit: String, speculative: Boolean = false) {
    publishLock.withLock {
      held?.close()
      held = null
      this.speculative = if (speculative) oldCommit to newCommit else null
      this.newCommit = newCommit
      session.startReview(oldCommit, newCommit)
    }
    events.trySend(null)
  }

  /** Shows the speculative review after all. */
  suspend fun confirm() {
    if (speculative == null) return
    publishLock.withLock {
      speculative = null
      held?.let(::publish)
      held = null
    }
    events.trySend(null)
  }

  /**
   * Fetches the blobs of [files] in [snapshot] first; call from the UI as the viewport moves. It
   * reads paths from [snapshot] right away, so the caller may close it afterwards.
   */
  fun prioritize(snapshot: ReviewSnapshot, files: IntRange) {
    if (closed) return
    visible = files.filterNot(snapshot::fileLoaded).map(snapshot::filePath)
    events.trySend(null)
  }

  /**
   * Fetches until the review is fully loaded and confirmed. Gives up after three failures in a row.
   * Runs on [Dispatchers.Default]: the engine calls it makes can wait on a pack being indexed.
   */
  suspend fun run() = withContext(Dispatchers.Default) { runLoop() }

  private suspend fun runLoop() = coroutineScope {
    var inFlight = 0
    var failures = 0
    while (true) {
      val visible = visible
      if (visible != null && visible !== prioritized) {
        prioritized = visible
        session.prioritize(visible)
      }
      while (true) {
        val request = session.pollRequest() ?: break
        inFlight++
        launch(Dispatchers.IO) { events.send(send(request)) }
      }
      takeSnapshot()
      speculative?.let { (old, new) ->
        if (session.isAncestor(old, new)) {
          log("merge base: the base, which the head descends from")
          confirm()
        }
      }
      if (session.isDone && inFlight == 0 && speculative == null) break
      val event = events.receive() ?: continue
      inFlight--
      event
        .onSuccess { failures = 0 }
        .onFailure {
          if (++failures >= 3) throw it
          log("retrying after: $it")
        }
    }
    takeSnapshot()
    val stats = session.stats
    log(
      "fully loaded: ${stats.requests} requests, ${stats.objects} objects, " +
        "${stats.packBytes / 1024} KiB of packs",
    )
  }

  private suspend fun send(request: PackRequest): Result<Unit> {
    val mark = TimeSource.Monotonic.markNow()
    log("send ${request.label}")
    val sink = session.openSink(request)
    return try {
      transport.uploadPack(request.body) { chunk, length -> sink.write(chunk, length) }
      sink.finish()
      log("done ${request.label} in ${mark.elapsedNow()}")
      Result.success(Unit)
    } catch (e: CancellationException) {
      sink.abort()
      throw e
    } catch (e: Exception) {
      sink.abort()
      Result.failure(e)
    }
  }

  private suspend fun takeSnapshot() {
    publishLock.withLock {
      val snapshot = session.takeSnapshot() ?: return
      if (speculative != null) {
        held?.close()
        held = snapshot
      } else {
        publish(snapshot)
      }
    }
  }

  private fun publish(snapshot: ReviewSnapshot) {
    if (!sawHeaders && snapshot.fileCount > 0) {
      sawHeaders = true
      log("first headers: ${snapshot.fileCount} files")
    }
    if (!sawBody && snapshot.fileCount > 0 && snapshot.fileLoaded(0)) {
      sawBody = true
      log("first file body")
    }
    snapshots.trySend(snapshot)
  }

  /**
   * Closes the session. Call once [run] has returned or been cancelled; snapshots already sent stay
   * receivable.
   */
  override fun close() {
    closed = true
    held?.close()
    snapshots.close()
    session.close()
  }
}
