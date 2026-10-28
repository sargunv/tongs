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
- `engine-rs/jni`: JNI bindings (`libtongs_jni`) for the Kotlin `engine` module
- `engine`: Kotlin facade over the engine (`Review`, row model types,
  `FetchSession`, and `ReviewLoader`, which drives a session over HTTP)
- `forge/github`: GitHub REST and git smart HTTP with Ktor, and
  `loadPullRequestReview`
- `codeview`: Compose Multiplatform canvas renderer for code and diffs
- `desktopApp`: desktop shell; opens a PR, or a pre-filled store for the
  renderer spike

## Dev tool commands

- `mise install`: install the pinned toolchain (Java, Rust, hk, dprint)
- `mise run check` / `mise run fix`: run every linter and formatter through hk
- `cargo test`: engine unit tests
- `cargo run --release -p tongs-spike -- maplibre/maplibre-native-ffi 760`:
  fetch and diff a 576k-line PR (needs `gh auth login` or `GH_TOKEN`); fills
  `/tmp/tongs-spike-store`
- `./gradlew :desktopApp:run`: open that PR in the renderer (builds the JNI
  library first). Add `--args="--bench"` to print frame timings, or
  `--args="--screenshot=out.png@<row>"` to render offscreen
- `./gradlew :desktopApp:run --args="--pr maplibre/maplibre-native-ffi#760"`:
  fetch any GitHub PR in the app and show it as it loads (needs `gh auth login`
  or `GH_TOKEN`). Objects persist per repo in
  `~/.cache/tongs/github.com/<owner>/<repo>/objects`. Add `--store=<dir>` for
  another store (an empty one measures a cold load), `--exit-when-loaded` to
  print timings and quit, and `--jump=0.6` to scroll 60% in as soon as the
  headers show
- `cargo test` needs `git` on the `PATH`: the fetch session tests serve packs
  with `git upload-pack`

## Project invariants
