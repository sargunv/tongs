# Forge client: design sketch

A native client for GitHub first, then Forgejo, then other forges, built with
Compose Multiplatform for Android and desktop. The inbox and activity feed are
home. From either one you open a repo, an issue, or a PR. Browsing a repo and
reviewing a PR should feel fast and smooth, including on agent-written PRs with
hundreds of thousands of changed lines.

Research basis, as of 2026-10-28:

- GitHub and Forgejo API docs, plus live probes of github.com and codeberg.org
- The Kotlin and Compose Multiplatform ecosystem
- How Pierre (`@pierre/diffs`), Codiff, GitHub's new Files changed view,
  ReviewStack, and Reviewable handle large diffs
- The Rust ecosystem: Zed, Helix tree-house, gitoxide, imara-diff, UniFFI

## Principles

1. **Git is the data plane, forge APIs are the metadata plane.** Code, trees,
   and diffs come from git objects fetched by OID and diffed on the device.
   Forge APIs supply only conversations, reviews, inbox, feed, and CI. This gets
   past every forge's diff truncation:
   - GitHub: 3000 files, patches omitted for large files, 406 above 300 files on
     the raw diff.
   - Forgejo: no patches at all, 1000-line cap.

   It also makes the expensive part of the app forge-neutral.

2. **A local database is the source of truth.** Screens observe Room. Sync code
   writes to it. Everything opens instantly from cache and works offline for
   reading.
3. **Forge differences are capabilities, not `if (github)`.** Each instance
   advertises what it supports, and the UI degrades gracefully.
4. **The UI thread does no proportional work.** Nothing that scales with PR size
   runs on the main thread. Zed's diff view freezes for minutes on an 800k-line
   file because it builds excerpts synchronously. We don't repeat that.

## Architecture

```
┌──────────────────────── Kotlin (commonMain) ─────────────────────────┐
│ feature/*  (inbox, feed, thread, review, repo, auth, settings)       │
│     │  ViewModel + StateFlow, Nav3 routes, adaptive scenes           │
│ codeview   (row-model client, Canvas renderer, selection, gutters)   │
│     │            ▲ fillRows(viewport) → DirectByteBuffer             │
│ core/data  (Room 3 store, sync engines, review drafts, viewed state) │
│ forge/api  (neutral model + capabilities)                            │
│   ├─ forge/github  (Apollo 5 GraphQL + Ktor REST)                    │
│   └─ forge/gitea   (Gitea-family core) ─ forge/forgejo               │
│ core/net   (Ktor, auth, rate-limit governor, conditional requests)   │
│ engine     (Kotlin facade over the Rust engine)                      │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ UniFFI (control plane) + jni-rs (hot path)
┌────────────────────────── Rust: forge-engine ────────────────────────┐
│ object store (pack/idx per repo, CAS, LRU by bytes)                  │
│ protocol v2 request builder + pack ingest   (gix-protocol, gix-pack) │
│ tree diff + rename detection                (gix-diff)               │
│ file diff: histogram + intra-line refinement (imara-diff)            │
│ classification: gitattributes, linguist, generated heuristics,       │
│                 CODEOWNERS                                           │
│ highlighting: tree-house + Helix queries, static grammars            │
│ row model: per-file row counts, prefix sums, text slabs, token runs  │
└──────────────────────────────────────────────────────────────────────┘
```

### Why there is a Rust engine

I considered a pure-Kotlin engine (JGit plus `ktreesitter`) and rejected it on
the merits:

- **Native code is unavoidable.** Tree-sitter grammars are C, so a native
  toolchain is needed regardless. Since we pay that cost anyway, Rust gives us
  the best pieces available:
  - tree-house: injections, locals, and a layered syntax tree that Helix uses in
    production.
  - imara-diff: used by Zed, Helix, and gitoxide.
  - gix-protocol and gix-pack: explicit-OID wants with filters, plus pack
    indexing.
  - gix-attributes and the `linguist` crate.
- **Memory.** Android JVM heaps are small. A 600k-line PR held as `String`s is
  millions of UTF-16 objects. The engine keeps compact byte slabs outside the
  heap, and Kotlin only ever holds the visible rows. Pierre halved memory on the
  Linux patch with the same idea.
- **JGit has become a liability on Android.** JGit 7 reportedly needs API 33 or
  later. JGit 8 requires Java 25.
- **iOS later.** The Rust engine ports as-is; a JVM-based engine would not.

What it costs us:

- A two-language build.
- FFI debugging.
- Contributors need to know Rust.

**HTTP stays in Kotlin.** Ktor owns auth, proxies, Android certificates, and
retry. The engine builds pkt-line request bodies and ingests streamed response
chunks. It never opens a socket.

**Interop:**

- **Control plane, through UniFFI.** Coarse async calls: `openRepo`,
  `planFetch`, `ingest`, `diffSummary`, `fileList`, `search`, progress, and
  cancel. Element X, Bitwarden, and Firefox ship this in production.
- **Hot path, through jni-rs.** The call is
  `fillRows(view, firstRow, count, out: DirectByteBuffer)`. Rust writes
  fixed-stride row records with these fields:
  - kind
  - old and new line numbers
  - text slab offset and length
  - token-run offset and count

  It follows them with packed token runs (`u32 utf16Column << 8 | styleId`, each
  running to the next) and a flag for rows whose highlights are still pending. A
  highlighter epoch, which Kotlin waits on from a background thread, tells it
  when to refetch those rows. UniFFI's JNA bindings cost about 4 µs per call,
  which is too slow per frame.

- **Build.** cargo-ndk and `uniffi-bindgen` run through a small Gradle
  convention plugin. Gobley would be nicer, but it hasn't released in a year and
  its AGP 9 support is unclear. I'll revisit it later.
