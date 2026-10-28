//! Spike: review-diff a GitHub PR from git objects alone, and time each stage.

use std::process::Command;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use clap::Parser;
use gix_hash::ObjectId;
use serde_json::Value;
use tongs_engine::file_diff;
use tongs_engine::protocol::{FetchRequest, Filter, PackStream};
use tongs_engine::store::Store;
use tongs_engine::tree_walk::TreeWalk;

#[derive(Parser)]
struct Args {
    /// `owner/repo`
    repo: String,
    number: u64,
    /// Object store directory; reuse it to measure warm runs.
    #[arg(long, default_value = "/tmp/tongs-spike-store")]
    store: String,
    /// Blobs per fetch request.
    #[arg(long, default_value_t = 500)]
    batch: usize,
    /// Concurrent fetch requests.
    #[arg(long, default_value_t = 4)]
    concurrency: usize,
    /// Print per-file `path\tadditions\tdeletions` to stdout.
    #[arg(long)]
    dump: bool,
}

struct Client {
    agent: ureq::Agent,
    token: String,
    repo: String,
}

impl Client {
    fn api(&self, path: &str) -> Result<Value> {
        let url = format!("https://api.github.com/repos/{}/{path}", self.repo);
        let mut response = self
            .agent
            .get(&url)
            .header("Authorization", &format!("Bearer {}", self.token))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .call()?;
        Ok(response.body_mut().read_json()?)
    }

    fn fetch(
        &self,
        store: &Store,
        wants: Vec<ObjectId>,
        filter: Option<Filter>,
        deepen: Option<u32>,
    ) -> Result<(u32, u64)> {
        let url = format!("https://github.com/{}.git/git-upload-pack", self.repo);
        let body = FetchRequest {
            wants,
            filter,
            deepen,
        }
        .body();
        let mut request = self
            .agent
            .post(&url)
            .header("Authorization", &basic_auth(&self.token));
        for (name, value) in FetchRequest::HEADERS {
            request = request.header(name, value);
        }
        let mut response = request.send(&body[..])?;
        let outcome = store.ingest(PackStream::new(response.body_mut().as_reader()))?;
        Ok((outcome.objects, outcome.pack_bytes))
    }
}

fn basic_auth(token: &str) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let input = format!("x-access-token:{token}");
    let mut out = String::from("Basic ");
    for chunk in input.as_bytes().chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..4 {
            out.push(if i <= chunk.len() {
                TABLE[(n >> (18 - 6 * i)) as usize & 63] as char
            } else {
                '='
            });
        }
    }
    out
}

fn token() -> Result<String> {
    if let Ok(token) = std::env::var("GH_TOKEN") {
        return Ok(token);
    }
    let out = Command::new("gh")
        .args(["auth", "token"])
        .output()
        .context("running gh auth token")?;
    Ok(String::from_utf8(out.stdout)?.trim().to_owned())
}

fn oid(value: &Value) -> Result<ObjectId> {
    Ok(ObjectId::from_hex(
        value.as_str().context("expected sha")?.as_bytes(),
    )?)
}

fn stage(name: &str, start: Instant) {
    eprintln!("  {name:<28} {:>8.2}s", start.elapsed().as_secs_f64());
}

