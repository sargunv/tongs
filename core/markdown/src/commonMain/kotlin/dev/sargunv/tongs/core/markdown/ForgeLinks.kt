package dev.sargunv.tongs.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ThreadKind

/** Something on the forge a web URL points at, which the app may open itself. */
sealed interface ForgeLink {
  /**
   * An issue or PR. [kind] is [ThreadKind.PullRequest] for `/pull/` links and null for `/issues/`
   * ones: GitHub redirects `/issues/N` to the PR when N is one, so the URL can't tell.
   */
  data class Thread(val repo: RepoRef, val number: Int, val kind: ThreadKind?) : ForgeLink

  data class User(val login: String) : ForgeLink

  data class Commit(val repo: RepoRef, val oid: String) : ForgeLink
}

/** Top-level paths on GitHub that aren't users or orgs. */
private val RESERVED =
  setOf(
    "about",
    "apps",
    "codespaces",
    "collections",
    "contact",
    "customer-stories",
    "dashboard",
    "enterprise",
    "events",
    "explore",
    "features",
    "gist",
    "issues",
    "login",
    "logout",
    "marketplace",
    "new",
    "notifications",
    "orgs",
    "organizations",
    "pricing",
    "pulls",
    "search",
    "security",
    "settings",
    "site",
    "sponsors",
    "topics",
    "trending",
    "user-attachments",
    "users",
  )

private val SHA = Regex("[0-9a-fA-F]{7,40}")

/**
 * Parses a link on the instance at [webUrl] (`https://github.com/o/r/pull/1`, `/issues/1`,
 * `/o/r/commit/<sha>`, `/<login>`) into what it points at, or null if it's elsewhere or something
 * else. Query strings and fragments are ignored, so a comment's permalink opens its thread.
 */
fun parseForgeLink(webUrl: String, url: String): ForgeLink? {
  val base = webUrl.trimEnd('/')
  val rest =
    when {
      url.startsWith("$base/") -> url.removePrefix("$base/")
      // Links that came through as http or without the scheme.
      url.substringAfter("://", url).startsWith(base.substringAfter("://") + "/") ->
        url.substringAfter("://", url).removePrefix(base.substringAfter("://") + "/")
      else -> return null
    }
  val path = rest.substringBefore('#').substringBefore('?').trimEnd('/')
  val parts = path.split('/').filter { it.isNotEmpty() }
  if (parts.size == 1) {
    val login = parts[0]
    return if (login.lowercase() in RESERVED || !login.matches(Regex("[A-Za-z0-9-]+"))) null
    else ForgeLink.User(login)
  }
  if (parts.size < 4 || parts[0].lowercase() in RESERVED) return null
  val repo = RepoRef(parts[0], parts[1])
  return when (parts[2]) {
    "pull",
    "pulls" -> parts[3].toIntOrNull()?.let { ForgeLink.Thread(repo, it, ThreadKind.PullRequest) }
    "issues" -> parts[3].toIntOrNull()?.let { ForgeLink.Thread(repo, it, kind = null) }
    "commit",
    "commits" -> parts[3].takeIf { it.matches(SHA) }?.let { ForgeLink.Commit(repo, it) }
    else -> null
  }
}

/**
 * Routes link clicks inside [content], which Markdown and other text send through
 * [LocalUriHandler]: issue and PR links on the instance at [webUrl] go to [onThread], and
 * everything else to the platform's handler, which opens a browser.
 */
@Composable
fun ProvideForgeLinks(
  webUrl: String,
  onThread: (ForgeLink.Thread) -> Unit,
  content: @Composable () -> Unit,
) {
  val platform = LocalUriHandler.current
  val currentOnThread = rememberUpdatedState(onThread)
  val handler =
    remember(platform, webUrl) {
      object : UriHandler {
        override fun openUri(uri: String) {
          when (val link = parseForgeLink(webUrl, uri)) {
            is ForgeLink.Thread -> currentOnThread.value(link)
            else -> platform.openUri(uri)
          }
        }
      }
    }
  CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
