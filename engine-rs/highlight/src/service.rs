//! Background highlighting with a cache keyed by blob, so it outlives any one
//! review and is shared by every view of the same file contents.
//!
//! Callers ask for highlights while filling rows. Misses become jobs that a
//! few worker threads take most-recent-request first, nearest the middle of
//! that request first, so the viewport wins over prefetch and over places the
//! user has already scrolled past. Each finished job bumps an epoch that
//! renderers wait on to know when to refetch rows.

use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::Duration;

use gix_hash::ObjectId;

use crate::file::{FileHighlights, MAX_FILE_BYTES};
use crate::languages::Lang;

/// Jobs past this many are dropped, oldest request first. Rows still on
/// screen ask again on their next fill.
const MAX_PENDING: usize = 64;
/// Rough bytes per cache entry besides the highlights, so plain results
/// count against the budget too.
const ENTRY_BYTES: usize = 64;

type Key = (ObjectId, Lang);
pub type Source = Arc<dyn AsRef<[u8]> + Send + Sync>;

pub struct Highlights {
    shared: Arc<Shared>,
    workers: Vec<JoinHandle<()>>,
}

struct Shared {
    state: Mutex<State>,
    /// Signalled when jobs arrive or on shutdown.
    work: Condvar,
    /// Signalled when the epoch advances or on shutdown.
    changed: Condvar,
}

#[derive(Default)]
struct State {
    cache: HashMap<Key, Entry>,
    cache_bytes: usize,
    max_cache_bytes: usize,
    pending: HashMap<Key, Job>,
    in_flight: Vec<Key>,
    /// Counts requests; newer requests take priority.
    request: u64,
    /// Counts finished jobs.
    epoch: u64,
    /// Bumps on every cache hit, for LRU eviction.
    clock: u64,
    shutdown: bool,
}

struct Entry {
    /// `None` when the file is too big or otherwise can't be highlighted.
    highlights: Option<Arc<FileHighlights>>,
    last_used: u64,
}

struct Job {
    source: Source,
    request: u64,
    /// Distance from the middle of the request; smaller is sooner.
    distance: u32,
}

impl Job {
    fn priority(&self) -> (u64, std::cmp::Reverse<u32>) {
        (self.request, std::cmp::Reverse(self.distance))
    }
}

/// What a caller gets for one file side.
#[derive(Clone)]
pub enum Lookup {
    Ready(Arc<FileHighlights>),
    /// Queued or running; plain text until it lands.
    Pending,
    /// Never highlighted: no grammar, too big, or failed to parse.
    Plain,
}

impl Highlights {
    pub fn new(max_cache_bytes: usize, threads: usize) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                max_cache_bytes,
                ..State::default()
            }),
            work: Condvar::new(),
            changed: Condvar::new(),
        });
        let workers = (0..threads.max(1))
            .map(|i| {
                let shared = shared.clone();
                std::thread::Builder::new()
                    .name(format!("tongs-highlight-{i}"))
                    .spawn(move || shared.work_loop())
                    .expect("spawning highlight worker")
            })
            .collect();
        Self { shared, workers }
    }

    /// Starts a batch of lookups, typically one per `fill_rows` call. Holds
    /// the lock until dropped, so keep it short.
    pub fn request(&self) -> Request<'_> {
        let mut state = self.shared.state.lock().unwrap();
        state.request += 1;
        Request {
            shared: &self.shared,
            state,
            queued: false,
        }
    }

    /// Number of jobs finished so far.
    pub fn epoch(&self) -> u64 {
        self.shared.state.lock().unwrap().epoch
    }

    /// Blocks until the epoch passes `after`, the timeout elapses, or the
    /// service shuts down. Returns the current epoch.
    pub fn wait_for_change(&self, after: u64, timeout: Duration) -> u64 {
        let state = self.shared.state.lock().unwrap();
        let (state, _) = self
            .shared
            .changed
            .wait_timeout_while(state, timeout, |s| s.epoch == after && !s.shutdown)
            .unwrap();
        state.epoch
    }

    /// Stops the workers and wakes every waiter. Lookups keep working from
    /// the cache.
    pub fn shutdown(&self) {
        self.shared.state.lock().unwrap().shutdown = true;
        self.shared.work.notify_all();
        self.shared.changed.notify_all();
    }

    /// Bytes of highlights currently cached.
    pub fn cache_bytes(&self) -> usize {
        self.shared.state.lock().unwrap().cache_bytes
    }
}

impl Drop for Highlights {
    fn drop(&mut self) {
        self.shutdown();
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
    }
}

pub struct Request<'a> {
    shared: &'a Shared,
    state: MutexGuard<'a, State>,
    queued: bool,
}