- **Android 16 KB pages.** Use NDK r28 or later and verify the output in CI.

## The code engine pipeline (diffs and repo browsing)

The same engine serves PR review, commit view, compare, and the file browser.

1. **Metadata.**
   - The PR's base and head OIDs come from the forge.
   - The merge base comes from the forge where it's cheap: Forgejo's
     `merge_base` field, or `merge_base_commit` from GitHub's compare API.
   - Otherwise the engine computes it by fetching commits only (`filter tree:0`,
     deepening until it finds a common ancestor). Diffs are three-dot, the same
     as on the forge.
   - The fetch doesn't wait for the merge base. It starts from the PR's base,
     and the review shows as soon as the fetched history proves the base is an
     ancestor of the head, which makes it the merge base. Otherwise it waits for
     the forge and restarts from the real merge base, reusing every object
     already fetched.
   - Every commit want carries `deepen 1`. Without it, servers send the whole
     history, whatever the filter.
2. **Trees.**
   - Small repos, under about 2 GB by the forge's reported size, fetch every
     tree with the commits in one round trip (`deepen 1` and `blob:none`). The
     whole tree set of a revision is 145 KiB for maplibre-native-ffi and 2 MiB
     for rust-lang/rust.
   - Larger repos fetch the root trees of the merge base and the head with
     `filter tree:0`, then walk only the subtrees whose OIDs differ, one batched
     round trip per directory level. The cost scales with the size of the
     change, not the size of the repo.
   - github.com rejects `tree:<n>` above 0 and `combine:` filters, so a walk
     can't fetch several levels per round trip there. Forgejo accepts both, so a
     Forgejo walk could.
   - Live tests confirmed github.com and codeberg.org serve single trees and
     blobs by OID with protocol v2 filters.
3. **File list, within seconds and uncapped.**
   - Tree diff and exact-rename detection on OIDs come first. Content-similarity
     renames are found lazily afterwards.
   - Classification comes from `.gitattributes` (`linguist-generated`,
     `-vendored`, `-diff`), linguist path data, and generated-file heuristics:
     - lockfiles
     - minified files
     - `@generated` and `DO NOT EDIT` markers
     - very long lines
   - CODEOWNERS ownership is matched here too. These are the first few small
     blob fetches.
4. **Blobs, on demand.**
   - Files on screen and in the next two screens go first, in a batch of their
     own. Everything else follows in path order.
   - Requests carry 32 wants at first and double up to 256, so the first diffs
     don't wait on a big batch. Up to six run at once, plus one for visible
     files. There are no `have` lines.
   - Fetched blobs are stored by OID, permanently valid, and shared by every PR,
     commit, and file view in that repo.
   - Files above a size threshold show "Load diff".
5. **Diff, per file in the background.**
   - Lines are interned to ints and diffed with histogram.
   - Intra-line refinement runs only for paired lines within a budget: lines up
     to about 1000 characters, with an edit-distance gate like delta's.
   - Each file has a timeout. On timeout the engine falls back to a whole-hunk
     replacement and flags the file.
   - Results are cached on disk keyed by `(oldOid, newOid, options)` and never
     need invalidating.
6. **Highlight, in the background.**
   - Plain text renders first. Token runs arrive later and the epoch bumps.
   - The whole old and new files are parsed and queried, because hunk-only
     lexing gets multi-line strings and comments wrong. Spans are kept per line
     and cached by blob OID, outside any one review, so snapshots and other
     views of the same blob share them.
   - Only files with rows near the viewport are highlighted, newest request
     first. Files over 1 MiB, minified files, non-UTF-8 files, and parses over 2
     seconds stay plain. Generated files aren't skipped: the file tree collapses
     them, so they're never requested unless someone opens them, and then
     they're worth coloring.
