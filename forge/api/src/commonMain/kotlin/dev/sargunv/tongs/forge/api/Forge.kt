package dev.sargunv.tongs.forge.api

import kotlinx.serialization.Serializable

/** The software an instance runs. Each kind has one [ForgeAdapter]. */
enum class ForgeKind {
  GitHub,
  Forgejo,
}

/**
 * One server: github.com, a GitHub Enterprise Server, codeberg.org, or another Forgejo. Codeberg is
 * just a Forgejo instance, and GHES is just a GitHub instance with another URL.
 */
@Serializable
data class Instance(val kind: ForgeKind, val webUrl: String, val apiUrl: String) {
  val host: String
    get() = webUrl.substringAfter("://").substringBefore('/')

  companion object {
    val GitHubDotCom = Instance(ForgeKind.GitHub, "https://github.com", "https://api.github.com")
  }
}

/** A repository on an instance, by name. */
@Serializable
data class RepoRef(val owner: String, val name: String) {
  override fun toString() = "$owner/$name"
}

/**
 * What an account can do on its instance. These come from the forge, its version and settings, and
 * the auth method: a fine-grained GitHub PAT, for one, can't read notifications. The UI checks
 * these rather than the forge kind.
 */
enum class Capability(val id: String) {
  /** Read the notification inbox. */
  Inbox("inbox"),
  /** Notifications say why you're subscribed. */
  InboxReasons("inbox.reasons"),
  /** Mark a thread done, removing it from the inbox until something new happens. */
  InboxDone("inbox.done"),
  /** Mark a read thread unread on the forge. Without it, unread is local to the device. */
  InboxMarkUnread("inbox.markUnread"),
  /** Fetch git objects by OID with filters, which the review engine needs. */
  GitPartialClone("git.partialClone"),
}
