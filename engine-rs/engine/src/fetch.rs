//! A sans-IO fetch session: plans the git requests that load a review,
//! ingests their responses, and publishes ever more complete [`Review`]
//! snapshots as trees and blobs arrive.
//!
//! The caller owns the network. It takes request bodies from
//! [`FetchSession::poll_request`], POSTs each to `<repo>.git/git-upload-pack`
//! with [`FetchRequest::HEADERS`], streams the response into a
//! [`ResponseSink`], and reports the outcome with [`FetchSession::complete`]
//! or [`FetchSession::fail`]. Any number of requests may be in flight; the
//! session caps blob requests itself.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{self, Read};
use std::sync::Arc;
use std::sync::mpsc::{Receiver, SyncSender, sync_channel};
use std::thread::JoinHandle;

use anyhow::{Context, Result, anyhow, bail};
use gix_hash::ObjectId;

use crate::protocol::{FetchRequest, Filter, PackStream};
use crate::review::{FileEntry, Review};
use crate::store::{IngestOutcome, Store};
use crate::tree_walk::{Change, TreeWalk};

#[derive(Clone, Debug)]
pub struct FetchOptions {
    /// From this directory depth on, fetch whole subtrees (`blob:none`) in one
    /// round trip instead of walking them a level at a time. `Some(0)` fetches
    /// every tree together with the commits, which suits small repos: all of
    /// maplibre-native-ffi's trees are 145 KiB, but chromium's are 18 MiB.
    /// github.com rejects `tree:<n>` filters above 0, so there's no middle way
    /// within one request.
    pub whole_trees_from_depth: Option<u32>,
    /// Most blobs wanted per request.
    pub blob_batch: usize,
    /// Most blob requests in flight, not counting one for visible files.
    pub max_blob_requests: usize,
}

impl Default for FetchOptions {
    fn default() -> Self {
        Self {
            whole_trees_from_depth: None,
            blob_batch: 256,
            max_blob_requests: 6,
        }
    }
}

pub struct Request {
    pub id: u64,
    pub body: Vec<u8>,
    /// What the request is for, e.g. `blobs ×256`; for logs.
    pub label: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Purpose {
    Commits,
    Trees,
    Blobs,
}

struct InFlight {
    purpose: Purpose,
    wants: Vec<ObjectId>,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    pub requests: u32,
    pub objects: u64,
    pub pack_bytes: u64,
}

pub struct FetchSession {
    store: Store,
    options: FetchOptions,
    next_id: u64,
    in_flight: HashMap<u64, InFlight>,
    /// Commits wanted but not yet requested.
    commit_queue: Vec<ObjectId>,
    commits_requested: HashSet<ObjectId>,
    review: Option<ReviewLoad>,
    pub stats: Stats,
}

/// Progress toward one review.
struct ReviewLoad {
    old: ObjectId,
    new: ObjectId,
    /// Starts once both commits are in the store.
    walk: Option<TreeWalk>,
    walk_done: bool,
    trees_in_flight: bool,
    files: BTreeMap<String, FileSlot>,
    /// Pending files by the missing blobs they wait on.
    waiting: HashMap<ObjectId, Vec<String>>,
    requested_blobs: HashSet<ObjectId>,
    bulk_batches: u32,
    /// Paths the viewer shows; their blobs are fetched first.
    visible: Vec<String>,
    /// Whether anything changed since the last snapshot.
    dirty: bool,
}

struct FileSlot {
    change: Change,
    entry: Arc<FileEntry>,
    missing: Vec<ObjectId>,
}

impl FetchSession {
    pub fn new(store: Store, options: FetchOptions) -> Self {
        Self {
            store,
            options,
            next_id: 1,
            in_flight: HashMap::new(),
            commit_queue: Vec::new(),
            commits_requested: HashSet::new(),
            review: None,
            stats: Stats::default(),
        }
    }

