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

## Benchmark Issues

Issues discovered during A/B benchmark runs (2026-03-26).

### Open: Parameterized queries not supported in benchmark `query-context`

The `query-context` function in `benchmark.clj` calls `run-named-query`
without parameters. Questions q30 (`file-imports`) and q31 (`test-impact`)
require a `:file-path` parameter, so they always fail with "Missing required
inputs." These questions can never score correctly under the query condition.

**Impact:** 2/40 questions always fail in the query condition.

**Recommendation:** Pass question-specific params (or a default target file)
from the question definition into `query-context`.

### Open: LLM misinterprets structured Datalog output as empty context

Even when KG queries return valid data, the LLM sometimes responds "I cannot
answer because the context is empty." This happens because the Datalog result
is a raw EDN vector of tuples, which the model doesn't recognize as useful
context. Affected 8-12 questions across runs.

**Impact:** Suppresses KG advantage on questions where data actually exists.

**Recommendation:** Format query results as human-readable tables or prose
before injecting into the answer prompt.

### Open: Significant LLM non-determinism at temperature=0.1

The raw condition (identical inputs across runs) varied by 8.5pp between two
benchmark runs. Single-run results are not reliable for comparisons.

**Impact:** Need multiple runs averaged for trustworthy A/B comparisons.

**Recommendation:** Use the `:repeats` parameter or run the benchmark 3-5
times and average results.

### Open: Cost tracking shows $0.00 for GLM provider

The GLM proxy uses quota-based pricing, so `estimate-cost` returns 0.0 for all
requests. Benchmark reports cannot show actual API cost when using GLM.

**Impact:** Cosmetic — does not affect accuracy results.
