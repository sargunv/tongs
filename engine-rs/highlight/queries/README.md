# Helix queries

These highlight, injection, and locals queries are copied from
[Helix](https://github.com/helix-editor/helix) `runtime/queries` at commit
`ba40e547426b0f9896c8bdc699a4ab11f2b37dbc` (2026-09-29). They are licensed under
the Mozilla Public License 2.0 in [LICENSE](LICENSE); some carry notices for
nvim-treesitter, which they derive from. Changes to these files stay under the
MPL.

Local changes, each marked with a `Tongs:` comment at the top of the file:

- `dart/highlights.scm`: adapted to `tree-sitter-dart-orchard`, because Helix's
  pinned Dart grammar has no crate that builds without a second tree-sitter
  runtime.

The grammars come from crates.io (plus one git pin for C++) and are all MIT
licensed; see `../Cargo.toml`.