7. **Row model.**
   - Per file, the engine keeps row counts and prefix sums covering headers,
     hunk separators, context, add/delete pairs, collapsed regions, and
     expanders.
   - Before a file is diffed, its row count is estimated from forge stats or
     blob sizes. When the real count arrives, the renderer anchors scroll to the
     first visible line (Pierre's technique), so nothing jumps.
8. **Render.** The Kotlin `codeview` module draws it.

**Cross-revision diffs come almost free.** Every blob and tree is addressed by
OID, so "changes since my last review", any commit range, or force-push
interdiffs are just another pair of trees. GitHub handles this badly, and
Reviewable built a business on it.

## The code view renderer

Code view and diff view are one renderer with two row sources. CI logs use it
too, through an ANSI-to-token-run adapter, since logs are also huge.

**Layout.**

- One virtual surface spans every file in the PR. There are no nested scroll
  containers.
- Line height is fixed and wrapping is off, so finding the row at any offset is
  just division, and the scrollbar is exact.
- Only rows that aren't code lines need height bookkeeping:
  - sticky file headers
  - inline comment threads
  - expanders

  They live in a small prefix-sum overlay.

**Drawing.**

- Visible rows are drawn directly on a Compose `Canvas`, one `drawText` per
  token run at its column. Rows waiting on highlights are refetched when the
  highlighter's epoch bumps.
- Composables are used only for interactive overlays: comment threads, the
  composer, and selection handles.
- Below the shared Compose drawing API, each platform has a small
  implementation:
  - Desktop: Skiko `TextLine` and `drawTextLine`, one shaped run per token run,
    with x = column × advance.
  - Android: glyph IDs cached per character and placed on the column grid with
    `Canvas.drawGlyphs` (API 31+). Lines with characters off the grid, and older
    devices, use `Canvas.drawText`.

  The row model, layout, scrolling, selection, find, and theming are shared
  code.

**Long lines.**

- A single horizontal offset is shared across the whole view, and the renderer
  clips to the viewport.
- Text far outside the viewport is never shaped.
- Lines over about 10k characters are truncated, with an expand option.

**Soft wrap is optional.** It's useful for Markdown and prose. Because the font
is monospace, the engine can compute wrapped row counts deterministically from
display width and column count, so fixed row height still holds.

**Selection, copy, find, accessibility.**

- Selection is our own: line-range selection is the primary gesture and starts
  the comment flow. Character selection is supported too.
- Find runs in Rust over the text slabs and streams back hits.
- Accessibility semantics nodes are exposed for visible rows only.

**Layouts.** Phones use a unified view. Wide windows can switch between split
and unified.

**Prefetch.** Rows and blobs are requested about two viewports ahead in the
scroll direction.

**Performance targets.** These are what we'll measure the spike against, not
measured numbers.

- On a 600k-line, roughly 5k-file PR on decent wifi:
  - File tree visible in under 2 seconds.
  - First diff visible within 1 second after that.
- No dropped frames when flinging across the whole PR. Rows may show plain text
  briefly; they never show blank space.
- Android JVM heap growth under 100 MB.

## Forge abstraction

**Entities.** `ForgeKind` (GitHub, Forgejo, Gitea, …), `Instance` (base URL,
version, capabilities), and `Account` (many per instance). Codeberg is just a
Forgejo instance. A GitHub Enterprise Server instance is just a GitHub instance
with another URL.

**Capabilities.** These are detected from version and settings probes such as
Forgejo's `/settings/api` and `/settings/ui`. They also depend on the auth
method, because a fine-grained GitHub PAT has no notifications access. Examples:

- `inbox.reasons` and `inbox.done` (GitHub), `inbox.pin` (Forgejo)
- `review.threads.resolve`, `review.multiLine`, `review.suggestions`
- `pr.draft`, `pr.mergeQueue`, `pr.autoMerge`, `pr.stacks`
- `ci.checks` (annotations) vs `ci.statuses`, `ci.logs`
- `issues.subIssues`, `issues.dependencies`, `issues.types`, `projects`
- the reaction set (fixed on GitHub, per-instance plus custom emoji on Forgejo)
- `search.code`, `git.partialClone`, and the max page size

**Neutral model rules.** These come from danielroe/forges, which models 11
forges.

- Enums are open, and each normalized value carries its raw value (`state` with
  `stateRaw`, `reason` with `reasonRaw`).
- Unknown values are mapped to `Other(raw)` rather than failing.
- Fields a forge doesn't report are absent, not zero.
- Keys are forge IDs, so they survive renames.

**Review comment anchor.** The neutral form is
`(commitOid, path, side, startLine, endLine)`. Our local diff gives us both old
and new line numbers, so every adapter can lower it:

- GitHub: `line`/`side`/`start_line`/`start_side`, or `subject_type: file`.
- Forgejo: `new_position`/`old_position` plus `extra_lines_count`.
- GitLab later: `position` with base, start, and head SHAs.

**Adapters.**

- **GitHub.**
  - Apollo 5 for GraphQL: threads, timelines, review threads, viewed state,
    `nodes(ids:)` hydration.
  - Ktor for REST: notifications, events, the new async merge API (GA since
    2026-10-01, and required for stacked PRs), and Actions logs.
  - Targets REST API version `2026-03-10`.
  - Apollo's normalized cache isn't used, because Room is the store.
- **Gitea family.**
  - One shared HTTP core, since the shapes overlap about 80%, with separate
    Forgejo and Gitea adapters on top.
  - DTOs are hand-written with kotlinx.serialization for the roughly 40
    endpoints we use. The swagger spec lacks enums and drifts from the
    implementation.
  - Forgejo v15 LTS is the compatibility floor.
  - Paging is clamped to the instance's `max_response_items`. Forgejo silently
    caps `limit` and its Link headers are wrong when that happens.

## Auth

- **GitHub, primary: an OAuth App with device flow.**
  - Device flow needs no client secret embedded in the app.
  - The notifications API accepts only OAuth tokens and classic PATs. GitHub App
    user tokens and fine-grained PATs can't read it, and the inbox is the home
    screen, so this rules out a GitHub App.
- **GitHub, fallback: a classic PAT** with the `repo`, `notifications`, and
  `read:org` scopes.
  - It gets past orgs that haven't approved our OAuth app. OAuth app
    restrictions are on by default for new orgs.
  - Fine-grained PATs are accepted. The capability model turns off the inbox and
    explains why.
- **SAML SSO orgs.** When a request fails for this reason, show the SSO
  authorization link.
- **Forgejo, primary: a scoped access token.**
  - Forgejo's OAuth has no device flow.
  - OAuth scopes are "not yet implemented", so an OAuth token gets full account
    access.
  - Every instance needs its own app registration.

  A scoped PAT is more secure and works on every instance.

- **Forgejo, later: OAuth with PKCE.**
  - Desktop uses a loopback redirect. Android uses an https App Link on our own
    domain.
  - Codeberg would come first.
  - Longer term, ask Forgejo to ship the app as a built-in default application,
    the way it ships `tea` and git-credential-oauth.
- **Token storage.** Android uses the Keystore. Desktop uses the OS keychain.

## Home screens

**Inbox.**

- **GitHub.**
  - Poll `/notifications` with `If-Modified-Since`, respecting
    `X-Poll-Interval`. A 304 doesn't cost rate limit.
  - Hydrate rows in batches with GraphQL `nodes(ids:)`, so each row shows PR
    state, review decision, CI rollup, and labels without opening it.
  - "Done" is `DELETE /notifications/threads/{id}`.
- **Forgejo.** Poll `/notifications/new`, then fetch. Forgejo notifications have
  no reasons, and it offers pinning.
- **Unified across every account and forge.** That's a differentiator no current
  client has.
- **Triage-first UX:**
  - Swipe to mark done or read.
  - Filters and saved views are stored locally, since GitHub has no API for
    them: review requested, mentioned, participating, CI failing, by repo or
    org.
  - Grouping by repo.
- Polling happens only while the app is in the foreground and on resume, since
  push notifications are out of scope.

**Activity feed.**

- **GitHub has no API for its "For you" feed,** so we build one:
  - Merge `received_events`, the user's own events, and each org's events.
  - The events API keeps only 300 events and 30 days, so persist locally to go
    further back.
  - Hydrate with `nodes(ids:)`. Since October 2025, push events no longer
    include commits, so hydrate those with the compare API.
- **Forgejo:** `/users/{me}/activities/feeds`.
- **Presentation:**
  - Aggregate bursts, such as 12 pushes to `main` or stars on a release.
  - Group by repo or by person.
  - Mute repos and actors locally.

## Navigation and screens

**Routing.**

- Navigation 3 with a sealed `Route` hierarchy. The back stack is a plain list,
  which makes deep links and state restoration straightforward.
- Material 3 adaptive list-detail scenes:
  - On phones, panes stack.
  - On tablets and desktop, the inbox list sits beside the open thread.
  - PR review uses three panes: file tree, diff, and conversation or comments.

**Screens.**

- **Inbox and Feed** are the top-level destinations, with an account and
  instance switcher.
- **Thread (issue or PR conversation).**
  - Timeline items include typed events.
  - Reactions, labels, and assignees.
  - Sub-issues and dependencies where the capability allows.
- **PR review.** One virtual surface over all files.
  - File tree: fuzzy filter on path, extension, and CODEOWNERS owner. Generated,
    lockfile, vendored, and rename-only files are grouped and collapsed
    automatically.
  - Viewed state syncs with GitHub's `markFileAsViewed` and is also kept
    locally, keyed by `(path, blob OID)`, so it resets by itself when a file
    changes.
  - An overview gutter scales each file by its lines changed.
  - A revision picker supports "since last review" and any commit range.
- **Commenting.**
  - Long-press or drag across a line range opens the composer. A "suggest
    change" action prefills the selected lines.
  - The pending review is drafted locally first, so drafts survive offline and
    crashes, then submitted as a batch.
- **CI.** Rollup on the PR, then runs and jobs, then logs in the code view.
- **Merge.**
  - GitHub: the async merge API (direct, queue, or auto-merge).
  - Forgejo: one of its merge styles, or `merge_when_checks_succeed`.
- **Repo.**
  - README, rendered.
  - A tree browser over lazily fetched trees. Navigating directories is instant
    after the first fetch, and browsing a repo you've already visited works
    offline.
  - A fuzzy file finder over the full path list.
  - A file view in the shared renderer, with a tree-sitter symbol outline and
    permalinks for line selections.
  - Branch and tag switcher.
  - Blame on GitHub through GraphQL.
- **Keyboard first on desktop:**
  - `j`/`k` next and previous hunk, `n`/`p` next and previous file
  - `v` toggles viewed, `c` comments
  - `t` opens the file finder, `/` finds
  - `⌘K` opens the command bar
- **Opening links.**
  - Parse github.com and instance URLs into routes.
  - Use an Android share target and intent filters. Since Android 12, the user
    has to enable unverified links manually in settings.
  - Offer a paste-a-URL entry point.

**Markdown.** Rendered locally with mikepenz's multiplatform-markdown-renderer
for every forge, so drafts preview identically and everything works offline. Two
hooks plug in:

- A reference linkifier for `#123`, `owner/repo#123`, `@user`, and commit SHAs.
- Code fences highlighted by our engine.

GitHub's `bodyHTML` is the fallback for constructs we can't render, such as
Mermaid and math.

## Stack

All versions verified against releases on 2026-10-28.

| Area                 | Choice                                                                                                                                         |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Language and UI      | Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.4.1, Gradle 9.8 with a version catalog                                                      |
| Material             | Material 3 (Expressive components arrive with CMP 1.13), material3-adaptive 1.3                                                                |
| Navigation and state | Navigation 3 (JetBrains 1.2.0), KMP ViewModel 2.11                                                                                             |
| DI                   | Metro 1.5 (forge adapters registered as a multibinding map)                                                                                    |
| Data                 | Room 3 for persistence, DataStore for preferences, Ktor 3.6, Apollo 5.2, kotlinx.serialization 1.12                                            |
| Images               | Coil 3                                                                                                                                         |
| Engine               | Rust with gix-protocol, gix-pack, gix-diff, gix-attributes, imara-diff, tree-house plus Helix queries, and `linguist`; UniFFI 0.32 plus jni-rs |
| Testing              | kotlin.test, Turbine, Compose UI tests, Roborazzi on desktop; cargo tests and criterion benches for the engine                                 |
| Formatting and lint  | ktfmt through dprint, detekt 2                                                                                                                 |
| Desktop packaging    | Compose native distributions; Conveyor if we want auto-update                                                                                  |

**Android floor.** minSdk 26 (Android 8.0), compileSdk and targetSdk 37, NDK r30
(30.0.16248370), with the engine built by cargo-ndk.

- Compose needs 23. The renderer's fast path needs 31, but it keeps a `drawText`
  path anyway for font fallback, so a lower floor costs one branch. On an API 26
  emulator the review renders correctly through that path.
- 26 rather than 23 drops compat code we'd otherwise write: notification
  channels for inbox notifications, `java.time`, and adaptive icons.
- compileSdk is 37 because CMP 1.12.2's androidx dependencies require it.
- AGP 9 conventions: KMP libraries use
  `com.android.kotlin.multiplatform.library` with a `kotlin { android {} }`
  block, and `androidApp` uses `com.android.application` with AGP's built-in
  Kotlin, so there's no `org.jetbrains.kotlin.android` plugin. AGP 9.5.0 came
  out on 2026-10-28, after this table was checked.

**Module layout.** This follows JetBrains' 2026 structure, split further by
feature:

- `androidApp`, `desktopApp`: thin application shells
- `core/{model,data,net,ui}`
- `forge/{api,github,gitea,forgejo}`
- `engine` (Kotlin facade) and `engine-rs` (Cargo workspace)
- `codeview`
- `feature/{inbox,feed,thread,review,repo,auth,settings}`

## Licensing

- Helix queries are MPL-2.0. That's fine to bundle unmodified, and changes to
  those files must stay MPL.
- The `nvim-treesitter` queries are Apache-2.0 but use Neovim-only predicates.
- **Don't copy Zed's `language`, `editor`, or `languages` crates or its built-in
  queries.** They're GPL-3.0, which would make the whole app GPL. GPUI and
  `sum_tree` are Apache-2.0 and safe to borrow from.
- The app's own license is still undecided. See the questions below.

## Spike results

### 2026-10-28: fetch and diff, engine only

Measured with `tongs-spike` against maplibre/maplibre-native-ffi#760 (+273,875
−302,522 across 2,185 files), starting from an empty store, on home internet:

