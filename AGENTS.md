# Tongs

Tongs (codename) is a native forge client for GitHub, then Forgejo, built with
Compose Multiplatform for Android and desktop. Its PR review and code views must
stay fast on PRs with hundreds of thousands of changed lines. The design is in
[docs/design.md](docs/design.md).

## Project map

- `docs/design.md`: architecture and the reasoning behind it
- `engine-rs/engine`: Rust code engine (`tongs-engine`): git protocol v2 fetch
  without HTTP, a sans-IO fetch session (`fetch.rs`), object store, tree walk
  with exact renames, file classification from `.gitattributes` and path
  heuristics (`classify.rs`), file diff, and the review row model with its
  reading layout (collapsed files, rows reserved for inline cards)
- `engine-rs/spike`: CLI that reviews a GitHub PR from git objects alone and
  times each stage
- `engine-rs/highlight`: syntax highlighting (`tongs-highlight`): tree-sitter
  grammars (linked on desktop; on Android, bundled compressed and extracted on
  first use), vendored Helix queries, and a background highlighter with a cache
  keyed by blob
- `engine-rs/grammars`: build tool (`tongs-grammars`) that compiles each grammar
  into its own compressed shared library for Android; `:engine:buildGrammars`
  runs it before cargo-ndk
- `engine-rs/jni`: JNI bindings (`libtongs_jni`) for the Kotlin `engine` module
- `engine`: Kotlin facade over the engine (`Review`, row model types, file
  metadata (`ReviewFile`), `ReviewLayout`, `FetchSession`, and `ReviewLoader`,
  which drives a session over HTTP); JVM and Android targets share the JNI code
  in `jvmAndAndroidMain`, and the Android target packages `libtongs_jni` for
  arm64-v8a and x86_64
- `forge/api`: the forge-neutral model: `Instance`, `Account`, `Capability`,
  open enums (`NotificationReason`, `SubjectType`), typed failures
  (`ForgeException`), what's new on a thread (`SubjectActivity`,
  `summarizeWhatsNew`), and the ports each forge adapter implements:
  `ForgeAdapter` (registered in a map by `ForgeKind`), `AuthProvider` with the
  OAuth device flow, `InboxSource`, `InboxHydrator`, `ReviewSource`, and
  `ThreadSource` with the conversation model in `Thread.kt` (`ThreadDetail`,
  typed `TimelineItem`s, `ReviewThread`, and open enums that serialize as their
  raw value), and `PullRequestReviews` (threads, viewed state, submitting a
  review) with the neutral `ReviewAnchor`
