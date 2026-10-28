//! Sorts a review's files into what a reader wants to see first and what they
//! usually skip: generated code, lockfiles, vendored code, and files that only
//! moved. The file tree groups and collapses the skippable ones.
//!
//! Classification comes from `.gitattributes` (`linguist-generated`,
//! `linguist-vendored`, `-diff`) at any depth, then from path heuristics in the
//! spirit of GitHub's linguist, then from markers in the file's first lines.
//! An explicit attribute always wins over a heuristic.

use std::cmp::Ordering;

use bstr::BStr;
use gix_glob::Pattern;
use gix_glob::wildmatch::Mode;

/// Why a file is grouped away from the rest, if it is.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum FileClass {
    #[default]
    Normal = 0,
    Generated = 1,
    Lockfile = 2,
    Vendored = 3,
    /// Renamed with identical content.
    Renamed = 4,
}

/// What `.gitattributes` says about one path. `None` means unspecified.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PathAttributes {
    pub generated: Option<bool>,
    pub vendored: Option<bool>,
    /// `-diff`, or the `binary` macro: the diff isn't meant to be read.
    pub diff: Option<bool>,
}

struct Rule {
    /// The directory holding the `.gitattributes` file, with a trailing `/`,
    /// or empty for the root.
    dir: String,
    pattern: Pattern,
    set: PathAttributes,
}

/// Every `.gitattributes` file a review has seen, applied git's way: deeper
/// files override shallower ones, and later lines override earlier ones.
#[derive(Default)]
pub struct Attributes {
    /// Sorted by depth, then by file and line order.
    rules: Vec<(usize, Rule)>,
}

impl Attributes {
    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// Adds the `.gitattributes` file in `dir` (empty for the root).
    pub fn add(&mut self, dir: &str, text: &[u8]) {
        let depth = if dir.is_empty() {
            0
        } else {
            dir.matches('/').count() + 1
        };
        let prefix = if dir.is_empty() {
            String::new()
        } else {
            format!("{dir}/")
        };
        for line in text.split(|&b| b == b'\n') {
            if let Some((pattern, set)) = parse_line(line) {
                self.rules.push((
                    depth,
                    Rule {
                        dir: prefix.clone(),
                        pattern,
                        set,
                    },
                ));
            }
        }
        // Stable, so line order holds within a depth.
        self.rules.sort_by_key(|(depth, _)| *depth);
    }

    pub fn lookup(&self, path: &str) -> PathAttributes {
        let mut out = PathAttributes::default();
        for (_, rule) in &self.rules {
            let Some(relative) = path.strip_prefix(rule.dir.as_str()) else {
                continue;
            };
            let basename = relative.rfind('/').map(|i| i + 1);
            let matched = rule.pattern.matches_repo_relative_path(
                BStr::new(relative),
                basename,
                Some(false),
                gix_glob::pattern::Case::Sensitive,
                Mode::NO_MATCH_SLASH_LITERAL,
            );
            if matched {
                let set = rule.set;
                out.generated = set.generated.or(out.generated);
                out.vendored = set.vendored.or(out.vendored);
                out.diff = set.diff.or(out.diff);
            }
        }
        out
    }
}

/// One line: a pattern, then attributes. `attr` and `attr=true` set,
/// `-attr` and `attr=false` unset, `!attr` returns it to unspecified, which
/// we treat like never mentioned.
fn parse_line(line: &[u8]) -> Option<(Pattern, PathAttributes)> {
    let line = std::str::from_utf8(line).ok()?.trim();
    if line.is_empty() || line.starts_with('#') {
        return None;
    }
    let mut parts = line.split_ascii_whitespace();
    let pattern = parts.next()?;
    // Quoted patterns are rare; skip them rather than misparse.
    if pattern.starts_with('"') {
        return None;
    }
    let mut set = PathAttributes::default();
    let mut any = false;
    for attr in parts {
        let (name, value) = if let Some(name) = attr.strip_prefix('-') {
            (name, Some(false))
        } else if let Some(name) = attr.strip_prefix('!') {
            (name, None)
        } else if let Some((name, value)) = attr.split_once('=') {
            (name, Some(!matches!(value, "false" | "0")))
        } else {
            (attr, Some(true))
        };
        let slot = match name {
            "linguist-generated" => &mut set.generated,
            "linguist-vendored" => &mut set.vendored,
            "diff" => &mut set.diff,
            // The `binary` macro is `-diff -merge -text`.
            "binary" if value == Some(true) => {
                set.diff = Some(false);
                any = true;
                continue;
            }
            _ => continue,
        };
        *slot = value;
        any = true;
    }
    if !any {
        return None;
    }
    // Negated patterns are forbidden in `.gitattributes`.
    Some((
        Pattern::from_bytes_without_negation(pattern.as_bytes())?,
        set,
    ))
}