| Stage                          | Time   | Detail                                                     |
| ------------------------------ | ------ | ---------------------------------------------------------- |
| Metadata (REST pull + compare) | 1.3 s  | Merge base from `merge_base_commit`                        |
| Commits                        | 0.3 s  | `filter tree:0`                                            |
| Tree walk                      | 3.2 s  | 796 trees, 135 KiB, 12 sequential round trips              |
| Blobs                          | 0.9 s  | 2,633 blobs, 5 MiB, 6 requests of ≤500 wants, 4 concurrent |
| Diff every file                | 0.05 s | One thread, imara-diff histogram; slowest file 1.2 ms      |
| Total                          | 5.8 s  |                                                            |

Findings:

- **Fetching explicit OIDs works at scale on github.com.** That's 500 blob wants
  per request with no `have` lines, and nothing was throttled.
- **GitHub's API can't show this PR, and we can.** The REST files endpoint
  reports 0/0 for 254 files that contain 92k changed lines, because GitHub's
  per-PR diff budget runs out. The PR-level totals are still computed in full.
- **The diff itself costs almost nothing.** The bottlenecks are the tree walk,
  which is bound by round-trip latency, and the metadata calls. Ideas:
  - Overlap the tree walk with the metadata calls.
  - For small repos, fetch every tree up front with `blob:none` at depth 1.
  - Start fetching blobs for each directory level as soon as it resolves.
