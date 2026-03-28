# Changelog

All notable changes to Noumenon are documented in this file.

## 0.2.1

### Features

- **Artifact storage** — Prompts, named queries, and Datalog rules are now stored
  in Datomic with full edit history. `reseed` command reloads from classpath.
  `artifact-history` command shows change history per artifact.

### Fixes

- **Security** — Cap introspect `max_iterations` (100) and `eval_runs` (10) to
  prevent unbounded LLM calls. Validate `target` parameter length. Anchor
  `validate-code-path!` to project root instead of JVM CWD.
- **NPE on update --analyze** — `sync/update-repo!` now passes `meta-db` through
  to `analyze-repo!`, fixing a NullPointerException introduced in 0.2.0.
- **Chunked prompt history** — `prompt-history` now tracks both template and chunk
  transactions, fixing empty results for prompts over 4000 chars.
- **Introspect git commits** — `git-commit-improvement!` checks exit codes from
  `git add` and `git commit` instead of silently discarding failures.
- **Agent query rules** — `dispatch-query` errors clearly when a query requires
  rules (`%`) but rules are not loaded, instead of silently running without them.
- **MCP artifact-history** — Validates that `name` is required for prompt history.
- **Introspect persistence** — Iterations saved incrementally; large modifications
  truncated before Datomic storage.
- **Artifact staleness** — Fixed stale data on prompt chunking transitions and
  query list regression.

### CLI / MCP UX

- Registered `reseed` and `artifact-history` as CLI subcommands (previously
  unreachable).
- Corrected `--target` default documentation (all targets, not just examples).
- Added `noumenon_introspect`, `noumenon_reseed`, and `noumenon_artifact_history`
  to README command reference.
- Improved MCP feedback: empty query list hints at reseeding, `introspect_stop`
  reports actual session status, `ask` no longer exposes internal status keywords.
- Batch CLI help improvements: `--reanalyze` default hint, `--continue-from`
  placement guidance, benchmark mode in pre-run log, `[COST WARNING]` and
  `[CANARY WARNING]` prefixes standardized.

---

## 0.2.0

### Features

- **Introspect** — Autonomous self-improvement loop. An LLM optimizer
  analyzes benchmark gaps and proposes changes to prompts, example queries,
  Datalog rules, source code, and ML model hyperparameters. Improvements are
  kept; regressions are reverted automatically. Inspired by Karpathy's
  autoresearch. See [reports/introspect-development-2026-03-28.md](reports/introspect-development-2026-03-28.md).
- **ML query routing model** — On-device feedforward network predicts which
  Datalog queries to try for a given question. Trained via introspect, runs at
  zero token cost. Integrated into the ask agent as optional hints.
- **Multi-repo evaluation** — Introspect evaluates across multiple repos to
  prevent overfitting prompts to a single codebase.
- **Async MCP introspect** — `noumenon_introspect_start`, `_status`, `_stop`
  tools for background optimization runs.
- **Internal meta database** — Dedicated Datomic database (`noumenon-internal`)
  for cross-repo introspect history, with 5 named Datalog queries.
- **Ask agent improvements** — Prompt caching (Anthropic API), parallel tool
  execution, column headers in query results, iteration-aware budget warnings,
  partial answers on budget exhaustion with resumable sessions.
- **Issue reference extraction** — Commits now extract issue refs (`#123`,
  `PROJ-456`, URLs) into `:commit/issue-refs`.
- **Scoped re-analysis** — `--reanalyze` flag for `analyze` command supports
  `all`, `prompt-changed`, `model-changed`, and `stale` modes.

### Fixes

- Pin Clojure 1.12.3 in deps.edn to fix uberjar compilation (tools.build
  resolves 1.11.2 transitively, breaking AOT with neanderthal).
- Python relative import handling in `enrich`.
- Rust `use` statement capture alongside `mod` declarations.
- Analysis prompt improvements: tighter confidence scoring, segment naming,
  call-names accuracy, safety concern calibration.
- Ask agent prompt hardening against hedge paragraphs and answer quality issues.
- Numerous introspect robustness fixes (path traversal blocking, TOCTOU
  mitigation, exception recovery, formatting-preserving revert).

### CLI Commands

Added `introspect` subcommand with `--max-iterations`, `--max-hours`,
`--max-cost`, `--target`, `--eval-runs`, `--git-commit`, and `--verbose` flags.

### Dependencies

- Added `uncomplicate/deep-diamond` 0.43.0 and `uncomplicate/neanderthal` 0.61.0
  (ML model training).
- Pinned `org.clojure/clojure` 1.12.3.

---

## 0.1.0

First public release of Noumenon, a Datomic-backed knowledge graph for
AI-assisted codebase understanding.

### Features

- **Import pipeline** — Import git history (commits, files, directories) into a
  Datomic knowledge graph. Supports local repos and git URLs with automatic
  cloning.
- **LLM analysis** — Enrich the knowledge graph with semantic metadata
  (complexity, safety concerns, purpose) via configurable LLM providers (GLM,
  Claude API, Claude CLI).
- **Import graph extraction** — Deterministic cross-file import/dependency
  extraction for Clojure, Elixir, Erlang, Python, JavaScript/TypeScript, Rust,
  and Java.
- **Incremental update** — `update` and `watch` commands detect HEAD changes and
  incrementally update the knowledge graph (import + enrich, optionally
  re-analyze).
- **Named Datalog queries** — EDN-defined reusable queries with parameterization
  and a built-in rule library.
- **Agentic query layer** — `ask` command for natural-language questions answered
  via iterative Datalog querying with LLM-driven refinement.
- **MCP server** — `serve` command exposes all capabilities as MCP tools for
  external AI agents. Auto-updates stale databases before queries.
- **Benchmark framework** — Evaluate knowledge graph efficacy with deterministic
  scoring and optional LLM-judged evaluation. Supports checkpointing, resume,
  and budget guardrails.
- **Concurrent processing** — Configurable parallelism for analysis (`--concurrency`,
  default 3) and enrichment (`--concurrency`, default 8).
- **Database management** — `list-databases` command with entity counts, pipeline
  stage tracking, and cost reporting. `--delete` flag for cleanup.
- **Unified CLI and MCP interface** — Consistent naming and behavior across CLI
  subcommands and MCP tools. CLI flags map directly to MCP parameters.

### CLI Commands

`digest`, `import`, `analyze`, `enrich`, `update`, `watch`, `query`, `ask`,
`show-schema`, `status`, `list-databases`, `serve`, `benchmark`

