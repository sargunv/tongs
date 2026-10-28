# Tongs

Tongs (codename) is a native forge client for GitHub, then Forgejo, built with
Compose Multiplatform for Android and desktop. Its PR review and code views must
stay fast on PRs with hundreds of thousands of changed lines. The design is in
[docs/design.md](docs/design.md).

## Project map

- `docs/design.md`: architecture and the reasoning behind it
- `engine-rs/engine`: Rust code engine (`tongs-engine`): git protocol v2 fetch
  without HTTP, a sans-IO fetch session (`fetch.rs`), object store, tree walk,
  file diff
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
- `engine`: Kotlin facade over the engine (`Review`, row model types,
  `FetchSession`, and `ReviewLoader`, which drives a session over HTTP); JVM and
  Android targets share the JNI code in `jvmAndAndroidMain`, and the Android
  target packages `libtongs_jni` for arm64-v8a and x86_64
- `forge/api`: the forge-neutral model: `Instance`, `Account`, `Capability`,
  open enums (`NotificationReason`, `SubjectType`), typed failures
  (`ForgeException`), what's new on a thread (`SubjectActivity`,
  `summarizeWhatsNew`), and the ports each forge adapter implements:
  `ForgeAdapter` (registered in a map by `ForgeKind`), `AuthProvider` with the
  OAuth device flow, `InboxSource`, `InboxHydrator`, and `ReviewSource`
- `forge/github`: the GitHub adapter: notifications REST (unread only; see
  `docs/design.md`), inbox hydration (`GitHubHydrator`, which batches
  `InboxHydration.graphql` under aliases), error mapping (`GitHubErrors.kt`:
  rate limits, scopes, SAML SSO), token checks through Apollo GraphQL
  (`src/commonMain/graphql`, with GitHub's public schema), the device flow, and
  git smart HTTP and `loadPullRequestReview` for reviews
- `core/net`: the shared Ktor client (OkHttp on both platforms) and conditional
  request, poll interval, `Link`, and rate limit helpers
- `core/data`: Room 3 (`TongsDatabase`, schemas in `core/data/schemas`), the
  inbox sync (`InboxSync`, with hydration and typed sync status), triage with
  undo (`InboxRepository`), filters in GitHub's vocabulary (`InboxQuery`), saved
  views (`InboxViewRepository`), accounts and per-account forge clients,
  settings in DataStore, and `TokenStore` implementations (Android Keystore;
  desktop OS keychain with a private-file fallback)
- `core/ui`: the design system: `TongsTheme` (Material 3 Expressive), density
  per platform, `TongsIcons` (Material Symbols), `ListRow`, `SwipeActionsBox`,
  `stateColors()` (open, merged, closed, and checks, harmonized with the theme),
  `TongsTopAppBar`, and `TongsNavigationSuite`
- `core/navigation`: the sealed `Route` hierarchy, `Navigator`, and
  `RouteEntries`, the seam through which each feature adds its screens
- `feature/*`: screens, each with its own `RouteEntries`: `inbox`, `thread`
  (placeholder), `review` (PR changes; `PullRequestLoad` fetches a PR in the
  background and `PullRequestReview` shows it as it loads), `auth` (sign-in),
  `settings`, and `feed` (Activity, placeholder)
- `codeview`: Compose Multiplatform canvas renderer for code and diffs
- `app`: the Metro graph (`AppGraph`) and `TongsApp`, the shared UI root with
  the navigation shell and list-detail scenes
- `build-logic`: convention plugins: `tongs.kmp.library` (JVM and Android
  targets, namespace from the module path) and `tongs.compose`
- `desktopApp`: desktop shell; runs the app, and keeps the spike modes behind
  flags
- `androidApp`: Android shell; `MainActivity` runs the app, and
  `PullRequestActivity` keeps the account-free PR spike

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
- `./gradlew :feature:inbox:verifyRoborazziJvm`: compare the inbox against its
  screenshots in `feature/inbox/src/jvmTest/snapshots` (rows, views, filters,
  grouping, snoozed, and each sync problem, from made-up data);
  `recordRoborazziJvm` updates them
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
- Room schema changes bump `TongsDatabase`'s version and commit the exported
  schema in `core/data/schemas`.
- Tongs is GPL-3.0-or-later (`LICENSE`). Vendored third-party files keep their
  own licenses and notices, e.g. Helix's MPL-2.0 queries in
  `engine-rs/highlight/queries`.
