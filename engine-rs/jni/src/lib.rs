//! JNI bindings for `dev.sargunv.tongs.engine.NativeEngine`.
//!
//! Reviews and the highlighter cross the boundary as opaque handles. The hot
//! path, `fillRows`, writes packed row records into a direct `ByteBuffer` the
//! caller reuses every frame, so scrolling allocates nothing on the JVM side
//! but strings and span arrays.

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JByteBuffer, JClass, JIntArray, JString};
use jni::sys::{jboolean, jint, jlong};
use std::sync::Arc;
use tongs_engine::review::{Insert, Layout, Review, RowKind, Side, TAB};

mod fetch;
mod snippet;
use tongs_engine::store::Store;
use tongs_highlight::file::project;
use tongs_highlight::languages::Lang;
use tongs_highlight::service::{Highlights, Lookup};

/// Fixed part of a row record: kind u8, flags u8, span count u16, then file,
/// old line, new line, and text length as u32s, all little-endian. UTF-8 text
/// follows, then one u32 per span: `utf16Column << 8 | styleId`.
const RECORD_HEADER: usize = 20;
/// Record flag: highlights for this row are still being computed.
const FLAG_HIGHLIGHT_PENDING: u8 = 1;
/// Record flag: a header whose file the layout collapsed.
const FLAG_COLLAPSED: u8 = 2;