    /// Starts fetching commits (and with whole trees, their trees) before the
    /// review is known, e.g. a PR's head while the forge computes the merge
    /// base.
    pub fn want_commits(&mut self, commits: &[ObjectId]) {
        for commit in commits {
            if !self.commits_requested.contains(commit)
                && !self.commit_queue.contains(commit)
                && !self.store.contains(commit)
            {
                self.commit_queue.push(*commit);
            }
        }
    }

    /// Loads the diff from `old` to `new`, replacing any earlier review.
    /// Requests already in flight still land in the store.
    pub fn start_review(&mut self, old: ObjectId, new: ObjectId) -> Result<()> {
        if self
            .review
            .as_ref()
            .is_some_and(|r| r.old == old && r.new == new)
        {
            return Ok(());
        }
        self.want_commits(&[old, new]);
        self.review = Some(ReviewLoad {
            old,
            new,
            walk: None,
            walk_done: false,
            trees_in_flight: false,
            files: BTreeMap::new(),
            waiting: HashMap::new(),
            requested_blobs: HashSet::new(),
            bulk_batches: 0,
            visible: Vec::new(),
            dirty: false,
        });
        self.advance()
    }

    /// Moves the given files' blobs to the front of the queue.
    pub fn prioritize(&mut self, paths: Vec<String>) {
        if let Some(review) = &mut self.review {
            review.visible = paths;
        }
    }

    /// The next request to send, if any is ready.
    pub fn poll_request(&mut self) -> Option<Request> {
        if !self.commit_queue.is_empty() {
            let wants = std::mem::take(&mut self.commit_queue);
            self.commits_requested.extend(&wants);
            let whole = self.options.whole_trees_from_depth == Some(0);
            let request = FetchRequest {
                wants: wants.clone(),
                filter: Some(if whole {
                    Filter::BlobNone
                } else {
                    Filter::TreeZero
                }),
                deepen: Some(1),
            };
            let label = format!(
                "commits ×{}{}",
                wants.len(),
                if whole { " with trees" } else { "" }
            );
            return Some(self.issue(Purpose::Commits, wants, request, label));
        }
        if let Some((wants, request, label)) = self.tree_request() {
            return Some(self.issue(Purpose::Trees, wants, request, label));
        }
        let (wants, label) = self.blob_batch()?;
        let request = FetchRequest {
            wants: wants.clone(),
            filter: Some(Filter::BlobNone),
            deepen: None,
        };
        Some(self.issue(Purpose::Blobs, wants, request, label))
    }

    /// The trees the walk needs next, unless they're already on their way.
    fn tree_request(&mut self) -> Option<(Vec<ObjectId>, FetchRequest, String)> {
        let review = self.review.as_mut()?;
        let walk = review.walk.as_ref()?;
        if review.walk_done || review.trees_in_flight {
            return None;
        }
        let wants = walk.missing(&self.store);
        if wants.is_empty() {
            return None;
        }
        review.trees_in_flight = true;
        let depth = walk.depth();
        let whole = self
            .options
            .whole_trees_from_depth
            .is_some_and(|from| depth >= from);
        let request = FetchRequest {
            wants: wants.clone(),
            filter: Some(if whole {
                Filter::BlobNone
            } else {
                Filter::TreeZero
            }),
            deepen: None,
        };
        let label = format!(
            "trees ×{} at depth {depth}{}",
            wants.len(),
            if whole { " with subtrees" } else { "" }
        );
        Some((wants, request, label))
    }

