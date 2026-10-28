//! The review row model: every changed file in a diff, flattened into one
//! sequence of fixed-height rows that a renderer can index directly.
//!
//! A [`Review`] is an immutable snapshot. Each file's rows and text live in an
//! `Arc<FileEntry>`, so while a fetch is still running, each new snapshot
//! shares every file that hasn't changed and only rebuilds the per-file prefix
//! sums. A file whose blobs haven't arrived yet shows its header and a
//! placeholder row.

use std::ops::Range;
use std::sync::Arc;

use anyhow::Result;
use gix_hash::ObjectId;
use imara_diff::{Algorithm, Diff, InternedInput};

use crate::store::Store;
use crate::tree_walk::{Change, TreeWalk};

const CONTEXT: u32 = 3;
pub const TAB: &str = "    ";
/// Longer lines are cut off; the renderer never needs to shape more.
const MAX_LINE_BYTES: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum RowKind {
    FileHeader = 0,
    Hunk = 1,
    Context = 2,
    Added = 3,
    Removed = 4,
    Binary = 5,
    /// Stands in for a file's diff until its blobs arrive.
    Loading = 6,
}

#[derive(Clone, Copy, Debug)]
pub struct Row {
    pub kind: RowKind,
    pub file: u32,
    /// 1-based; 0 when the row has no line on that side.
    pub old_line: u32,
    pub new_line: u32,
}

/// A row relative to its file; the file index depends on the snapshot.
#[derive(Clone, Copy, Debug)]
struct FileRow {
    kind: RowKind,
    old_line: u32,
    new_line: u32,
}

impl FileRow {
    const fn new(kind: RowKind, old_line: u32, new_line: u32) -> Self {
        Self {
            kind,
            old_line,
            new_line,
        }
    }
}

pub struct FileEntry {
    pub path: String,
    /// Blob ids; `None` on the side where the file doesn't exist.
    pub old_id: Option<ObjectId>,
    pub new_id: Option<ObjectId>,
    pub additions: u32,
    pub deletions: u32,
    /// Whether the diff is in; until then the file is a header and a
    /// [`RowKind::Loading`] row.
    pub loaded: bool,
    rows: Vec<FileRow>,
    pub old: Arc<Text>,
    pub new: Arc<Text>,
}

impl FileEntry {
    /// A file whose blobs haven't been fetched yet.
    pub fn pending(change: &Change) -> Self {
        Self {
            path: change.path.clone(),
            old_id: change.old,
            new_id: change.new,
            additions: 0,
            deletions: 0,
            loaded: false,
            rows: vec![
                FileRow::new(RowKind::FileHeader, 0, 0),
                FileRow::new(RowKind::Loading, 0, 0),
            ],
            old: Arc::new(Text::new(Vec::new())),
            new: Arc::new(Text::new(Vec::new())),
        }
    }

    /// Diffs a file whose blobs are in `store`.
    pub fn load(store: &Store, change: &Change) -> Result<Self> {
        let load = |id: Option<ObjectId>| -> Result<Text> {
            Ok(Text::new(
                id.map(|id| store.blob(&id))
                    .transpose()?
                    .unwrap_or_default(),
            ))
        };
        let (old, new) = (load(change.old)?, load(change.new)?);
        let mut rows = vec![FileRow::new(RowKind::FileHeader, 0, 0)];
        let (mut additions, mut deletions) = (0, 0);
        if old.binary || new.binary {
            rows.push(FileRow::new(RowKind::Binary, 0, 0));
        } else {
            let input = InternedInput::new(old.lines(), new.lines());
            let mut diff = Diff::compute(Algorithm::Histogram, &input);
            diff.postprocess_lines(&input);
            additions = diff.count_additions();
            deletions = diff.count_removals();
            let hunks: Vec<_> = diff.hunks().map(|h| (h.before, h.after)).collect();
            push_hunks(&mut rows, &hunks, old.line_count(), new.line_count());
        }
        Ok(Self {
            path: change.path.clone(),
            old_id: change.old,
            new_id: change.new,
            additions,
            deletions,
            loaded: true,
            rows,
            old: Arc::new(old),
            new: Arc::new(new),
        })
    }
}

/// The source line behind a code row.
pub struct RowSource<'a> {
    pub blob: ObjectId,
    pub text: &'a Arc<Text>,
    /// 0-based.
    pub line: u32,
}