- `forge/github`: the GitHub adapter: notifications REST (unread only; see
  `docs/design.md`), inbox hydration (`GitHubHydrator`, which batches
  `InboxHydration.graphql` under aliases), error mapping (`GitHubErrors.kt`:
  rate limits, scopes, SAML SSO), token checks through Apollo GraphQL
  (`src/commonMain/graphql`, with GitHub's public schema), the device flow,
  issue and PR threads (`GitHubThreads`, from `Thread.graphql`, whose operations
  and fragments all start with `Thread`), git smart HTTP and
  `loadPullRequestReview` for reviews, and `GitHubPullRequestReviews` (GraphQL
  reads and viewed state, REST review submission, and the anchor's lowering)
- `core/net`: the shared Ktor client (OkHttp on both platforms) and conditional
  request, poll interval, `Link`, and rate limit helpers
- `core/data`: Room 3 (`TongsDatabase`, schemas in `core/data/schemas`), the
  inbox sync (`InboxSync`, with hydration and typed sync status), triage with
  undo (`InboxRepository`), filters in GitHub's vocabulary (`InboxQuery`), saved
  views (`InboxViewRepository`), threads cached as JSON documents with
  optimistic comments, reactions, and state changes (`ThreadRepository`), drafts
  for every composer (`DraftRepository`, keyed by `DraftKey`), the PR changes
  screen's store (`PullRequestChangesRepository`: viewed files keyed by path and
  blob, the pending review's drafts, review threads; tables prefixed
  `changes_`), accounts and per-account forge clients, settings in DataStore,
  and `TokenStore` implementations (Android Keystore; desktop OS keychain with a
  private-file fallback)
- `core/ui`: the design system: `TongsTheme` (Material 3 Expressive), density
  per platform, `TongsIcons` (Material Symbols), `ListRow`, `SwipeActionsBox`,
  `stateColors()` (open, merged, closed, and checks, harmonized with the theme),
  `ConversationIcons`, `ReviewIcons`, `Avatar` (Coil), `TongsTopAppBar`, and
  `TongsNavigationSuite`
- `core/markdown`: `ForgeMarkdown` (mikepenz's renderer, with references linked
  by `linkifyReferences`, common GitHub HTML converted, Coil images, and code
  fences highlighted by the engine), and `ProvideForgeLinks`, which opens issue
  and PR links in the app
- `core/composer`: the reusable Markdown `Composer` (toolbar, preview, actions)
  and `rememberComposerState`, which autosaves to a `DraftKey`
- `core/navigation`: the sealed `Route` hierarchy, `Navigator` (`open` for the
  list's picks, `push` for links), and `RouteEntries`, the seam through which
  each feature adds its screens. `Pane.Extra` puts a screen beside a detail, as
  the composer beside its thread
- `feature/*`: screens, each with its own `RouteEntries`: `inbox`, `thread` (the
  conversation in one scroll, and `ThreadReply`, its composer), `review` (PR
  changes: `ChangesController` lays the review out as it loads, `FileTree` and
  `FileTreePane`, inline cards, the review pane, and `CommentComposer`, the seam
  for another composer; `PullRequestLoad` fetches a PR in the background, and
  `PullRequestReview` is the spikes' bare view), `auth` (sign-in), `settings`,
  and `feed` (Activity, placeholder)
- `codeview`: Compose Multiplatform canvas renderer for code and diffs, with
  file headers (collapse, counts, viewed box), gutter line selection, and
  overlays for annotation rows through a `CodeViewDelegate`
- `app`: the Metro graph (`AppGraph`) and `TongsApp`, the shared UI root with
  the navigation shell and list-detail scenes
- `build-logic`: convention plugins: `tongs.kmp.library` (JVM and Android
  targets, namespace from the module path) and `tongs.compose`
- `desktopApp`: desktop shell; runs the app, and keeps the spike modes behind
  flags
- `androidApp`: Android shell; `MainActivity` runs the app
  (`--es thread owner/repo#N` opens a thread, `--es open owner/repo#N` a PR's
  changes), and `PullRequestActivity` keeps the account-free PR spike

## Dev tool commands

- `mise install`: install the pinned toolchain (Java, Rust with its Android
  targets, cargo-ndk, hk, dprint). Android builds also need the Android SDK at
  `~/Android/Sdk` with the NDK version in `gradle/libs.versions.toml`
- `mise run check` / `mise run fix`: run every linter and formatter through hk
- `cargo test`: engine unit tests
- `cargo run --release -p tongs-spike -- maplibre/maplibre-native-ffi 760`:
  fetch and diff a 576k-line PR (needs `gh auth login` or `GH_TOKEN`); fills
  `/tmp/tongs-spike-store`
- `cargo run --release -p tongs-highlight --example highlight_review`: highlight
  every file in that PR and report throughput and span memory
- `prepare_languages` example: time extracting, loading, and compiling queries
  for every language on a device; its doc comment has the cargo-ndk and adb
  steps
- `./gradlew :desktopApp:run`: run the app (builds the JNI library first). Its
  data goes in `$XDG_DATA_HOME/tongs`; set `XDG_DATA_HOME` to sign in as someone
  else. Sign-in offers `gh`'s token on desktop
- `./gradlew :desktopApp:run --args="--app-screenshots=<dir>"`: render the
  inbox, an open thread, settings, and a PR's changes offscreen with the
  signed-in account's real data (signs in with `gh`'s token if there's no
  account)
- `./gradlew :desktopApp:run --args="--open=owner/repo#N"`: run the app with
  that PR's changes open, for the first account. On Android:
  `adb -s <serial> shell am start -n dev.sargunv.tongs/.android.MainActivity --es open owner/repo#N`
- `./gradlew :desktopApp:run --args="--changes-screenshots=<dir>"`: drive the
  changes screen offscreen like a reader (jump to a thread, drag across line
  numbers, write a comment, start a review) and save screenshots. `--open=` and
  `--review=` pick PRs. `--api-url=<url>` signs in with that API URL instead of
  api.github.com; pointed at a proxy that answers mutations itself, `--mutate`
  also marks a file viewed and submits the review. Without `--api-url` it never
  mutates. Set `XDG_DATA_HOME` to a scratch dir for these runs
- `./gradlew :desktopApp:run --args="--thread-screenshots=<dir> owner/repo#N…"`:
  render public threads beside the inbox, and the first with its composer open,
  offscreen with real data, in a throwaway data dir with the token in memory.
  Private repos' notifications are dropped first, so the shots are safe to share
- `./gradlew :feature:thread:verifyRoborazziJvm` and
  `:core:markdown:verifyRoborazziJvm`: compare the thread, composer, and
  Markdown against their screenshots (made-up data; they need the engine's JNI
  library, which the tasks build); `recordRoborazziJvm` updates them
- `./gradlew :app:jvmTest`: posting end to end (reply, close with comment, a
  refused reply) against a fake GitHub GraphQL server
- `adb -s <serial> shell am start -n dev.sargunv.tongs/.android.MainActivity --es thread owner/repo#N`:
  open a thread on the first account, over the inbox. Always pass `-s`: several
  teams share one adb server, and `installDebug` installs on every device
- `./gradlew :feature:inbox:verifyRoborazziJvm`: compare the inbox against its
  screenshots in `feature/inbox/src/jvmTest/snapshots` (rows, views, filters,
  grouping, snoozed, read elsewhere with its bulk action, and each sync problem,
  from made-up data); `recordRoborazziJvm` updates them
- `TONGS_LIVE_TESTS=1 GH_TOKEN=$(gh auth token) ./gradlew :forge:github:jvmTest --tests '*LiveInbox*' -i`:
  poll and hydrate your real inbox, read-only, and print each row and the
  GraphQL points it cost
- `./gradlew :desktopApp:run --args="--spike"`: open that PR in the renderer
  from `/tmp/tongs-spike-store`. Add `--bench` to print frame timings, or
  `--screenshot=out.png@<row>` to render offscreen (either implies `--spike`);
  add `--no-highlight` to turn off syntax highlighting
- `./gradlew :androidApp:installRelease`: install the Android app, signed with
  the debug key (builds the JNI library with cargo-ndk first)
- `adb shell am start -n dev.sargunv.tongs/.android.PullRequestActivity`: the PR
  spike, without an account. It fetches maplibre/maplibre-native-ffi#760 in the
  app and shows it as it loads; objects persist per repo in the app's cache dir.
  Add `--es pr owner/repo#N` to open another PR; a github.com PR link as the
  intent's data works too. Public repos need no token; debuggable builds
  (`installDebug`) take `--es token <token>`. For timing runs, add
  `--ez cold true` to empty the repo's store first, `--ez exitWhenLoaded true`
  to log timings under the `Tongs` tag and exit, `--ef jump 0.6` to scroll 60%
  in as soon as the headers show, and `--ez highlight false` to turn off syntax
  highlighting
- `--ez bench true` on the same command: once the PR is loaded, log frame
  timings for the desktop's fling and scrub passes, then exit. Run
  `adb shell am force-stop dev.sargunv.tongs` before each run, so highlights
  start cold, and `adb shell cmd package compile -m speed -f dev.sargunv.tongs`
  once after installing, since there's no baseline profile yet
- On [Linux.apk](https://github.com/sargunv/linux.apk) instead of an emulator:
  with `LINUXAPK_INSTANCE=tongs` set for a test session,
  `linuxapk install androidApp/build/outputs/apk/release/androidApp-release.apk`,
  then run the `adb shell` commands above as `linuxapk-session exec ...`. Its
  `docs/development.md` has a nested KWin that keeps windows off your desktop
- `./gradlew :desktopApp:run --args="--pr maplibre/maplibre-native-ffi#760"`:
  fetch any GitHub PR in the app and show it as it loads, with `GH_TOKEN` or the
  `gh` CLI's token if there is one. Objects persist per repo in
  `~/.cache/tongs/github.com/<owner>/<repo>/objects`. Add `--store=<dir>` for
  another store (an empty one measures a cold load), `--exit-when-loaded` to
  print timings and quit, and `--jump=0.6` to scroll 60% in as soon as the
  headers show
- `cargo test` needs `git` on the `PATH`: the fetch session tests serve packs
  with `git upload-pack`

## Project invariants

- Layering: `feature/*` modules depend on `core/*` and `forge/api`, not on a
  forge adapter or on each other; `app` wires them. Add a screen by adding a
  `Route` and an entry in the feature's `RouteEntries`.
- Screens read from Room through repositories in `core/data`, and write through
  them, optimistically; they don't call forge clients directly.
- Shared UI (theme, rows, app bars, navigation) lives in `core/ui`; features
  don't fork it. Desktop differs through `TongsDensity`, not other components.
- Room schema changes bump `TongsDatabase`'s version, add an `AutoMigration`
  where Room can derive one, and commit the exported schema in
  `core/data/schemas`.
- Don't mutate anything on real PRs while testing (reviews, comments, viewed
  state); use a fake server, as `--changes-screenshots --api-url` allows.
- Tongs is GPL-3.0-or-later (`LICENSE`). Vendored third-party files keep their
  own licenses and notices, e.g. Helix's MPL-2.0 queries in
  `engine-rs/highlight/queries`.
