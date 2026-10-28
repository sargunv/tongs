package dev.sargunv.tongs.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.InboxSettings
import dev.sargunv.tongs.core.data.SettingsRepository
import dev.sargunv.tongs.core.data.SignedInAccount
import dev.sargunv.tongs.core.data.SwipeAction
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ListRow
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.Capability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Inject
class SettingsViewModel(
  private val accounts: AccountRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  val accountList: StateFlow<List<SignedInAccount>> =
    accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val inbox: StateFlow<InboxSettings> =
    settings.inbox.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxSettings())

  fun setSwipeLeft(action: SwipeAction) {
    viewModelScope.launch { settings.setSwipeLeft(action) }
  }

  fun setSwipeRight(action: SwipeAction) {
    viewModelScope.launch { settings.setSwipeRight(action) }
  }

  fun signOut(accountId: Long) {
    viewModelScope.launch { accounts.signOut(accountId) }
  }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onAddAccount: () -> Unit) {
  val accounts by viewModel.accountList.collectAsStateWithLifecycle()
  val inbox by viewModel.inbox.collectAsStateWithLifecycle()
  Scaffold(topBar = { TongsTopAppBar(title = "Settings") }) { padding ->
    Box(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(Modifier.widthIn(max = 720.dp)) {
        SectionHeader("Accounts")
        for (signedIn in accounts) {
          val account = signedIn.account
          ListRow(
            overline = account.instance.host,
            headline = account.name?.let { "$it (${account.login})" } ?: account.login,
            supporting = accountDetail(signedIn),
            leading = { Icon(TongsIcons.Account, null) },
            trailing = {
              ActionIcon(TongsIcons.SignOut, "Sign out", { viewModel.signOut(account.id.value) })
            },
            onClick = {},
          )
        }
        ListRow(
          headline = "Add account",
          leading = { Icon(TongsIcons.Add, null) },
          onClick = onAddAccount,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SectionHeader("Inbox")
        SwipeSetting("Swipe left", inbox.swipeLeft, viewModel::setSwipeLeft)
        SwipeSetting("Swipe right", inbox.swipeRight, viewModel::setSwipeRight)
      }
    }
  }
}

private fun accountDetail(signedIn: SignedInAccount): String {
  val account = signedIn.account
  val method =
    when (account.authMethod) {
      AuthMethod.ClassicToken -> "Classic token"
      AuthMethod.ScopedToken -> "Fine-grained token"
      AuthMethod.OAuth -> "OAuth token"
    }
  return when {
    Capability.Inbox !in account.capabilities -> "$method · this token can't read notifications"
    signedIn.missingScopes.isNotEmpty() ->
      "$method · missing ${signedIn.missingScopes.joinToString()}"
    else -> method
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

private val SwipeAction.label
  get() =
    when (this) {
      SwipeAction.Done -> "Done"
      SwipeAction.Snooze -> "Snooze"
      SwipeAction.ToggleRead -> "Read or unread"
      SwipeAction.None -> "Nothing"
    }

@Composable
private fun SwipeSetting(title: String, value: SwipeAction, onChange: (SwipeAction) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    ListRow(headline = title, supporting = value.label, onClick = { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for (action in SwipeAction.entries) {
        DropdownMenuItem(
          text = { Text(action.label) },
          onClick = {
            onChange(action)
            open = false
          },
          leadingIcon = { if (action == value) Icon(TongsIcons.Done, null) },
        )
      }
    }
  }
}

@ContributesIntoSet(AppScope::class)
@Inject
class SettingsEntries(private val viewModel: Provider<SettingsViewModel>) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.Settings> {
      val navigator = LocalNavigator.current
      SettingsScreen(
        viewModel = viewModel { viewModel() },
        onAddAccount = { navigator.open(Route.SignIn) },
      )
    }
  }
}
