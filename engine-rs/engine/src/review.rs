//! The review row model: every changed file in a diff, flattened into one
//! sequence of fixed-height rows that a renderer can index directly.
//!
//! A [`Review`] is an immutable snapshot. Each file's rows and text live in an
//! `Arc<FileEntry>`, so while a fetch is still running, each new snapshot
//! shares every file that hasn't changed and only rebuilds the per-file prefix
//! sums. A file whose blobs haven't arrived yet shows its header and a
//! placeholder row.
//!
//! A snapshot can also be laid out for reading with [`Review::with_layout`]:
//! collapsed files show only their header, and inserted blocks reserve rows
//! below a line for UI the renderer overlays there, like comment threads or a
//! composer. Rows stay fixed-height either way, so the renderer still finds
//! any row by division.

use std::ops::Range;
use std::sync::Arc;

use anyhow::Result;
use gix_hash::ObjectId;
use imara_diff::{Algorithm, Diff, InternedInput};

use crate::classify::{self, Attributes, FileClass, PathAttributes};
use crate::store::Store;
use crate::tree_walk::{Change, ChangeKind, TreeWalk, pair_renames};

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
    /// The body of a file that moved without changes.
    Renamed = 7,
    /// A row reserved by an inserted block; `old_line` is the block's id and
    /// `new_line` the row's index within it.
    Annotation = 8,
}

#[derive(Clone, Copy, Debug)]
pub struct Row {
    pub kind: RowKind,
    pub file: u32,
    /// 1-based; 0 when the row has no line on that side.
    pub old_line: u32,
    pub new_line: u32,
    /// A header whose file is collapsed by the layout.
    pub collapsed: bool,
}

/// A side of the diff.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Old,
    New,
}

/// Rows to reserve below a line, for the renderer to overlay.
#[derive(Clone, Copy, Debug)]
pub struct Insert {
    pub file: u32,
    pub side: Side,
    /// 1-based; 0 puts the block right under the file's header.
    pub line: u32,
    pub rows: u32,
    pub id: u32,
}

/// How a snapshot is laid out for reading; see [`Review::with_layout`].
#[derive(Clone, Debug, Default)]
pub struct Layout {
    /// Per file; missing entries aren't collapsed.
    pub collapsed: Vec<bool>,
    pub inserts: Vec<Insert>,
}