- **Line counts:** the total number of changed lines matches GitHub's, but our
  per-file counts sometimes differ, because GitHub aligns hunks with git's Myers
  diff and we use histogram. Matching GitHub is a non-goal. Our diffs only need
  to read well, and reading better than GitHub's is a win. Candidates:
  - slider and indent heuristics
  - moved-block detection
  - word-level refinement

- **Still missing:**
  - Rename detection. GitHub shows 5 renames here, and we count each as a delete
    plus an add.
  - `.gitattributes` handling, including nested files such as
    `bindings/.gitattributes`.

### 2026-10-28: canvas renderer, desktop

The `desktopApp` spike opens the same PR from the local store through JNI and
draws it with `codeview`. Measured on a 120 Hz display with OpenGL, at
1400×1000:

- **Opening the review** (tree walk, diff, and row model for all 602,759 rows):
  70 ms. The JVM heap stays at 6 MiB, because rows live in native memory.
- **Fling** (200 px/frame for 3,000 frames): draw CPU p50 0.09 ms, p99 0.27 ms.
  Frames are locked to vsync at 8.33 ms.
- **Scrub** (the whole review in 600 frames, so every row is new every frame):
  draw CPU p50 0.19 ms, p99 0.42 ms. Fetching rows over JNI takes p50 0.02 ms.
- **What made the difference:** the first version shaped each line with Skia's
  `TextLine` (HarfBuzz), which cost 2–3 ms per line and 42–130 ms per frame. The
  monospace fast path looks up glyphs directly and places them on a fixed column
  grid in a `TextBlob`. Lines with glyphs the font lacks still go through
  `TextLine` for font fallback. Android needs the same trick.

Not covered yet:

- syntax highlighting
- wide (CJK) characters on the column grid
- selection
- Android

### 2026-10-28: syntax highlighting

`tongs-highlight` highlights each side's whole file with tree-house and Helix's
queries, on background threads, into per-line spans cached by blob. `fillRows`
projects ready spans onto rows. Measured on a 32-thread Ryzen AI Max+ 395 while
two other agents were building, so timings are noisy; ranges are across runs.

**Languages.** 22 languages from 21 grammars statically linked from crates.io:
Bash, C, C++, C#, CMake, Dart, Go, Java, JavaScript, JSX, JSON, Kotlin, Markdown
(with inline), Python, Rust, Swift, TOML, TypeScript, TSX, YAML, and Zig. That
covers 2,144 of #760's 2,190 files. All the grammars are MIT. Most crates.io
versions match Helix's pins, with three exceptions:

