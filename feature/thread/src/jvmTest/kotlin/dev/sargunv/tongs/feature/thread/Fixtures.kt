package dev.sargunv.tongs.feature.thread

import dev.sargunv.tongs.core.data.ThreadSnapshot
import dev.sargunv.tongs.core.data.TimelineEntry
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.Check
import dev.sargunv.tongs.forge.api.CheckState
import dev.sargunv.tongs.forge.api.ChecksSummary
import dev.sargunv.tongs.forge.api.EventKind
import dev.sargunv.tongs.forge.api.Label
import dev.sargunv.tongs.forge.api.MergeMethod
import dev.sargunv.tongs.forge.api.Mergeable
import dev.sargunv.tongs.forge.api.PullRequestSummary
import dev.sargunv.tongs.forge.api.Reaction
import dev.sargunv.tongs.forge.api.Reactions
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewDecision
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.ReviewerVerdict
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadReference
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.api.ViewerAbilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Made-up threads for screenshots, shaped like real ones. Avatars are absent, so initials show. */
object Fixtures {
  val now = Instant.parse("2026-10-28T12:00:00Z")
  val repo = RepoRef("octo-org", "hello-world")

  private fun ago(d: Duration) = now - d

  private val mona = Actor("mona")
  private val hubot = Actor("hubot")
  private val lin = Actor("lin-yu")
  private val bot = Actor("dependabot", isBot = true)

  private val perf = Label("performance", "1d76db")
  private val engine = Label("engine", "fbca04")
  private val bug = Label("bug", "d73a4a")
  private val android = Label("android", "3ddc84")

  val pullRequest =
    ThreadDetail(
      id = "PR_412",
      repo = repo,
      number = 412,
      kind = ThreadKind.PullRequest,
      title = "Stream packs into the object store as they arrive",
      body =
        """
        Reviews of big PRs used to wait for the whole pack before showing anything. This writes each pack to the store as it streams in, so the first files show ~3× sooner.

        - Writes each pack to a temp file, then **renames** it into place
        - Indexes with `gix-pack` on a background thread

        Fixes #398. cc @lin-yu
        """
          .trimIndent(),
      author = mona,
      createdAt = ago(2.days),
      updatedAt = ago(20.minutes),
      webUrl = "https://github.com/octo-org/hello-world/pull/412",
      state = ThreadState.Open,
      labels = listOf(perf, engine),
      assignees = listOf(mona),
      reactions = listOf(Reactions(Reaction.Rocket, 3, true), Reactions(Reaction.Eyes, 1, false)),
      commentCount = 4,
      viewer =
        ViewerAbilities(
          canComment = true,
          canReact = true,
          canClose = true,
          mergeMethods = listOf(MergeMethod.Squash, MergeMethod.Merge),
          defaultMergeMethod = MergeMethod.Squash,
        ),
      pullRequest =
        PullRequestSummary(
          baseRef = "main",
          headRef = "stream-packs",
          headOid = "9f2c1e0",
          additions = 342,
          deletions = 87,
          changedFiles = 12,
          commitCount = 5,
          viewedFiles = 7,
          reviewDecision = ReviewDecision.Approved,
          mergeable = Mergeable.Yes,
          mergeStateRaw = "CLEAN",
          checks =
            ChecksSummary(
              CheckState.Success,
              passed = 14,
              failed = 0,
              pending = 0,
              skipped = 2,
              checks =
                listOf(
                  Check("CI / test (ubuntu)", CheckState.Success),
                  Check("CI / test (macos)", CheckState.Success),
                  Check("CI / android", CheckState.Success),
                ),
            ),
          reviews = listOf(ReviewerVerdict(lin, ReviewState.Approved)),
          requestedReviewers = listOf("hubot"),
        ),
    )

  private val prItems =
    listOf(
      TimelineItem.Commit(
        "C1",
        ago(47.hours),
        "a1b2c3d4",
        "Write packs to a temp file first",
        mona,
        checks = CheckState.Success,
      ),
      TimelineItem.Commit(
        "C2",
        ago(47.hours),
        "b2c3d4e5",
        "Index packs on a background thread",
        mona,
        checks = CheckState.Success,
      ),
      TimelineItem.Event("E1", ago(46.hours), EventKind.Labeled, mona, label = perf),
      TimelineItem.Event("E2", ago(46.hours), EventKind.Labeled, mona, label = engine),
      TimelineItem.Event("E3", ago(46.hours), EventKind.ReviewRequested, mona, subject = "lin-yu"),
      TimelineItem.Comment(
        "IC1",
        ago(30.hours),
        lin,
        "Nice! Does this change the store layout? Old caches should keep working.",
        reactions = listOf(Reactions(Reaction.ThumbsUp, 1, false)),
        viewerCanReact = true,
      ),
      TimelineItem.Comment(
        "IC2",
        ago(29.hours),
        mona,
        "> Does this change the store layout?\n\nNo, same layout. I added a test that opens a store from the last release.",
        viewerCanReact = true,
      ),
      TimelineItem.Review(
        "R1",
        ago(5.hours),
        lin,
        ReviewState.Approved,
        "Looks great. One nit inline.",
        viewerCanReact = true,
      ),
      TimelineItem.Event(
        "E4",
        ago(3.hours),
        EventKind.CrossReferenced,
        hubot,
        reference =
          ThreadReference(
            RepoRef("octo-org", "hello-world"),
            415,
            "Show files as soon as their blobs land",
            ThreadKind.PullRequest,
            ThreadState.Open,
          ),
      ),
      TimelineItem.Comment(
        "IC3",
        ago(20.minutes),
        bot,
        "Benchmarks: **first file 1.3 s** (was 4.1 s) on the 576k-line PR.",
        viewerCanReact = true,
      ),
    )