#[derive(Clone, Default)]
struct FileLayout {
    collapsed: bool,
    /// `(after base row, rows, id)`, sorted by base row.
    inserts: Vec<(u32, u32, u32)>,
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
    pub kind: ChangeKind,
    /// Where a renamed file came from.
    pub old_path: Option<String>,
    /// Whether the content says it's generated; see
    /// [`classify::content_is_generated`]. False until loaded.
    pub content_generated: bool,
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
        if change.kind == ChangeKind::Renamed {
            return Self::renamed(change);
        }
        Self {
            path: change.path.clone(),
            kind: change.kind,
            old_path: change.old_path.clone(),
            content_generated: false,
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

    /// A pure rename: nothing to fetch or diff.
    fn renamed(change: &Change) -> Self {
        Self {
            path: change.path.clone(),
            kind: change.kind,
            old_path: change.old_path.clone(),
            content_generated: false,
            old_id: change.old,
            new_id: change.new,
            additions: 0,
            deletions: 0,
            loaded: true,
            rows: vec![
                FileRow::new(RowKind::FileHeader, 0, 0),
                FileRow::new(RowKind::Renamed, 0, 0),
            ],
            old: Arc::new(Text::new(Vec::new())),
            new: Arc::new(Text::new(Vec::new())),
        }
    }

    /// Whether the diff is hidden because either side is binary.
    pub fn is_binary(&self) -> bool {
        self.rows.get(1).is_some_and(|r| r.kind == RowKind::Binary)
    }

    /// Whether diffing this file needs its blobs.
    pub fn needs_blobs(change: &Change) -> bool {
        change.kind != ChangeKind::Renamed
    }

    /// Diffs a file whose blobs are in `store`.
    pub fn load(store: &Store, change: &Change) -> Result<Self> {
        if change.kind == ChangeKind::Renamed {
            return Ok(Self::renamed(change));
        }
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
        let content_generated = if new.binary || old.binary {
            false
        } else if change.new.is_some() {
            classify::content_is_generated(new.bytes())
        } else {
            classify::content_is_generated(old.bytes())
        };
        Ok(Self {
            path: change.path.clone(),
            kind: change.kind,
            old_path: change.old_path.clone(),
            content_generated,
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
    /// In display order: see [`classify::display_order`].
    pub files: Vec<Arc<FileEntry>>,
    pub classes: Vec<FileClass>,
    /// File indices sorted by path, for lookups.
    by_path: Vec<u32>,
    /// Empty, or one entry per file.
    layout: Vec<FileLayout>,
    /// Each file's first row, then the total row count.
    starts: Vec<u32>,
    /// Whether every changed file is known, though some may still be loading.
    pub complete: bool,
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
        let mut attributes = Attributes::default();
        for (dir, id) in &walk.attributes {
            // Unchanged attributes files may not be in a store filled for
            // one review.
            if store.contains(id) {
                attributes.add(dir, &store.blob(id)?);
            }
        }
        let mut changes = walk.changes;
        pair_renames(&mut changes);
        let files = changes
            .iter()
            .map(|change| {
                let entry = FileEntry::load(store, change)?;
                let attrs = attributes.lookup(&change.path);
                Ok((Arc::new(entry), attrs))
            })
            .collect::<Result<_>>()?;
        Ok(Self::assemble(files, true))
    }

    /// Assembles a snapshot, classifying each file with its attributes and
    /// putting them in display order.
    pub fn assemble(files: Vec<(Arc<FileEntry>, PathAttributes)>, complete: bool) -> Self {
        let mut files: Vec<_> = files
            .into_iter()
            .map(|(file, attrs)| {
                let class = classify::classify(
                    &file.path,
                    attrs,
                    file.content_generated,
                    file.kind == ChangeKind::Renamed,
                );
                (class, file)
            })
            .collect();
        files.sort_by(|a, b| classify::display_order((a.0, &a.1.path), (b.0, &b.1.path)));
        let (classes, files) = files.into_iter().unzip();
        Self::new(files, classes, Vec::new(), complete)
    }

    /// Assembles a snapshot of files already in display order, all
    /// unclassified.
    pub fn from_files(files: Vec<Arc<FileEntry>>) -> Self {
        let classes = vec![FileClass::Normal; files.len()];
        Self::new(files, classes, Vec::new(), true)
    }

    fn new(
        files: Vec<Arc<FileEntry>>,
        classes: Vec<FileClass>,
        layout: Vec<FileLayout>,
        complete: bool,
    ) -> Self {
        let mut starts = Vec::with_capacity(files.len() + 1);
        let mut total = 0u32;
        for (i, file) in files.iter().enumerate() {
            starts.push(total);
            total += match layout.get(i) {
                Some(l) if l.collapsed => 1,
                Some(l) => file.rows.len() as u32 + l.inserts.iter().map(|i| i.1).sum::<u32>(),
                None => file.rows.len() as u32,
            };
        }
        starts.push(total);
        let mut by_path: Vec<u32> = (0..files.len() as u32).collect();
        by_path.sort_by(|&a, &b| files[a as usize].path.cmp(&files[b as usize].path));
        Self {
            files,
            classes,
            by_path,
            layout,
            starts,
            complete,
        }
    }

    /// The same files laid out for reading: `layout.collapsed` files show only
    /// their header, and each insert reserves rows under its line. Inserts on
    /// files still loading are left out, and so are those whose line the diff
    /// doesn't show, which go under the header instead.
    pub fn with_layout(&self, layout: &Layout) -> Self {
        let mut files_layout = vec![FileLayout::default(); self.files.len()];
        for (i, collapsed) in layout.collapsed.iter().enumerate() {
            if let Some(slot) = files_layout.get_mut(i) {
                slot.collapsed = *collapsed;
            }
        }
        for insert in &layout.inserts {
            let Some(file) = self.files.get(insert.file as usize) else {
                continue;
            };
            if !file.loaded || insert.rows == 0 {
                continue;
            }
            let after = file
                .base_row_for_line(insert.side, insert.line)
                .unwrap_or(0);
            files_layout[insert.file as usize]
                .inserts
                .push((after, insert.rows, insert.id));
        }
        for slot in &mut files_layout {
            slot.inserts.sort_by_key(|i| i.0);
        }
        Self::new(
            self.files.clone(),
            self.classes.clone(),
            files_layout,
            self.complete,
        )
    }

    pub fn row_count(&self) -> usize {
        *self.starts.last().unwrap() as usize
    }

    pub fn file_first_row(&self, file: usize) -> u32 {
        self.starts[file]
    }

    pub fn file_index(&self, path: &str) -> Option<usize> {
        self.by_path
            .binary_search_by(|&i| self.files[i as usize].path.as_str().cmp(path))
            .ok()
            .map(|i| self.by_path[i] as usize)
    }

    pub fn is_collapsed(&self, file: usize) -> bool {
        self.layout.get(file).is_some_and(|l| l.collapsed)
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
            self.view_row(file, i as u32 - self.starts[file])
        })
    }

    /// Row `local` of `file`, counting the layout's collapsing and inserts.
    fn view_row(&self, file: usize, local: u32) -> Row {
        let entry = &self.files[file];
        let row = |r: FileRow, collapsed: bool| Row {
            kind: r.kind,
            file: file as u32,
            old_line: r.old_line,
            new_line: r.new_line,
            collapsed,
        };
        let Some(layout) = self.layout.get(file) else {
            return row(entry.rows[local as usize], false);
        };
        if layout.collapsed {
            return row(entry.rows[0], true);
        }
        let mut skipped = 0;
        for &(after, rows, id) in &layout.inserts {
            let start = after + 1 + skipped;
            if local < start {
                break;
            }
            if local < start + rows {
                return row(FileRow::new(RowKind::Annotation, id, local - start), false);
            }
            skipped += rows;
        }
        row(entry.rows[(local - skipped) as usize], false)
    }

    /// Where a row with this content sits in this snapshot, for keeping the
    /// reader's place when the layout changes. Matches code rows by kind and
    /// line numbers and annotation rows by block and index.
    pub fn find_row(
        &self,
        file: usize,
        kind: RowKind,
        old_line: u32,
        new_line: u32,
    ) -> Option<u32> {
        let entry = self.files.get(file)?;
        let start = self.starts[file];
        if kind == RowKind::FileHeader {
            return Some(start);
        }
        let layout = self.layout.get(file);
        if layout.is_some_and(|l| l.collapsed) {
            return None;
        }
        let inserts = layout.map_or(&[][..], |l| &l.inserts[..]);
        if kind == RowKind::Annotation {
            let mut skipped = 0;
            for &(after, rows, id) in inserts {
                if id == old_line {
                    return (new_line < rows).then_some(start + after + 1 + skipped + new_line);
                }
                skipped += rows;
            }
            return None;
        }
        let base = entry
            .rows
            .iter()
            .position(|r| r.kind == kind && r.old_line == old_line && r.new_line == new_line)?
            as u32;
        let before: u32 = inserts.iter().filter(|i| i.0 < base).map(|i| i.1).sum();
        Some(start + base + before)
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

    /// A line of a file as it is in the blob, without its terminator: for
    /// quoting it, as a suggested change does. Empty if there's no such line.
    pub fn line_text(&self, file: usize, side: Side, line: u32) -> &[u8] {
        let Some(file) = self.files.get(file) else {
            return &[];
        };
        let text = match side {
            Side::Old => &file.old,
            Side::New => &file.new,
        };
        if line == 0 || line > text.line_count() {
            return &[];
        }
        text.line(line - 1)
    }

    /// Display text for a row: tabs expanded, line ending stripped, capped.
    pub fn row_text(&self, row: &Row, out: &mut Vec<u8>) {
        let file = &self.files[row.file as usize];
        let line = match row.kind {
            RowKind::FileHeader => {
                if let Some(old_path) = &file.old_path {
                    out.extend_from_slice(old_path.as_bytes());
                    out.extend_from_slice(" \u{2192} ".as_bytes());
                }
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
            RowKind::Renamed => {
                out.extend_from_slice(b"Renamed without changes");
                return;
            }
            RowKind::Annotation => return,
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

impl FileEntry {
    /// The row showing `line` on `side`; line 0 is the header. On the old
    /// side, a removed line wins over context showing the same line.
    fn base_row_for_line(&self, side: Side, line: u32) -> Option<u32> {
        if line == 0 {
            return Some(0);
        }
        let find = |pred: &dyn Fn(&FileRow) -> bool| self.rows.iter().position(pred);
        let found = match side {
            Side::New => {
                find(&|r| matches!(r.kind, RowKind::Added | RowKind::Context) && r.new_line == line)
            }
            Side::Old => find(&|r| r.kind == RowKind::Removed && r.old_line == line)
                .or_else(|| find(&|r| r.kind == RowKind::Context && r.old_line == line)),
        };
        found.map(|i| i as u32)
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

    /// One loaded file: a header, a hunk, then `old` and `new` lines as
    /// removed, added, and context rows.
    fn file(path: &str, rows: &[(RowKind, u32, u32)]) -> Arc<FileEntry> {
        let mut entry = FileEntry::pending(&Change {
            path: path.into(),
            kind: ChangeKind::Modified,
            old_path: None,
            old: None,
            new: None,
        });
        entry.loaded = true;
        entry.rows = std::iter::once(FileRow::new(RowKind::FileHeader, 0, 0))
            .chain(rows.iter().map(|&(k, o, n)| FileRow::new(k, o, n)))
            .collect();
        Arc::new(entry)
    }

    fn kinds(review: &Review) -> Vec<(RowKind, u32, u32, bool)> {
        review
            .rows(0..usize::MAX)
            .map(|r| (r.kind, r.old_line, r.new_line, r.collapsed))
            .collect()
    }

    #[test]
    fn layout_collapses_files_and_reserves_rows_under_lines() {
        use RowKind::*;
        let review = Review::from_files(vec![
            file(
                "a",
                &[
                    (Hunk, 1, 1),
                    (Context, 1, 1),
                    (Removed, 2, 0),
                    (Added, 0, 2),
                ],
            ),
            file("b", &[(Hunk, 1, 1), (Added, 0, 1)]),
        ]);
        let laid_out = review.with_layout(&Layout {
            collapsed: vec![false, true],
            inserts: vec![
                Insert {
                    file: 0,
                    side: Side::New,
                    line: 2,
                    rows: 2,
                    id: 7,
                },
                Insert {
                    file: 0,
                    side: Side::Old,
                    line: 2,
                    rows: 1,
                    id: 8,
                },
                // Not in the diff: goes under the header.
                Insert {
                    file: 0,
                    side: Side::New,
                    line: 99,
                    rows: 1,
                    id: 9,
                },
            ],
        });
        assert_eq!(
            kinds(&laid_out),
            [
                (FileHeader, 0, 0, false),
                (Annotation, 9, 0, false),
                (Hunk, 1, 1, false),
                (Context, 1, 1, false),
                (Removed, 2, 0, false),
                (Annotation, 8, 0, false),
                (Added, 0, 2, false),
                (Annotation, 7, 0, false),
                (Annotation, 7, 1, false),
                (FileHeader, 0, 0, true),
            ]
        );
        assert_eq!(laid_out.file_first_row(1), 9);
        assert_eq!(laid_out.find_row(0, Added, 0, 2), Some(6));
        assert_eq!(laid_out.find_row(0, Annotation, 7, 1), Some(8));
        assert_eq!(laid_out.find_row(1, FileHeader, 0, 0), Some(9));
        assert_eq!(laid_out.find_row(1, Added, 0, 1), None, "collapsed");
        assert_eq!(review.find_row(0, Added, 0, 2), Some(4));
    }

    #[test]
    fn looks_files_up_by_path_in_display_order() {
        let none = PathAttributes::default();
        let generated = PathAttributes {
            generated: Some(true),
            ..none
        };
        let review = Review::assemble(
            vec![
                (file("z.rs", &[]), none),
                (file("gen/api.rs", &[]), generated),
                (file("Cargo.lock", &[]), none),
                (file("src/lib.rs", &[]), none),
            ],
            true,
        );
        let paths: Vec<_> = review.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(paths, ["src/lib.rs", "z.rs", "gen/api.rs", "Cargo.lock"]);
        assert_eq!(
            review.classes,
            [
                FileClass::Normal,
                FileClass::Normal,
                FileClass::Generated,
                FileClass::Lockfile
            ]
        );
        assert_eq!(review.file_index("gen/api.rs"), Some(2));
        assert_eq!(review.file_index("nope"), None);
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
