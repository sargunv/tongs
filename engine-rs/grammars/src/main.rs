//! Builds each tree-sitter grammar that `tongs-highlight` depends on as its
//! own shared library for Android, compresses it with LZMA, and writes a
//! `grammars.rs` table per target that `tongs-highlight` embeds. The app
//! extracts a grammar the first time a file needs it; see
//! `engine-rs/highlight/src/bundled.rs`.
//!
//! Usage: `cargo run --release -p tongs-grammars -- --ndk <dir> --api 26
//! --out <dir> aarch64-linux-android x86_64-linux-android`. The `engine`
//! module's `cargoNdkBuild` task runs it first.

use std::collections::HashMap;
use std::fmt::Write as _;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;

use anyhow::{Context, Result, bail, ensure};
use clap::Parser;
use lzma_rust2::{LzmaOptions, LzmaWriter};
use serde_json::Value;

#[derive(Parser)]
struct Args {
    /// Android NDK directory.
    #[arg(long)]
    ndk: PathBuf,
    /// Minimum Android API level to build for.
    #[arg(long)]
    api: u32,
    /// Output directory, with one subdirectory per target.
    #[arg(long)]
    out: PathBuf,
    /// Rust target triples, such as `aarch64-linux-android`.
    #[arg(required = true)]
    targets: Vec<String>,
}

/// One grammar's C sources, found by its generated `parser.c`.
struct Source {
    /// The grammar's name in its `tree_sitter_<symbol>` entry point.
    symbol: String,
    dir: PathBuf,
}

struct Built {
    symbol: String,
    len: usize,
    hash: u64,
    lzma: Vec<u8>,
}

fn main() -> Result<()> {
    let args = Args::parse();
    let clang = clang(&args.ndk)?;
    let sources = grammar_sources()?;

    let jobs: Vec<(&str, &Source)> = args
        .targets
        .iter()
        .flat_map(|target| sources.iter().map(move |source| (target.as_str(), source)))
        .collect();
    let next = Mutex::new(jobs.into_iter());
    let built: Mutex<HashMap<&str, Vec<Built>>> = Mutex::default();
    let scratch = args.out.join("scratch");
    std::fs::create_dir_all(&scratch)?;
    let threads = std::thread::available_parallelism().map_or(4, |n| n.get());
    std::thread::scope(|scope| -> Result<()> {
        let workers: Vec<_> = (0..threads)
            .map(|_| {
                scope.spawn(|| -> Result<()> {
                    loop {
                        let Some((target, source)) = next.lock().unwrap().next() else {
                            return Ok(());
                        };
                        let library = build(&clang, target, args.api, source, &scratch)
                            .with_context(|| format!("building {} for {target}", source.symbol))?;
                        let grammar = Built {
                            symbol: source.symbol.clone(),
                            len: library.len(),
                            hash: fnv1a(&library),
                            lzma: compress(&library)?,
                        };
                        built
                            .lock()
                            .unwrap()
                            .entry(target)
                            .or_default()
                            .push(grammar);
                    }
                })
            })
            .collect();
        workers
            .into_iter()
            .try_for_each(|worker| worker.join().unwrap())
    })?;
    std::fs::remove_dir_all(&scratch)?;

    for (target, mut grammars) in built.into_inner().unwrap() {
        grammars.sort_by(|a, b| a.symbol.cmp(&b.symbol));
        write_target(&args.out.join(target), &grammars)?;
        let raw: usize = grammars.iter().map(|g| g.len).sum();
        let lzma: usize = grammars.iter().map(|g| g.lzma.len()).sum();
        println!(
            "{target}: {} grammars, {raw} bytes, {lzma} compressed",
            grammars.len()
        );
    }
    Ok(())
}

/// The NDK's clang, which builds for any Android target given `--target`.
fn clang(ndk: &Path) -> Result<PathBuf> {
    let prebuilt = ndk.join("toolchains/llvm/prebuilt");
    for host in std::fs::read_dir(&prebuilt).with_context(|| format!("{prebuilt:?}"))? {
        let bin = host?.path().join("bin");
        for name in ["clang", "clang.exe"] {
            if bin.join(name).is_file() {
                return Ok(bin.join(name));
            }
        }
    }
    bail!("no clang in {prebuilt:?}")
}

