# Changelog

All notable changes to Noumenon are documented in this file.

## 0.4.0

Codebase-level architectural synthesis — the macro complement to per-file analysis.

### New

- **`synthesize` command** — Queries the knowledge graph for file summaries,
  import edges, and directory structure, then uses an LLM to identify logical
  components, classify files architecturally (layer, category, patterns, purpose),
  and map component dependencies. Language-agnostic.
- **Component entities** — `component/name`, `component/summary`, `component/purpose`,
  `component/layer`, `component/category`, `component/patterns`, `component/complexity`,
  `component/subsystem`, `component/depends-on`. Files link via `arch/component`.
- **9 new named queries** — `components`, `component-files`, `component-dependencies`,
  `component-dependents`, `component-authors`, `component-churn`, `component-bus-factor`,
  `cross-component-imports`, `subsystems`.
- **3 new Datalog rules** — `component-file`, `component-segment`, `component-commit`.
- **Top-down query strategy** — Ask agent now starts at component level for
  architectural questions, drilling to files and segments as needed.
- **Architectural hints** — File-level analysis captures `architectural-notes`
  and bundles hints as `sem/synthesis-hints` for the synthesize step.
- **Introspect integration** — Optimizer sees component topology for better
  self-improvement decisions.
- **Pipeline integration** — `digest` runs synthesize between analyze and benchmark.
  New `--skip-synthesize` flag.

## 0.3.1

Security and UX hardening release following a full codebase audit.

### Security

- **Path traversal fix** — `DELETE /api/databases/:name` validated against
  directory traversal (`..`, `/`). Previously, `DELETE /api/databases/..`
  could recursively delete the entire database directory.
- **Constant-time token comparison** — Bearer token auth uses
  `MessageDigest/isEqual` instead of string equality, preventing timing
  side-channel attacks.
- **HTTPS by default for remote hosts** — `noum --host` uses `https://` for
  non-localhost connections. Pass `--insecure` for plaintext HTTP.
- **Token passed via env var** — Daemon subprocess receives auth token via
  `NOUMENON_TOKEN` environment variable instead of `--token` CLI arg, hiding
  it from `ps aux`.
- **Config file permissions** — `config.edn` set to 600 (owner-only) on read,
  matching `daemon.edn` behavior.
- **HTTP input validation** — Added length caps on query params (4096 chars),
  `run_id` fields (256 chars), and layer allowlist validation, matching the
  MCP-side guards.
- **Error message sanitization** — HTTP error responses no longer leak internal
  filesystem paths.
- **Docker localhost default** — Container binds to `127.0.0.1` by default
  (was `0.0.0.0`), so it starts without requiring a token.
- **Installer integrity** — Dockerfile downloads Clojure installer to file
  before executing (no more `curl | bash`). Release pipeline publishes
  `.sha256` sidecar files; `install.sh` verifies binary integrity after
  download.
- **Introspect session management** — HTTP introspect endpoint now registers
  sessions (status/stop work) and enforces the same concurrency cap (5) as
  the MCP path.

### Fixes

- **SSE error propagation** — Launcher correctly surfaces server-side SSE
  error events as errors (exit code 1) instead of wrapping them as success.
- **Non-SSE error handling** — Non-2xx responses on SSE endpoints (e.g. 401)
  are now parsed as JSON errors instead of being fed to the SSE parser.
- **`noum serve --token`** — Token flag now passed through to JVM process.
- **URL-decoded path params** — Database names with special characters
  correctly resolved in HTTP API routes.
- **JRE spinner cleanup** — Extraction spinner stopped on download failure.
- **Analyze provenance** — Correct `resolved-model` stored on parse error
  retry (was using retry model instead of first call's model).
- **Empty database on lookup** — `lookup-repo-uri` no longer creates empty
  databases as a side effect when resolving unknown names.
- **Enrich count** — `noum databases` now shows the number of dependency
  edges for enrich (was showing transaction count).

### UX

- **Relative path resolution** — All commands now resolve relative repo paths
  (e.g. `noum digest myrepo`) to absolute before sending to the daemon.
  Previously, relative paths failed because the daemon runs in a different
  working directory.
- **Unified path/name handling** — `status`, `schema`, and `delete` now
  accept filesystem paths in addition to database names, matching all other
  commands.
- **Delete confirmation** — `noum delete` now prompts for y/N confirmation.
  Pass `--force` to skip in scripts.
- **First-run download notice** — JRE and JAR downloads show size estimates
  before starting.
- **Setup idempotency** — `noum setup` reports "already configured" on re-run
  instead of silently overwriting.
- **Setup path hint** — `noum setup code` prints the absolute path of
  `.mcp.json` and reminds users to run from the project directory.
- **Watch resilience** — Watch loop prints warnings on failure and exits after
  3 consecutive failures (was silently continuing forever).
- **Ask without quoting** — `noum ask /repo what is this` joins unquoted
  words (previously only used the first word as the question).
- **History usage** — Help now shows two forms: `noum history rules` and
  `noum history prompt <name>`, with available prompt names listed.
- **Ping recovery hint** — Failure message suggests `noum start`.
- **Start feedback** — Reports "already running" when daemon is up.
- **Stop feedback** — Prints "Daemon not running." instead of silent no-op.
- **Human-readable uptime** — `noum ping` shows `3m 12s` instead of raw
  milliseconds.
- **Version to stderr** — Consistent with all other informational output.
- **Upgrade accuracy** — Summary changed to "Update noumenon.jar (re-run
  installer to update noum)". Checks version before downloading.