pub struct Review {
    /// Sorted by path.
    pub files: Vec<Arc<FileEntry>>,
    /// Each file's first row, then the total row count.
    starts: Vec<u32>,
}

impl Review {
    /// Diffs two commits whose trees and changed blobs are already in `store`.
    pub fn open(store: &Store, old_commit: &ObjectId, new_commit: &ObjectId) -> Result<Self> {
        let mut walk = TreeWalk::new(
            store.commit_tree(old_commit)?,
            store.commit_tree(new_commit)?,
        );
        while !walk.is_done() {
            walk.step(store)?;
        }
        let mut changes = walk.changes;
        changes.sort_by(|a, b| a.path.cmp(&b.path));
        let files = changes
            .iter()
            .map(|change| FileEntry::load(store, change).map(Arc::new))
            .collect::<Result<_>>()?;
        Ok(Self::from_files(files))
    }

    /// Assembles a snapshot from files sorted by path.
    pub fn from_files(files: Vec<Arc<FileEntry>>) -> Self {
        let mut starts = Vec::with_capacity(files.len() + 1);
        let mut total = 0u32;
        for file in &files {
            starts.push(total);
            total += file.rows.len() as u32;
        }
        starts.push(total);
        Self { files, starts }
    }

    pub fn row_count(&self) -> usize {
        *self.starts.last().unwrap() as usize
    }

    pub fn file_first_row(&self, file: usize) -> u32 {
        self.starts[file]
    }

    pub fn file_index(&self, path: &str) -> Option<usize> {
        self.files
            .binary_search_by(|f| f.path.as_str().cmp(path))
            .ok()
    }

    /// Rows in `range`, clamped to the review.
    pub fn rows(&self, range: Range<usize>) -> impl Iterator<Item = Row> + '_ {
        let end = range.end.min(self.row_count());
        let start = range.start.min(end);
        let mut file = self.starts.partition_point(|&s| s as usize <= start) - 1;
        (start..end).map(move |i| {
            while self.starts[file + 1] as usize <= i {
                file += 1;
            }
            let row = self.files[file].rows[i - self.starts[file] as usize];
            Row {
                kind: row.kind,
                file: file as u32,
                old_line: row.old_line,
                new_line: row.new_line,
            }
        })
    }

    /// The blob and line a code row shows: the old side for removed lines,
    /// the new side otherwise.
    pub fn row_source(&self, row: &Row) -> Option<RowSource<'_>> {
        let file = &self.files[row.file as usize];
        let (blob, text, line) = match row.kind {
            RowKind::Removed => (file.old_id?, &file.old, row.old_line),
            RowKind::Added | RowKind::Context => (file.new_id?, &file.new, row.new_line),
            _ => return None,
        };
        Some(RowSource {
            blob,
            text,
            line: line - 1,
        })
    }

    /// Display text for a row: tabs expanded, line ending stripped, capped.
    pub fn row_text(&self, row: &Row, out: &mut Vec<u8>) {
        let file = &self.files[row.file as usize];
        let line = match row.kind {
            RowKind::FileHeader => {
                out.extend_from_slice(file.path.as_bytes());
                return;
            }
            RowKind::Hunk => {
                out.extend_from_slice(b"\xe2\x8b\xaf"); // ⋯
                return;
            }
            RowKind::Binary => {
                out.extend_from_slice(b"Binary file not shown");
                return;
            }
            RowKind::Loading => {
                out.extend_from_slice(b"Loading\xe2\x80\xa6"); // …
                return;
            }
            RowKind::Removed => file.old.line(row.old_line - 1),
            RowKind::Added | RowKind::Context => file.new.line(row.new_line - 1),
        };
        let start = out.len();
        for (i, part) in line.split(|&b| b == b'\t').enumerate() {
            if i > 0 {
                out.extend_from_slice(TAB.as_bytes());
            }
            out.extend_from_slice(part);
            if out.len() - start >= MAX_LINE_BYTES {
                break;
            }
        }
        if out.len() - start > MAX_LINE_BYTES {
            let mut end = start + MAX_LINE_BYTES;
            while end > start && (out[end] & 0xC0) == 0x80 {
                end -= 1;
            }
            out.truncate(end);
        }
    }
}

