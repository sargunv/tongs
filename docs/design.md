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
│ highlighting: tree-house + Helix queries, grammars bundled per OS    │
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
   - **As built** (`classify.rs`): the tree walk notes the `.gitattributes` blob
     in every directory it expands on the head side. Those are exactly the files
     that can apply to a change, since a changed file's directories all differ.
     They go out ahead of the first blobs, in a request of their own, and files
     regroup when they land. `linguist-generated`, `linguist-vendored`, and
     `-diff` (or `binary`) are read at any depth, deeper files and later lines
     winning; an explicit attribute beats every heuristic. Then lockfile names,
     vendored directories, generator suffixes (`.pb.go`, `.g.dart`, `.min.js`,
     …), and, once a file loads, `@generated` or `DO NOT EDIT` in its first
     lines or minified content. #760's 300-line `bindings/.gitattributes` groups
     its generated bindings this way.
   - **Exact renames,** as built: once the walk ends, each deleted file pairs
     with an added file of the same blob (not the empty blob). A pure rename
     needs no blobs and shows as one file, `old → new`. Similarity renames are
     still to do.
   - **Display order:** ordinary files first, then generated, lockfiles,
     vendored, and renamed, each in tree order (a directory's contents before
     the files beside it). The diff and the file tree share this order, so
     scrolling one walks the other.
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
   - Desktop links the grammars in. Android bundles each as its own shared
     library, compressed, inside `libtongs_jni`, because Android stores native
     libraries uncompressed and the grammars are 30 MB. The first file that
     needs a grammar extracts it to the app's cache and loads it, on the
     highlighting thread, so that file stays plain until it lands, like any
     other pending highlight.
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
    `Canvas.drawGlyphs` (API 31). Lines with characters off the grid use
    `Canvas.drawText`.

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

**Reading layout, as built.** A snapshot can be laid out for reading
(`Review::with_layout`): collapsed files keep only their header, and inserted
blocks reserve whole rows under a line. Rows stay fixed-height, so scrolling is
still division, and a layout is cheap: files are shared, and only the per-file
row counts are rebuilt. `codeview` composes UI only for the blocks on screen,
sized to their rows: review threads, pending comments, and the composer. When a
layout changes, `swapRows` finds the top row by its content (`find_row`), so
opening a block above the reader doesn't move what they're reading.

**Selection, copy, find, accessibility.**

- Selection is our own: line-range selection is the primary gesture and starts
  the comment flow. Character selection is supported too.
  - **As built:** a tap on a line number picks the line. A mouse drags across
    numbers at once; touch long-presses first, so a swipe over the gutter still
    scrolls. A range stays within one file and one hunk, which is what a forge
    can anchor. Character selection isn't built yet.
  - The gutter is as wide as the line numbers shown (at least 3 digits), and
    only grows, so text doesn't shift back and forth while scrolling. Six digits
    a side took a third of a folded phone.
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

As built, the anchor also carries `startSide`, defaulting to `side`, because a
range can start on removed lines and end on added ones, which GitHub anchors
with `start_side: LEFT` and `side: RIGHT`. Forgejo's single-side positions can't
express that, so its adapter will have to narrow such a range.

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
- **Token storage.** Tokens never go in the database.
  - Android encrypts each with an AES-GCM key held in the Android Keystore and
    keeps the ciphertext in the no-backup dir, since the key can't move to
    another device. The app opts out of backup.
  - Desktop uses the OS keychain through java-keyring: the Secret Service (GNOME
    Keyring, KWallet) on Linux, Keychain on macOS, Credential Manager on
    Windows. Without one, tokens go in a file only the user can read, in the
    app's data dir, which is what `gh` does too.