/// Files whose whole purpose is to pin dependency versions.
const LOCKFILES: &[&str] = &[
    "Cargo.lock",
    "package-lock.json",
    "npm-shrinkwrap.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "bun.lock",
    "bun.lockb",
    "deno.lock",
    "Gemfile.lock",
    "composer.lock",
    "poetry.lock",
    "Pipfile.lock",
    "pdm.lock",
    "uv.lock",
    "go.sum",
    "go.work.sum",
    "Podfile.lock",
    "Package.resolved",
    "Cartfile.resolved",
    "flake.lock",
    "pubspec.lock",
    "mix.lock",
    "gradle.lockfile",
    "packages.lock.json",
    "paket.lock",
    "mise.lock",
    "MODULE.bazel.lock",
    "conan.lock",
    "renv.lock",
    "shard.lock",
    "stack.yaml.lock",
    "Manifest.toml",
];

/// Directories that hold other people's code.
const VENDORED_DIRS: &[&str] = &[
    "vendor",
    "vendors",
    "third_party",
    "third-party",
    "thirdparty",
    "3rdparty",
    "3rd_party",
    "node_modules",
    "bower_components",
    "Pods",
    ".yarn",
];

/// File name endings of common code generators.
const GENERATED_SUFFIXES: &[&str] = &[
    ".min.js",
    ".min.css",
    ".min.mjs",
    ".js.map",
    ".css.map",
    ".pb.go",
    ".pb.cc",
    ".pb.h",
    "_pb2.py",
    "_pb2.pyi",
    "_pb2_grpc.py",
    ".pb.swift",
    ".g.dart",
    ".freezed.dart",
    ".mocks.dart",
    ".designer.cs",
    ".g.cs",
    "_generated.go",
    ".generated.ts",
    ".generated.js",
];

fn path_is_lockfile(name: &str) -> bool {
    LOCKFILES.contains(&name) || name.ends_with(".lockfile")
}

fn path_is_vendored(path: &str) -> bool {
    let mut dirs = path.split('/');
    dirs.next_back();
    dirs.any(|dir| VENDORED_DIRS.contains(&dir))
}

fn path_is_generated(name: &str) -> bool {
    GENERATED_SUFFIXES.iter().any(|s| name.ends_with(s))
}