  private val reviewThreads =
    mapOf(
      "R1" to
        listOf(
          ReviewThread(
            id = "T1",
            reviewId = "R1",
            path = "engine-rs/engine/src/store.rs",
            line = 88,
            isResolved = false,
            isOutdated = false,
            diffHunk =
              "@@ -80,6 +80,9 @@\n     let tmp = dir.join(\"incoming\");\n-    fs::write(&tmp, &pack)?;\n+    let mut file = File::create(&tmp)?;\n+    file.write_all(&pack)?;\n+    file.sync_all()?;",
            comments =
              listOf(
                TimelineItem.Comment(
                  "RC1",
                  ago(5.hours),
                  lin,
                  "Nit: `sync_all` here costs ~8 ms per pack on Android. `sync_data` is enough.",
                ),
                TimelineItem.Comment("RC2", ago(4.hours), mona, "Good call, switched."),
              ),
          ),
        ),
    )

  val pullRequestSnapshot =
    ThreadSnapshot(
      detail = pullRequest,
      items = prItems.map { TimelineEntry(it) },
      gapIndex = 5,
      hiddenCount = 0,
      reviewThreads = reviewThreads,
      fetchedAt = now,
    )

  /** The same PR with a long timeline whose middle isn't loaded, and a reply sending. */
  val pullRequestWithGap =
    pullRequestSnapshot.copy(
      gapIndex = 5,
      hiddenCount = 37,
      items =
        prItems.map { TimelineEntry(it) } +
          TimelineEntry(
            TimelineItem.Comment(
              "local:1",
              ago(0.minutes),
              Actor("octocat"),
              "Thanks all, merging once CI is green.",
            ),
            pending = true,
          ),
    )

  val issue =
    ThreadDetail(
      id = "I_409",
      repo = repo,
      number = 409,
      kind = ThreadKind.Issue,
      title = "Crash when a grammar fails to extract on a full disk",
      body =
        """
        On a phone with < 50 MB free, opening a PR with a Zig file crashes the app.

        ```kotlin
        java.io.IOException: No space left on device
            at dev.sargunv.tongs.engine.NativeEngine.fillRows(Native Method)
        ```

        **Expected:** the file shows without highlighting.
        """
          .trimIndent(),
      author = hubot,
      createdAt = ago(6.days),
      updatedAt = ago(1.hours),
      webUrl = "https://github.com/octo-org/hello-world/issues/409",
      state = ThreadState.Closed,
      stateReason = StateReason.Completed,
      labels = listOf(bug, android),
      assignees = listOf(lin),
      milestone = "v0.3",
      commentCount = 2,
      viewer = ViewerAbilities(canComment = true, canReact = true, canReopen = true),
    )

  val issueSnapshot =
    ThreadSnapshot(
      detail = issue,
      items =
        listOf(
            TimelineItem.Event("IE1", ago(6.days), EventKind.Labeled, mona, label = bug),
            TimelineItem.Event("IE2", ago(6.days), EventKind.Labeled, mona, label = android),
            TimelineItem.Event("IE3", ago(6.days), EventKind.Assigned, mona, subject = "lin-yu"),
            TimelineItem.Comment(
              "IIC1",
              ago(5.days),
              lin,
              "Reproduced on a Pixel 6 with the disk filled. Extraction throws and nothing catches it; I'll fall back to plain text.",
              reactions = listOf(Reactions(Reaction.Heart, 2, false)),
              viewerCanReact = true,
            ),
            TimelineItem.Event(
              "IE4",
              ago(4.days),
              EventKind.Renamed,
              lin,
              subject = "Crash when a grammar fails to extract on a full disk",
              previous = "Crash on Zig files",
            ),
            TimelineItem.Event(
              "IE5",
              ago(1.hours),
              EventKind.Closed,
              lin,
              reference =
                ThreadReference(
                  repo,
                  411,
                  "Fall back to plain text when a grammar can't load",
                  ThreadKind.PullRequest,
                  ThreadState.Merged,
                ),
              stateReason = StateReason.Completed,
            ),
          )
          .map { TimelineEntry(it) },
      gapIndex = 6,
      hiddenCount = 0,
      reviewThreads = emptyMap(),
      fetchedAt = now,
    )
}
