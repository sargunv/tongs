package dev.sargunv.tongs.core.navigation

import androidx.navigation3.runtime.NavKey
import dev.sargunv.tongs.forge.api.RepoRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Every screen in the app. The back stack is a plain list of these, so deep links and state
 * restoration are just lists. Add a screen by adding a subtype here and an entry for it in its
 * feature's [RouteEntries].
 */
@Serializable
sealed interface Route : NavKey {
  /** How the screen sits in an adaptive layout. */
  val pane: Pane

  /** A destination in the navigation bar or rail; the bottom of every back stack. */
  @Serializable
  sealed interface TopLevel : Route {
    override val pane: Pane
      get() = Pane.List
  }

  @Serializable data object Inbox : TopLevel

  /** The activity feed. Its issues and PRs open beside it, as the inbox's threads do. */
  @Serializable data object Activity : TopLevel

  @Serializable
  data object Settings : TopLevel {
    override val pane: Pane
      get() = Pane.Full
  }

  /** Adds an account. The app's only screen until there is one. */
  @Serializable
  data object SignIn : Route {
    override val pane: Pane
      get() = Pane.Full
  }

  /**
   * An issue or PR conversation, or another notification subject. [notificationId] is set when it's
   * opened from the inbox, which supplies its title until the thread is loaded; from the activity
   * feed, it's null.
   */
  @Serializable
  data class Thread(
    val accountId: Long,
    val repo: RepoRef,
    /** The raw subject type: `PullRequest`, `Issue`, and so on. */
    val subjectType: String,
    val number: Int?,
    val notificationId: String? = null,
  ) : Route {
    override val pane: Pane
      get() = Pane.Detail
  }

  /**
   * The composer replying to [thread]. Beside the thread on wide windows, hiding the list; the
   * whole screen on a phone. Its text is the thread's reply draft, so quoting appends to that draft
   * before opening this.
   */
  @Serializable
  data class ThreadReply(val thread: Thread) : Route {
    override val pane: Pane
      get() = Pane.Extra
  }

  /** A PR's changes: one virtual surface over every file. Takes the whole window. */
  @Serializable
  data class PullRequestChanges(val accountId: Long, val repo: RepoRef, val number: Int) : Route {
    override val pane: Pane
      get() = Pane.Full
  }
}

/** Where a route's screen goes in the window. */
enum class Pane {
  /** A list; on wide windows, its [Detail] screens open beside it. */
  List,
  /** An item from the list beside it; on narrow windows, it covers the list. */
  Detail,
  /**
   * Beside a [Detail] screen, as a composer beside its thread: the list makes way for it on wide
   * windows, and it covers everything on narrow ones.
   */
  Extra,
  /** The whole window, beside only the navigation rail. */
  Full,
}

/** Lets the back stack save and restore [Route]s on every platform. */
val RouteSerializers = SerializersModule {
  polymorphic(NavKey::class) { subclassesOfSealed<Route>() }
}
