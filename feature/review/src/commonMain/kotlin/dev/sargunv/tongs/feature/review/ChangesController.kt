package dev.sargunv.tongs.feature.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.sargunv.tongs.codeview.CodeViewState
import dev.sargunv.tongs.codeview.LineSelection
import dev.sargunv.tongs.core.data.DraftComment
import dev.sargunv.tongs.core.data.FileVersion
import dev.sargunv.tongs.core.data.PullRequestChangesRepository
import dev.sargunv.tongs.core.data.PullRequestKey
import dev.sargunv.tongs.core.data.PullRequestOverviewEntity
import dev.sargunv.tongs.core.data.ReviewThreadWithComments
import dev.sargunv.tongs.engine.DiffSide
import dev.sargunv.tongs.engine.FileClass
import dev.sargunv.tongs.engine.LayoutInsert
import dev.sargunv.tongs.engine.ReviewFile
import dev.sargunv.tongs.engine.ReviewLayout
import dev.sargunv.tongs.engine.ReviewSnapshot
import dev.sargunv.tongs.engine.Row
import dev.sargunv.tongs.engine.RowKind
import dev.sargunv.tongs.forge.api.ReviewAnchor
import dev.sargunv.tongs.forge.api.ReviewEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lines a comment goes on, and what it says so far. */
data class CommentTarget(
  val path: String,
  val startSide: DiffSide,
  val startLine: Int,
  val side: DiffSide,
  val endLine: Int,
  /** The draft being edited, or 0 for a new comment. */
  val draftId: Long = 0,
  val body: String = "",
  /** The lines as they are, if they're all on the new side, for "Suggest change". */
  val suggestion: String? = null,
) {
  val title: String
    get() {
      val name = path.substringAfterLast('/')
      return if (startLine == endLine && startSide == side) "Line $endLine of $name"
      else "Lines $startLine–$endLine of $name"
    }
}

/** Something drawn in the diff under a line: a thread, a pending comment, or the composer. */
sealed interface InlineItem {
  val id: Int
  val path: String
  val side: DiffSide
  /** 0 for under the file's header. */
  val line: Int

  class Thread(override val id: Int, val thread: ReviewThreadWithComments, val folded: Boolean) :
    InlineItem {
    override val path = thread.thread.path
    override val side = DiffSide.valueOf(thread.thread.side)
    override val line =
      if (thread.thread.isOutdated || thread.thread.isFileLevel) 0 else thread.thread.line ?: 0
  }

  class Draft(override val id: Int, val draft: DraftComment) : InlineItem {
    override val path = draft.anchor.path
    override val side = draft.anchor.side
    override val line = draft.anchor.endLine
  }

  class Composer(val target: CommentTarget) : InlineItem {
    override val id = COMPOSER_ID
    override val path = target.path
    override val side = target.side
    override val line = target.endLine
  }

  companion object {
    const val COMPOSER_ID = 1
  }
}

/**
 * Everything the changes screen shows, on the main thread: the review as it loads, laid out for
 * reading (viewed and generated files collapsed, threads, drafts, and the composer inline), the
 * file tree, and the pending review.
 *
 * Laying out runs off the main thread whenever an input changes. Snapshots are native memory, so
 * this tracks which are still needed: the latest from the loader, the one shown, and the one being
 * laid out.
 */