fn main() -> Result<()> {
    let args = Args::parse();
    let client = Client {
        agent: ureq::Agent::new_with_defaults(),
        token: token()?,
        repo: args.repo.clone(),
    };
    let store = Store::open(&args.store)?;
    let total = Instant::now();

    let t = Instant::now();
    let pr = client.api(&format!("pulls/{}", args.number))?;
    let (base, head) = (oid(&pr["base"]["sha"])?, oid(&pr["head"]["sha"])?);
    let compare = client.api(&format!("compare/{base}...{head}?per_page=1"))?;
    let merge_base = oid(&compare["merge_base_commit"]["sha"])?;
    stage("metadata (REST)", t);
    eprintln!(
        "  PR: +{} -{} in {} files; merge base {merge_base}, head {head}",
        pr["additions"], pr["deletions"], pr["changed_files"]
    );

    let t = Instant::now();
    let commits: Vec<_> = [merge_base, head]
        .into_iter()
        .filter(|id| !store.contains(id))
        .collect();
    if !commits.is_empty() {
        client.fetch(&store, commits, Some(Filter::TreeZero), Some(1))?;
    }
    let mut walk = TreeWalk::new(store.commit_tree(&merge_base)?, store.commit_tree(&head)?);
    stage("commits", t);

    let t = Instant::now();
    let (mut rounds, mut trees, mut tree_bytes) = (0, 0, 0);
    while !walk.is_done() {
        let missing = walk.missing(&store);
        if !missing.is_empty() {
            rounds += 1;
            let (objects, bytes) = client.fetch(&store, missing, Some(Filter::TreeZero), None)?;
            trees += objects;
            tree_bytes += bytes;
        }
        walk.step(&store)?;
    }
    stage("tree walk", t);
    eprintln!(
        "  {} changed files; {trees} trees ({} KiB) in {rounds} round trips",
        walk.changes.len(),
        tree_bytes / 1024
    );

    let t = Instant::now();
    let mut blobs: Vec<_> = walk
        .changes
        .iter()
        .flat_map(|c| [c.old, c.new])
        .flatten()
        .filter(|id| !store.contains(id))
        .collect();
    blobs.sort_unstable();
    blobs.dedup();
    let batches: Vec<_> = blobs.chunks(args.batch.max(1)).map(<[_]>::to_vec).collect();
    let (next, objects, bytes) = (AtomicU32::new(0), AtomicU32::new(0), AtomicU64::new(0));
    let slowest = std::sync::Mutex::new(Duration::ZERO);
    std::thread::scope(|scope| -> Result<()> {
        let workers: Vec<_> = (0..args.concurrency.max(1))
            .map(|_| {
                let store = store.clone();
                scope.spawn(|| -> Result<()> {
                    let store = store;
                    loop {
                        let i = next.fetch_add(1, Ordering::Relaxed) as usize;
                        let Some(batch) = batches.get(i) else {
                            return Ok(());
                        };
                        let started = Instant::now();
                        let (n, b) =
                            client.fetch(&store, batch.clone(), Some(Filter::BlobNone), None)?;
                        objects.fetch_add(n, Ordering::Relaxed);
                        bytes.fetch_add(b, Ordering::Relaxed);
                        let mut slowest = slowest.lock().unwrap();
                        *slowest = (*slowest).max(started.elapsed());
                    }
                })
            })
            .collect();
        for worker in workers {
            worker.join().unwrap()?;
        }
        Ok(())
    })?;
    stage("blobs", t);
    eprintln!(
        "  {} blobs ({} MiB) in {} requests of <= {}; slowest request {:.2}s",
        objects.into_inner(),
        bytes.into_inner() / (1024 * 1024),
        batches.len(),
        args.batch,
        slowest.into_inner().unwrap().as_secs_f64()
    );

    let t = Instant::now();
    let store = Store::open(&args.store)?;
    let (mut additions, mut deletions, mut hunks, mut binary) = (0u64, 0u64, 0u64, 0);
    let mut slowest_diff = (Duration::ZERO, String::new());
    for change in &walk.changes {
        let started = Instant::now();
        let old = change
            .old
            .map(|id| store.blob(&id))
            .transpose()?
            .unwrap_or_default();
        let new = change
            .new
            .map(|id| store.blob(&id))
            .transpose()?
            .unwrap_or_default();
        let diff = file_diff::diff(&old, &new);
        if args.dump {
            println!("{}\t{}\t{}", change.path, diff.additions, diff.deletions);
        }
        additions += u64::from(diff.additions);
        deletions += u64::from(diff.deletions);
        hunks += u64::from(diff.hunks);
        binary += usize::from(diff.binary);
        if started.elapsed() > slowest_diff.0 {
            slowest_diff = (started.elapsed(), change.path.clone());
        }
    }
    stage("diff all files (1 thread)", t);
    eprintln!(
        "  +{additions} -{deletions}, {hunks} hunks, {binary} binary; slowest file {:.1}ms ({})",
        slowest_diff.0.as_secs_f64() * 1000.0,
        slowest_diff.1
    );
    stage("total", total);

    if walk.changes.len() as u64 != pr["changed_files"].as_u64().unwrap_or(0) {
        bail!("changed file count differs from GitHub's");
    }
    Ok(())
}
