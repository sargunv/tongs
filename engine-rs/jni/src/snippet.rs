//! Highlighting for short snippets, such as Markdown code fences, which are
//! highlighted whole and at once rather than row by row through the cache.

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JIntArray, JString};
use jni::sys::jint;
use tongs_highlight::file::{FileHighlights, project};
use tongs_highlight::languages::Lang;

use crate::Error;

/// Snippets longer than this stay plain; fences this big are logs, not code.
const MAX_SNIPPET_BYTES: usize = 256 * 1024;

/// Highlights `text` as the language named `language` (a fence's info
/// string, like `kotlin` or `rs`). Returns `utf16Offset << 8 | styleId` per
/// style change across the whole text, or null when the language is unknown,
/// the text is too big, or it doesn't parse in time.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sargunv_tongs_engine_NativeSnippets_highlight<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    language: JString<'local>,
    text: JString<'local>,
) -> JIntArray<'local> {
    env.with_env(|env| -> Result<JIntArray<'local>, Error> {
        let language = language.try_to_string(env)?;
        let text = text.try_to_string(env)?;
        let Some(spans) = highlight(&language, &text) else {
            return Ok(JIntArray::default());
        };
        let array = JIntArray::new(env, spans.len())?;
        array.set_region(env, 0, &spans)?;
        Ok(array)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Spans as `utf16Offset << 8 | styleId` over all of `text`.
fn highlight(language: &str, text: &str) -> Option<Vec<jint>> {
    if text.len() > MAX_SNIPPET_BYTES {
        return None;
    }
    let lang = Lang::for_name(language.split([' ', ',', '{']).next()?)?;
    let highlights = FileHighlights::new(lang, text.as_bytes())?;
    let mut out = Vec::new();
    let mut line_spans = Vec::new();
    let mut line_start = 0u32;
    for (number, line) in text.split_inclusive('\n').enumerate() {
        let bytes = line.trim_end_matches('\n').as_bytes();
        line_spans.clear();
        // A tab is one UTF-16 unit of the snippet's own text.
        project(bytes, highlights.line(number as u32), 1, &mut line_spans);
        out.extend(line_spans.iter().map(|&span| {
            let column = span >> 8;
            (((line_start + column) << 8) | (span & 0xFF)) as jint
        }));
        line_start += line.encode_utf16().count() as u32;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tongs_highlight::style::Style;

    #[test]
    fn highlights_across_lines_in_utf16_offsets() {
        let text = "// é\nfun main() {}\n";
        let spans = highlight("kotlin", text).expect("kotlin highlights");
        let decoded: Vec<(u32, u8)> = spans
            .iter()
            .map(|&s| ((s as u32) >> 8, (s & 0xFF) as u8))
            .collect();
        assert_eq!(decoded.first(), Some(&(0, Style::Comment as u8)));
        // `fun` starts the second line: "// é\n" is five UTF-16 units.
        assert!(decoded.contains(&(5, Style::Keyword as u8)), "{decoded:?}");
    }

    #[test]
    fn unknown_languages_stay_plain() {
        assert!(highlight("no-such-language", "x").is_none());
        assert!(highlight("", "x").is_none());
    }

    #[test]
    fn info_strings_may_carry_attributes() {
        assert!(highlight("rust ignore", "fn main() {}").is_some());
    }
}
