package dev.sargunv.tongs.feature.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.tongs.core.composer.MarkdownFormatting
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.InboxRepository
import dev.sargunv.tongs.core.data.ThreadKey
import dev.sargunv.tongs.core.data.ThreadRef
import dev.sargunv.tongs.core.data.ThreadRepository
import dev.sargunv.tongs.core.data.ThreadSnapshot
import dev.sargunv.tongs.core.data.ThreadStatus
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.forge.api.Instance
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.SubjectType
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the thread screen shows. */
data class ThreadUiState(
  /** Null until the thread is in the cache: the first open of a thread not seen before. */
  val snapshot: ThreadSnapshot? = null,
  val status: ThreadStatus = ThreadStatus(),
  /** Whether there's an unsent reply. */
  val hasDraft: Boolean = false,
  /** The notification's title, shown until the thread loads. */
  val notificationTitle: String? = null,
  /** The instance's web URL, for links. */
  val webUrl: String = Instance.GitHubDotCom.webUrl,
)

/** What the thread screen can do; a fake one lets screenshots render it without a database. */
interface ThreadActions {
  fun refresh()

  fun loadHidden()

  /** Opens the composer. */
  fun reply()

  /** Quotes [markdown] by [author] into the reply draft and opens the composer. */
  fun quote(markdown: String, author: String?)

  fun react(subjectId: String, reaction: Reaction)

  fun setState(close: Boolean, reason: StateReason? = null)

  fun merge(method: MergeMethod)

  object None : ThreadActions {
    override fun refresh() {}

    override fun loadHidden() {}

    override fun reply() {}

    override fun quote(markdown: String, author: String?) {}

    override fun react(subjectId: String, reaction: Reaction) {}

    override fun setState(close: Boolean, reason: StateReason?) {}

    override fun merge(method: MergeMethod) {}
  }
}

@AssistedInject
class ThreadViewModel(
  @Assisted val route: Route.Thread,
  @Assisted private val openReply: () -> Unit,
  private val threads: ThreadRepository,
  private val drafts: DraftRepository,
  inbox: InboxRepository,
  accounts: AccountRepository,
) : ViewModel(), ThreadActions {
  /** Null for subjects that aren't issues or PRs, like releases. */
  val ref: ThreadRef? =
    route.number
      ?.takeIf {
        SubjectType.of(route.subjectType).let {
          it !is SubjectType.Release && it !is SubjectType.Commit
        }
      }
      ?.let { ThreadRef(route.accountId, route.repo, it) }

  val failures: SharedFlow<String> = threads.failures

  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<ThreadUiState> = run {
    val title: Flow<String?> =
      route.notificationId?.let { id ->
        inbox.thread(ThreadKey(route.accountId, id)).map { it?.title }
      } ?: flowOf(null)
    val webUrl = flow { emit(accounts.account(route.accountId)?.instance?.webUrl) }
    val thread: Flow<Triple<ThreadSnapshot?, ThreadStatus, Boolean>> =
      if (ref == null) flowOf(Triple(null, ThreadStatus(), false))
      else
        flow { emit(threads.status(ref)) }
          .flatMapLatest { status ->
            combine(
              threads.observe(ref),
              status,
              drafts.hasDraft(ref.accountId, ref.repo, ref.number),
            ) { snapshot, s, draft ->
              Triple(snapshot, s, draft)
            }
          }
    combine(thread, title, webUrl) { (snapshot, status, draft), title, web ->
        ThreadUiState(
          snapshot,
          status,
          draft,
          title,
          web ?: Instance.GitHubDotCom.webUrl,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())
  }

  init {
    refresh()
  }

  override fun refresh() {
    ref ?: return
    viewModelScope.launch { threads.refresh(ref) }
  }

  override fun loadHidden() {
    ref ?: return
    viewModelScope.launch { threads.loadHidden(ref) }
  }

  override fun reply() = openReply()

  override fun quote(markdown: String, author: String?) {
    ref ?: return
    viewModelScope.launch {
      drafts.append(ref.replyDraft, MarkdownFormatting.quote(markdown, author).trimEnd() + "\n\n")
      openReply()
    }
  }

  override fun react(subjectId: String, reaction: Reaction) {
    ref ?: return
    viewModelScope.launch { threads.react(ref, subjectId, reaction) }
  }

  override fun setState(close: Boolean, reason: StateReason?) {
    ref ?: return
    viewModelScope.launch { threads.setState(ref, close, reason) }
  }

  override fun merge(method: MergeMethod) {
    ref ?: return
    viewModelScope.launch { threads.merge(ref, method) }
  }

  @AssistedFactory
  fun interface Factory {
    fun create(route: Route.Thread, openReply: () -> Unit): ThreadViewModel
  }
}

/** The composer replying to a thread: its detail, for the title and the close action. */
@AssistedInject
class ReplyViewModel(
  @Assisted val route: Route.ThreadReply,
  private val threads: ThreadRepository,
  val drafts: DraftRepository,
  accounts: AccountRepository,
  private val appScope: CoroutineScope,
) : ViewModel() {
  val ref = ThreadRef(route.thread.accountId, route.thread.repo, route.thread.number ?: 0)

  val snapshot: StateFlow<ThreadSnapshot?> =
    threads.observe(ref).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  val webUrl: StateFlow<String> = flow {
    emit(accounts.account(ref.accountId)?.instance?.webUrl ?: Instance.GitHubDotCom.webUrl)
  }
    .stateIn(viewModelScope, SharingStarted.Eagerly, Instance.GitHubDotCom.webUrl)

  private var sent = false

  /** Saves and queues once per composer, in the app scope so dismissal cannot cancel it. */
  fun send(body: String, close: Boolean = false, reopen: Boolean = false): Boolean {
    if (sent) return false
    sent = true
    appScope.launch {
      threads.comment(
        ref,
        body,
        close = if (close) StateReason.Completed else null,
        reopen = reopen,
      )
    }
    return true
  }

  @AssistedFactory
  fun interface Factory {
    fun create(route: Route.ThreadReply): ReplyViewModel
  }
}