/// Finds the grammar crates `tongs-highlight` depends on with `cargo
/// metadata`, and the grammars in each: `tree-sitter-md`, for example, has
/// two.
fn grammar_sources() -> Result<Vec<Source>> {
    let cargo = std::env::var_os("CARGO").unwrap_or_else(|| "cargo".into());
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../Cargo.toml");
    let output = Command::new(cargo)
        .args([
            "metadata",
            "--format-version=1",
            "--locked",
            "--manifest-path",
        ])
        .arg(workspace)
        .output()?;
    ensure!(
        output.status.success(),
        "cargo metadata failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let metadata: Value = serde_json::from_slice(&output.stdout)?;
    let packages: HashMap<&str, &Value> = metadata["packages"]
        .as_array()
        .context("packages")?
        .iter()
        .map(|package| (package["id"].as_str().unwrap_or_default(), package))
        .collect();
    let highlight = metadata["resolve"]["nodes"]
        .as_array()
        .context("resolve")?
        .iter()
        .find(|node| {
            let id = node["id"].as_str().unwrap_or_default();
            packages
                .get(id)
                .is_some_and(|p| p["name"] == "tongs-highlight")
        })
        .context("tongs-highlight isn't in the workspace")?;

    let mut sources = Vec::new();
    for dep in highlight["deps"].as_array().context("deps")? {
        let package = packages[dep["pkg"].as_str().unwrap_or_default()];
        let name = package["name"].as_str().unwrap_or_default();
        if !name.starts_with("tree-sitter-") || name == "tree-sitter-language" {
            continue;
        }
        let manifest = Path::new(package["manifest_path"].as_str().context("manifest_path")?);
        let mut parsers = Vec::new();
        find_parsers(manifest.parent().unwrap(), &mut parsers)?;
        ensure!(!parsers.is_empty(), "no parser.c in {name}");
        for dir in parsers {
            let symbol = symbol(&dir.join("parser.c"))?;
            sources.push(Source { symbol, dir });
        }
    }
    sources.sort_by(|a, b| a.symbol.cmp(&b.symbol));
    if let Some(pair) = sources
        .windows(2)
        .find(|pair| pair[0].symbol == pair[1].symbol)
    {
        bail!("two grammars named {}", pair[0].symbol);
    }
    Ok(sources)
}

/// Collects every `src` directory with a generated `parser.c`.
fn find_parsers(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    if dir.file_name().is_some_and(|name| name == "src") && dir.join("parser.c").is_file() {
        out.push(dir.to_owned());
        return Ok(());
    }
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        let skip = path.file_name().is_some_and(|name| {
            name.to_str().is_some_and(|name| {
                name.starts_with('.') || ["node_modules", "target", "bindings"].contains(&name)
            })
        });
        if path.is_dir() && !skip {
            find_parsers(&path, out)?;
        }
    }
    out.sort();
    Ok(())
}

/// Reads the grammar's name from the entry point `parser.c` defines.
fn symbol(parser: &Path) -> Result<String> {
    let text = std::fs::read_to_string(parser)?;
    const ENTRY: &str = "TSLanguage *tree_sitter_";
    let at = text.find(ENTRY).context("no entry point")? + ENTRY.len();
    let name: String = text[at..]
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect();
    ensure!(!name.is_empty(), "empty grammar name in {parser:?}");
    Ok(name)
}

/// Compiles one grammar into a stripped shared library and returns it.
fn build(clang: &Path, target: &str, api: u32, source: &Source, scratch: &Path) -> Result<Vec<u8>> {
    let out = scratch.join(format!("{target}-{}.so", source.symbol));
    let mut command = Command::new(clang);
    command
        .arg(format!("--target={target}{api}"))
        .args(["-shared", "-fPIC", "-O2", "-std=c11", "-w"])
        .args([
            "-ffunction-sections",
            "-fdata-sections",
            "-Wl,--gc-sections",
        ])
        // 16 KB pages, as Android 15+ devices and Play require.
        .args(["-Wl,-z,max-page-size=16384", "-s"])
        .arg("-I")
        .arg(&source.dir)
        .arg("-o")
        .arg(&out)
        .arg(source.dir.join("parser.c"));
    for scanner in ["scanner.c", "scanner.cc"] {
        if source.dir.join(scanner).is_file() {
            ensure!(scanner == "scanner.c", "C++ scanners aren't supported");
            command.arg(source.dir.join(scanner));
        }
    }
    let output = command.output()?;
    ensure!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let library = std::fs::read(&out)?;
    std::fs::remove_file(&out)?;
    Ok(library)
}

/// `.lzma` with its header, which records the uncompressed size. Parse
/// tables are most of each library, and LZMA packs them about 20× (xz -9e
/// is within 1%; zstd -19 is 18% bigger, and deflate 72%).
fn compress(library: &[u8]) -> Result<Vec<u8>> {
    let options = LzmaOptions::with_preset(9);
    let mut writer = LzmaWriter::new_use_header(Vec::new(), &options, Some(library.len() as u64))?;
    writer.write_all(library)?;
    Ok(writer.finish()?)
}

/// Names extracted files by content, so a new build never loads an old one.
fn fnv1a(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |hash, &b| {
        (hash ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3)
    })
}

fn write_target(dir: &Path, grammars: &[Built]) -> Result<()> {
    if dir.exists() {
        std::fs::remove_dir_all(dir)?;
    }
    std::fs::create_dir_all(dir)?;
    let dir = std::path::absolute(dir)?;
    let mut table = String::from("// Generated by tongs-grammars.\n&[\n");
    for grammar in grammars {
        let path = dir.join(format!("{}.so.lzma", grammar.symbol));
        std::fs::write(&path, &grammar.lzma)?;
        writeln!(
            table,
            "    Bundled {{ symbol: {:?}, file: \"{}-{:016x}.so\", len: {}, lzma: include_bytes!({:?}) }},",
            grammar.symbol, grammar.symbol, grammar.hash, grammar.len, path
        )?;
    }
    table.push_str("]\n");
    std::fs::write(dir.join("grammars.rs"), table)?;
    Ok(())
}