- Swift is pinned to 0.7.2.
- C++ is a git dependency at Helix's revision.
- Dart uses `tree-sitter-dart-orchard` with a three-line patch to Helix's query,
  because the pinned grammar's crate brings its own tree-sitter runtime.

A unit test checks that every query compiles.

**Throughput.** Measured with the `highlight_review` example over every distinct
blob in #760: 2,570 blobs, 24 MiB, 706k lines.

| Measure                      | Result                                               |
| ---------------------------- | ---------------------------------------------------- |
| One core                     | 2.1–3.6 MB/s, 7–12 s for all of #760                 |
| All 32 threads, wall time    | 0.39–0.83 s, bound by the largest file               |
| Slowest file                 | 0.13–0.25 s alone (422 KiB of Go)                    |
| Parsing vs. queries          | Parsing with injections and locals is about 70%      |
| Query compile, all languages | 0.6–1.3 s; lazy per language, Swift slowest at 0.1 s |
| Spans for all of #760        | 18.9 MiB: 4.2M spans, 28 bytes per source line       |

**Rendering.** `--bench` at 1400×1000 on a 120 Hz display. Cold runs start with
an empty cache, and warm runs repeat the same path. Two runs each:

| Pass         | Draw CPU p50 | p99          | Rows drawn plain |
| ------------ | ------------ | ------------ | ---------------- |
| Fling, plain | 0.09–0.10 ms | 0.32 ms      |                  |
| Fling, cold  | 0.11–0.15 ms | 0.41–0.53 ms | 0.6%             |
| Fling, warm  | 0.09–0.23 ms | 0.21–0.50 ms | 0%               |
| Scrub, plain | 0.19 ms      | 0.35–0.41 ms |                  |
| Scrub, cold  | 0.20–0.32 ms | 0.41–0.56 ms | 91%              |
| Scrub, warm  | 0.22–0.24 ms | 0.40 ms      | 0%               |

Frames stayed at 8.33 ms p50 in every pass, and the JVM heap stayed at 6 MiB.
Scrubbing jumps about 1,000 rows a frame, so cold rows are plain until the
scrubbing stops, as intended.

**Binary size.** Each grammar's code and data, measured with `llvm-size` on its
static archive for `aarch64-linux-android` (NDK r28, API 26):

| Grammar          | MB   | Grammar    | MB   |
| ---------------- | ---- | ---------- | ---- |
| Kotlin           | 5.79 | Markdown   | 0.78 |
| C++              | 5.59 | Zig        | 0.69 |
| C#               | 5.33 | C          | 0.63 |
| Swift            | 3.38 | Python     | 0.45 |
| TypeScript + TSX | 2.87 | JavaScript | 0.42 |
| Bash             | 1.36 | Java       | 0.42 |
| Rust             | 1.12 | Go         | 0.22 |
| Dart             | 1.08 | YAML       | 0.19 |
| tree-sitter core | 0.19 | CMake      | 0.07 |
| TOML, JSON       | 0.04 |            |      |

All grammars together are 30.4 MB. Stripped `libtongs_jni` grows from 1.3 MB to
34.6 MB on x86-64. On arm64 it's 34.4 MB, or 4.2 MB gzipped, with 16 KB-aligned
segments.

Findings:

- **Highlighting is cheap to draw.** A `drawText` per run costs little more than
  one per line; warm scrubs take about 20% more draw CPU than plain text, still
  a quarter of a millisecond. Cold flings show plain text on 0.6% of rows.
- **Binary size is the real cost,** and five grammars are three quarters of it.
  With the Android spike merged, the release APK with both ABIs grows from 3.2
  MiB to 67 MiB, because native libraries are stored uncompressed. A per-ABI
  split still installs about 34 MB. Options, best first:
  - Play Feature Delivery for the big grammars.
  - Dropping C# and Swift from the Android build.
  - Sharing one C/C++ grammar.
- **Helix's queries track grammar `master`, not crates.io.** Each grammar bump
  needs the query compile test, and some need a git pin.
- **Throughput is lower than hoped,** at about 100k lines per second per core.
  The cache, viewport priority, and the 1 MiB cap keep that off the critical
  path. Incremental parsing doesn't help, since blobs never change. Skipping
  Helix's locals queries would save only 13%.

### 2026-10-28: canvas renderer, Android

The `androidApp` spike opens the same PR from a store pushed with adb and draws
it with the same `codeview`. Method:

