//! Whole-file highlighting, stored per line so rows can look up their spans
//! directly.

use std::time::Duration;

use ropey::Rope;
use tree_house::Syntax;
use tree_house::highlighter::{HighlightEvent, Highlighter};

use crate::languages::{LOADER, Lang};
use crate::style::Style;

/// Larger files stay plain. Parsing and querying run at ~3.5 MB/s per
/// desktop core, so this bounds one file to ~300 ms there and roughly a
/// second on a phone. Lockfiles and generated bundles this big aren't worth
/// coloring.
pub const MAX_FILE_BYTES: usize = 1 << 20;
/// Files whose lines average more than this look minified and stay plain.
const MAX_AVERAGE_LINE_BYTES: usize = 300;
/// Parsing gives up after this long and the file stays plain. Generous,
/// since a capped file can take most of a second on a slow phone core.
const PARSE_TIMEOUT: Duration = Duration::from_secs(2);

/// A style that starts at a byte offset within a line and runs until the next
/// span or the end of the line. Lines start out [`Style::Plain`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Span {
    pub start: u16,
    pub style: Style,
}

/// One side of a file's highlights, by line. Offsets past `u16::MAX` within a
/// line are dropped; the row model truncates long lines well before that.
#[derive(Debug, Default)]
pub struct FileHighlights {
    /// Index into `spans` where each line's spans begin, plus a final entry.
    /// Empty when the file wasn't highlighted.
    line_spans: Vec<u32>,
    spans: Vec<Span>,
}

impl FileHighlights {
    /// Highlights a whole file, or returns `None` if it's too big, looks
    /// minified, isn't UTF-8, or fails to parse in time.
    pub fn new(lang: Lang, text: &[u8]) -> Option<FileHighlights> {
        if text.len() > MAX_FILE_BYTES {
            return None;
        }
        let line_starts = line_starts(text);
        let line_count = line_starts.len() - 1;
        if line_count > 0 && text.len() / line_count > MAX_AVERAGE_LINE_BYTES {
            return None;
        }
        let rope = Rope::from_str(std::str::from_utf8(text).ok()?);
        let syntax = Syntax::new(rope.slice(..), lang.language(), PARSE_TIMEOUT, &*LOADER).ok()?;
        let mut highlighter = Highlighter::new(&syntax, rope.slice(..), &*LOADER, ..);

        let mut builder = Builder {
            line_starts: &line_starts,
            line: 0,
            style: Style::Plain,
            out: FileHighlights {
                line_spans: Vec::with_capacity(line_starts.len()),
                spans: Vec::new(),
            },
        };
        builder.out.line_spans.push(0);
        let mut stack_top = Style::Plain;
        loop {
            let offset = highlighter.next_event_offset();
            if offset == u32::MAX {
                break;
            }
            let (event, mut highlights) = highlighter.advance();
            let top = highlights.next_back().map(|h| style_from(h.get()));
            stack_top = match (event, top) {
                (HighlightEvent::Refresh, top) => top.unwrap_or(Style::Plain),
                (HighlightEvent::Push, Some(top)) => top,
                (HighlightEvent::Push, None) => stack_top,
            };
            builder.set_style(offset, stack_top);
        }
        builder.finish_lines(line_count);
        let mut out = builder.out;
        out.line_spans.shrink_to_fit();
        out.spans.shrink_to_fit();
        Some(out)
    }

    /// Spans for a 0-based line; empty when the file wasn't highlighted.
    pub fn line(&self, line: u32) -> &[Span] {
        let line = line as usize;
        match (self.line_spans.get(line), self.line_spans.get(line + 1)) {
            (Some(&start), Some(&end)) => &self.spans[start as usize..end as usize],
            _ => &[],
        }
    }

    /// Heap bytes held, for cache accounting.
    pub fn size_bytes(&self) -> usize {
        self.line_spans.capacity() * size_of::<u32>()
            + self.spans.capacity() * size_of::<Span>()
            + size_of::<Self>()
    }

    pub fn span_count(&self) -> usize {
        self.spans.len()
    }
}

fn style_from(id: u32) -> Style {
    // Safety: the loader only configures highlights from `Style` discriminants.
    unsafe { std::mem::transmute::<u8, Style>(id as u8) }
}

/// Byte offset of each line start, plus a final entry at `text.len()`. Lines
/// match the review row model's.
fn line_starts(text: &[u8]) -> Vec<u32> {
    let mut starts = vec![0];
    starts.extend(
        text.iter()
            .enumerate()
            .filter(|(_, b)| **b == b'\n')
            .map(|(i, _)| i as u32 + 1),
    );
    if starts.last() != Some(&(text.len() as u32)) {
        starts.push(text.len() as u32);
    }
    starts
}

/// Turns a stream of style changes at file offsets into per-line spans.
struct Builder<'a> {
    line_starts: &'a [u32],
    line: usize,
    style: Style,
    out: FileHighlights,
}

