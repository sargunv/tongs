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

  It follows them with packed token runs (`u32 start | u16 len | u16 styleId`)
  and an epoch number that tells Kotlin when highlighting has replaced plain
  text. UniFFI's JNA bindings cost about 4 µs per call, which is too slow per
  frame.

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
2. **Trees.**
   - Fetch the root trees of the merge base and the head with `filter tree:0`.
   - Then walk only the subtrees whose OIDs differ, one batched round trip per
     directory level.
   - The cost scales with the size of the change, not the size of the repo.
   - Live tests confirmed github.com serves single trees and blobs by OID with
     protocol v2 filters. Codeberg supports `blob:none` and `refs/pull/N/head`.
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
   - A priority queue is ordered by distance from the viewport and scroll
     direction.
   - It sends requests of about 50–200 wants, with no `have` lines.
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
   - The whole old and new files are parsed, because hunk-only lexing gets
     multi-line strings and comments wrong. Queries then run only over the
     requested row ranges.
   - Size and line-length caps apply, and generated or minified files are
     skipped.
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

- Visible rows are drawn directly on a Compose `Canvas` from a token-run cache
  keyed by `(row identity, epoch)`.
- Composables are used only for interactive overlays: comment threads, the
  composer, and selection handles.
- Below the shared Compose drawing API, each platform has a small
  implementation:
  - Desktop: Skiko `TextLine` and `drawTextLine`, one shaped run per token run,
    with x = column × advance.
  - Android: `Canvas.drawText` with cached `Paint` objects.

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
- fetching and progressive loading inside the app
- wide (CJK) characters on the column grid
- selection
- Android

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
- **Grammar size.** About 23 common languages come to roughly 31 MB
  uncompressed, and Android stores native libraries uncompressed. Mitigations:
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