- x86_64 API 36 emulator, 4 cores, `-gpu host` (Radeon 8060S through the
  emulator's GLES translator), 60 Hz, 1080×2400 at 420 dpi. That's about 40 rows
  of 38 columns of code.
- Release build with R8, AOT-compiled with `cmd package compile -m speed`, since
  there's no baseline profile yet.
- Two other agents were building on the same host (load average 8–22 on 32
  threads). These are emulator numbers, so they show relative costs, not what a
  phone does.
- Ranges are p50s across 3–5 runs.

Results:

- **Opening the review:** 99–157 ms, against 70 ms on desktop. After the passes
  the JVM heap holds 3 MiB and the native heap 69 MiB.
- **Text drawing.** This records 50 lines of 40 columns into a `RenderNode`, 600
  times:

  | Path                        | New lines every frame | Same lines every frame |
  | --------------------------- | --------------------- | ---------------------- |
  | `Canvas.drawText` (Minikin) | 0.19–0.27 ms          | 0.10–0.18 ms           |
  | Glyph grid (`drawGlyphs`)   | 0.03–0.04 ms          | 0.02–0.03 ms           |

  At row 300,000, both paths produce pixel-identical screenshots.

- **Fling and scrub.** These are the desktop passes, with draw CPU per frame:

  | Pass                       | Glyph grid p50 | Glyph grid p99 | `drawText` p50 | `drawText` p99 |
  | -------------------------- | -------------- | -------------- | -------------- | -------------- |
  | Fling, 200 px/frame ×3,000 | 0.20–0.33 ms   | 0.59–2.93 ms   | 0.42–0.70 ms   | 0.97–4.67 ms   |
  | Scrub, whole review in 600 | 0.18–0.27 ms   | 0.46–0.83 ms   | 0.65–0.86 ms   | 1.14–2.49 ms   |

  Row fetches over JNI take 0.04–0.09 ms at p50.

- **Frames.** The Choreographer interval is 16.67 ms at p50 in every run, but
  missed vsyncs range from 0 to about 1,100 per 3,600 frames, with no consistent
  difference between the two paths.
  - Misses track the RenderThread's `eglSwapBuffers` and GPU stages, whose p99
    reaches 40–70 ms in runs that miss.
  - UI thread work stays at 0.6–1.7 ms p99 in most runs, and 6.7 ms at worst.

  This looks like the emulator's host GPU pipe under contention, not the
  renderer. Judging jank needs a device.

Findings:

- **Android doesn't hit desktop's cliff.** Minikin caches layouts per word, so
  `drawText` costs about 4–5 µs per line, not the 2–3 ms of Skia's `TextLine`.
  Both paths fit the frame budget.
- **The glyph grid is still the default on API 31+.** It cuts text recording
  5–8× for new lines and 3–6× for cached ones, and total draw CPU 2–4×, which
  leaves headroom for highlighting. It needs `TextRunShaper` (API 31) to get the
  monospace `Font` and glyph IDs. A UTF-16 unit goes on the grid only if shaping
  it alone gives one glyph from that font, one column wide. Any other character
  sends the whole line to `drawText`.
- **`libtongs_jni`** is 1.16 MiB on arm64-v8a and 1.27 MiB on x86_64 after AGP
  strips it (546 and 582 KiB gzipped). Before stripping it's 16.6 and 15.8 MiB,
  because the release profile keeps line tables.
  - The whole release APK, with both ABIs, is 3.2 MiB.
  - NDK r30 aligns the load segments to 16 KB, and `zipalign -P 16` passes.

Not covered yet:

- a physical device, and a baseline profile
- tabs and wide characters (the same gap as desktop)
- touch input beyond a hand check: a swipe flings vertically and scrolls
  horizontally

### 2026-10-28: in-app fetch, progressive review

`./gradlew :desktopApp:run --args="--pr owner/repo#N"` now opens any GitHub PR
by fetching it in the app. Kotlin does the HTTP with Ktor and OkHttp. The engine
plans each request, and the response body streams into it as it arrives, so a
pack is never buffered whole. Each response produces a new immutable `Review`
snapshot. Files are shared between snapshots through `Arc`, so a snapshot costs
one prefix-sum array. `CodeViewState.swapRows` swaps it in and keeps the top row
on the same file and row.

Measured on maplibre/maplibre-native-ffi#760 with `--exit-when-loaded`, three
runs each, on home internet. Two other agents were building on the machine at
the time, so the numbers are noisy. Times run from app start, after JVM startup
and the `gh auth token` call, to the frame that shows the result. Metadata alone
took 0.55–0.77 s.

| Run                        | Headers on screen | First diff | Everything | Git requests      |
| -------------------------- | ----------------- | ---------- | ---------- | ----------------- |
| Cold store                 | 0.90–1.11 s       | 1.03–1.22  | 1.50–2.01  | 14 (5 MiB)        |
| Warm store                 | 0.81–0.88 s       | same       | same       | 0                 |
| CLI, cold, same hour       | none until done   |            | 5.4–6.1 s  | 19 (12 for trees) |
| CLI, cold, earlier (above) |                   |            | 5.8 s      |                   |

The cold app makes 14 git requests: one for both commits with every tree, then
13 blob batches. It also sends a capability probe that warms the connection, and
two REST calls. On a warm store, the time is the PR metadata call, plus 115–125
ms to walk, diff, and draw all 602,759 rows. The app would read that metadata
from its database.

What made the difference:

- **Whole trees with the commits.** Most of the CLI's 2.8–3.2 s tree walk was 12
  round trips for 135 KiB. One `deepen 1` and `blob:none` request returns all
  864 objects of both revisions in 0.25 s. Whole trees grow with the repo, and
  the forge's repo size is the cheapest predictor of what they cost:

  | Repo                | Repo size | All trees, one revision | Time   |
  | ------------------- | --------- | ----------------------- | ------ |
  | maplibre-native-ffi | 20 MB     | 145 KiB, 864 objects    | 0.27 s |
  | rust-lang/rust      | 1.0 GB    | 2.1 MiB, 4,650 objects  | 0.6 s  |
  | microsoft/vscode    | 1.6 GB    | under 1 MiB             | 0.4 s  |
  | NixOS/nixpkgs       | 3.5 GB    | 3.5 MiB, 38,899 objects | 0.9 s  |
  | torvalds/linux      | 6.4 GB    | 3.0 MiB, 6,340 objects  | 1.6 s  |
  | chromium/chromium   | 68 GB     | 17 MiB, 45,687 objects  | 37 s   |

  Above 2 GB the app walks instead. On nixpkgs#580183, a one-line change 5
  directories deep, the walk takes 5 round trips of about 170 ms each and
  fetches 14 objects.

- **Not waiting for the merge base.** The fetch starts from the PR's base while
  GitHub's compare API runs, and the review shows once the head's parents prove
  the base is its ancestor. On microsoft/vscode#344790 the base had moved on, so
  the review restarted from the real merge base for one extra request.
- **Warming the git connection** during the metadata call took the first fetch
  from about 0.5 s to 0.25 s.
- **Small first batches.** Batches start at 32 wants, so the top file's diff
  lands about 120 ms after the headers. When the view jumps 60% of the way into
  the review, the visible files' batch goes ahead of the queue, and they show
  375 ms later.

Findings:

- **A latent bug from the CLI spike:** wanting a commit with `tree:0` but
  without `deepen` sends its whole history. That's harmless on
  maplibre-native-ffi, but on nixpkgs it meant 1.1M objects and 500 MB, taking
  16 s. Commit wants now always carry `deepen 1`.
- **github.com allows only `tree:0`.** It answers `tree:1` with "tree filter
  allows max depth 0" and `combine:` with "filter 'combine' not supported".
  Batching sibling subtrees is already what the per-level walk does.
- **Codeberg (Forgejo 16) supports everything the engine needs.** Anonymous
  protocol v2 on forgejo/forgejo advertises
  `fetch=shallow wait-for-done filter`. It serves:
  - commits by OID with `deepen 1`, including a PR head that lives in a fork
    (through `refs/pull/N/head`)
  - single trees by OID with `tree:0`
  - several blobs by OID with `blob:none`
  - all trees in one request: 1,711 objects, 340 KiB, 1.3 s
  - `tree:1` and `combine:tree:2+blob:none`, which github.com rejects

  Each probe ran on a new connection and took 0.55–0.65 s there, against
  0.2–0.55 s on github.com. Forgejo's pulls API also returns `merge_base`. One
  API quirk: `/git/commits/{sha}` reports the commit's own SHA as
  `commit.tree.sha`.

- **Interop:** the session went through jni-rs as well, about 20 calls of
  handles and strings. UniFFI wasn't needed for it.

Storage: each repo gets its own object store at
`<cache>/tongs/<host>/<owner>/<repo>/objects` (`$XDG_CACHE_HOME` on desktop).
Each response adds a pack, so the store only grows: a cold #760 leaves 14 packs.
Still to do: merging small packs, and LRU eviction by bytes, with the repo as
the unit.

Not covered yet:

- Forgejo in the app
- Blob batches sized by bytes; all blob sizes are unknown up front
- `have` lines, so small repos on a warm store don't fetch every tree again
- Estimating row counts for files still loading; the scrollbar grows as they
  load
- Cancelling requests for files that scrolled away

### 2026-10-28: the three spikes merged

Highlighting, Android, and in-app fetch merged into `main`. Fetched snapshots
carry the shared highlighter, so a PR opened with `--pr` is highlighted as it
loads. Rerun on the desktop with no agents building (host load average about 11
from other work):

- **Cold `--pr` load of #760**, from app start: fully loaded and on screen in
  2.1–2.8 s over 14 requests (5 MiB of packs).
- **`--bench` on the stored review, with highlighting:**

  | Pass  | Draw CPU p50 | p99     | Frame p99 | Rows drawn plain |
  | ----- | ------------ | ------- | --------- | ---------------- |
  | Fling | 0.12 ms      | 0.45 ms | 9.25 ms   | 0.6% (cold)      |
  | Scrub | 0.21 ms      | 0.52 ms | 9.30 ms   | 89% (cold)       |

  Warm passes are the same within noise. The review opens in 77 ms, and the JVM
  heap stays at 6 MiB.

## Plan

0. **Spike the engine first. It's the main risk.**
   - Fetch trees and blobs by OID from github.com and codeberg.org, with Kotlin
     doing the HTTP and Rust ingesting the packs.
   - Diff and highlight, then render one real huge PR in the desktop canvas
     renderer.
   - Measure against the targets above.
   - Prove out three things:
     - fetches of explicit OIDs work at scale
     - undocumented git rate limits don't bite
     - grammar size is acceptable on Android
1. **Skeleton.**
   - Accounts: GitHub device flow and PAT.
   - Room store and sync.
   - Inbox and read-only thread view.
   - Android and desktop shells.
2. **Review.**
   - Full PR review: viewed state, comment drafts, submitting reviews, merging,
     CI.
3. **Repo browser and feed.**
4. **Forgejo adapter.** Gitea-family core, PAT auth, capability-driven UI.
5. **Polish and distribution.**
   - Android performance work and baseline profiles.
   - Desktop packaging.
   - F-Droid build from source, including Rust.

## Risks

- **Server behavior.**
  - Fetching explicit OIDs with filters works on github.com, but nobody has
    tested it at thousands of wants.
  - GitHub's rate limits for git over HTTPS are undocumented.
  - Forgejo admins can disable partial clone. Fallback: Forgejo's batch blob
    API.
- **Grammar size.** The 22 starter languages add 33 MB to `libtongs_jni`
  uncompressed (4 MB gzipped), and Android stores native libraries uncompressed.
  Kotlin, C++, C#, Swift, and TypeScript alone are 23 MB. Mitigations:
  - Start with about 20 languages.
  - Fall back to plain text for the rest.
  - Consider a TextMate engine for the long tail (syntect is MIT).
  - Consider Play Feature Delivery packs.

  Downloading native code outside Play violates Play policy.

- **Library maturity.**
  - tree-house is at 0.x and its API changes between releases.
  - Gobley's release cadence is slow.
  - UniFFI's zero-copy writes are still unreleased, which is why the hot path
    uses jni-rs.

## Questions for you

These are product calls, not technical ones:

- **License.** GPL or Apache/MIT? This mostly matters for what we can borrow and
  for F-Droid.
- **Distribution.** Google Play, F-Droid, or both? This affects grammar
  packaging.
- **Name.**
