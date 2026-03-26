# Pre-Release Issues Found

Issues discovered during E2E testing across all supported languages.
All issues below have been fixed.

## Fixed: Import fails on repos with merge commits

**Fix:** Added `--topo-order` to `git log` in `git.clj` so parent commits
always precede children. Commit: `fix(git): use --topo-order for commit import`.

## Fixed: Postprocess resolves 0 import edges for mono-repos

**Fix:** Added suffix-matching fallback to Clojure, Java, Python, and Elixir
resolvers in `imports.clj` to handle subproject directories (e.g.,
`ring-core/src/...`) and Maven/Gradle layouts (`src/main/java/...`).
Commit: `fix(imports): resolve imports in mono-repos and prefixed layouts`.

## Fixed: `analyze` command lacks `--max-files` flag

**Fix:** Added `--max-files N` to CLI and MCP `noumenon_analyze`.
Commit: `feat(analyze): add --max-files flag to limit analysis scope`.

## Fixed: `list-databases` missing postprocess stage when 0 edges

**Fix:** Postprocess now always records a marker transaction with `:tx/op
:postprocess`, even when no import edges are found.
Commit: `fix(imports): record postprocess tx even when 0 edges resolved`.
