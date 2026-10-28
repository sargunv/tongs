//! The review row model: every changed file in a diff, flattened into one
//! sequence of fixed-height rows that a renderer can index directly.
//!
//! This spike version builds all rows eagerly. Diffing the 576k-line
//! maplibre PR takes ~50ms, so laziness can wait until fetching is wired in.

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
}

#[derive(Clone, Copy, Debug)]
pub struct Row {
    pub kind: RowKind,
    pub file: u32,
    /// 1-based; 0 when the row has no line on that side.
    pub old_line: u32,
    pub new_line: u32,
}

pub struct FileEntry {
    pub path: String,
    pub first_row: u32,
    pub additions: u32,
    pub deletions: u32,
    pub old_blob: Option<ObjectId>,
    pub new_blob: Option<ObjectId>,
    pub old: Arc<Text>,
    pub new: Arc<Text>,
}

/// The source line behind a code row.
pub struct RowSource<'a> {
    pub blob: ObjectId,
    pub text: &'a Arc<Text>,
    /// 0-based.
    pub line: u32,
}

pub struct Review {
    pub files: Vec<FileEntry>,
    pub rows: Vec<Row>,
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

        let mut review = Review {
            files: Vec::with_capacity(changes.len()),
            rows: Vec::new(),
        };
        for change in changes {
            review.push_file(store, change)?;
        }
        Ok(review)
    }

    fn push_file(&mut self, store: &Store, change: Change) -> Result<()> {
        let load = |id: Option<ObjectId>| -> Result<Text> {
            Ok(Text::new(
                id.map(|id| store.blob(&id))
                    .transpose()?
                    .unwrap_or_default(),
            ))
        };
        let (old, new) = (load(change.old)?, load(change.new)?);
        let file = self.files.len() as u32;
        let first_row = self.rows.len() as u32;
        self.rows.push(Row {
            kind: RowKind::FileHeader,
            file,
            old_line: 0,
            new_line: 0,
        });

        let (mut additions, mut deletions) = (0, 0);
        if old.binary || new.binary {
            self.rows.push(Row {
                kind: RowKind::Binary,
                file,
                old_line: 0,
                new_line: 0,
            });
        } else {
            let input = InternedInput::new(old.lines(), new.lines());
            let mut diff = Diff::compute(Algorithm::Histogram, &input);
            diff.postprocess_lines(&input);
            additions = diff.count_additions();
            deletions = diff.count_removals();
            let hunks: Vec<_> = diff.hunks().map(|h| (h.before, h.after)).collect();
            self.push_hunks(file, &hunks, old.line_count(), new.line_count());
        }
        self.files.push(FileEntry {
            path: change.path,
            first_row,
            additions,
            deletions,
            old_blob: change.old,
            new_blob: change.new,
            old: Arc::new(old),
            new: Arc::new(new),
        });
        Ok(())
    }

    /// Emits unified-diff rows, merging hunks whose context would overlap.
    fn push_hunks(
        &mut self,
        file: u32,
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
            self.rows.push(Row {
                kind: RowKind::Hunk,
                file,
                old_line: old_start + 1,
                new_line: new_start + 1,
            });
            let (mut old_pos, mut new_pos) = (old_start, new_start);
            for (before, after) in &hunks[i..=j] {
                while old_pos < before.start {
                    self.push_context(file, &mut old_pos, &mut new_pos);
                }
                for line in before.clone() {
                    self.rows.push(Row {
                        kind: RowKind::Removed,
                        file,
                        old_line: line + 1,
                        new_line: 0,
                    });
                }
                for line in after.clone() {
                    self.rows.push(Row {
                        kind: RowKind::Added,
                        file,
                        old_line: 0,
                        new_line: line + 1,
                    });
                }
                (old_pos, new_pos) = (before.end, after.end);
            }
            let old_end = (hunks[j].0.end + CONTEXT).min(old_len);
            while old_pos < old_end && new_pos < new_len {
                self.push_context(file, &mut old_pos, &mut new_pos);
            }
            i = j + 1;
        }
    }

    fn push_context(&mut self, file: u32, old_pos: &mut u32, new_pos: &mut u32) {
        self.rows.push(Row {
            kind: RowKind::Context,
            file,
            old_line: *old_pos + 1,
            new_line: *new_pos + 1,
        });
        *old_pos += 1;
        *new_pos += 1;
    }

    /// The blob and line a code row shows: the old side for removed lines,
    /// the new side otherwise.
    pub fn row_source(&self, row: &Row) -> Option<RowSource<'_>> {
        let file = &self.files[row.file as usize];
        let (blob, text, line) = match row.kind {
            RowKind::Removed => (file.old_blob?, &file.old, row.old_line),
            RowKind::Added | RowKind::Context => (file.new_blob?, &file.new, row.new_line),
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

    fn kinds(review: &Review) -> Vec<RowKind> {
        review.rows.iter().map(|r| r.kind).collect()
    }

    #[test]
    fn merges_nearby_hunks_and_trims_context() {
        let mut review = Review {
            files: Vec::new(),
            rows: Vec::new(),
        };
        // Changes at lines 2 and 5 of 20: one merged hunk, context capped at 3.
        review.push_hunks(0, &[(1..2, 1..2), (4..5, 4..5)], 20, 20);
        use RowKind::*;
        assert_eq!(
            kinds(&review),
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
