package dev.sargunv.tongs.feature.feed

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.sargunv.tongs.core.data.AccountSyncStatus
import dev.sargunv.tongs.core.data.FeedData
import dev.sargunv.tongs.core.data.FeedEventEntity
import dev.sargunv.tongs.core.data.FeedGrouping
import dev.sargunv.tongs.core.data.FeedItem
import dev.sargunv.tongs.core.data.MuteKind
import dev.sargunv.tongs.core.data.SyncProblem
import dev.sargunv.tongs.core.data.aggregateFeed
import dev.sargunv.tongs.core.navigation.Route
import dev.sargunv.tongs.core.navigation.route
import dev.sargunv.tongs.core.ui.EmptyState
import dev.sargunv.tongs.core.ui.NavigationItem
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.TongsNavigationSuite
import dev.sargunv.tongs.core.ui.TongsTheme
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Made-up activity rendered at folded, unfolded, and desktop widths, with no network. */
@OptIn(ExperimentalTestApi::class)
class FeedScreenshotTest {
  private val now = Instant.parse("2026-10-28T19:00:00Z")

  private fun event(
    id: String,
    kind: String,
    actor: String = "alice",
    repo: String = "hello-world",
    ago: Int = id.toInt(),
    number: Int? = null,
    title: String? = null,
    state: String? = null,
    ref: String? = null,
    commits: String? = null,
    count: Int? = null,
    excerpt: String? = null,
  ) =
    FeedEventEntity(
      accountId = 1,
      id = id,
      kind = kind,
      kindRaw = "${kind}Event",
      action = "Opened",
      actionRaw = "opened",
      actorLogin = actor,
      actorAvatarUrl = null,
      repoOwner = "octo-org",
      repoName = repo,
      createdAt = (now - ago.minutes).toEpochMilliseconds(),
      isPublic = true,
      subjectType = number?.let { if (kind == "Issue") "Issue" else "PullRequest" },
      subjectNumber = number,
      subjectTitle = title,
      subjectState = state,
      ref = ref,
      refType = null,
      before = "base$id",
      head = "head$id",
      commits = commits,
      commitCount = count,
      pushHydrated = true,
      title = title,
      excerpt = excerpt,
      reviewState = null,
      target = null,
      webUrl = "https://github.com/octo-org/$repo",
    )

  private val events =
    listOf(
      event(
        "1",
        "PullRequest",
        number = 412,
        title = "Stream packs into the object store as they arrive",
        state = "OPEN",
        excerpt = "The first objects are ready before the download finishes.",
      ),
      event(
        "2",
        "Push",
        ref = "main",
        commits =
          """[{"sha":"f19ac81","title":"Keep the pack reader off the UI thread"},{"sha":"3be0a2c","title":"Stream objects as they arrive"}]""",
        count = 2,
      ),
      event(
        "3",
        "Push",
        ref = "main",
        commits = """[{"sha":"8d204bc","title":"Handle a cancelled fetch"}]""",
        count = 1,
      ),
      event(
        "4",
        "Push",
        ref = "main",
        commits = """[{"sha":"c614d0e","title":"Add fetch progress"}]""",
        count = 1,
      ),
      event("5", "Star", actor = "bob"),
      event("6", "Star", actor = "carol"),
      event("7", "Star", actor = "dana"),
      event(
        "8",
        "Issue",
        actor = "bob",
        repo = "codeview",
        number = 409,
        title = "Keep wide characters on the column grid",
        state = "OPEN",
        excerpt = "The cursor should stay aligned when a line contains emoji.",
      ),
      event("9", "Release", repo = "codeview", title = "Codeview 0.3.0", ref = "v0.3.0"),
      event("10", "Fork", actor = "erin", repo = "codeview"),
      event("11", "Fork", actor = "frank", repo = "codeview"),
    )
  private val items = aggregateFeed(events, emptyMap(), webUrls = mapOf(1L to "https://github.com"))
  private val synced =
    AccountSyncStatus(1, "alice", null, emptyList(), (now - 2.minutes).toEpochMilliseconds())
  private val state =
    FeedUiState(
      FeedData(items, events.size, events.size, 0),
      sync = listOf(synced),
      accounts = listOf(FeedAccount(1, "alice")),
    )

  private fun capture(
    name: String,
    state: FeedUiState = this.state,
    width: Int = 480,
    height: Int = 720,
    dark: Boolean = false,
    shell: Boolean = false,
  ) =
    runDesktopComposeUiTest(width = width, height = height) {
      setContent {
        TongsTheme(darkTheme = dark) {
          val feed: @Composable () -> Unit = {
            FeedContent(state, now, { false }, object : FeedActions {})
          }
          if (shell) ActivityShell(feed) else feed()
        }
      }
      onAllNodes(isRoot()).onLast().captureRoboImage("src/jvmTest/snapshots/$name.png")
    }

  @Test fun folded() = capture("feed-folded", width = 360, shell = true)

  @Test fun unfolded() = capture("feed-unfolded", width = 700, shell = true)

  @Test fun desktop() = capture("feed-desktop", width = 1200, height = 800, shell = true)

  @Test fun dark() = capture("feed-dark", dark = true)

  @Test fun groupedByRepo() = capture("feed-grouped-repo", state.copy(grouping = FeedGrouping.Repo))

  @Test
  fun groupedByPerson() = capture("feed-grouped-person", state.copy(grouping = FeedGrouping.Person))

