//! Grammars bundled compressed rather than linked, for Android.
//!
//! Android stores native libraries uncompressed, and the grammars are 30 MB
//! of mostly parse tables, so linking them would cost that much on every
//! install. Instead `tongs-grammars` builds each as its own shared library,
//! compressed about 20×, and this crate embeds them. The first time a file
//! needs a grammar, it's extracted to a cache directory, made read-only (as
//! Android 14 requires of loaded dex files), and loaded from there. Extraction
//! runs on the highlighting thread that asked, so the file stays plain until
//! it lands, like any pending highlight.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

pub(crate) struct Bundled {
    /// The grammar's name in its `tree_sitter_<symbol>` entry point.
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
    pub symbol: &'static str,
    /// Extracted file name, which includes a hash of its contents.
    pub file: &'static str,
    /// Uncompressed size.
    pub len: u64,
    /// The library in the `.lzma` format.
    pub lzma: &'static [u8],
}

#[cfg(target_os = "android")]
static BUNDLED: &[Bundled] = include!(env!("TONGS_BUNDLED_GRAMMARS"));

#[cfg(target_os = "android")]
pub(crate) static DIR: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

#[cfg(target_os = "android")]
pub(crate) fn load(symbol: &str) -> Result<tree_house::tree_sitter::Grammar, String> {
    static CLEANED: std::sync::Once = std::sync::Once::new();
    let bundled = BUNDLED
        .iter()
        .find(|bundled| bundled.symbol == symbol)
        .ok_or_else(|| format!("{symbol} isn't bundled"))?;
    let dir = DIR.get().ok_or("no grammar directory set")?;
    CLEANED.call_once(|| remove_stale(dir, BUNDLED));
    let path = extract(dir, bundled).map_err(|err| format!("extracting {symbol}: {err}"))?;
    // Safety: the library is one of ours, built from the grammar's sources.
    unsafe { tree_house::tree_sitter::Grammar::new(symbol, &path) }.map_err(|err| err.to_string())
}

/// Returns the extracted library's path, extracting it first unless an
/// earlier run did.
fn extract(dir: &Path, bundled: &Bundled) -> io::Result<PathBuf> {
    let path = dir.join(bundled.file);
    if fs::metadata(&path).is_ok_and(|meta| meta.len() == bundled.len) {
        return Ok(path);
    }
    fs::create_dir_all(dir)?;
    // Write elsewhere and rename, so the file at `path` is always whole.
    let temp = dir.join(format!(".{}.{}", bundled.file, std::process::id()));
    let result = (|| {
        let mut reader = lzma_rust2::LzmaReader::new_mem_limit(bundled.lzma, u32::MAX, None)?;
        let mut file = fs::File::create(&temp)?;
        // Read-only before writing, as Android 14 asks of loaded code; the
        // open handle can still write.
        let mut permissions = file.metadata()?.permissions();
        permissions.set_readonly(true);
        file.set_permissions(permissions)?;
        let len = io::copy(&mut reader, &mut file)?;
        if len != bundled.len {
            return Err(io::Error::other(format!(
                "{len} bytes, expected {}",
                bundled.len
            )));
        }
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, &path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result.map(|()| path)
}

/// Deletes files left by other builds' grammars, or by interrupted
/// extractions.
fn remove_stale(dir: &Path, bundled: &[Bundled]) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        if !bundled.iter().any(|bundled| name == bundled.file) {
            let _ = fs::remove_file(entry.path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn compress(bytes: &[u8]) -> &'static [u8] {
        let options = lzma_rust2::LzmaOptions::with_preset(6);
        let mut writer =
            lzma_rust2::LzmaWriter::new_use_header(Vec::new(), &options, Some(bytes.len() as u64))
                .unwrap();
        writer.write_all(bytes).unwrap();
        writer.finish().unwrap().leak()
    }

    #[test]
    fn extracts_once_read_only_and_cleans_up() {
        let dir = std::env::temp_dir().join(format!("tongs-bundled-{}", std::process::id()));
        let contents = b"not really a grammar ".repeat(1000);
        let bundled = Bundled {
            symbol: "test",
            file: "test-1.so",
            len: contents.len() as u64,
            lzma: compress(&contents),
        };
        let path = extract(&dir, &bundled).unwrap();
        assert_eq!(fs::read(&path).unwrap(), contents);
        assert!(fs::metadata(&path).unwrap().permissions().readonly());
        // A second call finds the file rather than writing it again.
        let modified = fs::metadata(&path).unwrap().modified().unwrap();
        assert_eq!(extract(&dir, &bundled).unwrap(), path);
        assert_eq!(fs::metadata(&path).unwrap().modified().unwrap(), modified);

        fs::write(dir.join("test-0.so"), b"old").unwrap();
        remove_stale(&dir, std::slice::from_ref(&bundled));
        let names: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert_eq!(names, ["test-1.so"]);

        // A truncated file is replaced.
        let mut permissions = fs::metadata(&path).unwrap().permissions();
        #[allow(clippy::permissions_set_readonly_false)]
        permissions.set_readonly(false);
        fs::set_permissions(&path, permissions).unwrap();
        fs::write(&path, b"short").unwrap();
        assert_eq!(
            fs::read(extract(&dir, &bundled).unwrap()).unwrap(),
            contents
        );
        fs::remove_dir_all(&dir).unwrap();
    }
}
