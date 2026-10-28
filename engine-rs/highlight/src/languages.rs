//! Statically linked grammars and Helix's queries for them.
//!
//! Grammars come from crates.io at the versions closest to Helix's pins, so
//! Helix's queries compile against them unchanged; `all_queries_compile`
//! checks that. Each language's config is built on first use, since
//! compiling a large query takes tens of milliseconds.

use std::sync::{LazyLock, OnceLock};

use tree_house::highlighter::Highlight;
use tree_house::tree_sitter::Grammar;
use tree_house::{InjectionLanguageMarker, Language, LanguageConfig, LanguageLoader};
use tree_sitter_language::LanguageFn;

use crate::style::Style;

/// A language the engine can highlight.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Lang(u8);

struct Spec {
    /// Helix's language name, which is also its query directory.
    name: &'static str,
    grammar: LanguageFn,
    /// File extensions, or whole file names when they start with `=`.
    files: &'static [&'static str],
    /// Names that select this language in Markdown fences and shebangs.
    aliases: &'static [&'static str],
}

macro_rules! specs {
    ($($name:literal => $grammar:expr, files: [$($file:literal),*], aliases: [$($alias:literal),*];)*) => {
        const SPECS: &[Spec] = &[$(Spec {
            name: $name,
            grammar: $grammar,
            files: &[$($file),*],
            aliases: &[$($alias),*],
        }),*];
    };
}

specs! {
    "bash" => tree_sitter_bash::LANGUAGE,
        files: ["sh", "bash", "zsh", "=.bashrc", "=.zshrc", "=PKGBUILD"],
        aliases: ["bash", "sh", "shell", "zsh", "console"];
    "c" => tree_sitter_c::LANGUAGE, files: ["c"], aliases: ["c"];
    "cmake" => tree_sitter_cmake::LANGUAGE,
        files: ["cmake", "=CMakeLists.txt"], aliases: ["cmake"];
    // Helix reads `.h` as C++, which is a superset for highlighting purposes.
    "cpp" => tree_sitter_cpp::LANGUAGE,
        files: ["cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx", "inl", "ipp", "cu", "cuh", "mm"],
        aliases: ["cpp", "c++", "cxx", "cuda", "objc", "objective-c"];
    "c-sharp" => tree_sitter_c_sharp::LANGUAGE,
        files: ["cs", "csx"], aliases: ["cs", "csharp", "c#", "c-sharp"];
    "dart" => tree_sitter_dart_orchard::LANGUAGE, files: ["dart"], aliases: ["dart"];
    "go" => tree_sitter_go::LANGUAGE, files: ["go"], aliases: ["go", "golang"];
    "java" => tree_sitter_java::LANGUAGE, files: ["java"], aliases: ["java"];
    "javascript" => tree_sitter_javascript::LANGUAGE,
        files: ["js", "mjs", "cjs"], aliases: ["js", "javascript", "node"];
    "jsx" => tree_sitter_javascript::LANGUAGE, files: ["jsx"], aliases: ["jsx"];
    "json" => tree_sitter_json::LANGUAGE,
        files: ["json", "jsonc", "json5", "=.babelrc", "=flake.lock"], aliases: ["json", "jsonc"];
    "kotlin" => tree_sitter_kotlin_sg::LANGUAGE,
        files: ["kt", "kts"], aliases: ["kotlin", "kt", "kts"];
    "markdown" => tree_sitter_md::LANGUAGE,
        files: ["md", "markdown", "mdx"], aliases: ["markdown", "md"];
    "markdown.inline" => tree_sitter_md::INLINE_LANGUAGE, files: [], aliases: [];
    "python" => tree_sitter_python::LANGUAGE,
        files: ["py", "pyi", "pyw", "=SConstruct", "=SConscript"],
        aliases: ["python", "py", "python3", "uv"];
    "rust" => tree_sitter_rust::LANGUAGE, files: ["rs"], aliases: ["rust", "rs"];
    "swift" => tree_sitter_swift::LANGUAGE,
        files: ["swift", "swiftinterface"], aliases: ["swift"];
    "toml" => tree_sitter_toml_ng::LANGUAGE,
        files: ["toml", "=Cargo.lock", "=uv.lock", "=poetry.lock"], aliases: ["toml"];
    "tsx" => tree_sitter_typescript::LANGUAGE_TSX, files: ["tsx"], aliases: ["tsx"];
    "typescript" => tree_sitter_typescript::LANGUAGE_TYPESCRIPT,
        files: ["ts", "mts", "cts"], aliases: ["ts", "typescript", "deno", "bun"];
    "yaml" => tree_sitter_yaml::LANGUAGE,
        files: ["yml", "yaml", "=.clang-format", "=.clang-tidy"], aliases: ["yaml", "yml"];
    "zig" => tree_sitter_zig::LANGUAGE, files: ["zig", "zon"], aliases: ["zig"];
}

