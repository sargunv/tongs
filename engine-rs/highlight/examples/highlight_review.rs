//! Highlights both sides of every file in a review and reports throughput,
//! wall time on all cores, and span memory.
//!
//! Usage: `cargo run --release -p tongs-highlight --example highlight_review
//! [--threads=N] [storeDir oldCommit newCommit]`. Defaults to maplibre/maplibre-native-ffi#760
//! in `/tmp/tongs-spike-store`.

use std::collections::{BTreeMap, HashSet};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use tongs_engine::review::{Review, Text};
use tongs_engine::store::Store;
use tongs_highlight::file::FileHighlights;
use tongs_highlight::languages::Lang;

#[derive(Default)]
struct Totals {
    files: usize,
    bytes: usize,
    lines: usize,
    time: Duration,
    skipped: usize,
}

fn main() -> anyhow::Result<()> {
    let (flags, args): (Vec<String>, Vec<String>) = std::env::args()
        .skip(1)
        .partition(|arg| arg.starts_with("--"));
    let threads = match flags.iter().find_map(|f| f.strip_prefix("--threads=")) {
        Some(n) => n.parse()?,
        None => std::thread::available_parallelism()?.get(),
    };
    let (store, old, new) = match &args[..] {
        [store, old, new] => (store.as_str(), old.as_str(), new.as_str()),
        _ => (
            "/tmp/tongs-spike-store",
            "d269e1bdf76667607c2783521d4d2eb119cd76c0",
            "3b56ef91641085ad555def7a4b408c87f131fd8d",
        ),
    };
    let review = Review::open(&Store::open(store)?, &old.parse()?, &new.parse()?)?;

    let started = Instant::now();
    let failed: Vec<_> = Lang::all().filter(|lang| !lang.prepare()).collect();
    println!(
        "compile queries for {} languages: {:.0} ms{}",
        Lang::all().count(),
        started.elapsed().as_secs_f64() * 1e3,
        if failed.is_empty() {
            String::new()
        } else {
            format!(", failed: {failed:?}")
        }
    );

    // Each distinct blob once, as the cache would see it.
    let mut seen = HashSet::new();
    let mut jobs: Vec<(Lang, &Text)> = Vec::new();
    let mut unsupported = 0;
    for file in &review.files {
        let Some(lang) = Lang::for_path(&file.path) else {
            unsupported += 1;
            continue;
        };
        for (blob, text) in [(file.old_blob, &file.old), (file.new_blob, &file.new)] {
            if let Some(blob) = blob
                && !text.is_binary()
                && seen.insert(blob)
            {
                jobs.push((lang, text));
            }
        }
    }
    // Biggest first, so one large file doesn't finish last on its own.
    jobs.sort_by_key(|(_, text)| std::cmp::Reverse(text.bytes().len()));
    println!(
        "{} files, {} without a grammar; {} distinct blobs to highlight",
        review.files.len(),
        unsupported,
        jobs.len()
    );

    let next = AtomicUsize::new(0);
    let by_lang = Mutex::new(BTreeMap::<&str, Totals>::new());
    let (spans, span_bytes) = (AtomicUsize::new(0), AtomicUsize::new(0));
    let slowest = Mutex::new((Duration::ZERO, 0, ""));
    let started = Instant::now();
    std::thread::scope(|scope| {
        for _ in 0..threads {
            scope.spawn(|| {
                while let Some((lang, text)) = jobs.get(next.fetch_add(1, Ordering::Relaxed)) {
                    let t = Instant::now();
                    let highlights = FileHighlights::new(*lang, text.bytes());
                    let elapsed = t.elapsed();
                    if let Some(h) = &highlights {
                        spans.fetch_add(h.span_count(), Ordering::Relaxed);
                        span_bytes.fetch_add(h.size_bytes(), Ordering::Relaxed);
                    }
                    let mut by_lang = by_lang.lock().unwrap();
                    let totals = by_lang.entry(lang.name()).or_default();
                    totals.files += 1;
                    totals.bytes += text.bytes().len();
                    totals.lines += text.line_count() as usize;
                    totals.time += elapsed;
                    totals.skipped += usize::from(highlights.is_none());
                    let mut slowest = slowest.lock().unwrap();
                    if elapsed > slowest.0 {
                        *slowest = (elapsed, text.bytes().len(), lang.name());
                    }
                }
            });
        }
    });
    let wall = started.elapsed();

    let by_lang = by_lang.into_inner().unwrap();
    println!(
        "{:<16} {:>6} {:>8} {:>9} {:>9} {:>8} {:>7}",
        "language", "blobs", "skipped", "KiB", "lines", "CPU ms", "MB/s"
    );
    let mut all = Totals::default();
    for (name, t) in &by_lang {
        println!(
            "{name:<16} {:>6} {:>8} {:>9} {:>9} {:>8.0} {:>7.1}",
            t.files,
            t.skipped,
            t.bytes / 1024,
            t.lines,
            t.time.as_secs_f64() * 1e3,
            t.bytes as f64 / t.time.as_secs_f64() / 1e6
        );
        all.files += t.files;
        all.skipped += t.skipped;
        all.bytes += t.bytes;
        all.lines += t.lines;
        all.time += t.time;
    }
    println!(
        "{:<16} {:>6} {:>8} {:>9} {:>9} {:>8.0} {:>7.1}",
        "total",
        all.files,
        all.skipped,
        all.bytes / 1024,
        all.lines,
        all.time.as_secs_f64() * 1e3,
        all.bytes as f64 / all.time.as_secs_f64() / 1e6
    );
    let slowest = slowest.into_inner().unwrap();
    println!(
        "wall time on {threads} threads: {:.0} ms; slowest blob {:.0} ms ({} KiB of {})",
        wall.as_secs_f64() * 1e3,
        slowest.0.as_secs_f64() * 1e3,
        slowest.1 / 1024,
        slowest.2
    );
    let (spans, span_bytes) = (spans.into_inner(), span_bytes.into_inner());
    println!(
        "spans: {spans} in {:.1} MiB ({:.1} bytes per line)",
        span_bytes as f64 / (1 << 20) as f64,
        span_bytes as f64 / all.lines as f64
    );
    Ok(())
}
