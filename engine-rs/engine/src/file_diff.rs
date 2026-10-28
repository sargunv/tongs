//! Line diff of two blobs.

use imara_diff::{Algorithm, Diff, InternedInput};

pub struct FileDiff {
    pub binary: bool,
    pub additions: u32,
    pub deletions: u32,
    pub hunks: u32,
}

pub fn diff(old: &[u8], new: &[u8]) -> FileDiff {
    if is_binary(old) || is_binary(new) {
        return FileDiff {
            binary: true,
            additions: 0,
            deletions: 0,
            hunks: 0,
        };
    }
    let input = InternedInput::new(old, new);
    let mut diff = Diff::compute(Algorithm::Histogram, &input);
    diff.postprocess_lines(&input);
    FileDiff {
        binary: false,
        additions: diff.count_additions(),
        deletions: diff.count_removals(),
        hunks: diff.hunks().count() as u32,
    }
}

/// Git's heuristic: a NUL byte in the first 8000 bytes.
fn is_binary(data: &[u8]) -> bool {
    data[..data.len().min(8000)].contains(&0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_line_changes() {
        let d = diff(b"a\nb\nc\n", b"a\nB\nc\nd\n");
        assert_eq!((d.additions, d.deletions, d.hunks), (2, 1, 2));
    }

    #[test]
    fn detects_binary() {
        assert!(diff(b"\0", b"x").binary);
    }
}
