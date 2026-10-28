//! JNI bindings for `dev.sargunv.tongs.engine.NativeEngine`.
//!
//! Reviews cross the boundary as opaque handles. The hot path, `fillRows`,
//! writes packed row records into a direct `ByteBuffer` the caller reuses
//! every frame, so scrolling allocates nothing on the JVM side but strings.

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jint, jlong};
use std::sync::Arc;
use tongs_engine::review::Review;

mod fetch;
use tongs_engine::store::Store;

/// Fixed part of a row record: kind u8, 3 padding bytes, then file, old line,
/// new line, and text length as little-endian u32s. UTF-8 text follows.
const RECORD_HEADER: usize = 20;

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
/// if it fills. Returns the number of rows written.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeEngine_fillRows<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
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
            first as usize,
            count as usize,
            out,
        ))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

fn fill_rows(review: &Review, first: usize, count: usize, out: &mut [u8]) -> jint {
    let (mut pos, mut written) = (0, 0);
    let mut text = Vec::new();
    for row in review.rows(first..first + count) {
        text.clear();
        review.row_text(&row, &mut text);
        let len = RECORD_HEADER + text.len();
        if pos + len > out.len() {
            break;
        }
        let record = &mut out[pos..pos + len];
        record[..4].copy_from_slice(&[row.kind as u8, 0, 0, 0]);
        for (i, value) in [row.file, row.old_line, row.new_line, text.len() as u32]
            .into_iter()
            .enumerate()
        {
            record[4 + i * 4..8 + i * 4].copy_from_slice(&value.to_le_bytes());
        }
        record[RECORD_HEADER..].copy_from_slice(&text);
        pos += len;
        written += 1;
    }
    written
}