    /// Picks the next blobs to fetch: visible files first, in a batch of their
    /// own so it returns quickly, then everything else in path order.
    fn blob_batch(&mut self) -> Option<(Vec<ObjectId>, String)> {
        let review = self.review.as_mut()?;
        let blob_requests = self
            .in_flight
            .values()
            .filter(|r| r.purpose == Purpose::Blobs)
            .count();
        let batch = self.options.blob_batch.max(1);
        let mut wants = Vec::new();
        let requested = &mut review.requested_blobs;
        let mut take = |slot: &FileSlot, wants: &mut Vec<ObjectId>, batch: usize| {
            for id in &slot.missing {
                if wants.len() < batch && requested.insert(*id) {
                    wants.push(*id);
                }
            }
        };
        if blob_requests <= self.options.max_blob_requests {
            for path in &review.visible {
                if let Some(slot) = review.files.get(path) {
                    take(slot, &mut wants, batch);
                }
            }
            if !wants.is_empty() {
                let label = format!("visible blobs ×{}", wants.len());
                return Some((wants, label));
            }
        }
        if blob_requests >= self.options.max_blob_requests {
            return None;
        }
        // Start small, so the first files' diffs don't wait on a big batch.
        let batch = batch.min(32 << review.bulk_batches.min(8));
        for slot in review.files.values() {
            if wants.len() >= batch {
                break;
            }
            take(slot, &mut wants, batch);
        }
        (!wants.is_empty()).then(|| {
            review.bulk_batches += 1;
            let label = format!("blobs ×{}", wants.len());
            (wants, label)
        })
    }

    fn issue(
        &mut self,
        purpose: Purpose,
        wants: Vec<ObjectId>,
        request: FetchRequest,
        label: String,
    ) -> Request {
        let id = self.next_id;
        self.next_id += 1;
        self.stats.requests += 1;
        self.in_flight.insert(id, InFlight { purpose, wants });
        Request {
            id,
            body: request.body(),
            label,
        }
    }

    /// A sink that streams request `id`'s response into the store. It needs no
    /// access to the session, so several can ingest at once.
    pub fn response_sink(&self) -> ResponseSink {
        ResponseSink::new(self.store.clone())
    }

    /// Records that request `id`'s pack is in the store, and advances.
    pub fn complete(&mut self, id: u64, outcome: &IngestOutcome) -> Result<()> {
        let request = self
            .in_flight
            .remove(&id)
            .with_context(|| format!("unknown request {id}"))?;
        self.stats.objects += u64::from(outcome.objects);
        self.stats.pack_bytes += outcome.pack_bytes;
        match request.purpose {
            Purpose::Commits => {}
            Purpose::Trees => {
                if let Some(review) = &mut self.review {
                    review.trees_in_flight = false;
                }
            }
            Purpose::Blobs => {
                if let Some(missing) = request.wants.iter().find(|id| !self.store.contains(id)) {
                    bail!("the server left out blob {missing}");
                }
                if let Some(review) = &mut self.review {
                    review.blobs_arrived(&self.store, &request.wants)?;
                }
            }
        }
        self.advance()
    }

    /// Forgets request `id` after a transport error, so its objects are asked
    /// for again.
    pub fn fail(&mut self, id: u64) {
        let Some(request) = self.in_flight.remove(&id) else {
            return;
        };
        match request.purpose {
            Purpose::Commits => {
                for commit in request.wants {
                    self.commits_requested.remove(&commit);
                    self.want_commits(&[commit]);
                }
            }
            Purpose::Trees => {
                if let Some(review) = &mut self.review {
                    review.trees_in_flight = false;
                }
            }
            Purpose::Blobs => {
                if let Some(review) = &mut self.review {
                    for blob in &request.wants {
                        review.requested_blobs.remove(blob);
                    }
                }
            }
        }
    }

    /// Walks as far as the trees in the store allow, and diffs every file
    /// whose blobs are already present.
    fn advance(&mut self) -> Result<()> {
        let Some(review) = &mut self.review else {
            return Ok(());
        };
        if review.walk.is_none() {
            if !(self.store.contains(&review.old) && self.store.contains(&review.new)) {
                return Ok(());
            }
            review.walk = Some(TreeWalk::new(
                self.store.commit_tree(&review.old)?,
                self.store.commit_tree(&review.new)?,
            ));
        }
        let mut walk = review.walk.take().unwrap();
        let stepped = (|| -> Result<()> {
            while !walk.is_done() && walk.missing(&self.store).is_empty() {
                walk.step(&self.store)?;
                for change in std::mem::take(&mut walk.changes) {
                    review.add_file(&self.store, change)?;
                }
            }
            Ok(())
        })();
        if walk.is_done() && !review.walk_done {
            review.walk_done = true;
            review.dirty = true;
        }
        review.walk = Some(walk);
        stepped
    }