impl Builder<'_> {
    fn set_style(&mut self, offset: u32, style: Style) {
        while self.line + 1 < self.line_starts.len() && offset >= self.line_starts[self.line + 1] {
            self.finish_line();
        }
        if style == self.style {
            return;
        }
        self.style = style;
        if self.line + 1 >= self.line_starts.len() {
            return;
        }
        let line_start = self.line_starts[self.line];
        let Ok(start) = u16::try_from(offset - line_start) else {
            return;
        };
        let line_first = *self.out.line_spans.last().unwrap() as usize;
        match self.out.spans[line_first..].last_mut() {
            // A zero-width run: the later style wins.
            Some(last) if last.start == start => last.style = style,
            _ => self.out.spans.push(Span { start, style }),
        }
    }

    /// Closes the current line, carrying the active style into the next one.
    fn finish_line(&mut self) {
        self.line += 1;
        self.out.line_spans.push(self.out.spans.len() as u32);
        if self.style != Style::Plain && self.line + 1 < self.line_starts.len() {
            self.out.spans.push(Span {
                start: 0,
                style: self.style,
            });
        }
    }

    fn finish_lines(&mut self, line_count: usize) {
        while self.out.line_spans.len() <= line_count {
            self.finish_line();
        }
    }
}

/// Converts a line's spans from source byte offsets to UTF-16 columns of the
/// row's display text, in which tabs become `tab_width` spaces. Appends
/// `column << 8 | style` per span to `out`.
pub fn project(line: &[u8], spans: &[Span], tab_width: u32, out: &mut Vec<u32>) {
    let mut spans = spans.iter().peekable();
    let mut column = 0u32;
    for (offset, &byte) in line.iter().enumerate() {
        while let Some(span) = spans.next_if(|span| usize::from(span.start) <= offset) {
            out.push(column << 8 | span.style as u32);
        }
        if spans.peek().is_none() {
            return;
        }
        column += match byte {
            b'\t' => tab_width,
            0x80..=0xBF => 0,
            0xF0..=0xFF => 2,
            _ => 1,
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn styled(lang: &str, text: &str) -> Vec<Vec<(String, Style)>> {
        let highlights = FileHighlights::new(Lang::for_name(lang).unwrap(), text.as_bytes())
            .expect("highlighted");
        text.split_inclusive('\n')
            .enumerate()
            .map(|(i, line)| {
                let line = line.trim_end_matches('\n');
                let spans = highlights.line(i as u32);
                let ends = spans.iter().skip(1).map(|s| s.start as usize);
                spans
                    .iter()
                    .zip(ends.chain([line.len()]))
                    .map(|(span, end)| {
                        let start = (span.start as usize).min(line.len());
                        (line[start..end.min(line.len())].to_owned(), span.style)
                    })
                    .filter(|(text, style)| !text.trim().is_empty() && *style != Style::Plain)
                    .collect()
            })
            .collect()
    }

    #[test]
    fn highlights_rust() {
        let lines = styled("rust", "/// Doc\nfn main() {\n    let s = \"hi\\n\";\n}\n");
        assert_eq!(lines[0], [("/// Doc".into(), Style::Comment)]);
        assert!(lines[1].contains(&("fn".into(), Style::Keyword)));
        assert!(lines[1].contains(&("main".into(), Style::Function)));
        assert!(lines[2].contains(&("\\n".into(), Style::StringSpecial)));
        assert!(lines[2].contains(&("\"hi".into(), Style::String)));
    }

    #[test]
    fn carries_styles_across_lines() {
        let lines = styled("c", "int a; /* one\ntwo */ int b;\n");
        assert_eq!(lines[1][0], ("two */".into(), Style::Comment));
        assert_eq!(lines[1][1], ("int".into(), Style::Type));
    }

    #[test]
    fn injects_markdown_fences() {
        let lines = styled("markdown", "# Title\n\n```rust\nlet x = 1;\n```\n");
        assert!(lines[0].iter().any(|(_, style)| *style == Style::Heading));
        assert!(lines[3].contains(&("let".into(), Style::Keyword)));
        assert!(lines[3].contains(&("1".into(), Style::Number)));
    }

    #[test]
    fn skips_minified_and_huge_files() {
        let js = Lang::for_name("js").unwrap();
        assert!(FileHighlights::new(js, "x=1;".repeat(1000).as_bytes()).is_none());
        let big = "let x = 1;\n".repeat(MAX_FILE_BYTES / 10);
        assert!(FileHighlights::new(js, big.as_bytes()).is_none());
    }

    #[test]
    fn projects_to_utf16_columns() {
        let spans = [
            Span {
                start: 1,
                style: Style::Keyword,
            },
            Span {
                start: 3,
                style: Style::String,
            },
            Span {
                start: 9,
                style: Style::Plain,
            },
        ];
        // Tab, then "é" (2 bytes, 1 unit), then "𝄞" (4 bytes, 2 units).
        let line = "\tab\u{e9}\u{1d11e}c".as_bytes();
        let mut out = Vec::new();
        project(line, &spans, 4, &mut out);
        let unpack = |v: u32| (v >> 8, v & 0xFF);
        assert_eq!(
            out.into_iter().map(unpack).collect::<Vec<_>>(),
            [
                (4, Style::Keyword as u32),
                (6, Style::String as u32),
                (9, 0)
            ]
        );
    }
}
