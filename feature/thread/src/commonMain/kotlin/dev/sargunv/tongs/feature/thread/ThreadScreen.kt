package dev.sargunv.tongs.feature.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.sargunv.tongs.core.data.InboxRepository
import dev.sargunv.tongs.core.data.ThreadKey
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.forge.api.SubjectType
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the thread screen knows before threads load: the notification's title and link. */
data class ThreadSummary(val title: String?, val webUrl: String?)

@AssistedInject
class ThreadViewModel(@Assisted val route: Route.Thread, inbox: InboxRepository) : ViewModel() {
  val summary: StateFlow<ThreadSummary> =
    (route.notificationId?.let { id ->
        inbox.thread(ThreadKey(route.accountId, id)).map { ThreadSummary(it?.title, it?.webUrl) }
      } ?: flowOf(ThreadSummary(null, null)))
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadSummary(null, null))

  @AssistedFactory
  fun interface Factory {
    fun create(route: Route.Thread): ThreadViewModel
  }
}

/**
 * An issue or PR conversation. A placeholder for now: the subject's title, a link out, and for a
 * PR, its changes. The conversation itself (summary card, description, timeline, composer) is the
 * thread team's to build here.
 */
@Composable
fun ThreadScreen(
  viewModel: ThreadViewModel,
  onBack: (() -> Unit)?,
  onOpenChanges: (() -> Unit)?,
) {
  val summary by viewModel.summary.collectAsStateWithLifecycle()
  val route = viewModel.route
  val number = route.number?.let { "#$it" }
  val uriHandler = LocalUriHandler.current
  Scaffold(
    topBar = {
      TongsTopAppBar(
        title = number ?: route.repo.toString(),
        subtitle = route.repo.toString().takeIf { number != null },
        onBack = onBack,
      )
    },
  ) { padding ->
    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier =
        Modifier.padding(padding)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
      Text(
        summary.title ?: "${route.repo} ${number.orEmpty()}",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.widthIn(max = 720.dp),
      )
      Text(
        "The conversation will show here. For now, read it on the web.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (onOpenChanges != null) {
          Button(onClick = onOpenChanges) {
            Icon(TongsIcons.Changes, null, Modifier.size(ButtonDefaults.IconSize))
            Text("Changes", Modifier.padding(start = ButtonDefaults.IconSpacing))
          }
        }
        summary.webUrl?.let { url ->
          OutlinedButton(onClick = { uriHandler.openUri(url) }) {
            Icon(TongsIcons.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
            Text("Open on GitHub", Modifier.padding(start = ButtonDefaults.IconSpacing))
          }
        }
      }
    }
  }
}

internal val Route.Thread.isPullRequest: Boolean
  get() = SubjectType.of(subjectType) == SubjectType.PullRequest && number != null
