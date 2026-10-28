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
- `engine-rs/highlight`: syntax highlighting (`tongs-highlight`): statically
  linked tree-sitter grammars, vendored Helix queries, and a background
  highlighter with a cache keyed by blob
- `engine-rs/jni`: JNI bindings (`libtongs_jni`) for the Kotlin `engine` module
- `engine`: Kotlin facade over the engine (`Review`, row model types,
  `FetchSession`, and `ReviewLoader`, which drives a session over HTTP); JVM and
  Android targets share the JNI code in `jvmAndAndroidMain`, and the Android
  target packages `libtongs_jni` for arm64-v8a and x86_64
- `forge/github`: GitHub REST and git smart HTTP with Ktor (the OkHttp engine on
  JVM and Android), and `loadPullRequestReview`
- `codeview`: Compose Multiplatform canvas renderer for code and diffs
- `feature/review`: the PR screen both apps share: `PullRequestLoad` fetches a
  PR in the background, and `PullRequestReview` shows it as it loads
- `desktopApp`: desktop shell; opens a PR, or a pre-filled store for the
  renderer spike
- `androidApp`: Android shell; opens a PR

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
- `./gradlew :desktopApp:run`: open that PR in the renderer (builds the JNI
  library first). Add `--args="--bench"` to print frame timings, or
  `--args="--screenshot=out.png@<row>"` to render offscreen; add
  `--no-highlight` to either to turn off syntax highlighting
- `./gradlew :androidApp:installRelease`: install the Android app, signed with
  the debug key (builds the JNI library with cargo-ndk first). It fetches
  maplibre/maplibre-native-ffi#760 in the app and shows it as it loads. Objects
  persist per repo in the app's cache dir
- `adb shell am start -n dev.sargunv.tongs/.android.MainActivity --es pr owner/repo#N`:
  open another PR; a github.com PR link as the intent's data works too. Public
  repos need no token; debuggable builds (`installDebug`) take
  `--es token <token>`. For timing runs, add `--ez cold true` to empty the
  repo's store first, `--ez exitWhenLoaded true` to log timings under the
  `Tongs` tag and exit, `--ef jump 0.6` to scroll 60% in as soon as the headers
  show, and `--ez highlight false` to turn off syntax highlighting
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
