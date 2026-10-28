//! On Android, points `src/bundled.rs` at the compressed grammars that
//! `tongs-grammars` built for this target.

fn main() {
    println!("cargo::rerun-if-env-changed=TONGS_GRAMMARS_DIR");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    let dir = std::env::var("TONGS_GRAMMARS_DIR").expect(
        "set TONGS_GRAMMARS_DIR to the output of tongs-grammars; \
         `./gradlew :engine:cargoNdkBuild` does both",
    );
    let target = std::env::var("TARGET").unwrap();
    let table = std::path::Path::new(&dir).join(target).join("grammars.rs");
    assert!(table.is_file(), "no grammars built at {table:?}");
    println!("cargo::rerun-if-changed={}", table.display());
    println!(
        "cargo::rustc-env=TONGS_BUNDLED_GRAMMARS={}",
        table.display()
    );
}
