# Tongs

Tongs (codename) is a native forge client for GitHub, then Forgejo, built with
Compose Multiplatform for Android and desktop. Its PR review and code views must
stay fast on PRs with hundreds of thousands of changed lines. The design is in
[docs/design.md](docs/design.md).

## Project map

- `docs/design.md`: architecture and the reasoning behind it
- `engine-rs/engine`: Rust code engine (`tongs-engine`): git protocol v2 fetch
  without HTTP, object store, tree walk, file diff
- `engine-rs/spike`: CLI that reviews a GitHub PR from git objects alone and
  times each stage
- `engine-rs/highlight`: syntax highlighting (`tongs-highlight`): statically
  linked tree-sitter grammars, vendored Helix queries, and a background
  highlighter with a cache keyed by blob
- `engine-rs/jni`: JNI bindings (`libtongs_jni`) for the Kotlin `engine` module
- `engine`: Kotlin facade over the engine (`Review`, row model types); JVM and
  Android targets share the JNI code in `jvmAndAndroidMain`, and the Android
  target packages `libtongs_jni` for arm64-v8a and x86_64
- `codeview`: Compose Multiplatform canvas renderer for code and diffs
- `desktopApp`: desktop shell; currently the renderer spike
- `androidApp`: Android shell; currently the renderer spike

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
- `./gradlew :androidApp:installRelease`: install the Android spike, signed with
  the debug key (builds the JNI library with cargo-ndk first). It opens the same
  PR from a store pushed with adb; launch the app once so it creates its files
  dir, then
  `adb push /tmp/tongs-spike-store /sdcard/Android/data/dev.sargunv.tongs/files/`
- `adb shell am start -n dev.sargunv.tongs/.android.MainActivity --ez bench true`:
  log text-drawing and frame timings under the `Tongs` tag, then exit. Run
  `adb shell cmd package compile -m speed -f dev.sargunv.tongs` first, since
  there's no baseline profile yet. Add `--ei row <row>` to open at a row, or
  `--es textPath DrawText` to use the slower text path

## Project invariants