    /// A new snapshot if anything changed since the last one.
    pub fn snapshot(&mut self) -> Option<Review> {
        let review = self.review.as_mut()?;
        if !std::mem::take(&mut review.dirty) {
            return None;
        }
        Some(Review::from_files(
            review.files.values().map(|s| s.entry.clone()).collect(),
        ))
    }

    /// Whether the review is fully loaded.
    pub fn is_done(&self) -> bool {
        self.review.as_ref().is_some_and(|r| {
            r.walk_done && r.waiting.is_empty() && r.files.values().all(|s| s.entry.loaded)
        })
    }

    /// Whether `ancestor` is reachable from `descendant` through commits
    /// already in the store. If so, it's their merge base, and a PR whose
    /// base is an ancestor of its head needn't wait for the forge to say so.
    /// `false` may just mean the history isn't fetched.
    pub fn is_ancestor(&self, ancestor: &ObjectId, descendant: &ObjectId) -> bool {
        let mut seen = HashSet::new();
        let mut queue = vec![*descendant];
        while let Some(commit) = queue.pop() {
            if commit == *ancestor {
                return true;
            }
            if seen.len() > 10_000 || !seen.insert(commit) || !self.store.contains(&commit) {
                continue;
            }
            queue.extend(self.store.commit_parents(&commit).unwrap_or_default());
        }
        false
    }

    pub fn in_flight(&self) -> usize {
        self.in_flight.len()
    }
}

impl ReviewLoad {
    fn add_file(&mut self, store: &Store, change: Change) -> Result<()> {
        let missing: Vec<_> = [change.old, change.new]
            .into_iter()
            .flatten()
            .filter(|id| !store.contains(id))
            .collect();
        let entry = if missing.is_empty() {
            FileEntry::load(store, &change)?
        } else {
            FileEntry::pending(&change)
        };
        for id in &missing {
            self.waiting
                .entry(*id)
                .or_default()
                .push(change.path.clone());
        }
        self.files.insert(
            change.path.clone(),
            FileSlot {
                change,
                entry: Arc::new(entry),
                missing,
            },
        );
        self.dirty = true;
        Ok(())
    }

    fn blobs_arrived(&mut self, store: &Store, blobs: &[ObjectId]) -> Result<()> {
        for blob in blobs {
            for path in self.waiting.remove(blob).unwrap_or_default() {
                let slot = self.files.get_mut(&path).unwrap();
                slot.missing.retain(|id| id != blob);
                if slot.missing.is_empty() {
                    slot.entry = Arc::new(FileEntry::load(store, &slot.change)?);
                    self.dirty = true;
                }
            }
        }
        Ok(())
    }
}

/// Streams an upload-pack response into the store on a background thread, so
/// callers can push body chunks as they arrive instead of buffering the pack.
pub struct ResponseSink {
    tx: Option<SyncSender<Vec<u8>>>,
    thread: Option<JoinHandle<Result<IngestOutcome>>>,
}

impl ResponseSink {
    fn new(store: Store) -> Self {
        // A few chunks of slack; a full pipe blocks the caller's IO thread.
        let (tx, rx) = sync_channel(16);
        let thread = std::thread::spawn(move || {
            let mut stream = PackStream::new(ChannelReader {
                rx,
                chunk: Vec::new(),
                pos: 0,
            });
            let outcome = store.ingest(&mut stream)?;
            // The indexer stops at the pack's checksum; read on to the closing
            // flush-pkt so the caller can write the whole body.
            io::copy(&mut stream, &mut io::sink())?;
            Ok(outcome)
        });
        Self {
            tx: Some(tx),
            thread: Some(thread),
        }
    }