/// Whether a file's own text says it's generated: an `@generated` or
/// `DO NOT EDIT` marker near the top, or minified content.
pub fn content_is_generated(text: &[u8]) -> bool {
    let head = &text[..text.len().min(1024)];
    let head_lines: Vec<&[u8]> = head.split(|&b| b == b'\n').take(6).collect();
    let marker = head_lines.iter().any(|line| {
        contains(line, b"@generated")
            || contains(line, b"DO NOT EDIT")
            || contains(line, b"Do not edit")
            || contains(line, b"do not edit")
            || contains(line, b"auto-generated")
            || contains(line, b"autogenerated")
            || contains(line, b"Autogenerated")
            || contains(line, b"Auto-generated")
            || contains(line, b"automatically generated")
    });
    if marker {
        return true;
    }
    // Minified: few, very long lines.
    if text.len() >= 4096 {
        let lines = text.iter().filter(|&&b| b == b'\n').count() + 1;
        if text.len() / lines > 1000 {
            return true;
        }
    }
    false
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

/// Classifies a file from its path, its attributes, and whether its content
/// looks generated. `renamed` is an exact rename.
pub fn classify(
    path: &str,
    attrs: PathAttributes,
    content_generated: bool,
    renamed: bool,
) -> FileClass {
    if renamed {
        return FileClass::Renamed;
    }
    let name = path.rsplit('/').next().unwrap_or(path);
    match attrs.generated {
        Some(true) => return FileClass::Generated,
        Some(false) => {}
        None => {
            if path_is_lockfile(name) {
                return FileClass::Lockfile;
            }
            if path_is_generated(name) || content_generated || attrs.diff == Some(false) {
                return FileClass::Generated;
            }
        }
    }
    match attrs.vendored {
        Some(true) => FileClass::Vendored,
        Some(false) => FileClass::Normal,
        None if path_is_vendored(path) => FileClass::Vendored,
        None => FileClass::Normal,
    }
}

/// The order a file tree shows paths in: component by component, with a
/// directory's contents before the files beside it, so walking the tree
/// depth-first visits files in this order.
pub fn tree_order(a: &str, b: &str) -> Ordering {
    let mut a_parts = a.split('/').peekable();
    let mut b_parts = b.split('/').peekable();
    loop {
        match (a_parts.next(), b_parts.next()) {
            (Some(x), Some(y)) => {
                let x_dir = a_parts.peek().is_some();
                let y_dir = b_parts.peek().is_some();
                if x != y || x_dir != y_dir {
                    return match (x_dir, y_dir) {
                        (true, false) => Ordering::Less,
                        (false, true) => Ordering::Greater,
                        _ => x.cmp(y),
                    };
                }
            }
            (None, None) => return Ordering::Equal,
            (None, Some(_)) => return Ordering::Less,
            (Some(_), None) => return Ordering::Greater,
        }
    }
}

/// Display order: ordinary files first, then each group, each in tree order.
pub fn display_order(a: (FileClass, &str), b: (FileClass, &str)) -> Ordering {
    a.0.cmp(&b.0).then_with(|| tree_order(a.1, b.1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_attributes_override_and_anchor() {
        let mut attrs = Attributes::default();
        attrs.add("", b"*.jar binary\n*.gen.rs linguist-generated\n");
        attrs.add(
            "bindings",
            b"# Generated by tools/bindgen. Do not edit.\n\
              /dart/lib/x.g.dart linguist-generated=true\n\
              /go/api.go linguist-generated=true\n\
              keep.gen.rs -linguist-generated\n",
        );
        assert_eq!(
            attrs.lookup("bindings/go/api.go").generated,
            Some(true),
            "anchored to its directory"
        );
        assert_eq!(attrs.lookup("go/api.go").generated, None);
        assert_eq!(attrs.lookup("bindings/x/go/api.go").generated, None);
        assert_eq!(attrs.lookup("src/a.gen.rs").generated, Some(true));
        assert_eq!(
            attrs.lookup("bindings/deep/keep.gen.rs").generated,
            Some(false),
            "a deeper file overrides"
        );
        assert_eq!(attrs.lookup("lib/x.jar").diff, Some(false));
    }

    #[test]
    fn classifies_by_attribute_path_and_content() {
        let none = PathAttributes::default();
        let generated = PathAttributes {
            generated: Some(true),
            ..none
        };
        let not_generated = PathAttributes {
            generated: Some(false),
            ..none
        };
        assert_eq!(
            classify("src/main.rs", none, false, false),
            FileClass::Normal
        );
        assert_eq!(
            classify("src/main.rs", generated, false, false),
            FileClass::Generated
        );
        assert_eq!(
            classify("Cargo.lock", none, false, false),
            FileClass::Lockfile
        );
        assert_eq!(
            classify("Cargo.lock", not_generated, false, false),
            FileClass::Normal
        );
        assert_eq!(
            classify("web/vendor/lib.js", none, false, false),
            FileClass::Vendored
        );
        assert_eq!(classify("vendor", none, false, false), FileClass::Normal);
        assert_eq!(
            classify("api/v1/x.pb.go", none, false, false),
            FileClass::Generated
        );
        assert_eq!(classify("a.rs", none, true, false), FileClass::Generated);
        assert_eq!(classify("a.rs", none, false, true), FileClass::Renamed);
        assert!(content_is_generated(
            b"// Code generated by protoc. DO NOT EDIT.\n"
        ));
        assert!(content_is_generated(&[b'x'; 5000]));
        assert!(!content_is_generated(b"fn main() {}\n"));
    }

    #[test]
    fn tree_order_puts_directories_first() {
        let mut paths = vec![
            "b.txt",
            "a/z.txt",
            "a.txt",
            "a/b/c.txt",
            "README",
            "a/a.txt",
        ];
        paths.sort_by(|a, b| tree_order(a, b));
        assert_eq!(
            paths,
            [
                "a/b/c.txt",
                "a/a.txt",
                "a/z.txt",
                "README",
                "a.txt",
                "b.txt"
            ]
        );
    }
}