  @Test
  fun burst() =
    capture(
      "feed-burst",
      state.copy(data = FeedData(items.filterIsInstance<FeedItem.Push>(), 3, 3, 0)),
      width = 360,
      height = 320,
    )

  @Test fun empty() = capture("feed-empty", state.copy(data = FeedData(emptyList(), 0, 0, 0)))

  @Test fun muted() = capture("feed-muted", state.copy(data = FeedData(emptyList(), 11, 11, 11)))

  @Test
  fun olderActivityIsReachableWhenTheLoadedPageIsAllMuted() =
    runDesktopComposeUiTest(width = 360, height = 720) {
      var data by mutableStateOf(FeedData(emptyList(), 1501, 1500, 1500))
      var loads = 0
      val older = items.filterIsInstance<FeedItem.Subject>().first()
      val actions =
        object : FeedActions {
          override fun loadMore() {
            loads++
            data = FeedData(listOf(older), 1501, 1501, 1500)
          }
        }
      setContent { TongsTheme { FeedContent(state.copy(data = data), now, { false }, actions) } }
      onNodeWithText("Everything here is muted").assertExists()
      onNodeWithText("Show muted").assertExists()
      onAllNodes(isRoot()).onLast().captureRoboImage("src/jvmTest/snapshots/feed-muted-older.png")
      onNodeWithText("Show older activity").performClick()
      assertEquals(1, loads)
      onNodeWithText("Stream packs into the object store as they arrive").assertExists()
      onNodeWithText("Show older activity").assertDoesNotExist()
    }

  @Test
  fun offline() =
    capture("feed-offline", state.copy(sync = listOf(problem(SyncProblem.Kind.Offline))))

  @Test
  fun rateLimited() =
    capture("feed-rate-limited", state.copy(sync = listOf(problem(SyncProblem.Kind.RateLimited))))

  @Test
  fun noAccess() =
    capture(
      "feed-no-access",
      state.copy(
        data = FeedData(emptyList(), 0, 0, 0),
        sync = listOf(problem(SyncProblem.Kind.NoAccess).copy(lastSyncedAt = null)),
      ),
    )

  /** The app's navigation suite and medium-width list/detail strategy around the feed. */
  @Composable
  private fun ActivityShell(feed: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
      TongsNavigationSuite(
        listOf(
          NavigationItem("Inbox", TongsIcons.Inbox, TongsIcons.InboxFilled, false) {},
          NavigationItem("Activity", TongsIcons.Activity, TongsIcons.ActivityFilled, true) {},
          NavigationItem("Settings", TongsIcons.Settings, TongsIcons.SettingsFilled, false) {},
        ),
        showNavigation = true,
      ) {
        BoxWithConstraints {
          val directive =
            calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2())
              .copy(horizontalPartitionSpacerSize = 0.dp)
          val expansion =
            rememberPaneExpansionState(
              anchors =
                remember {
                  listOf(0.3f, 0.4f, 0.5f, 0.6f).map { PaneExpansionAnchor.Proportion(it) }
                },
              initialAnchoredIndex = if (maxWidth < 760.dp) 2 else 1,
            )
          NavDisplay(
            backStack = listOf<NavKey>(Route.Activity),
            onBack = {},
            sceneStrategies =
              listOf(
                rememberListDetailSceneStrategy<NavKey>(
                  directive = directive,
                  paneExpansionState = expansion,
                ),
              ),
            entryProvider =
              entryProvider {
                route<Route.Activity>(
                  detailPlaceholder = {
                    EmptyState(
                      TongsIcons.Activity,
                      "Nothing open",
                      body = "Pick an issue or pull request to read it here.",
                    )
                  },
                ) {
                  feed()
                }
              },
          )
        }
      }
    }
  }

  private fun problem(kind: SyncProblem.Kind) =
    synced.copy(
      problem =
        SyncProblem(kind, "", sso = null, retryAt = (now + 23.minutes).toEpochMilliseconds()),
    )

  @Test
  fun menusGroupAndMuteAndSubjectsOpenTheThreadRoute() = runDesktopComposeUiTest {
    var grouping: FeedGrouping? = null
    var muted: Pair<MuteKind, String>? = null
    var opened: FeedItem? = null
    val actions =
      object : FeedActions {
        override fun open(item: FeedItem) {
          opened = item
        }

        override fun setGrouping(value: FeedGrouping) {
          grouping = value
        }

        override fun mute(accountId: Long, kind: MuteKind, value: String) {
          muted = kind to value
        }
      }
    setContent { TongsTheme { FeedContent(state, now, { false }, actions) } }
    onNodeWithContentDescription("More").performClick()
    onNodeWithText("Group by person").performClick()
    assertEquals(FeedGrouping.Person, grouping)
    onNodeWithText("Stream packs into the object store as they arrive").performClick()
    val subject = items.filterIsInstance<FeedItem.Subject>().first()
    assertEquals(subject, opened)
    onNodeWithText("Stream packs into the object store as they arrive").performTouchInput {
      longClick()
    }
    onNodeWithText("Mute octo-org/hello-world").performClick()
    assertEquals(MuteKind.Repo to "octo-org/hello-world", muted)
    assertEquals(412, threadRoute(subject)?.number)
    assertEquals(null, threadRoute(items.filterIsInstance<FeedItem.Push>().first()))
  }
}