/// Helix query files, by directory. Missing files are empty queries.
fn query_source(dir: &str, kind: &str) -> &'static str {
    macro_rules! queries {
        ($($dir:literal: [$($kind:literal),*];)*) => {
            match (dir, kind) {
                $($(($dir, $kind) => include_str!(concat!("../queries/", $dir, "/", $kind, ".scm")),)*)*
                _ => "",
            }
        };
    }
    queries! {
        "_javascript": ["highlights", "locals"];
        "_jsx": ["highlights"];
        "_typescript": ["highlights", "locals"];
        "bash": ["highlights", "injections", "locals"];
        "c": ["highlights", "injections", "locals"];
        "c-sharp": ["highlights", "injections", "locals"];
        "cmake": ["highlights", "injections"];
        "cpp": ["highlights", "injections", "locals"];
        "dart": ["highlights", "injections", "locals"];
        "ecma": ["highlights", "injections", "locals"];
        "go": ["highlights", "injections", "locals"];
        "java": ["highlights", "injections", "locals"];
        "javascript": ["highlights", "injections", "locals"];
        "json": ["highlights", "injections"];
        "jsx": ["highlights", "injections", "locals"];
        "kotlin": ["highlights", "injections", "locals"];
        "markdown": ["highlights", "injections"];
        "markdown.inline": ["highlights", "injections"];
        "python": ["highlights", "injections", "locals"];
        "rust": ["highlights", "injections", "locals"];
        "swift": ["highlights", "injections", "locals"];
        "toml": ["highlights", "injections"];
        "tsx": ["highlights", "injections", "locals"];
        "typescript": ["highlights", "injections", "locals"];
        "yaml": ["highlights", "injections"];
        "zig": ["highlights", "injections", "locals"];
    }
}

impl Lang {
    /// Picks a language from a file path's name or extension.
    pub fn for_path(path: &str) -> Option<Lang> {
        let name = path.rsplit('/').next().unwrap_or(path);
        let ext = name.rsplit_once('.').map(|(_, ext)| ext);
        let position = SPECS.iter().position(|spec| {
            spec.files.iter().any(|file| match file.strip_prefix('=') {
                Some(file_name) => file_name == name,
                None => ext.is_some_and(|ext| ext.eq_ignore_ascii_case(file)),
            })
        })?;
        Some(Lang(position as u8))
    }

    /// Picks a language from a name such as a Markdown fence's info string.
    pub fn for_name(name: &str) -> Option<Lang> {
        let name = name.trim().to_ascii_lowercase();
        let position = SPECS
            .iter()
            .position(|spec| spec.name == name || spec.aliases.contains(&name.as_str()))?;
        Some(Lang(position as u8))
    }

    pub fn all() -> impl Iterator<Item = Lang> {
        (0..SPECS.len() as u8).map(Lang)
    }

    /// Compiles this language's queries now rather than on first use, and
    /// reports whether they compiled.
    pub fn prepare(self) -> bool {
        LOADER.get_config(self.language()).is_some()
    }

    pub fn name(self) -> &'static str {
        SPECS[self.0 as usize].name
    }

    pub(crate) fn language(self) -> Language {
        Language::new(u32::from(self.0))
    }
}

/// Lazily built configs for every language, shared by all highlighting
/// threads. Queries map their captures straight to [`Style`] IDs.
pub(crate) struct Loader {
    configs: Vec<OnceLock<Option<LanguageConfig>>>,
}

pub(crate) static LOADER: LazyLock<Loader> = LazyLock::new(|| Loader {
    configs: SPECS.iter().map(|_| OnceLock::new()).collect(),
});

impl Loader {
    fn build(lang: Lang) -> Result<LanguageConfig, String> {
        let spec = &SPECS[lang.0 as usize];
        let grammar = Grammar::try_from(spec.grammar).map_err(|err| err.to_string())?;
        let read =
            |kind: &str| tree_house::read_query(spec.name, |dir| query_source(dir, kind).into());
        let config = LanguageConfig::new(
            grammar,
            &read("highlights"),
            &read("injections"),
            &read("locals"),
        )
        .map_err(|err| format!("{err:?}"))?;
        // Unrecognized captures still map to `Plain` rather than `None`, so
        // they keep their precedence over later patterns, as in Helix.
        config.configure(|name| Some(Highlight::new(Style::for_capture(name) as u32)));
        Ok(config)
    }
}

impl LanguageLoader for Loader {
    fn language_for_marker(&self, marker: InjectionLanguageMarker) -> Option<Language> {
        let lang = match marker {
            InjectionLanguageMarker::Name(name) => Lang::for_name(name),
            InjectionLanguageMarker::Match(text) => Lang::for_name(&text.to_string()),
            InjectionLanguageMarker::Filename(text) => Lang::for_path(&text.to_string()),
            InjectionLanguageMarker::Shebang(text) => {
                let text = text.to_string();
                let program = text.rsplit('/').next().unwrap_or(&text);
                Lang::for_name(program.trim_end_matches(|c: char| c.is_ascii_digit() || c == '.'))
            }
        };
        lang.map(Lang::language)
    }

    fn get_config(&self, lang: Language) -> Option<&LanguageConfig> {
        self.configs
            .get(lang.idx())?
            .get_or_init(|| Self::build(Lang(lang.idx() as u8)).ok())
            .as_ref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_queries_compile() {
        let failures: Vec<_> = (0..SPECS.len())
            .filter_map(|i| {
                let lang = Lang(i as u8);
                Loader::build(lang)
                    .err()
                    .map(|err| format!("{}: {err}", lang.name()))
            })
            .collect();
        assert!(failures.is_empty(), "{failures:#?}");
    }

    #[test]
    fn detects_languages() {
        let name = |path| Lang::for_path(path).map(Lang::name);
        assert_eq!(name("src/lib.rs"), Some("rust"));
        assert_eq!(name("include/maplibre.h"), Some("cpp"));
        assert_eq!(name("bindings/CMakeLists.txt"), Some("cmake"));
        assert_eq!(name("build.gradle.kts"), Some("kotlin"));
        assert_eq!(name("README"), None);
        assert_eq!(Lang::for_name("Rust").map(Lang::name), Some("rust"));
    }
}