    pub fn write(&mut self, data: &[u8]) -> Result<()> {
        let tx = self.tx.as_ref().context("response already finished")?;
        if tx.send(data.to_vec()).is_err() {
            // The ingest thread gave up; its error says why.
            self.tx = None;
            return Err(self
                .join()
                .err()
                .unwrap_or_else(|| anyhow!("ingest stopped early")));
        }
        Ok(())
    }

    /// Ends the body and waits for the pack to be indexed.
    pub fn finish(mut self) -> Result<IngestOutcome> {
        self.tx = None;
        self.join()
    }

    fn join(&mut self) -> Result<IngestOutcome> {
        let thread = self.thread.take().context("response already finished")?;
        thread
            .join()
            .map_err(|_| anyhow!("ingest thread panicked"))?
    }
}

impl Drop for ResponseSink {
    fn drop(&mut self) {
        // Aborted: closing the pipe mid-pack makes the ingest thread fail and
        // discard its temporary pack.
        self.tx = None;
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

struct ChannelReader {
    rx: Receiver<Vec<u8>>,
    chunk: Vec<u8>,
    pos: usize,
}

impl Read for ChannelReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        while self.pos == self.chunk.len() {
            match self.rx.recv() {
                Ok(chunk) => (self.chunk, self.pos) = (chunk, 0),
                Err(_) => return Ok(0),
            }
        }
        let n = buf.len().min(self.chunk.len() - self.pos);
        buf[..n].copy_from_slice(&self.chunk[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};

    use super::*;
    use crate::review::RowKind;

    fn git(dir: &Path, args: &[&str]) -> String {
        let out = Command::new("git")
            .current_dir(dir)
            .args(args)
            .output()
            .expect("running git");
        assert!(out.status.success(), "git {args:?}: {out:?}");
        String::from_utf8(out.stdout).unwrap().trim().to_owned()
    }

    fn write(dir: &Path, path: &str, text: &str) {
        let path = dir.join(path);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    /// A scratch directory, removed on drop.
    struct TempDir(PathBuf);

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn temp_dir(name: &str) -> TempDir {
        let dir = std::env::temp_dir().join(format!("tongs-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir)
    }

    /// A repo with a deep change, a shallow one, an add, and a delete.
    fn fixture(name: &str) -> (TempDir, ObjectId, ObjectId) {
        let dir = temp_dir(&format!("{name}-repo"));
        let repo = &dir.0;
        git(repo, &["init", "-q", "-b", "main"]);
        for (key, value) in [
            ("user.name", "t"),
            ("user.email", "t@t"),
            ("uploadpack.allowFilter", "true"),
            ("uploadpack.allowAnySHA1InWant", "true"),
        ] {
            git(repo, &["config", key, value]);
        }
        write(repo, "a/b/c/deep.txt", "one\ntwo\nthree\n");
        write(repo, "a/same.txt", "same\n");
        write(repo, "gone.txt", "bye\n");
        write(repo, "top.txt", "top\n");
        git(repo, &["add", "-A"]);
        git(repo, &["commit", "-q", "-m", "old"]);
        let old = git(repo, &["rev-parse", "HEAD"]);
        write(repo, "a/b/c/deep.txt", "one\n2\nthree\n");
        write(repo, "new/file.txt", "hi\n");
        write(repo, "top.txt", "top\nmore\n");
        std::fs::remove_file(repo.join("gone.txt")).unwrap();
        git(repo, &["add", "-A"]);
        git(repo, &["commit", "-q", "-m", "new"]);
        let new = git(repo, &["rev-parse", "HEAD"]);
        (dir, old.parse().unwrap(), new.parse().unwrap())
    }

    /// Plays the server with `git upload-pack`, one request at a time.
    fn serve(repo: &Path, body: &[u8], sink: &mut ResponseSink) {
        let mut child = Command::new("git")
            .args(["upload-pack", "--stateless-rpc"])
            .arg(repo)
            .env("GIT_PROTOCOL", "version=2")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        child.stdin.take().unwrap().write_all(body).unwrap();
        let out = child.wait_with_output().unwrap();
        for chunk in out.stdout.chunks(7) {
            sink.write(chunk).unwrap();
        }
    }

    fn load(options: FetchOptions, name: &str) -> (Review, Vec<String>) {
        let (repo, old, new) = fixture(name);
        let store_dir = temp_dir(name);
        let store = Store::open(&store_dir.0).unwrap();
        let mut session = FetchSession::new(store, options);
        session.start_review(old, new).unwrap();
        let mut labels = Vec::new();
        let mut last = None;
        while let Some(request) = session.poll_request() {
            labels.push(request.label.clone());
            let mut sink = session.response_sink();
            serve(&repo.0, &request.body, &mut sink);
            session
                .complete(request.id, &sink.finish().unwrap())
                .unwrap();
            last = session.snapshot().or(last);
        }
        assert!(session.is_done());
        (last.unwrap(), labels)
    }

    fn summary(review: &Review) -> Vec<(String, u32, u32)> {
        review
            .files
            .iter()
            .map(|f| (f.path.clone(), f.additions, f.deletions))
            .collect()
    }

    const EXPECTED: [(&str, u32, u32); 4] = [
        ("a/b/c/deep.txt", 1, 1),
        ("gone.txt", 0, 1),
        ("new/file.txt", 1, 0),
        ("top.txt", 1, 0),
    ];

    #[test]
    fn walks_one_level_per_round_trip() {
        let (review, labels) = load(FetchOptions::default(), "fetch-walk");
        let expected: Vec<_> = EXPECTED
            .iter()
            .map(|(p, a, d)| (p.to_string(), *a, *d))
            .collect();
        assert_eq!(summary(&review), expected);
        assert_eq!(
            labels,
            [
                "commits ×2",
                "trees ×2 at depth 0",
                "trees ×3 at depth 1",
                "trees ×2 at depth 2",
                "trees ×2 at depth 3",
                "blobs ×6",
            ]
        );
        assert!(
            review
                .rows(0..usize::MAX)
                .all(|r| r.kind != RowKind::Loading)
        );
    }

    #[test]
    fn fetches_whole_trees_with_commits() {
        let options = FetchOptions {
            whole_trees_from_depth: Some(0),
            ..FetchOptions::default()
        };
        let (review, labels) = load(options, "fetch-whole");
        assert_eq!(summary(&review).len(), EXPECTED.len());
        assert_eq!(labels, ["commits ×2 with trees", "blobs ×6"]);
    }

    #[test]
    fn visible_files_jump_the_queue() {
        let (repo, old, new) = fixture("fetch-visible");
        let store_dir = temp_dir("fetch-visible");
        let store = Store::open(&store_dir.0).unwrap();
        let options = FetchOptions {
            whole_trees_from_depth: Some(0),
            ..FetchOptions::default()
        };
        let mut session = FetchSession::new(store, options);
        session.start_review(old, new).unwrap();
        let request = session.poll_request().unwrap();
        let mut sink = session.response_sink();
        serve(&repo.0, &request.body, &mut sink);
        session
            .complete(request.id, &sink.finish().unwrap())
            .unwrap();
        assert!(session.is_ancestor(&old, &new));
        assert!(!session.is_ancestor(&new, &old));
        let headers = session.snapshot().unwrap();
        assert_eq!(headers.files.len(), 4);
        assert!(headers.files.iter().all(|f| !f.loaded));
        session.prioritize(vec!["top.txt".into()]);
        assert_eq!(session.poll_request().unwrap().label, "visible blobs ×2");
        assert_eq!(session.poll_request().unwrap().label, "blobs ×4");
    }
}
