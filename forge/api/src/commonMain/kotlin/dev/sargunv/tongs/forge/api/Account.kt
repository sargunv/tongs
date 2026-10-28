package dev.sargunv.tongs.forge.api

/** The app's own key for a signed-in account. */
data class AccountId(val value: Long)

/** A signed-in user on an instance. There may be several per instance. */
data class Account(
  val id: AccountId,
  val instance: Instance,
  /** The forge's ID for the user, which survives renames. */
  val userId: String,
  val login: String,
  val name: String?,
  val avatarUrl: String?,
  val authMethod: AuthMethod,
  val capabilities: Set<Capability>,
)

enum class AuthMethod {
  /** A GitHub classic personal access token. */
  ClassicToken,
  /** A GitHub fine-grained token, or a Forgejo scoped token. */
  ScopedToken,
  /** An OAuth access token, from the device flow or a browser redirect. */
  OAuth,
}
