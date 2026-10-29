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
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.FileVersion
import dev.sargunv.tongs.core.data.PullRequestChangesRepository
import dev.sargunv.tongs.core.data.PullRequestKey
import dev.sargunv.tongs.core.data.PullRequestOverviewEntity
import dev.sargunv.tongs.core.data.ReviewThreadWithComments
import dev.sargunv.tongs.core.markdown.MarkdownContext
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
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A line of code a comment is on, as the composer shows it when the diff is out of sight. */
data class QuotedLine(val kind: RowKind, val line: Int, val text: String)

/** What the composer is writing: a line comment, or a reply to a thread. */
sealed interface ComposerTarget {
  /** What it's on, like `Line 12 of Map.kt`. */
  val title: String
  /** The pending review comment being edited, or 0 for a new one. */
  val draftId: Long
  /** The text to start from when there's no draft: the pending comment's, or nothing. */
  val body: String

  /** A comment on lines picked in the gutter, or a pending one being edited. */
  data class Line(
    val path: String,
    val startSide: DiffSide,
    val startLine: Int,
    val side: DiffSide,
    val endLine: Int,
    override val draftId: Long = 0,
    override val body: String = "",
    /** The lines as they are, if they're all on the new side, for "Suggest change". */
    val suggestion: String? = null,
    /** The lines, for a composer that covers the diff. */
    val quoted: List<QuotedLine> = emptyList(),
    /** The commit these lines were selected from, kept with the autosaved draft. */
    val commitOid: String,
  ) : ComposerTarget {
    fun anchor() = ReviewAnchor(commitOid, path, side, startLine, endLine, startSide)

    override val title: String
      get() {
        val name = path.substringAfterLast('/')
        return if (startLine == endLine && startSide == side) "Line $endLine of $name"
        else "Lines $startLine–$endLine of $name"
      }

    /** The draft slot for a new comment on these lines. */
    val slot: String
      get() = "$LINE_SLOT$commitOid:${startSide.code}$startLine-${side.code}$endLine:$path"
  }

  /** A reply to thread [threadId], or a pending reply being edited. */
  data class Reply(
    val threadId: String,
    val path: String,
    override val draftId: Long = 0,
    override val body: String = "",
    /** An edited pending reply's anchor, which it keeps. */
    val anchor: ReviewAnchor? = null,
  ) : ComposerTarget {
    override val title: String
      get() = "Reply on ${path.substringAfterLast('/')}"
  }

  companion object {
    /** Slots of drafts for new line comments: source commit, sides and lines, then the path. */
    const val LINE_SLOT = "review/line:"
    /** Slots of drafts for replies: the thread's id follows. */
    const val REPLY_SLOT = "review/reply:"

    private val DiffSide.code
      get() = if (this == DiffSide.Old) 'o' else 'n'

    private fun side(code: Char) = if (code == 'o') DiffSide.Old else DiffSide.New

    private val LineSlot = Regex("""([^:]+):([on])(\d+)-([on])(\d+):(.+)""")

    /** The lines a [LINE_SLOT] draft is on, or null if [slot] isn't one. */
    fun parseLineSlot(slot: String): Line? {
      val match =
        LineSlot.matchEntire(slot.removePrefix(LINE_SLOT).takeIf { it != slot } ?: return null)
      val (commit, startSide, start, side, end, path) = match?.destructured ?: return null
      return Line(
        path,
        side(startSide[0]),
        start.toInt(),
        side(side[0]),
        end.toInt(),
        commitOid = commit,
      )
    }
  }
}

/** Something drawn in the diff under a line: a thread, a pending comment, or an unsent draft. */
sealed interface InlineItem {
  val id: Int
  val path: String
  val side: DiffSide
  /** 0 for under the file's header. */
  val line: Int

  /**
   * What the card shows, to tell when a measured height is stale. Equal signatures draw the same
   * height at the same width.
   */
  val signature: Any

  /**
   * A thread, with the reader's pending [replies] to it, and whether a reply to it is drafted.
   * Resolved threads are [folded] to one line until opened.
   */
  class Thread(
    override val id: Int,
    val thread: ReviewThreadWithComments,
    val folded: Boolean,
    val replies: List<DraftComment>,
    val replyDrafted: Boolean,
  ) : InlineItem {
    override val path = thread.thread.path
    override val side = DiffSide.valueOf(thread.thread.side)
    override val line =
      if (thread.thread.isOutdated || thread.thread.isFileLevel) 0 else thread.thread.line ?: 0
    override val signature: Any =
      listOf(
        folded,
        thread.thread.copy(position = 0),
        thread.comments.map { Triple(it.id, it.body, it.state) },
        replies.map { it.id to it.body },
        replyDrafted,
      )
  }

