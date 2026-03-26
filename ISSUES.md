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

---

## LongBench v2 Benchmark Issues

Issues discovered during the full LongBench v2 benchmark run (2026-03-26).

### Open: Empty API responses for large contexts (17/50 questions)

34% of LongBench questions received empty responses from the GLM API proxy.
All 17 affected questions show 0 input/output tokens and ~2-4s response times
(immediate rejection). Strongly correlated with "long" context questions (15/17).

The `invoke-api` function in `llm.clj` returns nil text and zero usage without
logging a warning, making it hard to distinguish silent API rejection from a
model that chose not to answer.

**Impact:** Effective accuracy on answered questions is 39.4% (13/33), but
reported overall is 26.0% (13/50) due to these silent failures.

**Recommendation:** Log a warning when HTTP 200 returns no text content. Consider
retry with reduced context, or surface the issue in the experiment report
automatically.

### Open: Cost tracking shows $0.00 for GLM provider

The GLM proxy uses quota-based pricing, so `estimate-cost` returns 0.0 for all
requests. LongBench experiment reports cannot show actual API cost when using GLM.

**Impact:** Cosmetic — does not affect accuracy results.
