//! Times making each language ready: on Android, extracting and loading its
//! bundled grammar, then compiling its queries. Run it twice to separate the
//! two, since the second run finds the grammars already extracted.
//!
//! Usage on a device or emulator: build with `TONGS_GRAMMARS_DIR=engine/build/grammars
//! cargo ndk -t x86_64 build --release -p tongs-highlight --example prepare_languages`
//! (after `./gradlew :engine:buildGrammars`), push it to `/data/local/tmp`, and run it there
//! with a grammar directory such as `/data/local/tmp/tongs-grammars`.

use std::time::{Duration, Instant};

use tongs_highlight::languages::{Lang, set_grammar_dir};

fn main() {
    let dir = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "/data/local/tmp/tongs-grammars".into());
    set_grammar_dir(dir.into());
    let mut total = Duration::ZERO;
    for lang in Lang::all() {
        let start = Instant::now();
        let ok = lang.prepare();
        let elapsed = start.elapsed();
        total += elapsed;
        println!(
            "{:<16} {:>7.1} ms{}",
            lang.name(),
            elapsed.as_secs_f64() * 1e3,
            if ok { "" } else { "  failed" }
        );
    }
    println!("{:<16} {:>7.1} ms", "total", total.as_secs_f64() * 1e3);
}