- **What's built.** Sign-in takes a classic PAT and checks it with GraphQL
  `viewer`, reading its scopes from `X-OAuth-Scopes`. A token missing requested
  scopes still signs in, with a warning; `repo` also grants the notifications
  API. Desktop can import `gh auth token` as a developer shortcut. The device
  flow is modeled (`DeviceFlow`, `awaitToken`, and GitHub's implementation) but
  has no UI until there's an OAuth App client ID.

## Home screens

**Inbox.**

- **GitHub.**
  - Poll `/notifications` with `If-Modified-Since`, respecting
    `X-Poll-Interval`. A 304 doesn't cost rate limit.
  - Hydrate rows in batches with GraphQL, so each row shows PR state, review
    decision, and CI rollup without opening it. Aliases rather than
    `nodes(ids:)`: notifications carry no subject node IDs, and each thread
    needs its own `since`.
  - "Done" is `DELETE /notifications/threads/{id}`.
- **Forgejo.** Poll `/notifications/new`, then fetch. Forgejo notifications have
  no reasons, and it offers pinning.
- **Unified across every account and forge.** That's a differentiator no current
  client has.
- **Keep GitHub's semantics.** One list, newest update first, with read, unread,
  and done meaning exactly what the API means. No reclassifying into our own
  buckets: `reason` says why you're subscribed, and it's sticky. Of 50 recent
  notifications on the owner's account, 37 had reason `author`, whatever
  actually happened (a push, CI, a merge). The one exception GitHub forces: it
  can't report done, so a thread done elsewhere stays here, marked read
  elsewhere, until it's done here (see "How sync works, as built").
- **What's new is added, not inferred.** Each row shows GitHub's reason, plus a
  second line saying what happened since `last_read_at` ("2 new commits",
  "checks failed", "new comment from …"). That comes from hydration: GraphQL
  `timelineItems(since: lastReadAt)` per thread, batched with aliases. A thread
  you were mentioned in that got a new commit reads "Mentioned" and "1 new
  commit".
- **Triage like email:**
  - Swipe actions are configurable per direction (done, snooze, read or unread,
    none). The defaults are swipe left for done and right for snooze.
  - Snooze is local: it hides the thread until a time, then it reappears as
    unread. GitHub has no snooze API.
  - Filters and saved views are stored locally, since GitHub has no API for
    them, and use GitHub's own vocabulary: `reason:`, `is:pr`, `repo:`,
    `is:unread`, like GitHub's web inbox.
  - Grouping by repo.
  - Drafts show on their thread's row, like email drafts.
- Polling happens only while the app is in the foreground and on resume, since
  push notifications are out of scope.
- **How sync works, as built.**
  - **GitHub can't list what's still in the inbox once it's read.** With
    `all=true`, the notifications API also returns threads marked done, on the
    web or with `DELETE /notifications/threads/{id}`, as plain read threads
    (`unread: false`); nothing tells them apart (measured on 2026-10-28: a
    thread marked done was still listed minutes later, and the owner's
    `all=true` list held 150 read threads while their web inbox was at zero). A
    single thread's GET has no done field either, and GraphQL's
    `notificationThreads`, which GitHub's own apps use, isn't public ("Field
    'notificationThreads' doesn't exist on type 'User'"). Reported, unanswered:
    [community discussion 152852](https://github.com/orgs/community/discussions/152852).
    Only unread is certain: new activity brings a done thread back unread. So
    polls ask for unread threads only.
  - **So Tongs keeps its own inbox.** A thread joins when GitHub reports it
    unread, and leaves only when it's marked done in Tongs. One that stops being
    unread without Tongs reading it stays, read, and is marked **read
    elsewhere**: GitHub can't say whether it was read or done there, and
    dropping it would also drop threads that were only read, which are still in
    the GitHub inbox. New activity (unread again) clears the mark, and so does
    reading or opening it here. "Mark done: read elsewhere" in the overflow menu
    clears them from the current view at once, with the bulk undo window;
    sending done for a thread already done on GitHub is harmless.
    `is:read-elsewhere` filters to them. The rule is in `InboxSync.merge`.
  - **The first sign-in starts from unread threads only.** Older threads that
    are read but not done on GitHub can't be told from done ones, so they're
    never listed; the inbox grows from there.
  - With `If-Modified-Since`, a changed inbox answers with only the threads
    updated since, as if `since` were set, not the whole first page. Such a poll
    covers only what it lists. The first poll of a session, one every 15
    minutes, and every refresh omit the cursor, so threads read or done
    elsewhere are noticed. Each poll reads 50 a page, up to three pages. The
    poll interval and `Last-Modified` are stored per account, so a restart
    within the interval doesn't poll early.
  - Failures are typed (`ForgeException`) and stored per account with their
    kind: offline, token rejected, token can't read notifications, rate limited
    (sync waits for the reset), SAML SSO required (with the authorization link).
    A poll that succeeds but that GitHub filtered for SSO
    (`X-GitHub-SSO: partial-results`) names the organizations, and their threads
    aren't treated as read elsewhere. Otherwise an account waits at least two
    minutes after an error.
  - Done waits five seconds before `DELETE` (eleven for "mark all as done"),
    since GitHub has no way to bring a thread back; undo inside the window
    cancels it. Reads go at once. A done or read the app didn't get to send,
    because it closed, goes out on the next start. A failed call undoes the
    change with a message.
  - **GitHub has no API to mark a thread unread.** Neither REST nor the public
    GraphQL schema has one. So "unread" is local, like snooze, until the thread
    is read again; a forge that has one would advertise `inbox.markUnread`.
  - Snoozing marks the thread unread locally, hides it, and sorts it by the
    snooze's end once it's back. A snoozed thread stays snoozed through new
    activity, as in email. `is:snoozed` lists them.
- **Hydration, as built.** After each poll, threads whose latest update hasn't
  been hydrated (up to 150, newest first) go to `InboxHydrator`. GitHub's sends
  one GraphQL request per 50 threads: `InboxHydration.graphql` is one thread's
  query, checked against the schema and parsed by Apollo, and the request
  repeats its `repository` field under an alias per thread with the variables
  suffixed. Each alias reads the subject's state, the head commit's check
  rollup, the review decision, and `timelineItems(since: lastReadAt, last: 20)`.
  - A thread is hydrated once per update. Reading it doesn't change its line,
    which keeps saying what was new when it last changed, like an email's
    snippet, and saves a request per read.
  - What's new is summarized forge-neutrally (`summarizeWhatsNew` over
    `SubjectActivity`), most important first, leaving out your own actions:
    "Review requested from you", "Merged by alice", "Changes requested by bob",
    "Checks failed" (failing checks on a commit newer than your last read,
    whoever pushed it), "2 new comments from alice and bob", "3 new commits".
  - `timelineItems(since:)` filters commits by their commit date, so commits
    made before your last read but pushed after it don't count as new.
  - **Cost, measured with GraphQL's `rateLimit { cost }`:** 1 point per 50
    threads (two connections each: `commits(last: 1)` and
    `timelineItems(last: 20)`, so 100 requests, which rounds to 1). A cold sync
    of 150 threads cost 3 points and took about 4 seconds with the three batches
    in parallel; an unchanged inbox costs nothing (a 304, and nothing to
    hydrate); a poll with new activity costs 1 REST request and 1 point. With
    GitHub's 60-second poll interval that's at most 60 points an hour of the
    5,000.
- **Triage and views, as built.**
  - Rows show the repo and number, the time, the title, GitHub's reason (in the
    primary color when it asks something of you), and what's new. The icon's
    color is the subject's state (open green, merged purple, closed red, draft
    gray; issues closed as completed purple, not planned gray). An open PR's
    checks and review decision follow as small marks. A thread read or done
    elsewhere ends its reason line with "Read elsewhere", in the line's muted
    color, so it wraps rather than crowding the repo on a phone.
    `InboxRow.draft` is the seam for reply drafts, always null until the thread
    feature stores them.
  - Filters use GitHub's vocabulary (`reason:`, `is:pr`, `is:issue`,
    `is:unread`, `repo:`, `org:`), plus `is:open`, `is:merged`, `is:closed`, and
    `is:draft` from GitHub's search, and Tongs' own `is:snoozed` and
    `is:read-elsewhere`. Terms of one kind are alternatives, a leading `-`
    negates, and anything else matches the title. Saved views are named filters,
    local in Room, and each can group by repo; the default view is one
    chronological list.
  - "Mark all as done" acts on the current view, and so does "Mark done: read
    elsewhere", which marks done only its rows read or done elsewhere.
  - Desktop: hover actions over the row's end, and `j`/`k`, Enter or `o`, `e`,
    `s`, Shift+U, Shift+I, `z` (undo), `/` (filter), `r`, Esc, and `?`. Snooze
    presets show their times and take number keys.

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
  - PR changes use three panes: file tree, diff, and conversation or comments.