impl Request<'_> {
    /// Looks up highlights for a blob, queueing a job on a miss. `source` is
    /// only called on a miss.
    pub fn get(
        &mut self,
        blob: ObjectId,
        lang: Lang,
        len: usize,
        distance: u32,
        source: impl FnOnce() -> Source,
    ) -> Lookup {
        let key = (blob, lang);
        let state = &mut *self.state;
        state.clock += 1;
        if let Some(entry) = state.cache.get_mut(&key) {
            entry.last_used = state.clock;
            return match &entry.highlights {
                Some(highlights) => Lookup::Ready(highlights.clone()),
                None => Lookup::Plain,
            };
        }
        if len > MAX_FILE_BYTES {
            return Lookup::Plain;
        }
        if !state.in_flight.contains(&key) {
            let request = state.request;
            state
                .pending
                .entry(key)
                .and_modify(|job| {
                    if job.request < request {
                        job.distance = distance;
                    } else {
                        job.distance = job.distance.min(distance);
                    }
                    job.request = request;
                })
                .or_insert_with(|| Job {
                    source: source(),
                    request,
                    distance,
                });
            self.queued = true;
        }
        Lookup::Pending
    }
}

impl Drop for Request<'_> {
    fn drop(&mut self) {
        if !self.queued {
            return;
        }
        let state = &mut *self.state;
        while state.pending.len() > MAX_PENDING {
            let oldest = *state
                .pending
                .iter()
                .min_by_key(|(_, job)| job.priority())
                .unwrap()
                .0;
            state.pending.remove(&oldest);
        }
        self.shared.work.notify_all();
    }
}

impl Shared {
    fn work_loop(&self) {
        loop {
            let (key, source) = {
                let mut state = self.state.lock().unwrap();
                loop {
                    if state.shutdown {
                        return;
                    }
                    if !state.pending.is_empty() {
                        break;
                    }
                    state = self.work.wait(state).unwrap();
                }
                let key = *state
                    .pending
                    .iter()
                    .max_by_key(|(_, job)| job.priority())
                    .unwrap()
                    .0;
                let job = state.pending.remove(&key).unwrap();
                state.in_flight.push(key);
                (key, job.source)
            };

            let (_, lang) = key;
            // A panic in tree-house must not strand the job as in flight.
            let highlights = std::panic::catch_unwind(AssertUnwindSafe(|| {
                FileHighlights::new(lang, source.as_ref().as_ref())
            }))
            .ok()
            .flatten()
            .map(Arc::new);

            let mut state = self.state.lock().unwrap();
            state.in_flight.retain(|k| *k != key);
            state.insert(key, highlights);
            state.epoch += 1;
            drop(state);
            self.changed.notify_all();
        }
    }
}

impl State {
    fn insert(&mut self, key: Key, highlights: Option<Arc<FileHighlights>>) {
        self.cache_bytes += ENTRY_BYTES + highlights.as_ref().map_or(0, |h| h.size_bytes());
        self.clock += 1;
        let entry = Entry {
            highlights,
            last_used: self.clock,
        };
        self.cache.insert(key, entry);
        while self.cache_bytes > self.max_cache_bytes && self.cache.len() > 1 {
            let oldest = *self
                .cache
                .iter()
                .min_by_key(|(_, entry)| entry.last_used)
                .unwrap()
                .0;
            let evicted = self.cache.remove(&oldest).unwrap();
            self.cache_bytes -= ENTRY_BYTES + evicted.highlights.map_or(0, |h| h.size_bytes());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blob(n: u8) -> ObjectId {
        ObjectId::from_bytes_or_panic(&[n; 20])
    }

    fn source(text: &'static str) -> impl FnOnce() -> Source {
        move || Arc::new(text.as_bytes())
    }

    #[test]
    fn highlights_in_the_background() {
        let service = Highlights::new(1 << 20, 1);
        let rust = Lang::for_name("rust").unwrap();
        let text = "fn main() {}\n";
        let lookup = service
            .request()
            .get(blob(1), rust, text.len(), 0, source(text));
        assert!(matches!(lookup, Lookup::Pending));
        assert_eq!(service.wait_for_change(0, Duration::from_secs(10)), 1);
        match service
            .request()
            .get(blob(1), rust, text.len(), 0, source(text))
        {
            Lookup::Ready(highlights) => assert!(!highlights.line(0).is_empty()),
            _ => panic!("expected highlights"),
        }
    }

    #[test]
    fn evicts_least_recently_used() {
        let mut state = State {
            max_cache_bytes: 1,
            ..State::default()
        };
        let rust = Lang::for_name("rust").unwrap();
        for n in 0..3 {
            let highlights = FileHighlights::new(rust, b"fn a() {}\n").map(Arc::new);
            state.insert((blob(n), rust), highlights);
        }
        assert_eq!(state.cache.len(), 1);
        assert!(state.cache.contains_key(&(blob(2), rust)));
    }
}