/// Engine errors, surfaced to Kotlin as `RuntimeException`s.
#[derive(Debug)]
pub(crate) struct Error(String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

impl From<jni::errors::Error> for Error {
    fn from(err: jni::errors::Error) -> Self {
        Self(err.to_string())
    }
}

impl From<anyhow::Error> for Error {
    fn from(err: anyhow::Error) -> Self {
        Self(format!("{err:#}"))
    }
}

/// Review handles are boxed `Arc`s, so fetch sessions can share files between
/// the snapshots they hand out.
pub(crate) fn review<'a>(handle: jlong) -> &'a Review {
    // Safety: handles come from `openReview` or `takeSnapshot` and stay valid
    // until `closeReview`.
    unsafe { &*(handle as *const Arc<Review>) }
}

pub(crate) fn review_handle(review: Review) -> jlong {
    Box::into_raw(Box::new(Arc::new(review))) as jlong
}

fn highlights<'a>(handle: jlong) -> Option<&'a Highlights> {
    // Safety: handles come from `newHighlighter` and stay valid until
    // `closeHighlighter`; 0 means no highlighting.
    (handle != 0).then(|| unsafe { &*(handle as *const Highlights) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_newHighlighter<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cache_bytes: jlong,
    threads: jint,
) -> jlong {
    let highlights = Highlights::new(cache_bytes as usize, threads as usize);
    Box::into_raw(Box::new(highlights)) as jlong
}

/// Sets where grammars bundled compressed (on Android) are extracted.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_setGrammarDir<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    dir: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        tongs_highlight::languages::set_grammar_dir(dir.try_to_string(env)?.into());
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Stops the workers and wakes `awaitHighlights` callers, so the handle can
/// be closed once they return.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_shutdownHighlighter<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Some(highlights) = highlights(handle) {
        highlights.shutdown();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_closeHighlighter<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // Safety: see `highlights`; the Kotlin side forgets the handle after this.
        drop(unsafe { Box::from_raw(handle as *mut Highlights) });
    }
}

/// Blocks until highlights land after epoch `after`, or the timeout passes.
/// Returns the current epoch.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_awaitHighlights<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    after: jlong,
    timeout_millis: jint,
) -> jlong {
    let timeout = std::time::Duration::from_millis(timeout_millis as u64);
    highlights(handle).map_or(0, |h| h.wait_for_change(after as u64, timeout) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_highlighterCacheBytes<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    highlights(handle).map_or(0, |h| h.cache_bytes() as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_openReview<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    store_dir: JString<'local>,
    old_commit: JString<'local>,
    new_commit: JString<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, Error> {
        let (store_dir, old_commit, new_commit) = (
            store_dir.try_to_string(env)?,
            old_commit.try_to_string(env)?,
            new_commit.try_to_string(env)?,
        );
        let open = || -> anyhow::Result<Review> {
            let store = Store::open(store_dir)?;
            Review::open(&store, &old_commit.parse()?, &new_commit.parse()?)
        };
        Ok(review_handle(open()?))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_closeReview<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // Safety: see `review`; the Kotlin side forgets the handle after this.
        drop(unsafe { Box::from_raw(handle as *mut Arc<Review>) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_rowCount<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    review(handle).row_count() as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_fileCount<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    review(handle).files.len() as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_fileFirstRow<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    file: jint,
) -> jint {
    review(handle).file_first_row(file as usize) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_filePath<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    file: jint,
) -> JString<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        JString::from_str(env, &review(handle).files[file as usize].path)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Writes records for rows `first..first + count` into `out`, stopping early
/// if it fills. Returns the number of rows written. With a highlighter, rows
/// carry the spans that are ready and queue the rest, nearest the middle of
/// the range first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_fillRows<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    highlighter: jlong,
    first: jint,
    count: jint,
    out: JByteBuffer<'local>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let ptr = env.get_direct_buffer_address(&out)?;
        let capacity = env.get_direct_buffer_capacity(&out)?;
        // Safety: the buffer is direct, and Kotlin doesn't touch it during the call.
        let out = unsafe { std::slice::from_raw_parts_mut(ptr, capacity) };
        Ok(fill_rows(
            review(handle),
            highlights(highlighter),
            first as usize,
            count as usize,
            out,
        ))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

fn fill_rows(
    review: &Review,
    highlights: Option<&Highlights>,
    first: usize,
    count: usize,
    out: &mut [u8],
) -> jint {
    let end = (first + count).min(review.row_count());
    let middle = (first + end) / 2;
    let mut request = highlights.map(Highlights::request);
    // Lookups for the file sides seen so far; rows of one file alternate
    // between its two sides.
    let mut lookups = Vec::new();
    let (mut pos, mut written) = (0, 0);
    let (mut text, mut spans) = (Vec::new(), Vec::new());
    for (i, row) in review.rows(first..end).enumerate() {
        text.clear();
        spans.clear();
        review.row_text(&row, &mut text);
        let mut flags = if row.collapsed { FLAG_COLLAPSED } else { 0 };
        if let (Some(request), Some(source)) = (&mut request, review.row_source(&row)) {
            let lookup = match lookups.iter().find(|(blob, _)| *blob == source.blob) {
                Some((_, lookup)) => lookup,
                None => {
                    let path = &review.files[row.file as usize].path;
                    let lookup = match Lang::for_path(path) {
                        Some(lang) => request.get(
                            source.blob,
                            lang,
                            source.text.bytes().len(),
                            (first + i).abs_diff(middle) as u32,
                            || source.text.clone(),
                        ),
                        None => Lookup::Plain,
                    };
                    lookups.push((source.blob, lookup));
                    &lookups.last().unwrap().1
                }
            };
            match lookup {
                Lookup::Ready(file) => {
                    project(
                        source.text.line(source.line),
                        file.line(source.line),
                        TAB.len() as u32,
                        &mut spans,
                    );
                    // Drop spans past the truncated text; UTF-16 is never
                    // longer than UTF-8.
                    spans.retain(|span| (span >> 8) < text.len() as u32);
                }
                Lookup::Pending => flags |= FLAG_HIGHLIGHT_PENDING,
                Lookup::Plain => {}
            }
        }
        let len = RECORD_HEADER + text.len() + spans.len() * 4;
        if pos + len > out.len() {
            break;
        }
        let record = &mut out[pos..pos + len];
        record[0] = row.kind as u8;
        record[1] = flags;
        record[2..4].copy_from_slice(&(spans.len() as u16).to_le_bytes());
        for (i, value) in [row.file, row.old_line, row.new_line, text.len() as u32]
            .into_iter()
            .enumerate()
        {
            record[4 + i * 4..8 + i * 4].copy_from_slice(&value.to_le_bytes());
        }
        let spans_at = RECORD_HEADER + text.len();
        record[RECORD_HEADER..spans_at].copy_from_slice(&text);
        for (i, span) in spans.iter().enumerate() {
            record[spans_at + i * 4..spans_at + i * 4 + 4].copy_from_slice(&span.to_le_bytes());
        }
        pos += len;
        written += 1;
    }
    written
}

/// Every file's metadata, packed for one call: per file, its class, change
/// kind, and flags (1 loaded, 2 binary) as u8s and a pad byte; additions and
/// deletions as u32s; old and new blob ids as 20 bytes each, zero when
/// absent; then the path and the old path (empty unless renamed), each as a
/// u32 length and UTF-8 bytes. Little-endian.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_fileInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JByteArray<'local> {
    env.with_env(|env| -> Result<_, Error> {
        let review = review(handle);
        let mut out = Vec::with_capacity(review.files.len() * 96);
        for (file, class) in review.files.iter().zip(&review.classes) {
            let flags = u8::from(file.loaded) | (u8::from(file.is_binary()) << 1);
            out.extend_from_slice(&[*class as u8, file.kind as u8, flags, 0]);
            out.extend_from_slice(&file.additions.to_le_bytes());
            out.extend_from_slice(&file.deletions.to_le_bytes());
            for id in [file.old_id, file.new_id] {
                match id {
                    Some(id) => out.extend_from_slice(id.as_bytes()),
                    None => out.extend_from_slice(&[0; 20]),
                }
            }
            for text in [file.path.as_str(), file.old_path.as_deref().unwrap_or("")] {
                out.extend_from_slice(&(text.len() as u32).to_le_bytes());
                out.extend_from_slice(text.as_bytes());
            }
        }
        Ok(env.byte_array_from_slice(&out)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Whether every changed file is known, though some may still be loading.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_reviewComplete<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    review(handle).complete
}

/// A new review handle with the same files laid out for reading:
/// `collapsed` has a byte per file, nonzero to collapse it, and `inserts`
/// has five ints per block: file, side (0 old, 1 new), line, rows, and id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_withLayout<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    collapsed: JByteArray<'local>,
    inserts: JIntArray<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, Error> {
        let collapsed_len = collapsed.len(env)?;
        let mut flags = vec![0i8; collapsed_len];
        collapsed.get_region(env, 0, &mut flags)?;
        let inserts_len = inserts.len(env)?;
        let mut ints = vec![0i32; inserts_len];
        inserts.get_region(env, 0, &mut ints)?;
        let layout = Layout {
            collapsed: flags.iter().map(|&f| f != 0).collect(),
            inserts: ints
                .as_chunks::<5>()
                .0
                .iter()
                .map(|c| Insert {
                    file: c[0] as u32,
                    side: if c[1] == 0 { Side::Old } else { Side::New },
                    line: c[2] as u32,
                    rows: c[3] as u32,
                    id: c[4] as u32,
                })
                .collect(),
        };
        Ok(review_handle(review(handle).with_layout(&layout)))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// The row with this content in `file`, or -1; see `Review::find_row`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_findRow<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    file: jint,
    kind: jint,
    old_line: jint,
    new_line: jint,
) -> jint {
    let Some(kind) = ROW_KINDS.get(kind as usize) else {
        return -1;
    };
    review(handle)
        .find_row(file as usize, *kind, old_line as u32, new_line as u32)
        .map_or(-1, |row| row as jint)
}

const ROW_KINDS: [RowKind; 9] = [
    RowKind::FileHeader,
    RowKind::Hunk,
    RowKind::Context,
    RowKind::Added,
    RowKind::Removed,
    RowKind::Binary,
    RowKind::Loading,
    RowKind::Renamed,
    RowKind::Annotation,
];

/// A line as it is in the blob, tabs and all; side 0 is old, 1 new.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_lineText<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    file: jint,
    side: jint,
    line: jint,
) -> JString<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let side = if side == 0 { Side::Old } else { Side::New };
        let text = review(handle).line_text(file as usize, side, line as u32);
        JString::from_str(env, String::from_utf8_lossy(text))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