- **As built.** Each `Route` declares its pane: list, detail, or full window.
  - Two panes from medium width up (600 dp), not Material's default of expanded
    (840 dp), so an unfolded foldable around 700 dp shows the list beside the
    thread. The list starts at half the width on medium windows and 40% on wider
    ones, and a drag handle resizes it.
  - The detail pane is a rounded card a tone off the background.
  - Opening a thread while another is open replaces it rather than stacking.
  - A bottom bar when folded, hidden on screens below the top level; a rail
    otherwise, always shown.
  - PR changes and Settings take the full window beside the rail; Activity too,
    until it has items to open.

**Screens.**

- **Inbox and Feed** are the top-level destinations, with an account and
  instance switcher.
- **Thread (issue or PR conversation).**
  - One scroll, not tabs: a summary card (for PRs: changes and viewed progress,
    checks, reviews), the primary action, the description, then the timeline.
    The owner leans this way but isn't sure, so revisit it once it's in use.
  - Timeline items include typed events.
  - Reactions, labels, and assignees.
  - Sub-issues and dependencies where the capability allows.
  - **As built.**
    - Order: title and a state pill, the summary card, the primary action, the
      description, the timeline, then Reply and Quote. The summary card has one
      row each for changes (files, +/−, commits, and a viewed bar from GitHub's
      `viewerViewedState`), checks (expandable to the failing and running ones
      first), reviews (verdicts and who's still requested), mergeability with
      head → base, labels, assignees, and milestone. Issues show only the
      metadata rows.
    - The primary action is Changes; Merge joins it, filled, when the PR is
      open, approved or not requiring review, mergeable, its checks green, and
      the account can merge. Merge asks for a method, from those the repo
      allows, and sends `expectedHeadOid` so a push since loading fails it.
    - The timeline collapses runs: consecutive commits read "mona added 3
      commits", one person's label changes within ten minutes read as one line,
      and a merge hides the closed event GitHub records with it. Reviews show
      their body and the review comment threads they started, each collapsed to
      `file:line`, its resolved and outdated tags, and a one-line peek; it
      expands to the diff hunk's tail and the comments. Reviews that only
      replied in other threads are one quiet line. Events are one line each, an
      icon in the avatar column, with closes and merges in their state colors.
    - Long timelines load their first 15 and last 40 items, with "N hidden items
      · Load more" between them, which pages forward from the start. A refresh
      replaces both ends.
    - Opens from Room at once and refreshes in the background on every open;
      pull to refresh on touch, a refresh button on desktop. A failed refresh
      keeps the cache and says why only when there's nothing cached.
    - Links in bodies to issues and PRs open in the app and stack, so back
      returns (`Navigator.push`); picking from the inbox still replaces. `@user`
      and commit links open the browser until those screens exist.
- **PR changes.** Reading a PR's changes and reviewing it are the same screen,
  opened from a "Changes" entry on the PR, not a "Start review" button: people
  read diffs without meaning to review. One virtual surface over all files.
  - Reviewing starts implicitly: the first line comment creates a pending
    review, and the top bar then shows the pending count and "Finish review".
    When your review is requested, approve and request changes are always one
    tap away.
  - File tree: a real tree, with collapsible directories, single-child chains
    compacted (`bindings/kotlin/…/ffi`), viewed state rolled up per directory,
    and a fuzzy filter on path, extension, and CODEOWNERS owner. Generated,
    lockfile, vendored, and rename-only files are grouped and collapsed
    automatically.
  - Viewed state syncs with GitHub's `markFileAsViewed` and is also kept
    locally, keyed by `(path, blob OID)`, so it resets by itself when a file
    changes.
  - An overview gutter scales each file by its lines changed.
  - A revision picker supports "since last review" and any commit range.
  - **As built:**
    - Layout follows the window: below 560 dp of content the diff takes it all,
      with the tree in a full-height sheet and a bottom bar for Approve, Request
      changes, and Finish review. From 560 dp, as on an unfolded foldable beside
      the rail, the tree is a pane. From 1000 dp, as on desktop, a third pane
      holds the pending review (its comments, summary, verdict, and submit) and
      the PR's threads. The tree and review panes toggle from the top bar.
    - Viewed and generated files collapse in the diff to their header, which
      shows a chevron, `+a −d`, and a viewed box; tapping the header opens or
      folds it, and `v` toggles viewed on desktop. Picking a generated file in
      the tree opens it.
    - The tree folds single-child chains, eliding long ones in the middle
      (`bindings/kotlin/…/ffi`), rolls viewed counts up per directory, and
      follows the diff, unless the reader is scrolling it. Its filter matches
      each word as a subsequence within one path component (a word with `/`
      matches across them); plain subsequences matched nearly everything in
      #760. Flattening runs off the main thread; 2,190 files take a few
      milliseconds.
    - Viewed state is optimistic, like inbox triage, and serialized per account
      so the forge sees toggles in order; a refusal undoes it. Once the file
      list is complete, the forge's state for the current versions is merged in,
      leaving pending changes alone.
    - Existing threads show inline, read-only: resolved ones fold to one line,
      and a card opens the whole thread in the side pane or a sheet. Comment
      bodies show as plain text until Markdown rendering lands.
    - The pending review lives in Room (`changes_draft_comment`,
      `changes_draft_review`) and goes out as one REST create-review call,
      pinned to the commit the drafts were written against. On failure it all
      stays.
    - The composer is a seam (`CommentComposer`): the inline one reserves rows
      under the lines; one that reserves none shows in the side pane or a sheet,
      which suits the thread screen's email-style composer.
- **Commenting, modeled on email.**
  - The thread keeps the timeline. Its end has Reply and Quote actions rather
    than an always-open text box, and each comment has "Quote reply".
  - Reply opens a full composer: a full-screen sheet when folded, a docked pane
    beside the thread when unfolded or on desktop. It has a Markdown toolbar,
    preview, and attachments, and its send actions are Comment and Close with
    comment.
  - Drafts autosave per thread and survive restarts, like email drafts.
  - In the diff, long-press or drag across a line range opens the same composer.
    A "suggest change" action prefills the selected lines.
  - The pending review is drafted locally first, so drafts survive offline and
    crashes, then submitted as a batch.
  - **As built.**
    - The composer is `Composer` in `core/composer`, which `feature/review` can
      reuse for line comments without depending on `feature/thread`. It takes a
      `ComposerState` (from `rememberComposerState(draftKey, drafts)`), a title,
      and a list of `ComposerAction`s, and fills the space it's given; where it
      sits is the caller's choice.
    - The thread's composer is its own route, `Route.ThreadReply`, in a new
      `Pane.Extra`: Material's list-detail extra pane. Unfolded or on desktop it
      docks beside the thread and the inbox makes way, so the thread stays
      readable while you write; folded it covers the screen. Back closes it, and
      the draft stays.
    - Drafts live in Room's `draft` table, keyed by account and `DraftKey`
      (thread plus a slot: `reply`, or e.g. `review/<path>:<line>`). The
      composer saves 400 ms after typing stops and again when it leaves the
      screen, deletes the draft when emptied, and adopts changes made elsewhere,
      so "Quote reply" appends to an open composer. The end of a thread with a
      draft says Draft instead of Reply, and inbox rows show the draft's start.
    - Sending is optimistic: the composer closes, the comment shows at once as
      "Sending…", and the draft is deleted only once GitHub has the comment. If
      GitHub refuses, the comment disappears, the draft stays, and a snackbar
      says so. Close and Reopen with comment post the comment first, then change
      the state, then refetch for GitHub's own event.
    - The toolbar has bold, italic, strikethrough, heading, code (a fence when
      the selection spans lines), link, quote, lists, task list, and mention;
      Ctrl/⌘ B, I, E, K format, Ctrl/⌘+Enter comments, and Escape closes.
      Preview renders exactly as the thread will. No attachments yet.
    - Reactions are GitHub's fixed eight, as chips under each body and comment
      with an add button; tapping toggles optimistically and refetches if GitHub
      refuses.
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

**As built** (`core/markdown`, renderer 0.46.0):

- The linkifier walks the JetBrains Markdown AST and splices links into plain
  text only, never into code, links, or HTML. `#123` and SHAs need the thread's
  repo; links point at the web URL, which `ProvideForgeLinks` turns back into an
  in-app route.
- The renderer drops HTML, so common GitHub HTML is rewritten first: comments
  removed, `<img>` and `<a>` to Markdown, `<summary>` and headings to bold
  lines, `<br>` to breaks. `<details>` doesn't collapse; `bodyHTML` isn't used
  yet.
- Code fences are highlighted by the engine, through a JNI call that runs
  `tongs-highlight`'s whole-file highlighter on the snippet (`highlightSnippet`)
  off the main thread; the fence shows plain first. Unknown languages, snippets
  over 256 KiB, and builds without the native library stay plain.
- Images and avatars load through one Coil 3 `ImageLoader` on the app's Ktor
  client, with a disk cache. Requests carry no token, so images on private repos
  may not load.
- Single newlines are line breaks, as in GitHub comments, and bodies under 4,000
  characters parse during composition so lists don't jump; longer ones parse off
  the main thread.

## Stack

All versions verified against releases on 2026-10-28.

| Area                 | Choice                                                                                                                                         |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Language and UI      | Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.4.1, Gradle 9.8 with a version catalog                                                      |
| Material             | Material 3 Expressive (`org.jetbrains.compose.material3` 1.12.0-alpha03), material3-adaptive 1.3.0, material-kolor 5 for desktop colors        |
| Navigation and state | Navigation 3 (JetBrains 1.2.0), KMP ViewModel 2.11                                                                                             |
| DI                   | Metro 1.5 (forge adapters registered as a multibinding map)                                                                                    |
| Data                 | Room 3.0 (`androidx.room3`) with bundled SQLite 2.7, DataStore 1.3, Ktor 3.6, Apollo 5.2, kotlinx.serialization 1.12, kotlinx-datetime 0.8     |
| Images               | Coil 3.6 (`coil-network-ktor3` on the app's client); Markdown through mikepenz's multiplatform-markdown-renderer 0.46                          |
| Engine               | Rust with gix-protocol, gix-pack, gix-diff, gix-attributes, imara-diff, tree-house plus Helix queries, and `linguist`; UniFFI 0.32 plus jni-rs |
| Testing              | kotlin.test, Turbine, Ktor's mock engine, Compose UI tests, Roborazzi 1.77 on desktop; cargo tests and criterion benches for the engine        |
| Formatting and lint  | ktfmt through dprint; detekt 2 once it's out of alpha                                                                                          |
| Desktop packaging    | Compose native distributions; Conveyor if we want auto-update                                                                                  |

**Android floor.** minSdk 31 (Android 12), compileSdk and targetSdk 37, NDK r30
(30.0.16248370), with the engine built by cargo-ndk.

- 31 is what the renderer's fast path needs: `TextRunShaper` and
  `Canvas.drawGlyphs`. With one text path, the only `drawText` left is the
  per-line fallback for characters the glyph grid can't place.
- It also drops compat code we'd otherwise write: the platform splash screen,
  dynamic color without a version check, notification channels, `java.time`, and
  adaptive icons.
- Nothing concrete justifies a higher floor yet. The one workaround in the code
  that a later floor removes is in `Review.kt`: a relative `ByteBuffer.get`
  instead of the absolute bulk `get` from API 35. That isn't worth dropping
  Android 12 to 14. Edge-to-edge and the notification permission look
  version-bound, but they follow targetSdk, not minSdk.
- compileSdk is 37 because CMP 1.12.2's androidx dependencies require it.
- AGP 9 conventions: KMP libraries use
  `com.android.kotlin.multiplatform.library` with a `kotlin { android {} }`
  block, and `androidApp` uses `com.android.application` with AGP's built-in
  Kotlin, so there's no `org.jetbrains.kotlin.android` plugin. AGP 9.5.0 came
  out on 2026-10-28, after this table was checked.

**Corrections from building the app,** on 2026-10-28:

- **Expressive is available now.** CMP versions Material 3 apart from the rest
  of Compose, and the one CMP 1.12.2 pairs with, 1.12.0-alpha03 (Jetpack 1.5.0-
  alpha22), has `MaterialExpressiveTheme`, `MotionScheme.expressive()`, and the
  Expressive components, behind `ExperimentalMaterial3ExpressiveApi`. There's no
  stable CMP Material 3 past 1.9.0.
- **Room 3 is its own artifact and package:** `androidx.room3:room3-*` and
  `androidx.room3.*`, KSP only, and a `SQLiteDriver` is required. Its builders
  need no `Context`, so the database opens from common code with a path.
- **Material icons stopped at 1.7.3**, deprecated in favor of Material Symbols.
  `TongsIcons` keeps the few we use as path data.
- **Apollo's Ktor engine** (`apollo-engine-ktor`) is only at 1.0.0-alpha, so
  `forge/github` has a 50-line adapter that sends Apollo's requests through the
  app's Ktor client.
- **detekt 2** is still alpha (2.0.0-alpha.6), so it isn't set up yet.
- **Nucleus** (2.5.18) isn't adopted. Its decorated window needs
  `nucleusApplication` and its Tao windowing backend in place of AWT, or a
  deprecated backend that needs the JetBrains Runtime. That would change the
  window under the spike modes, which use AWT and Skiko's render API. Revisit it
  for a custom title bar once the app's chrome settles.

**Module layout,** as built. Layering is strict: features see the core modules
and `forge/api`, never a forge adapter or each other, and the app module wires
them together.

- `androidApp`, `desktopApp`: thin shells that supply an `AppEnvironment`
  (directories, token store) and show `TongsApp`
- `app`: the Metro graph and the UI root
- `feature/{inbox,thread,review,auth,settings,feed}`: screens, each adding its
  routes through a `RouteEntries` contributed to the graph. `feature/review`
  still also depends on `forge/github`, for the spike entry points
- `core/{navigation,ui,data,net}`: routes and navigator, design system, Room
  store and sync, HTTP. The neutral model lives in `forge/api` rather than a
  separate `core/model`
- `core/markdown` and `core/composer`: rendering forge Markdown, and writing it.
  Both are core modules so the thread and the changes view share them without
  depending on each other
- `forge/{api,github}`, later `gitea` and `forgejo`
- `engine` (Kotlin facade) and `engine-rs` (Cargo workspace)
- `codeview`
- `build-logic`: convention plugins for KMP libraries and Compose

## Licensing

- Helix queries are MPL-2.0. That's fine to bundle unmodified, and changes to
  those files must stay MPL.
- The `nvim-treesitter` queries are Apache-2.0 but use Neovim-only predicates.
- Zed's `language`, `editor`, and `languages` crates and its built-in queries
  are GPL-3.0, which is compatible with our license. Helix's queries are still
  the choice, on merit (see highlighting). GPUI and `sum_tree` are Apache-2.0.
- **Tongs is GPL-3.0-or-later** (decided 2026-10-28). GPLv3 rather than v2,
  because Apache-2.0 dependencies (gix, Compose, AndroidX) are only compatible
  with v3. F-Droid is happy with it. Apple's App Store terms conflict with the
  GPL for code others contributed, which only matters if we ever ship iOS; the
  owner can relicense their own code, and contributions would then need consent.

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

### 2026-10-28: grammar size on Android

Statically linked grammars made the release APK 67 MiB with both ABIs, and a
per-ABI install about 34 MB, because Android stores native libraries
uncompressed. Tongs ships on Google Play and F-Droid, so the fix had to work for
both. Android now bundles each grammar compressed inside `libtongs_jni` and
extracts it the first time a file needs it. Desktop still links them in.

**How it works.**

- `tongs-grammars` (`engine-rs/grammars`) finds the grammar crates that
  `tongs-highlight` depends on with `cargo metadata`. It compiles each grammar's
  `parser.c` and scanner with the NDK's clang into its own stripped shared
  library (`-O2`, section GC, 16 KB pages) and compresses it with LZMA.
  `:engine:buildGrammars` runs it before `cargoNdkBuild`. It takes 7 s for 21
  grammars × 2 ABIs, and the output is byte-for-byte reproducible.
- On Android, `tongs-highlight` doesn't depend on the grammar crates. It
  `include_bytes!`s the compressed libraries for its target instead, so they're
  ABI-split like any native code. Play can't split assets by ABI.
- The first `get_config` for a language extracts its library to
  `<cache>/tongs-grammars/<name>-<hash>.so`, read-only, then renames it into
  place, and loads it with `dlopen`. `Highlighter` passes that directory by
  default, from `java.io.tmpdir`, which Android sets to the app's cache dir, so
  callers need no `Context`. Files from other builds are deleted. If Android
  clears the cache, the grammars are extracted again.
- **While a grammar loads,** the extraction runs on the highlighting thread that
  wanted it. Its file is `Pending` and drawn plain. When the highlights land,
  the epoch bumps and the rows repaint, as with any other pending highlight.
  Other languages keep highlighting on other threads.
- A host test checks that each language's symbol names the same grammar as its
  crate, since Android loads grammars by symbol.

**Options measured.** Sizes are for arm64-v8a, stripped, unless noted:

| Option                                         | `libtongs_jni`  | APK, one ABI | APK, both ABIs |
| ---------------------------------------------- | --------------- | ------------ | -------------- |
| Linked, as before                              | 34.8 MB         | 35.6 MB      | 70.6 MB        |
| `-Os` or `-Oz` for grammar C code              | −0.15 MB (0.5%) |              |                |
| Fat LTO                                        | 34.1 MB         |              |                |
| Fat LTO, `opt-level = "s"`, C `-Os`            | 33.4 MB         |              |                |
| Compressed in the APK (`useLegacyPackaging`)   | 34.8 MB         | about 5 MB   | about 10 MB    |
| **Bundled compressed, extracted on first use** | **5.9 MB**      | **6.7 MB**   | **13.1 MB**    |

- **Compiler and linker settings barely help.** Parse tables are `.rodata`:
  Kotlin's library is 5.6 MB of data and 0.14 MB of code. The LTO savings come
  from the engine's Rust code, not the grammars.
- **Compressed native libraries** shrink the APK, but the installer extracts
  them, so a device keeps both the APK and 35 MB of libraries. Google also
  recommends against it for download size.
- **Play Feature Delivery** works only on Play. Its library is proprietary, and
  F-Droid flags it as NonFreeDep, so F-Droid would need another approach anyway.
  On-demand modules also need the network the first time a language appears. I
  didn't build it.
- **Trimming** isn't needed. Bundled, C# and Swift together cost 0.35 MB.
- **Loading from a `memfd`** with `android_dlopen_ext` would need no disk. The
  linker allows it for apps, but each launch would decompress every grammar in
  use into unswappable memory, 5.8 MB for Kotlin alone. File-backed pages can be
  dropped under memory pressure. I didn't build it.

**Compression,** summed over all 21 grammars for arm64 (30.6 MB uncompressed):

| deflate -9 | brotli -11 | zstd -19 | xz -9e  | LZMA preset 9 (used) |
| ---------- | ---------- | -------- | ------- | -------------------- |
| 2.67 MB    | 1.72 MB    | 1.84 MB  | 1.56 MB | 1.58 MB              |

x86_64 is 1.60 MB. Tuning `lc`, `lp`, and `pb` saved another 1.4%, which wasn't
worth it. The decoder is pure Rust (`lzma-rust2`, Apache-2.0). With it and the
grammars, `libtongs_jni` is 5.9 MB on arm64 and 6.3 MB on x86_64, against about
4.3 MB for the engine alone. The engine is now most of the APK: fat LTO with
`opt-level = "s"` would take the library to 4.5 MB, but that's a speed tradeoff
for later.

**On the x86_64 API 36 emulator,** with the universal APK:

- The installed APK is 13.1 MB, and `/data/app` holds 14.1 MB with its compiled
  code. A Play install gets one ABI, about 6.7 MB. Gzip puts the download at
  about 4.0 MB, against 5.1 MB before; Play already compressed the uncompressed
  libraries for transfer, so most of the gain is on the device. F-Droid serves
  the APK as is: 13.1 MB universal, against 70.6 MB.
- **The grammar cache grows with use.** After scrolling #760's Markdown, YAML,
  C, C++, Go, Kotlin, Python, and Rust, it held 9 grammars in 14.5 MB. All 21
  take 30.4 MB on x86_64 and 30.6 MB on arm64. Settings counts it as cache.
- **First-use latency,** from the `prepare_languages` example, three runs each.
  "Warm" means the grammar is already extracted, so the difference is
  extraction:

  | Language | First use  | Already extracted | Extraction |
  | -------- | ---------- | ----------------- | ---------- |
  | Kotlin   | 58–70 ms   | 44 ms             | 14–26 ms   |
  | C++      | 94–110 ms  | 78–80 ms          | 13–32 ms   |
  | C#       | 49–64 ms   | 34–35 ms          | 15–29 ms   |
  | Swift    | 124–130 ms | 111–114 ms        | 12–18 ms   |
  | Rust     | 47–48 ms   | 41 ms             | 6–7 ms     |
  | C        | 10–11 ms   | 6 ms              | 3–5 ms     |
  | All 22   | 721–797 ms | 607–614 ms        | 108–183 ms |

  The rest is mostly compiling Helix's queries, which desktop pays too. These
  are emulator numbers on a fast desktop; a phone core is slower.

- Highlighted rows appear for Kotlin, C, Rust, C++, Go, and Markdown in #760.
  SELinux logs each load as `granted { execute }` on `app_data_file`.

**Policy.**

- **Google Play** forbids apps that "download executable code (such as dex, JAR,
  .so files) from a source other than Google Play"
  ([Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)).
  These grammars ship inside the APK that Play delivers, and nothing is
  downloaded. Meta's apps do the same with compressed native code
  ([Superpack](https://engineering.fb.com/2021/09/13/android/superpack/)).
- **F-Droid** forbids downloading "additional executable binary files" without
  consent, and requires binaries to come from source
  ([Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/)). Every
  grammar is built from crate sources during the Gradle build, and nothing
  prebuilt is committed. No anti-feature applies. Play Feature Delivery would be
  flagged NonFreeDep by fdroidserver's scanner.
- **Android 10's W^X change** blocks `execve` of files in the app's home
  directory, not `dlopen`. `untrusted_app_all.te` keeps `execute` on
  `app_data_file`, for apps that "ship with shared libraries… that they write
  out to their sandbox directory"
  ([behavior change](https://developer.android.com/about/versions/10/behavior-changes-10)).
- **Android 14's safer dynamic code loading** requires dynamically loaded files
  to be read-only
  ([behavior change](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)).
  ART enforces it only for dex, but the extracted grammars are made read-only
  before they're written anyway.
- **The linker namespace** permits all of `/data` for app class loaders
  (`kAlwaysPermittedDirectories` in libnativeloader), so the cache dir is
  loadable.

Not covered yet:

- A physical device, and API 31 (the emulator ran API 36)
- A cap on the grammar cache; it only shrinks when Android clears it
- Retrying a grammar that failed to extract, for example on a full disk. The
  language stays plain until the app restarts.

### 2026-10-28: Android fetch, measured on Linux.apk

`androidApp` now fetches and opens any GitHub PR in the app, like the desktop's
`--pr`. The PR comes from a github.com PR link as the intent's data or a `pr`
extra, and defaults to #760. Changes:

- **Shared loading glue.** `forge/github` has an Android target, and both
  platforms share an OkHttp client in `jvmAndAndroidMain`. A new
  `feature/review` module holds what the desktop shell used to do itself:
  - `PullRequestLoad` runs `loadPullRequestReview` in the background.
  - `PullRequestReview` swaps each snapshot in, fetches the files on screen
    first, handles the jump, and logs when things reach the screen.

  Each shell keeps only its platform wiring: the token, the store dir, the
  highlighter, and lifecycle.

- **The token is optional.** Git fetches of public repos need no auth, and a
  review makes two REST calls against the 60 an hour GitHub allows without one.
  Debuggable builds take a `token` extra, release builds ignore it, and no build
  contains one. The desktop still uses `GH_TOKEN` or `gh auth token` if it can.
- **The store** is `<cacheDir>/github.com/<owner>/<repo>/objects`. It's a cache:
  everything in it can be fetched again, and "Clear cache" should clear it.
- **One `Highlighter` per process,** since its cache is keyed by blob.
- **The activity handles size changes itself** (`configChanges`), so resizing a
  window doesn't restart a load. That matters on desktops and foldables.
- **The adb-pushed store is gone.** The app fills its own store, so a benchmark
  run on a warm store costs only the two REST calls. `bench` now waits for the
  load, then runs the desktop's passes, cold then warm when highlighting.

Method:

- **[Linux.apk](https://github.com/sargunv/linux.apk)**, built from source at
  `dcf03df`, since there's no release yet.
  - A test session (`LINUXAPK_INSTANCE=tongs`) on its pinned AOSP 17 image (API
    37, x86_64), with RADV on the host's Radeon 8060S.
  - It ran inside an isolated nested KWin (`--virtual`, 1920×1080).
  - The app's window is 1280×800 at 160 dpi, about 40 rows of 150 columns.
- **The emulator** from the Android entry above, run headless (`-no-window`), at
  1080×2400 and 420 dpi: about 44 rows of 36 columns.
- Both ran the same release APK, AOT-compiled with `speed`, with no token.
- Other agents kept the host's load average at 12–32 during the Linux.apk runs
  and 6–13 during the emulator runs.

**Cold in-app load of #760.** Times run from `onCreate` to the frame that shows
the result; the process starts about 20 ms before `onCreate`.

| Where            | Runs | Headers on screen | Fully loaded on screen | Commits and trees request |
| ---------------- | ---- | ----------------- | ---------------------- | ------------------------- |
| Linux.apk        | 7    | 0.40–1.11 s       | 1.03–2.24 s            | 0.25–0.78 s               |
| Emulator         | 5    | 0.33–0.57 s       | 0.92–1.22 s            | 0.22–0.26 s               |
| Desktop (`--pr`) | 8    | 0.93–1.54 s       | 1.7–7.4 s              | 0.23–0.32 s               |

- Every cold load made 14 requests for 5 MiB of packs.
- On a warm store, Linux.apk shows all 602,759 rows in 0.19–0.26 s, and the
  emulator in 0.23 s. Most of that is the PR metadata call.
- The metadata call usually took 0.1–0.3 s on Android (once 1.3 s), and
  0.54–0.85 s on the desktop with or without a token. The desktop's time likely
  includes the JVM warming up Ktor.
- Linux.apk's network (pasta) shows more spread on the first request than the
  emulator's, and runs interleaved with the emulator still differed. Five runs
  each aren't enough to call it slower.
- Desktop runs went through `xvfb-run`, so they drew in software, with
  highlighting. They're a same-hour reference, not a comparison.

**Fling and scrub.** These are the desktop's passes, measured as draw CPU per
frame. The earlier emulator runs above were plain text and windowed.

| Pass                     | Linux.apk p50 | p99       | Emulator p50 | p99       | Earlier, p50 / p99    |
| ------------------------ | ------------- | --------- | ------------ | --------- | --------------------- |
| Fling, plain             | 0.18–0.19 ms  | 0.38–0.42 | 0.18–0.19    | 0.48–0.56 | 0.20–0.33 / 0.59–2.93 |
| Scrub, plain             | 0.19–0.30 ms  | 0.36–0.55 | 0.24–0.26    | 0.57–0.63 | 0.18–0.27 / 0.46–0.83 |
| Fling, highlighted, cold | 0.28–0.39 ms  | 0.53–1.04 | 0.23–0.28    | 0.63–0.74 |                       |
| Fling, highlighted, warm | 0.23–0.38 ms  | 0.44–1.22 | 0.20–0.21    | 0.52–0.57 |                       |
| Scrub, highlighted, cold | 0.27–0.40 ms  | 0.49–1.52 | 0.24–0.25    | 0.58–0.60 |                       |
| Scrub, highlighted, warm | 0.27–0.50 ms  | 0.48–1.62 | 0.30–0.32    | 0.67–0.73 |                       |

There were 3 plain and 8 highlighted runs on Linux.apk, one of which stalled
after its first pass, and 2 of each on the emulator. Rows drawn plain were
0–0.1% on cold flings, and 22–40% on cold scrubs on Linux.apk against 26% on the
emulator. After the passes, the JVM heap held 3–4 MiB, and the native heap 54–61
MiB plain and 76–81 MiB with 13 MiB of highlights.

Findings:

- **Frames held on both.** The frame interval was 16.67 ms at p99 in every pass.
  Linux.apk missed 1–2 vsyncs, in the cold fling of 3 of its 8 highlighted runs,
  and none anywhere else. The emulator missed none.
  - The earlier emulator runs missed up to 1,100 vsyncs, in its windowed
    host-GPU pipe. Running headless avoids that.
- **Linux.apk reports real GPU time.** Its HWUI stages were all under 4 ms at
  p99, and GPU time was 0.67–1.78 ms. The emulator reports about 15.5 ms of GPU
  in every frame, which is its fence waiting for vsync, not work.
- **Draw cost is about the same on both,** within host noise, though Linux.apk's
  rows show four times as many columns.
- **One Linux.apk run stalled** after its first pass and never logged the next.
  It didn't happen again in seven more runs, and its log was cleared before
  anyone looked, so the cause is unknown: our code or Linux.apk's frame
  delivery.

Notes on Linux.apk:

- It needs a source build: about 8 minutes, reusing build container images
  already on the host. It installs over its own CLI, and `linuxapk-session exec`
  runs `am`, `cmd`, `logcat`, and `dumpsys` as adb would.
- Each app window is its own Android display, so `screencap` shows the launcher.
  Screenshots come from `spectacle` in the nested KWin instead.
- Its nested-KWin recipe puts the Wayland socket under the checkout. From a deep
  path that exceeds the 108-byte socket limit, and KWin crashes. A short private
  `XDG_RUNTIME_DIR` works around it.
- `linuxapk install` writes a desktop entry under `$XDG_DATA_HOME`, even for a
  test instance. Pointing `XDG_DATA_HOME` at the nested desktop's data, and
  `LINUXAPK_IMAGES` at the shared image cache, keeps it off the real menu.

Not covered yet:

- A physical device and a baseline profile, as before
- What happens to the store if the system deletes some of its packs while the
  app is running
- A sign-in flow; tokens only come in through the debug extra
- Resizing a Linux.apk window during a load. `configChanges` should keep the
  load, but nobody has watched it happen.

### 2026-10-28: the PR changes screen

The screen, its engine support, and `codeview`'s headers, selection, and
overlays are described under the pipeline, the renderer, and Navigation and
screens. What they cost the renderer, measured with `--bench` on #760 and
alternating `main` with the branch, two runs each:

| Pass, draw CPU p50 / p99 (ms) | Desktop, `main`  | Desktop, branch       | Android, `main`       | Android, branch       |
| ----------------------------- | ---------------- | --------------------- | --------------------- | --------------------- |
| Fling, cold                   | 0.07–0.08 / 0.38 | 0.09 / 0.38–0.40      | 0.29–0.32 / 0.72–0.83 | 0.29–0.33 / 0.75–0.91 |
| Fling, warm                   | 0.07 / 0.20–0.21 | 0.07 / 0.19–0.21      | 0.24–0.29 / 0.70–0.73 | 0.24–0.28 / 0.72–0.81 |
| Scrub, cold                   | 0.17–0.18 / 0.38 | 0.19–0.20 / 0.39–0.40 | 0.32–0.35 / 0.87      | 0.35–0.43 / 0.85–0.99 |
| Scrub, warm                   | 0.16 / 0.29–0.30 | 0.19 / 0.35–0.38      | 0.35–0.40 / 0.77–0.93 | 0.35–0.46 / 0.98–1.02 |

- Desktop ran under `xvfb-run` (Skiko's software renderer), so absolute numbers
  sit below the earlier GPU runs; only the comparison matters. Android ran on a
  Pixel 9 Pro Fold AVD (API 36, x86_64, `-gpu host`, headless), release builds
  AOT-compiled with `speed`. Other agents were building on the host throughout.
- Flinging costs the same. Scrubbing, where every frame lands in a new file,
  costs 0.02–0.03 ms more on desktop: the header now draws a chevron path and
  reads its row, and the gutter scans the rows on screen for its width. Frames
  held at the display rate in every pass.
- Opening #760 from the store takes 91 ms against 73–75 ms, for classifying
  files and pairing renames; two renames make it 2,188 files.

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
1. **Skeleton.** Built on 2026-10-28, except where noted:
   - Accounts: GitHub PAT. The device flow is modeled but waits on an OAuth App.
   - Room store and sync.
   - Inbox.
   - The thread view and composer, built on 2026-10-28: see Navigation and
     screens.
   - Android and desktop shells.
2. **Review.**
   - Full PR review: viewed state, comment drafts, submitting reviews, merging,
     CI. Built on 2026-10-28: the changes screen with its file tree, viewed
     state, line comments drafted into a pending review, submitting it, and
     read-only threads. Not yet: replying to and resolving threads, merging, CI,
     and the revision picker.
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
- **Grammar size.** Solved for the 22 starter languages: Android bundles them
  compressed and extracts each on first use, which keeps the APK at 6.7 MB per
  ABI and costs 3–32 ms per grammar the first time. See the grammar size spike
  below. What's left:
  - Extracted grammars take up to 30 MB of cache once a user has seen all 22
    languages.
  - Every grammar we add costs about 5% of its size in the APK, which leaves
    room for a long tail. A TextMate engine (syntect is MIT) is still an option
    if it doesn't.

- **Library maturity.**
  - tree-house is at 0.x and its API changes between releases.
  - Gobley's release cadence is slow.
  - UniFFI's zero-copy writes are still unreleased, which is why the hot path
    uses jni-rs.

## Questions for you

These are product calls, not technical ones:

- **Name.**
