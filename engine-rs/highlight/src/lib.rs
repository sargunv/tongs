//! Syntax highlighting with tree-house and Helix's queries.

#[cfg(any(target_os = "android", test))]
mod bundled;
pub mod file;
pub mod languages;
pub mod service;
pub mod style;
