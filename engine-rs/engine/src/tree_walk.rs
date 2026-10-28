//! Diffs two root trees, descending only into subtrees whose ids differ.
//!
//! Trees arrive lazily: [`TreeWalk::missing`] lists the trees the next
//! [`TreeWalk::step`] needs, so the caller can fetch a whole directory level
//! in one round trip. Cost scales with the size of the change, not the repo.

use anyhow::Result;
use gix_hash::ObjectId;
use gix_object::tree::EntryKind;

use crate::store::Store;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChangeKind {
    Added,
    Deleted,
    Modified,
    /// Moved with identical content; see [`pair_renames`].
    Renamed,
}

#[derive(Clone, Debug)]
pub struct Change {
    pub path: String,
    pub kind: ChangeKind,
    /// Where a renamed file came from.
    pub old_path: Option<String>,
    /// Blob ids; `None` on the side where the file doesn't exist.
    pub old: Option<ObjectId>,
    pub new: Option<ObjectId>,
}

/// The empty blob: git doesn't pair empty files as renames, since any two
/// would match.
const EMPTY_BLOB: &str = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";

/// Pairs each deleted file with an added file of identical content, so a pure
/// move reads as one rename instead of a delete and an add. Content-similarity
/// renames aren't detected yet.
pub fn pair_renames(changes: &mut Vec<Change>) {
    let empty: ObjectId = EMPTY_BLOB.parse().unwrap();
    let mut added: std::collections::HashMap<ObjectId, Vec<usize>> = Default::default();
    for (i, change) in changes.iter().enumerate() {
        if let (ChangeKind::Added, Some(id)) = (change.kind, change.new)
            && id != empty
        {
            added.entry(id).or_default().push(i);
        }
    }
    let mut renamed_from = vec![None; changes.len()];
    let mut drop = vec![false; changes.len()];
    for (i, change) in changes.iter().enumerate() {
        if let (ChangeKind::Deleted, Some(id)) = (change.kind, change.old)
            && let Some(targets) = added.get_mut(&id)
            && !targets.is_empty()
        {
            let target = targets.remove(0);
            renamed_from[target] = Some(change.path.clone());
            drop[i] = true;
        }
    }
    let mut i = 0;
    changes.retain_mut(|change| {
        let keep = !drop[i];
        if let Some(from) = renamed_from[i].take() {
            change.kind = ChangeKind::Renamed;
            change.old = change.new;
            change.old_path = Some(from);
        }
        i += 1;
        keep
    });
}

pub struct TreeWalk {
    /// Directory pairs still to compare. `None` means absent on that side.
    frontier: Vec<(String, Option<ObjectId>, Option<ObjectId>)>,
    /// Directory depth of the frontier; the root trees are depth 0.
    depth: u32,
    pub changes: Vec<Change>,
    /// `.gitattributes` blobs on the new side of every directory the walk
    /// expanded, as `(directory, blob)`; the root is `""`. A changed file's
    /// directories all differ, so these are every attributes file that can
    /// apply to a change.
    pub attributes: Vec<(String, ObjectId)>,
}

impl TreeWalk {
    pub fn new(old_root: ObjectId, new_root: ObjectId) -> Self {
        Self {
            frontier: vec![(String::new(), Some(old_root), Some(new_root))],
            depth: 0,
            changes: Vec::new(),
            attributes: Vec::new(),
        }
    }

    pub fn is_done(&self) -> bool {
        self.frontier.is_empty()
    }

    /// Depth of the trees [`Self::missing`] returns.
    pub fn depth(&self) -> u32 {
        self.depth
    }

    pub fn missing(&self, store: &Store) -> Vec<ObjectId> {
        let mut ids: Vec<_> = self
            .frontier
            .iter()
            .flat_map(|(_, old, new)| [old, new])
            .flatten()
            .filter(|id| !store.contains(id))
            .copied()
            .collect();
        ids.sort_unstable();
        ids.dedup();
        ids
    }