/// Emits unified-diff rows, merging hunks whose context would overlap.
fn push_hunks(
    rows: &mut Vec<FileRow>,
    hunks: &[(Range<u32>, Range<u32>)],
    old_len: u32,
    new_len: u32,
) {
    let mut i = 0;
    while i < hunks.len() {
        let mut j = i;
        while j + 1 < hunks.len() && hunks[j + 1].0.start - hunks[j].0.end <= 2 * CONTEXT {
            j += 1;
        }
        let old_start = hunks[i].0.start.saturating_sub(CONTEXT);
        let new_start = hunks[i].1.start.saturating_sub(CONTEXT);
        rows.push(FileRow::new(RowKind::Hunk, old_start + 1, new_start + 1));
        let (mut old_pos, mut new_pos) = (old_start, new_start);
        for (before, after) in &hunks[i..=j] {
            while old_pos < before.start {
                push_context(rows, &mut old_pos, &mut new_pos);
            }
            for line in before.clone() {
                rows.push(FileRow::new(RowKind::Removed, line + 1, 0));
            }
            for line in after.clone() {
                rows.push(FileRow::new(RowKind::Added, 0, line + 1));
            }
            (old_pos, new_pos) = (before.end, after.end);
        }
        let old_end = (hunks[j].0.end + CONTEXT).min(old_len);
        while old_pos < old_end && new_pos < new_len {
            push_context(rows, &mut old_pos, &mut new_pos);
        }
        i = j + 1;
    }
}

fn push_context(rows: &mut Vec<FileRow>, old_pos: &mut u32, new_pos: &mut u32) {
    rows.push(FileRow::new(RowKind::Context, *old_pos + 1, *new_pos + 1));
    *old_pos += 1;
    *new_pos += 1;
}

pub struct Text {
    bytes: Vec<u8>,
    /// Byte offset of each line start, plus a final entry at `bytes.len()`.
    starts: Vec<u32>,
    binary: bool,
}

impl Text {
    fn new(bytes: Vec<u8>) -> Self {
        let binary = bytes[..bytes.len().min(8000)].contains(&0);
        let mut starts = vec![0];
        if !binary {
            starts.extend(memchr_iter(&bytes).map(|i| i as u32 + 1));
            if starts.last() != Some(&(bytes.len() as u32)) {
                starts.push(bytes.len() as u32);
            }
        }
        Self {
            bytes,
            starts,
            binary,
        }
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn is_binary(&self) -> bool {
        self.binary
    }

    pub fn line_count(&self) -> u32 {
        self.starts.len() as u32 - 1
    }

    /// Lines including their terminators, as imara-diff tokens.
    fn lines(&self) -> impl imara_diff::TokenSource<Token = &[u8]> + '_ {
        imara_diff::sources::byte_lines(&self.bytes)
    }

    /// A line without its terminator.
    pub fn line(&self, index: u32) -> &[u8] {
        let (start, end) = (self.starts[index as usize], self.starts[index as usize + 1]);
        let line = &self.bytes[start as usize..end as usize];
        let line = line.strip_suffix(b"\n").unwrap_or(line);
        line.strip_suffix(b"\r").unwrap_or(line)
    }
}

impl AsRef<[u8]> for Text {
    fn as_ref(&self) -> &[u8] {
        &self.bytes
    }
}

fn memchr_iter(bytes: &[u8]) -> impl Iterator<Item = usize> + '_ {
    bytes
        .iter()
        .enumerate()
        .filter(|(_, b)| **b == b'\n')
        .map(|(i, _)| i)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_nearby_hunks_and_trims_context() {
        let mut rows = Vec::new();
        // Changes at lines 2 and 5 of 20: one merged hunk, context capped at 3.
        push_hunks(&mut rows, &[(1..2, 1..2), (4..5, 4..5)], 20, 20);
        use RowKind::*;
        assert_eq!(
            rows.iter().map(|r| r.kind).collect::<Vec<_>>(),
            [
                Hunk, Context, Removed, Added, Context, Context, Removed, Added, Context, Context,
                Context
            ]
        );
    }

    #[test]
    fn line_text_strips_terminators() {
        let text = Text::new(b"a\r\nb\nc".to_vec());
        assert_eq!(text.line_count(), 3);
        assert_eq!(
            [text.line(0), text.line(1), text.line(2)],
            [b"a", b"b", b"c"]
        );
    }
}