  class Draft(override val id: Int, val draft: DraftComment) : InlineItem {
    override val path = draft.anchor.path
    override val side = draft.anchor.side
    override val line = draft.anchor.endLine
    override val signature: Any = draft.body to draft.inReplyTo
  }

  /** A line comment written and closed without adding it to the review: an email-style draft. */
  class Unsent(
    override val id: Int,
    val target: ComposerTarget.Line,
    val slot: String,
    val body: String,
  ) : InlineItem {
    override val path = target.path
    override val side = target.side
    override val line = target.endLine
    override val signature: Any = body
  }
}

/**
 * Everything the changes screen shows, on the main thread: the review as it loads, laid out for
 * reading (viewed and generated files collapsed, threads, pending comments, and unsent drafts
 * inline), the file tree, the pending review, and what the composer is writing.
 *
 * Laying out runs off the main thread whenever an input changes. Snapshots are native memory, so
 * this tracks which are still needed: the latest from the loader, the one shown, and the one being
 * laid out.
 */
class ChangesController(
  private val scope: CoroutineScope,
  val load: PullRequestLoad,
  private val repository: PullRequestChangesRepository,
  val draftRepository: DraftRepository,
  val key: PullRequestKey,
) {
  /** Resolves references in comments on this PR; the screen sets the instance's web URL. */
  var markdownContext by mutableStateOf(MarkdownContext("https://github.com", key.repo))

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

  /** The pending review's summary as stored; null until it has loaded. */
  var summary by mutableStateOf<String?>(null)
    private set

  /** The comment being written, if any. */
  var composer by mutableStateOf<ComposerTarget?>(null)
    private set

  /** Whether a review is under way here; see [PullRequestChangesRepository.reviewStarted]. */
  var reviewStarted by mutableStateOf(false)
    private set

  /** Unsent drafts in this PR's review slots, by slot. */
  private var slots by mutableStateOf<Map<String, String>>(emptyMap())

  /**
   * How many rows each inline card takes before it's drawn: an estimate from its text at the diff's
   * width. A card on screen measures itself and reports its height through [measured].
   */
  var cardRows: (InlineItem) -> Int = { 3 }
    set(value) {
      field = value
      measuredRows.clear()
      estimates++
    }

  private var estimates by mutableStateOf(0)

  /** Heights cards measured, in rows, with the signature they had then. */
  private val measuredRows = mutableStateMapOf<Int, Pair<Any, Int>>()

  /** Row height in pixels, for [measured]. */
  var rowPx = 1f

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
  private val unsentIds = HashMap<String, Int>()
  private var nextUnsentId = UNSENT_IDS

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
    scope.launch { repository.reviewStarted(key).collect { reviewStarted = it } }
    scope.launch {
      draftRepository.slots(key.accountId, key.repo, key.number, SLOT_PREFIX).collect { slots = it }
    }
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
    val (replies, lines) = drafts.partition { it.inReplyTo != null }
    val repliesTo = replies.groupBy { it.inReplyTo }
    val threadIdsShown = HashSet<String>()
    for (thread in threads) {
      val threadId = thread.thread.id
      threadIdsShown += threadId
      val id = threadIds.getOrPut(threadId) { nextThreadId++ }
      val folded = thread.thread.isResolved && threadId !in unfoldedThreads
      list +=
        InlineItem.Thread(
          id,
          thread,
          folded,
          repliesTo[threadId].orEmpty(),
          replyDrafted = "${ComposerTarget.REPLY_SLOT}$threadId" in slots,
        )
    }
    for (draft in lines) list += InlineItem.Draft(DRAFT_IDS + draft.id.toInt(), draft)
    // A reply whose thread is gone shows where the thread was.
    for (draft in replies) {
      if (draft.inReplyTo !in threadIdsShown)
        list += InlineItem.Draft(DRAFT_IDS + draft.id.toInt(), draft)
    }
    val writing = (composer as? ComposerTarget.Line)?.takeIf { it.draftId == 0L }?.slot
    for ((slot, body) in slots) {
      if (slot == writing) continue
      val target = ComposerTarget.parseLineSlot(slot) ?: continue
      list += InlineItem.Unsent(unsentIds.getOrPut(slot) { nextUnsentId++ }, target, slot, body)
    }
    return list
  }

  private fun layoutInputs(): LayoutInputs {
    estimates
    val measure = cardRows
    val placements =
      inlineItems().map {
        val measured = measuredRows[it.id]?.takeIf { (signature, _) -> signature == it.signature }
        Placement(it.id, it.path, it.side, it.line, measured?.second ?: measure(it))
      }
    return LayoutInputs(collapseOverrides.toMap(), viewed, placements)
  }

  /** A card on screen measured [heightPx] tall; the layout makes room if that's new. */
  fun measured(item: InlineItem, heightPx: Int) {
    val rows = ceil(heightPx / rowPx).toInt().coerceAtLeast(1)
    val known = measuredRows[item.id]
    if (known?.first == item.signature && known.second == rows) return
    // Unchanged rows leave the layout's inputs equal, so this relayouts only for a new height.
    measuredRows[item.id] = item.signature to rows
  }

  private suspend fun relayout() {
    val base = latest ?: return
    val inputs = layoutInputs()
    val baseChanged = base !== shown
    val knownFiles = files
    deriving = base
    var created: ReviewSnapshot? = null
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
            base.withLayout(ReviewLayout(collapsed, inserts)).also { created = it },
            files,
            if (baseChanged) FileTree(files) else null,
          )
        }
      } catch (error: Throwable) {
        // withContext can discard a native result when cancellation wins the return to the UI.
        created?.close()
        if (base !== shown && base !== latest) base.close()
        throw error
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
      // Once the snapshot on screen has the block's rows: the layout runs in the background.
      val at =
        withTimeoutOrNull(JUMP_TIMEOUT) {
          snapshotFlow {
            val rows = view.rows
            val index = rows.fileIndex(item.path)
            if (index < 0) -1
            else rows.findRow(index, Row(RowKind.Annotation, index, item.id, 0, ""))
          }
            .first { it >= 0 }
        }
      if (at != null) view.scrollToRow((at - 3).coerceAtLeast(0))
      else fileIndex(item.path).takeIf { it >= 0 }?.let(view::scrollToFile)
    }
  }

  fun toggleThread(threadId: String) {
    if (!unfoldedThreads.remove(threadId)) unfoldedThreads += threadId
  }

  /** Whether the reader's comments go into a review rather than out at once. */
  val inReview: Boolean
    get() = reviewStarted || drafts.isNotEmpty() || overview?.pendingReviewId != null

  /** Comments in the review: drafted here, and in a review left pending on the forge. */
  val pendingCount: Int
    get() = drafts.size + (overview?.pendingReviewComments ?: 0)

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
    val quoted =
      rows.rows(selection.first, selection.last - selection.first + 1).mapNotNull { row ->
        row.side?.let { QuotedLine(row.kind, row.line, row.text) }
      }
    composer =
      ComposerTarget.Line(
        path,
        startSide,
        first.line,
        side,
        last.line,
        suggestion = suggestion,
        quoted = quoted,
        commitOid = load.loader.value?.newCommit ?: overview?.headOid ?: return,
      )
    // Keep the lines near the top, clear of a keyboard and beside the composer's pane.
    view.scrollToRow((selection.first - 3).coerceAtLeast(0))
  }

  /** Opens an unsent line comment again, where it was left. */
  fun resume(item: InlineItem.Unsent) {
    val t = item.target
    val current = t.commitOid == (load.loader.value?.newCommit ?: overview?.headOid)
    composer =
      t.copy(
        suggestion =
          if (current && t.startSide == DiffSide.New && t.side == DiffSide.New)
            lines(t.path, DiffSide.New, t.startLine, t.endLine)
          else null,
        quoted = if (current) quote(t.path, t.side, t.startLine, t.endLine) else emptyList(),
      )
  }

  fun discard(item: InlineItem.Unsent) {
    scope.launch {
      draftRepository.delete(DraftKey(key.accountId, key.repo, key.number, item.slot))
    }
  }

  fun editDraft(draft: DraftComment) {
    val a = draft.anchor
    val thread = draft.inReplyTo
    composer =
      if (thread != null) {
        ComposerTarget.Reply(thread, a.path, draft.id, draft.body, anchor = a)
      } else {
        ComposerTarget.Line(
          a.path,
          a.startSide,
          a.startLine,
          a.side,
          a.endLine,
          draft.id,
          draft.body,
          suggestion =
            if (a.startSide == DiffSide.New && a.side == DiffSide.New)
              lines(a.path, a.side, a.startLine, a.endLine)
            else null,
          quoted = quote(a.path, a.side, a.startLine, a.endLine),
          commitOid = a.commitOid,
        )
      }
  }

  /** Whether a reply to thread [threadId] is drafted and not sent. */
  fun replyDrafted(threadId: String): Boolean = "${ComposerTarget.REPLY_SLOT}$threadId" in slots

  /** Starts a reply to [thread], or opens the one drafted. */
  fun startReply(thread: ReviewThreadWithComments) {
    composer = ComposerTarget.Reply(thread.thread.id, thread.thread.path)
  }

  /** Where the composer's text autosaves while it's open. */
  fun draftKey(target: ComposerTarget): DraftKey {
    if (target.draftId != 0L) return repository.pendingEditKey(key, target.draftId)
    val slot =
      when (target) {
        is ComposerTarget.Line -> target.slot
        is ComposerTarget.Reply -> "${ComposerTarget.REPLY_SLOT}${target.threadId}"
      }
    return DraftKey(key.accountId, key.repo, key.number, slot)
  }

  /** Closes the composer. Its text stays a draft. */
  fun closeComposer() {
    composer = null
    codeView?.selection = null
  }

  /**
   * Sends what the composer wrote: a line comment, or a reply during a review, goes into the
   * pending review, which this starts if it's the first; any other reply posts at once.
   */
  fun send(target: ComposerTarget, body: String) {
    val draftKey = draftKey(target)
    if (composer == target) closeComposer()
    when (target) {
      is ComposerTarget.Line -> {
        scope.launch {
          repository.saveDraft(key, target.anchor(), body, id = target.draftId)
          draftRepository.delete(draftKey)
        }
      }
      is ComposerTarget.Reply -> {
        val thread = threads.firstOrNull { it.thread.id == target.threadId }
        if (target.draftId == 0L && !inReview) {
          scope.launch { repository.reply(key, target.threadId, body, draftKey) }
          return
        }
        val head = load.loader.value?.newCommit ?: overview?.headOid ?: return
        val t = thread?.thread
        val line = (t?.line ?: t?.originalLine ?: 1).coerceAtLeast(1)
        val side = t?.side?.let(DiffSide::valueOf) ?: DiffSide.New
        scope.launch {
          repository.saveDraft(
            key,
            target.anchor ?: ReviewAnchor(head, target.path, side, line, line, side),
            body,
            id = target.draftId,
            inReplyTo = target.threadId,
          )
          draftRepository.delete(draftKey)
        }
      }
    }
  }

  /** The send action's label for [target]. */
  fun sendLabel(target: ComposerTarget): String =
    when {
      target.draftId != 0L -> "Update"
      target is ComposerTarget.Reply && !inReview -> "Reply"
      !inReview -> "Start review"
      else -> "Add to review"
    }

  fun deleteDraft(draft: DraftComment) {
    scope.launch { repository.deleteDraft(key, draft.id) }
  }

  /** Resolves [thread] and folds it away, or unresolves it. */
  fun setResolved(thread: ReviewThreadWithComments, resolved: Boolean) {
    unfoldedThreads.remove(thread.thread.id)
    scope.launch { repository.setResolved(key, thread.thread.id, resolved) }
  }

  fun continuePendingReview() {
    scope.launch { repository.continuePendingReview(key) }
  }

  fun discardPendingReview() {
    scope.launch { repository.discardPendingReview(key) }
  }

  /**
   * Lines [start] to [end] of [path] on [side] as they are, for a suggestion to show as a diff
   * against; null if the file isn't loaded.
   */
  fun lines(path: String, side: DiffSide, start: Int, end: Int): String? {
    val rows = codeView?.rows as? ReviewSnapshot ?: return null
    val file = fileIndex(path).takeIf { it >= 0 && rows.fileLoaded(it) } ?: return null
    if (start < 1 || end < start) return null
    return (start..end).joinToString("\n") { rows.lineText(file, side, it) }
  }

  /** What a thread's suggestions change, where its lines are still on the diff. */
  fun suggestionBase(thread: ReviewThreadWithComments): String? {
    val t = thread.thread
    if (t.isOutdated || t.isFileLevel) return null
    val end = t.line ?: return null
    return lines(t.path, DiffSide.valueOf(t.side), t.startLine ?: end, end)
  }

  private fun quote(path: String, side: DiffSide, start: Int, end: Int): List<QuotedLine> {
    val text = lines(path, side, start, end) ?: return emptyList()
    val kind = if (side == DiffSide.Old) RowKind.Removed else RowKind.Context
    return text.split('\n').mapIndexed { i, line -> QuotedLine(kind, start + i, line) }
  }

  fun saveSummary(text: String) {
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
    const val UNSENT_IDS = 2_000_000
    val JUMP_TIMEOUT = 5.seconds
    /** Every draft slot the changes screen writes. */
    const val SLOT_PREFIX = "review/"
  }
}
