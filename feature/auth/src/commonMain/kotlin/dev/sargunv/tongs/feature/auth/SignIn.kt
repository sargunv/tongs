package dev.sargunv.tongs.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.AppEnvironment
import dev.sargunv.tongs.core.navigation.LocalNavigator
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.RouteEntries
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsTopAppBar
import dev.sargunv.tongs.forge.api.AuthException
import dev.sargunv.tongs.forge.api.Instance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SignInState {
  data object Idle : SignInState

  data object Checking : SignInState

  data class Failed(val message: String) : SignInState

  /** Signed in, but the token lacks scopes the app asks for. */
  data class MissingScopes(val login: String, val scopes: Set<String>) : SignInState

  data object Done : SignInState
}

/**
 * Signs in to GitHub with a classic personal access token. The OAuth device flow is modeled in the
 * auth layer (`AuthProvider.deviceFlow`) but has no UI until there's an OAuth App to run it.
 */
@Inject
class SignInViewModel(
  private val accounts: AccountRepository,
  private val environment: AppEnvironment,
) : ViewModel() {
  private val instance = Instance.GitHubDotCom
  private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
  val state: StateFlow<SignInState> = _state

  val auth = accounts.auth(instance.kind)
  val newTokenUrl = auth.newTokenUrl(instance)

  /** Desktop only: the GitHub CLI's token, as a developer shortcut. */
  val canImportCliToken = environment.isDesktop

  fun signIn(token: String) {
    if (token.isBlank()) return
    _state.value = SignInState.Checking
    viewModelScope.launch {
      _state.value =
        try {
          val signedIn = accounts.signIn(instance, token)
          if (signedIn.missingScopes.isEmpty()) SignInState.Done
          else SignInState.MissingScopes(signedIn.account.login, signedIn.missingScopes)
        } catch (e: AuthException) {
          SignInState.Failed(e.message ?: "GitHub didn't accept this token.")
        } catch (e: Exception) {
          SignInState.Failed("Couldn't sign in: ${e.message}")
        }
    }
  }

  fun importCliToken() {
    viewModelScope.launch {
      val token = environment.localGitHubToken()
      if (token == null) {
        _state.value = SignInState.Failed("No token from gh auth token. Run gh auth login first.")
      } else {
        signIn(token)
      }
    }
  }

  fun acknowledge() {
    _state.value = SignInState.Done
  }
}

@Composable
fun SignInScreen(viewModel: SignInViewModel, onBack: (() -> Unit)?, onSignedIn: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  if (state == SignInState.Done) {
    LaunchedEffect(Unit) { onSignedIn() }
  }
  var token by rememberSaveable { mutableStateOf("") }
  val uriHandler = LocalUriHandler.current
  Scaffold(topBar = { TongsTopAppBar(title = "Add a GitHub account", onBack = onBack) }) { padding
    ->
    Box(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.widthIn(max = 560.dp).padding(24.dp),
      ) {
        Text("Sign in with a token", style = MaterialTheme.typography.headlineSmall)
        Text(
          "Tongs reads your notifications with a classic personal access token. Create one with " +
            "the ${viewModel.auth.tokenScopes.joinToString(", ")} scopes, then paste it here. " +
            "It's kept in your device's keystore.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { uriHandler.openUri(viewModel.newTokenUrl) }) {
          Icon(TongsIcons.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
          Text("Create a token on GitHub", Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          label = { Text("Personal access token") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
          isError = state is SignInState.Failed,
          supportingText = (state as? SignInState.Failed)?.let { { Text(it.message) } },
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = { viewModel.signIn(token) },
            enabled = token.isNotBlank() && state != SignInState.Checking,
          ) {
            Text("Sign in")
          }
          if (viewModel.canImportCliToken) {
            TextButton(
              onClick = viewModel::importCliToken,
              enabled = state != SignInState.Checking,
            ) {
              Icon(TongsIcons.Terminal, null, Modifier.size(ButtonDefaults.IconSize))
              Text("Use gh's token", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
          }
          if (state == SignInState.Checking) LoadingIndicator(Modifier.size(32.dp))
        }
        (state as? SignInState.MissingScopes)?.let { missing ->
          Text(
            "Signed in as ${missing.login}, but the token lacks " +
              "${missing.scopes.joinToString(", ")}. Some things won't work until " +
              "you add them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
          Button(onClick = viewModel::acknowledge) { Text("Continue") }
        }
      }
    }
  }
}

@ContributesIntoSet(AppScope::class)
@Inject
class AuthEntries(private val viewModel: Provider<SignInViewModel>) : RouteEntries {
  override fun EntryProviderScope<NavKey>.install() {
    route<Route.SignIn> {
      val navigator = LocalNavigator.current
      // Without another screen under it, sign-in is the app's first screen, with no way back.
      val canGoBack = navigator.topLevel != null
      SignInScreen(
        viewModel = viewModel { viewModel() },
        onBack = if (canGoBack) ({ navigator.back() }) else null,
        onSignedIn = { if (canGoBack) navigator.back() else navigator.reset(Route.Inbox) },
      )
    }
  }
}
