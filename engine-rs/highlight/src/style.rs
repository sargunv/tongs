//! The small fixed set of token styles the renderer colors. Helix capture
//! names are many and theme-specific; the engine folds them into these IDs and
//! leaves the colors to Kotlin's `CodeViewColors`.

/// Mirrors `TokenStyle` in `engine/src/commonMain/.../ReviewRows.kt`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Style {
    #[default]
    Plain = 0,
    Comment,
    Keyword,
    Operator,
    Punctuation,
    String,
    /// Escapes, regexes, and other special parts of strings.
    StringSpecial,
    Number,
    /// Built-in constants such as `true` and `null`, plus enum-like constants.
    Constant,
    Function,
    /// Types and constructors.
    Type,
    /// Fields and members.
    Property,
    /// `self`, `this`, and similar.
    VariableBuiltin,
    /// Attributes, annotations, and decorators.
    Attribute,
    Namespace,
    Label,
    /// Markup tags, as in JSX.
    Tag,
    Heading,
    Link,
    /// Bold and italic text; a monospace grid can't change the font.
    Emphasis,
}

impl Style {
    /// Maps a Helix capture name such as `keyword.control.return`, trying
    /// the full name and then each shorter prefix, like Helix themes do.
    pub fn for_capture(name: &str) -> Style {
        let mut name = name;
        loop {
            if let Some(style) = Self::exact(name) {
                return style;
            }
            match name.rsplit_once('.') {
                Some((parent, _)) => name = parent,
                None => return Style::Plain,
            }
        }
    }

    fn exact(name: &str) -> Option<Style> {
        Some(match name {
            "comment" => Style::Comment,
            "keyword" => Style::Keyword,
            "operator" => Style::Operator,
            "punctuation" | "special" | "markup.list" => Style::Punctuation,
            "string" | "constant.character" | "markup.raw" => Style::String,
            "string.regexp" | "string.special" | "constant.character.escape" => {
                Style::StringSpecial
            }
            "constant.numeric" => Style::Number,
            "constant" => Style::Constant,
            "function" => Style::Function,
            "type" | "constructor" => Style::Type,
            "variable.other.member" => Style::Property,
            "variable.builtin" => Style::VariableBuiltin,
            "variable" => Style::Plain,
            "attribute" => Style::Attribute,
            "namespace" => Style::Namespace,
            "label" => Style::Label,
            "tag" => Style::Tag,
            "markup.heading" => Style::Heading,
            "markup.link" => Style::Link,
            "markup.quote" => Style::Comment,
            "markup.bold" | "markup.italic" | "markup.strikethrough" => Style::Emphasis,
            _ => return None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn falls_back_to_parent_captures() {
        assert_eq!(Style::for_capture("keyword.control.return"), Style::Keyword);
        assert_eq!(
            Style::for_capture("constant.character.escape"),
            Style::StringSpecial
        );
        assert_eq!(Style::for_capture("constant.character"), Style::String);
        assert_eq!(
            Style::for_capture("constant.builtin.boolean"),
            Style::Constant
        );
        assert_eq!(Style::for_capture("variable.parameter"), Style::Plain);
        assert_eq!(Style::for_capture("diff.plus"), Style::Plain);
    }
}