class ChangesController(
  private val scope: CoroutineScope,
  val load: PullRequestLoad,
  private val repository: PullRequestChangesRepository,
  val key: PullRequestKey,
) {
  var codeView by mutableStateOf<CodeViewState?>(null)
    private set

  /** The files of the snapshot shown, in display order. */
  var files by mutableStateOf<List<ReviewFile>>(emptyList())
    private set

  var tree by mutableStateOf<FileTree?>(null)
    private set

  /** Whether every changed file is known. */
  var complete by mutableStateOf(false)
    private set

  var overview by mutableStateOf<PullRequestOverviewEntity?>(null)
    private set

  var viewed by mutableStateOf<Set<FileVersion>>(emptySet())
    private set

  var threads by mutableStateOf<List<ReviewThreadWithComments>>(emptyList())
    private set

  var drafts by mutableStateOf<List<DraftComment>>(emptyList())
    private set

  var summary by mutableStateOf("")
    private set

  /** The comment being written, if any. */
  var composer by mutableStateOf<CommentTarget?>(null)
    private set

  /** Whether the composer takes rows in the diff; see [CommentComposer.inlineRows]. */
  var composerRows by mutableStateOf(InlineCommentComposer.inlineRows)

  /** How many rows each inline card takes; the screen measures them for the diff's width. */
  var cardRows by mutableStateOf<(InlineItem) -> Int>({ 3 })

  /** Files the reader opened or closed by hand, by path, overriding the default. */
  private val collapseOverrides = mutableStateMapOf<String, Boolean>()

  /** Resolved threads the reader unfolded. */
  private val unfoldedThreads = mutableStateSetOf<String>()

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

  /** One-off messages, like a failed submission, for a snackbar. */
  val messages: SharedFlow<String> = _messages

  /** Paths to file indices in [files]. */
  private var indexOfPath: Map<String, Int> = emptyMap()
  private val threadIds = HashMap<String, Int>()
  private var nextThreadId = 1_000

  private var latest: ReviewSnapshot? = null
  private var shown: ReviewSnapshot? = null
  private var deriving: ReviewSnapshot? = null
  private val relayouts = Channel<Unit>(Channel.CONFLATED)

  /** The items drawn inline, by id, as last laid out. */
  var items by mutableStateOf<Map<Int, InlineItem>>(emptyMap())
    private set

  init {
    scope.launch { repository.overview(key).collect { overview = it } }
    scope.launch { repository.viewed(key).collect { viewed = it } }
    scope.launch { repository.threads(key).collect { threads = it } }
    scope.launch { repository.drafts(key).collect { drafts = it } }
    scope.launch { repository.summary(key).collect { summary = it } }
    scope.launch { repository.failures.collect { _messages.emit(it) } }
    scope.launch {
      runCatching { repository.refresh(key) }
        .onFailure { _messages.emit("Couldn't load review threads: ${it.message}") }
    }
    // Once every file is known, take the forge's viewed state for these versions.
    scope.launch {
      snapshotFlow { complete }.first { it }
      runCatching {
        repository.syncViewed(
          key,
          files.mapNotNull { f -> f.blob?.let { FileVersion(f.path, it) } },
        )
      }
        .onFailure { _messages.emit("Couldn't load viewed files: ${it.message}") }
    }
    scope.launch {
      val loader = load.loader.filterNotNull().first()
      for (snapshot in loader.snapshots) {
        val old = latest
        latest = snapshot
        if (old != null && old !== shown && old !== deriving) old.close()
        relayouts.trySend(Unit)
      }
    }
    // Items can change without moving, like a thread gaining a reply; the cards redraw.
    scope.launch {
      snapshotFlow { inlineItems() }.collect { list -> items = list.associateBy { it.id } }
    }
    scope.launch {
      snapshotFlow { layoutInputs() }.distinctUntilChanged().collect { relayouts.trySend(Unit) }
    }
    scope.launch {
      try {
        for (unit in relayouts) relayout()
      } finally {
        // After any layout in flight has returned: native calls can't be interrupted.
        (codeView?.rows as? ReviewSnapshot)?.close()
        shown?.close()
        latest?.takeIf { it !== shown }?.close()
      }
    }
  }

  /** Where an inline item goes and how tall it is: all a layout needs of it. */
  private data class Placement(
    val id: Int,
    val path: String,
    val side: DiffSide,
    val line: Int,
    val rows: Int,
  )

  private data class LayoutInputs(
    val overrides: Map<String, Boolean>,
    val viewed: Set<FileVersion>,
    val placements: List<Placement>,
  )

  /** Rows each inline item takes, as last laid out. */
  var itemRows: Map<Int, Int> = emptyMap()
    private set

  private fun inlineItems(): List<InlineItem> {
    val list = mutableListOf<InlineItem>()
    for (thread in threads) {
      val id = threadIds.getOrPut(thread.thread.id) { nextThreadId++ }
      val folded = thread.thread.isResolved && thread.thread.id !in unfoldedThreads
      list += InlineItem.Thread(id, thread, folded)
    }
    val editing = composer?.draftId ?: 0
    for (draft in drafts) {
      if (draft.id != editing) list += InlineItem.Draft(DRAFT_IDS + draft.id.toInt(), draft)
    }
    composer?.let { if (composerRows > 0) list += InlineItem.Composer(it) }
    return list
  }

  private fun layoutInputs(): LayoutInputs {
    val measure = cardRows
    val placements =
      inlineItems().map {
        val rows = if (it is InlineItem.Composer) composerRows else measure(it)
        Placement(it.id, it.path, it.side, it.line, rows)
      }
    return LayoutInputs(collapseOverrides.toMap(), viewed, placements)
  }

  private suspend fun relayout() {
    val base = latest ?: return
    val inputs = layoutInputs()
    val baseChanged = base !== shown
    val knownFiles = files
    deriving = base
    val (laidOut, newFiles, newTree) =
      try {
        withContext(Dispatchers.Default) {
          val files = if (baseChanged) base.files() else knownFiles
          val index = files.withIndex().associate { it.value.path to it.index }
          val collapsed =
            BooleanArray(files.size) { i ->
              val file = files[i]
              inputs.overrides[file.path] ?: defaultCollapsed(file, inputs.viewed)
            }
          val inserts =
            inputs.placements.mapNotNull { p ->
              val file = index[p.path] ?: return@mapNotNull null
              LayoutInsert(file, p.side, p.line, p.rows, p.id)
            }
          Triple(
            base.withLayout(ReviewLayout(collapsed, inserts)),
            files,
            if (baseChanged) FileTree(files) else null,
          )
        }
      } finally {
        deriving = null
      }
    if (baseChanged) {
      files = newFiles
      indexOfPath = newFiles.withIndex().associate { it.value.path to it.index }
      newTree?.let { tree = it }
      complete = base.isComplete
    }
    itemRows = inputs.placements.associate { it.id to it.rows }
    val view = codeView
    if (view == null) {
      codeView = CodeViewState(laidOut)
      load.codeView = codeView
    } else {
      (view.swapRows(laidOut) as ReviewSnapshot).close()
    }
    val previous = shown
    shown = base
    if (previous != null && previous !== base && previous !== latest) previous.close()
  }

  private fun defaultCollapsed(file: ReviewFile, viewed: Set<FileVersion>): Boolean =
    file.fileClass != FileClass.Normal ||
      file.blob?.let { FileVersion(file.path, it) in viewed } == true

  fun fileIndex(path: String): Int = indexOfPath[path] ?: -1

  fun isViewed(file: Int): Boolean {
    val f = files.getOrNull(file) ?: return false
    return f.blob?.let { FileVersion(f.path, it) in viewed } == true
  }

  fun isCollapsed(file: Int): Boolean {
    val f = files.getOrNull(file) ?: return false
    return collapseOverrides[f.path] ?: defaultCollapsed(f, viewed)
  }

  fun toggleViewed(file: Int) {
    val f = files.getOrNull(file) ?: return
    val blob = f.blob ?: return
    val nowViewed = !isViewed(file)
    // Marking viewed folds the file away; unmarking opens it.
    collapseOverrides.remove(f.path)
    scope.launch { repository.setViewed(key, FileVersion(f.path, blob), nowViewed) }
  }

  fun toggleCollapsed(file: Int) {
    val f = files.getOrNull(file) ?: return
    collapseOverrides[f.path] = !isCollapsed(file)
  }

  /** Scrolls the diff to [file], opening it if it was folded only for being generated. */
  fun jumpTo(file: Int) {
    val f = files.getOrNull(file) ?: return
    if (isCollapsed(file) && !isViewed(file)) collapseOverrides[f.path] = false
    codeView?.scrollToFile(file)
  }

  /** Scrolls the diff to an inline item, such as a thread picked from the side pane. */
  fun jumpTo(item: InlineItem) {
    val view = codeView ?: return
    val file = fileIndex(item.path).takeIf { it >= 0 } ?: return
    if (isCollapsed(file)) collapseOverrides[item.path] = false
    scope.launch {
      // Once laid out, the block's rows exist.
      snapshotFlow { items[item.id] != null && !isCollapsed(file) }.first { it }
      val at = view.rows.findRow(file, Row(RowKind.Annotation, file, item.id, 0, ""))
      if (at >= 0) view.scrollToRow((at - 3).coerceAtLeast(0)) else view.scrollToFile(file)
    }
  }

  fun toggleThread(threadId: String) {
    if (!unfoldedThreads.remove(threadId)) unfoldedThreads += threadId
  }

  /** Starts a comment on lines picked in the gutter. */
  fun startComment(selection: LineSelection) {
    val view = codeView ?: return
    val rows = view.rows as? ReviewSnapshot ?: return
    val first = selection.firstRow
    val last = selection.lastRow
    val startSide = first.side ?: return
    val side = last.side ?: return
    val path = rows.filePath(selection.file)
    val suggestion =
      if (startSide == DiffSide.New && side == DiffSide.New) {
        (first.line..last.line).joinToString("\n") {
          rows.lineText(selection.file, DiffSide.New, it)
        }
      } else null
    composer = CommentTarget(path, startSide, first.line, side, last.line, suggestion = suggestion)
    // Keep the lines near the top, so the composer under them stays clear of a keyboard.
    view.scrollToRow((selection.first - 3).coerceAtLeast(0))
  }

  fun editDraft(draft: DraftComment) {
    val a = draft.anchor
    composer =
      CommentTarget(a.path, a.startSide, a.startLine, a.side, a.endLine, draft.id, draft.body)
  }

  fun cancelComment() {
    composer = null
    codeView?.selection = null
  }

  /** Saves the composer's comment to the pending review, which this starts if it's the first. */
  fun saveComment(body: String) {
    val target = composer ?: return
    val head = load.loader.value?.newCommit ?: overview?.headOid ?: return
    composer = null
    codeView?.selection = null
    scope.launch {
      repository.saveDraft(
        key,
        ReviewAnchor(
          head,
          target.path,
          target.side,
          target.startLine,
          target.endLine,
          target.startSide,
        ),
        body,
        id = target.draftId,
      )
    }
  }

  fun deleteDraft(draft: DraftComment) {
    scope.launch { repository.deleteDraft(draft.id) }
  }

  fun saveSummary(text: String) {
    summary = text
    scope.launch { repository.setSummary(key, text) }
  }

  /** Submits the pending review; [onDone] hears whether it went through. */
  fun submit(event: ReviewEvent, body: String, onDone: (Boolean) -> Unit) {
    val head = load.loader.value?.newCommit ?: overview?.headOid ?: return onDone(false)
    scope.launch {
      runCatching { repository.submit(key, event, body, head) }
        .onSuccess {
          _messages.emit(
            when (event) {
              ReviewEvent.Approve -> "Approved"
              ReviewEvent.RequestChanges -> "Requested changes"
              ReviewEvent.Comment -> "Review submitted"
            },
          )
          onDone(true)
        }
        .onFailure {
          _messages.emit("Couldn't submit the review: ${it.message}")
          onDone(false)
        }
    }
  }

  private companion object {
    const val DRAFT_IDS = 1_000_000
  }
}