- **Immediate spinner** — SSE commands show a spinner immediately while
  waiting for the first server event, instead of a blank cursor.
- **Indeterminate progress** — SSE progress with total=0 (digest step
  transitions) shows a spinner instead of silent gaps.
- **UTF-8 fallback** — TUI table separators, progress bars, and spinners
  fall back to ASCII on non-UTF-8 terminals.
- **Shell-aware install** — `install.sh` detects bash/zsh/fish and prints
  the correct PATH config file.
- **Removed `install` command** — Users install Claude Desktop/Code via their
  own official installers.
- **Removed `open` command** — Was listed as "future" in help but failed
  when run.

---

## 0.3.0

### Features

- **`noum` CLI launcher** — Self-contained Babashka binary with zero external
  dependencies. Auto-downloads JRE and backend on first use. 30 one-word
  commands with a custom TUI (spinner, menu, progress bar, table) built on
  JLine3. See [reports/noum-launcher-development-2026-03-29.md](reports/noum-launcher-development-2026-03-29.md).
- **HTTP daemon API** — 22 REST endpoints on localhost via http-kit. Bearer
  token auth, SSE progress streaming. Three frontends share one backend:
  `noum` TUI, MCP server, future GUI app.
- **SSE progress streaming** — All long-running endpoints (import, analyze,
  enrich, digest, benchmark, introspect) stream progress events via
  `Accept: text/event-stream`. TUI progress bars driven by SSE in real-time.
- **MCP progress notifications** — Sends `notifications/progress` JSON-RPC
  messages during long-running tool calls when client provides `progressToken`.
- **Shared introspect sessions** — Runs started via MCP can be monitored and
  stopped via HTTP (`noum` CLI), and vice versa.
- **`--host` for remote backends** — `noum --host server:7891 --token abc`
  connects to a remote daemon. Skips local JRE/JAR download.
- **Docker image** — 167MB Alpine image with custom jlink JRE (7 modules).
  Non-root execution, auth required for network access. Published to
  `ghcr.io/leifericf/noumenon` on each release.
- **`noum setup`** — Auto-configures MCP for Claude Desktop and Claude Code.
- **`noum install`** — Installs Claude Desktop and/or Claude Code.
- **Config file** — `~/.noumenon/config.edn` for persistent defaults
  (host, token, provider, model). CLI flags override.
- **Database name resolution** — Commands that accept `<repo>` now also accept
  a database name (reverse lookup via `:repo/uri`).
- **OpenAPI spec** — Hand-written OpenAPI 3.1 specification at `docs/openapi.yaml`.

### CI/CD

- Multi-platform release pipeline: 4 `noum` binaries (macOS arm64, Linux
  arm64/x86_64, Windows x86_64) built via Babashka self-contained executable.
- Three-tier release validation: dry-run (`workflow_dispatch`), pre-release
  (`v*-rc*` tags — draft release, no Homebrew/Scoop/Pages), and final release.
- Integration tests on clean macOS and Linux GitHub Actions runners.
- Automated Homebrew formula and Scoop manifest updates on release.
- GitHub Pages deployment gated on release tags (no longer deploys on push).
- Docker image build and push to ghcr.io on release tags.

### Security

- Daemon refuses to bind to non-localhost without `--token` or `NOUMENON_TOKEN`.
- Token resolved from env var to avoid process-list leaks.
- `daemon.edn` written with owner-only file permissions (600).
- Docker image runs as non-root user.

### Breaking

- Removed `uncomplicate/deep-diamond` and `uncomplicate/neanderthal` from
  dependencies (unused, reduced uberjar from 350MB to 16MB). ML model
  training uses pure Clojure.

---

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