    /// Expands one directory level. Every tree in [`Self::missing`] must be in
    /// the store.
    pub fn step(&mut self, store: &Store) -> Result<()> {
        let mut next = Vec::new();
        let (mut old_buf, mut new_buf) = (Vec::new(), Vec::new());
        for (dir, old, new) in std::mem::take(&mut self.frontier) {
            let old_entries = entries(store, old, &mut old_buf)?;
            let new_entries = entries(store, new, &mut new_buf)?;
            if let Some(entry) = new_entries
                .iter()
                .find(|e| e.0 == ".gitattributes" && e.2 == Node::Blob)
            {
                self.attributes.push((dir.clone(), entry.1));
            }
            let (mut i, mut j) = (0, 0);
            while i < old_entries.len() || j < new_entries.len() {
                let ordering = match (old_entries.get(i), new_entries.get(j)) {
                    (Some(a), Some(b)) => a.0.cmp(&b.0),
                    (Some(_), None) => std::cmp::Ordering::Less,
                    _ => std::cmp::Ordering::Greater,
                };
                let (a, b) = match ordering {
                    std::cmp::Ordering::Less => (old_entries.get(i).cloned(), None),
                    std::cmp::Ordering::Greater => (None, new_entries.get(j).cloned()),
                    std::cmp::Ordering::Equal => {
                        (old_entries.get(i).cloned(), new_entries.get(j).cloned())
                    }
                };
                if a.is_some() {
                    i += 1;
                }
                if b.is_some() {
                    j += 1;
                }
                let name = a
                    .as_ref()
                    .or(b.as_ref())
                    .map(|e| e.0.clone())
                    .unwrap_or_default();
                let path = if dir.is_empty() {
                    name
                } else {
                    format!("{dir}/{name}")
                };
                self.compare(path, a, b, &mut next);
            }
        }
        self.frontier = next;
        self.depth += 1;
        Ok(())
    }

    fn compare(
        &mut self,
        path: String,
        a: Option<Entry>,
        b: Option<Entry>,
        next: &mut Vec<(String, Option<ObjectId>, Option<ObjectId>)>,
    ) {
        if let (Some(a), Some(b)) = (&a, &b)
            && a.1 == b.1
            && a.2 == b.2
        {
            return;
        }
        let tree = |e: &Option<Entry>| e.as_ref().filter(|e| e.2 == Node::Tree).map(|e| e.1);
        let file = |e: &Option<Entry>| e.as_ref().filter(|e| e.2 == Node::Blob).map(|e| e.1);
        let (old_tree, new_tree) = (tree(&a), tree(&b));
        if old_tree.is_some() || new_tree.is_some() {
            next.push((path.clone(), old_tree, new_tree));
        }
        let (old, new) = (file(&a), file(&b));
        let kind = match (old, new) {
            (None, None) => return,
            (Some(_), None) => ChangeKind::Deleted,
            (None, Some(_)) => ChangeKind::Added,
            (Some(_), Some(_)) => ChangeKind::Modified,
        };
        self.changes.push(Change {
            path,
            kind,
            old_path: None,
            old,
            new,
        });
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Node {
    Tree,
    Blob,
    /// Submodule commits; not diffed.
    Other,
}

type Entry = (String, ObjectId, Node);

/// Tree entries sorted by plain name, so old and new can be merged in order.
/// (Git's own order sorts trees as if they had a trailing `/`.)
fn entries(store: &Store, id: Option<ObjectId>, buf: &mut Vec<u8>) -> Result<Vec<Entry>> {
    let Some(id) = id else { return Ok(Vec::new()) };
    let tree = store.tree(&id, buf)?;
    let mut out: Vec<Entry> = tree
        .entries
        .iter()
        .map(|e| {
            let node = match e.mode.kind() {
                EntryKind::Tree => Node::Tree,
                EntryKind::Blob | EntryKind::BlobExecutable | EntryKind::Link => Node::Blob,
                EntryKind::Commit => Node::Other,
            };
            (e.filename.to_string(), e.oid.to_owned(), node)
        })
        .collect();
    out.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn change(path: &str, kind: ChangeKind, old: Option<&str>, new: Option<&str>) -> Change {
        Change {
            path: path.into(),
            kind,
            old_path: None,
            old: old.map(|s| s.repeat(40).parse().unwrap()),
            new: new.map(|s| s.repeat(40).parse().unwrap()),
        }
    }

    #[test]
    fn pairs_identical_deletes_and_adds() {
        let mut changes = vec![
            change("a/moved.txt", ChangeKind::Deleted, Some("1"), None),
            change("b/edited.txt", ChangeKind::Modified, Some("2"), Some("3")),
            change("c/moved.txt", ChangeKind::Added, None, Some("1")),
            change("d/new.txt", ChangeKind::Added, None, Some("4")),
        ];
        pair_renames(&mut changes);
        let summary: Vec<_> = changes
            .iter()
            .map(|c| (c.path.as_str(), c.kind, c.old_path.as_deref()))
            .collect();
        assert_eq!(
            summary,
            [
                ("b/edited.txt", ChangeKind::Modified, None),
                ("c/moved.txt", ChangeKind::Renamed, Some("a/moved.txt")),
                ("d/new.txt", ChangeKind::Added, None),
            ]
        );
        assert_eq!(changes[1].old, changes[1].new);
    }
}
