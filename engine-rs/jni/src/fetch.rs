//! JNI bindings for `dev.sargunv.tongs.engine.NativeFetch`: a fetch session
//! that Kotlin drives over HTTP, and review lookups it needs.
//!
//! Sessions are called from several Kotlin IO threads at once, so each lives
//! behind a mutex. Response sinks ingest without it: only `finishResponse`
//! takes the lock, to advance the session once a pack is indexed.

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use tongs_engine::fetch::{FetchOptions, FetchSession, Request, ResponseSink};
use tongs_engine::store::Store;

use crate::{Error, review, review_handle};

struct Session {
    session: FetchSession,
    /// Polled requests whose bodies Kotlin hasn't taken yet.
    outgoing: HashMap<u64, Request>,
}

fn session<'a>(handle: jlong) -> MutexGuard<'a, Session> {
    // Safety: handles come from `openSession` and stay valid until
    // `closeSession`, which Kotlin calls only after every request settles.
    let session = unsafe { &*(handle as *const Mutex<Session>) };
    session.lock().unwrap_or_else(|e| e.into_inner())
}

fn oid(text: &str) -> anyhow::Result<gix_hash::ObjectId> {
    Ok(text.parse()?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_openSession<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    store_dir: JString<'local>,
    whole_trees_from_depth: jint,
    blob_batch: jint,
    max_blob_requests: jint,
) -> jlong {
    env.with_env(|env| -> Result<jlong, Error> {
        let store = Store::open(store_dir.try_to_string(env)?)?;
        let options = FetchOptions {
            whole_trees_from_depth: u32::try_from(whole_trees_from_depth).ok(),
            blob_batch: blob_batch as usize,
            max_blob_requests: max_blob_requests as usize,
        };
        let session = Mutex::new(Session {
            session: FetchSession::new(store, options),
            outgoing: HashMap::new(),
        });
        Ok(Box::into_raw(Box::new(session)) as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_closeSession<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // Safety: see `session`.
        drop(unsafe { Box::from_raw(handle as *mut Mutex<Session>) });
    }
}

/// `commits` is space-separated hex ids.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_wantCommits<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    commits: JString<'local>,
) {
    env.with_env(|env| -> Result<(), Error> {
        let commits = commits
            .try_to_string(env)?
            .split_whitespace()
            .map(oid)
            .collect::<anyhow::Result<Vec<_>>>()?;
        session(handle).session.want_commits(&commits);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_startReview<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    old_commit: JString<'local>,
    new_commit: JString<'local>,
) {
    env.with_env(|env| -> Result<(), Error> {
        let old = oid(&old_commit.try_to_string(env)?)?;
        let new = oid(&new_commit.try_to_string(env)?)?;
        session(handle).session.start_review(old, new)?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the next request's id, or 0 if nothing is ready.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_pollRequest<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    let mut session = session(handle);
    match session.session.poll_request() {
        Some(request) => {
            let id = request.id;
            session.outgoing.insert(id, request);
            id as jlong
        }
        None => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_requestLabel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<_, Error> {
        let label = session(handle)
            .outgoing
            .get(&(id as u64))
            .map(|r| r.label.clone())
            .unwrap_or_default();
        Ok(JString::from_str(env, label)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Hands over a polled request's body; call once per request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_takeRequestBody<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) -> JByteArray<'local> {
    env.with_env(|env| -> Result<_, Error> {
        let request = session(handle)
            .outgoing
            .remove(&(id as u64))
            .ok_or_else(|| Error(format!("no request {id}")))?;
        Ok(env.byte_array_from_slice(&request.body)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_openSink<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    let sink = session(handle).session.response_sink();
    Box::into_raw(Box::new(sink)) as jlong
}

/// Streams `length` bytes of `chunk` from `offset` into a response sink. May
/// block while the pack indexer catches up.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_sinkWrite<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    sink: jlong,
    chunk: JByteArray<'local>,
    offset: jint,
    length: jint,
) {
    env.with_env(|env| -> Result<(), Error> {
        let mut buf = vec![0i8; length as usize];
        chunk.get_region(env, offset, &mut buf)?;
        // Safety: i8 and u8 have the same size and alignment.
        let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<u8>(), buf.len()) };
        // Safety: sink handles come from `openSink` and stay valid until
        // `finishResponse` or `abortResponse`.
        let sink = unsafe { &mut *(sink as *mut ResponseSink) };
        sink.write(bytes)?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Waits for the sink's pack to be indexed, then advances the session.
/// Consumes the sink.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_finishResponse<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    sink: jlong,
    id: jlong,
) {
    env.with_env(|_env| -> Result<(), Error> {
        // Safety: see `sinkWrite`.
        let sink = unsafe { Box::from_raw(sink as *mut ResponseSink) };
        let outcome = sink.finish();
        let mut session = session(handle);
        match outcome {
            Ok(outcome) => session.session.complete(id as u64, &outcome)?,
            Err(err) => {
                session.session.fail(id as u64);
                return Err(err.into());
            }
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Drops a request after a transport error, discarding its partial pack.
/// `sink` may be 0 if none was opened.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_abortResponse<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    sink: jlong,
    id: jlong,
) {
    if sink != 0 {
        // Safety: see `sinkWrite`.
        drop(unsafe { Box::from_raw(sink as *mut ResponseSink) });
    }
    session(handle).session.fail(id as u64);
}

/// Fetches the blobs of `review`'s files `first..=last` before any others.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_prioritize<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    review_handle: jlong,
    first: jint,
    last: jint,
) {
    let files = &review(review_handle).files;
    let (first, last) = (first.max(0) as usize, (last.max(-1) + 1) as usize);
    let paths = files[first.min(files.len())..last.min(files.len())]
        .iter()
        .filter(|f| !f.loaded)
        .map(|f| f.path.clone())
        .collect();
    session(handle).session.prioritize(paths);
}

/// Returns a review handle if anything changed since the last snapshot, or 0.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_takeSnapshot<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    session(handle).session.snapshot().map_or(0, review_handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_isDone<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    session(handle).session.is_done()
}

/// Requests sent, objects received, and pack bytes, space-separated.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_stats<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<_, Error> {
        let stats = session(handle).session.stats;
        let text = format!("{} {} {}", stats.requests, stats.objects, stats.pack_bytes);
        Ok(JString::from_str(env, text)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// The index of the file at `path` in `review`, or -1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_fileIndex<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    review_handle: jlong,
    path: JString<'local>,
) -> jint {
    env.with_env(|env| -> Result<jint, Error> {
        let path = path.try_to_string(env)?;
        Ok(review(review_handle)
            .file_index(&path)
            .map_or(-1, |i| i as jint))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Whether file `file` of `review` has its diff, rather than a placeholder.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeFetch_fileLoaded<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    review_handle: jlong,
    file: jint,
) -> jboolean {
    review(review_handle).files[file as usize].loaded
}
