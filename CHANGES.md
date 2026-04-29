# Changelog

## Unreleased

## 0.5.6

### New

- **Pipeline selectors** — `analyze`, `enrich`, `update`, and `digest` now accept `--path`, `--include`, `--exclude`, and `--lang` to scope work to selected files/directories/languages. Added parity across JVM CLI, launcher (`noum`), HTTP API, and MCP tool schemas.
- **OpenAPI selector schema** — Added `PathSelectors` to `docs/openapi.yaml` and wired it into analyze/enrich/update/digest endpoint request bodies.

### Changed

- **Prompt/model drift behavior** — Drift is now advisory by default. Noumenon logs recommended re-analysis counts but does not auto re-analyze unless you explicitly pass `--reanalyze prompt-changed` or `--reanalyze model-changed`.

### Fixes

- **MCP repo path mapping** — Remote MCP proxy now derives database names from local path semantics (e.g. `mino`) instead of org-repo remote URL synthesis (e.g. `leifericf-mino`), preventing status/query failures on path-to-db translation.
- **Launcher command help** — `noum help <command>` now renders command options (including `analyze`) so users can discover flags without leaving the CLI.

## 0.5.5

### New

- **Multi-repo introspect evaluation** — `extra_repos` parameter on MCP, HTTP, and CLI introspect commands. Evaluates prompt changes across multiple repos to reduce overfitting. Averages scores from primary + extra repos.
- **`introspect-skipped` query** — New named query exposes skipped iterations (parse failures, validation errors, gate failures) for diagnosing introspect issues.
- **Introspect status progress** — `noumenon_introspect_status` now shows current iteration number and last outcome message, not just elapsed time.

### Fixes

- **Cascading template expansion** — All prompt renderers (`agent`, `introspect`, `benchmark`, `analyze`, `synthesize`) switched from sequential `str/replace` to single-pass regex substitution. Previously, inserting a template that contained `{{placeholder}}` strings caused subsequent replacements to cascade, bloating prompts from 5K to 924K.
- **Stale chunked prompts** — `reseed` / bootstrap now uses `save-prompt!` which properly retracts old chunks before writing. Previously, a prompt bloated by introspect and stored as chunks survived reseeds because the raw upsert added `:template` without retracting stale `:chunks`.
- **EDN extraction from prose** — Introspect proposal parser now extracts the outermost `{...}` EDN map from optimizer responses that wrap the proposal in explanatory prose. Previously, the entire response was parsed as EDN, failing on any surrounding text.
- **Git commit on Datomic-only changes** — `git-commit-improvement!` no longer throws when introspect improves a Datomic-only target (examples, system-prompt, rules) that produces no filesystem changes. Previously, `git commit` exited 1 with "nothing to commit", which propagated as an exception inside `with-modification`, reverting the improvement.
- **Introspect error persistence** — Skipped iterations now store the raw optimizer response or error message in `:introspect.iter/error` for post-hoc diagnosis.

## 0.5.4

### Fixes

- **MCP daemon lock contention** — `noum serve` now auto-detects a running local daemon via `daemon.edn` and proxies tool calls to it instead of opening the database directly. Previously, the daemon's exclusive file lock caused every MCP tool call to fail with a generic "unexpected internal error."
- **MCP error messages** — Tool call errors now include the actual cause and tool name instead of "An unexpected internal error occurred." Database lock errors include actionable kill instructions and explicitly tell AI agents not to retry.
- **MCP proxy auth header** — Proxy mode no longer sends `Authorization: Bearer null` when connecting to a local daemon without a token.
- **Setup binary path** — `noum setup code` now resolves the `noum` binary via `PATH` (e.g. Homebrew at `/opt/homebrew/bin/noum`) instead of always hardcoding `~/.local/bin/noum`.
- **Demo release fallback** — `noum demo` now searches the 5 most recent GitHub releases for a demo tarball instead of only checking the latest. Prevents "not found" errors when a patch release ships without a new demo database.
- **Progress bar lifecycle** — The launcher's progress handler now resets the bar on completion and creates a new bar when the total changes. Fixes the flashing green bar during digest benchmark and spurious "✓ digest done." lines between steps.
- **Progress bar step labels** — Digest sub-steps (analyze, benchmark) tag their SSE progress events with `:step`, so the bar shows "✓ analyze done." instead of "✓ digest done."
- **Synthesize progress event** — Added missing `:current`/`:total` keys to the synthesize progress event, preventing NPE in the launcher handler.
- **Digest output formatting** — Nested result maps (analyze, benchmark, synthesize) are now printed as an indented tree with floats rounded to 2 decimal places, instead of raw EDN.

## 0.5.3

### Fixes

- **Stale JAR auto-update** — `jar/ensure!` now reads `version.edn` from the installed JAR and compares against the launcher version. On mismatch, stops the daemon, downloads the matching release, and restarts fresh. Previously, an existing JAR was never re-checked, so Homebrew launcher updates silently ran against an old backend.
- **Daemon bounce on upgrade** — `noum upgrade` now stops the running daemon after downloading a new JAR, so the next command starts with the updated code.
- **Version def shared** — Moved from `main.clj` (private) to `paths.clj` so both `main` and `api` pass it to `jar/ensure!`.

## 0.5.2

Security hardening, bug fixes, and UX polish.

### Security

- **EDN read-eval disabled** — `*read-eval*` bound to false in introspect code verification; `{:readers {}}` added to all `edn/read-string` calls parsing LLM responses, checkpoints, and external data
- **CORS restricted** — `file://` origins now require explicit `NOUMENON_ALLOW_FILE_ORIGIN` env var
- **Admin-only endpoints** — `/api/query-raw` and `/api/ask/sessions` added to admin-only prefixes
- **SSRF hardening** — CGN range `100.64.0.0/10` added to blocked IP patterns; `--` separator in git clone commands; proxy host URL validation
- **Subprocess timeouts** — Python, Node, C, and Elixir import extractors now timeout after 30 seconds
- **Hook state directory** — Moved from world-writable `/tmp` to user-private `~/.noumenon/tmp/`
- **CI tag validation** — `GITHUB_REF_NAME` validated as semver before shell substitution in release workflow
- **Credential handling** — Directory permissions set before writing config; warning on `--token` + `--insecure`
- **MCP proxy** — Admin tool forwarding logged; read-only flag respected for `git_commit`; SSRF check on proxy host
- **Electron navigation** — Restricted to exact daemon port instead of any localhost port

### Fixes

- **MCP digest skip flag** — Synthesize step was gated on `skip_analyze` instead of `skip_synthesize`
- **Merge retry usage** — `invoke-merge` now accumulates LLM token usage from both attempts
- **Agent nil dispatch** — Guard against nil tool dispatch when LLM sends only `:reflect`
- **Benchmark stop-flag** — `run-benchmark!` accepts external stop-flag for HTTP introspect sessions
- **Database deletion** — Removed post-Datomic filesystem deletion that could corrupt shared storage
- **Session limit race** — `register-ask-session!` enforced atomically via single `swap!`
- **Leaf file re-enrichment** — Files with no imports now get empty `[]` for `:file/imports` to prevent redundant re-processing
- **Test speed** — 429 retry test binds `*max-retries*` to avoid 6-second sleep
- **Limit param coercion** — HTTP query endpoints coerce string `:limit` to long
- **History help text** — Replaced hardcoded prompt names with dynamic hint

### UX Improvements

- **CLI** — Spinner cleanup on API errors; actionable watch failure messages; dynamic prompt listing; post-setup instructions; upgrade progress spinner; explicit "Daemon: not running" message
- **TUI** — Non-interactive auto-select warns to stderr; confirm defaults to false for safety
- **UI** — Feedback polarity from event data; in-app delete confirmation; active nav indicator; flex layout for ask results; theme cached in localStorage; graph loading skeleton; empty table/history states; truncation with tooltips; formatted introspect deltas; error state on network failure
- **MCP** — Digest description lists all pipeline steps; `skip_synthesize` in schema; search clarifies embed prerequisite; list_queries mentions required parameters
- **Sidebar** — Unicode icons replace ambiguous single letters
- **Benchmark** — "Select 2 runs to compare" hint text

## 0.5.1

TUI hotfix.

### Fixes

- **Arrow key navigation** — Menu selector now uses `cond` instead of `case` for escape sequence matching (Babashka's `case` doesn't resolve var references)
- **Menu line breaks** — Raw terminal mode uses `\r\n` instead of `\n` for correct vertical layout
- **Back navigation** — Selecting "← Back" no longer leaves a stray line in the console
- **Key input** — Reads from `/dev/tty` directly instead of `System/in` for reliable raw-mode input

### New

- **`embed` command** in launcher — help text and Pipeline menu entry

## 0.5.0

TF-IDF vector search, hierarchical synthesis, and cross-repo benchmarks.

### New

- **TF-IDF vector search** — `embed` pipeline stage builds a vocabulary and vector index from file and component summaries. Pure Clojure, no external dependencies beyond Nippy for serialization.
- **`noumenon_search` MCP tool** — Semantic file/component search without the agent loop. Zero LLM calls, millisecond responses.
- **Ask agent seeding** — The ask agent is seeded with TF-IDF search results before querying the knowledge graph, giving it a warm start on relevant files and components.
- **`embedded` benchmark layer** — Measures TF-IDF retrieval quality alongside raw and full KG layers.
- **`:full` layer enriched** — Benchmark's full layer now includes both KG query results and TF-IDF search results when available — representing everything Noumenon has.
- **Hierarchical map-reduce synthesis** — Repos with 250+ files are synthesized per directory partition, then merged. Fixes guava (3,333 files) and redis (1,754 files) which previously returned 0 components.
- **Session seed logging** — Ask sessions persist TF-IDF seed results to Datomic for analytics.

### Changed

- **Neural net input** — Query routing model now uses TF-IDF vectors instead of bag-of-words, giving it term-importance weighting. Existing trained models require retraining.
- **MCP digest handler** — Now includes synthesize and embed steps (was missing both).
- **Raw context limit** — Reduced from 800K to 500K chars to stay within the ~200K token API limit.
- **Default benchmark provider** — Falls back to GLM instead of Claude CLI.

### Fixes

- MCP benchmark handler wasn't passing model-config, causing raw layer to silently fail via Claude CLI
- Synthesize retraction + creation in same Datomic transaction caused datoms-conflict on re-synthesis
- MCP synthesize and digest handlers weren't seeding new prompt templates
- Recursive directory partitioning caused StackOverflowError on flat directory structures (redis)
- Merge synthesis validator rejected components with `:source-components` instead of `:files`

### Benchmarks

Cross-repo benchmark (8 repos, 7 languages, 22 deterministic questions each):

| Metric | Without Noumenon | With Noumenon | Improvement |
|--------|-----------------|---------------|-------------|
| Accuracy | 20% | 53% | 2.7x |
| Token cost | 37K | 7K | 80% cheaper |
| Speed | 13.6s | 6.1s | 55% faster |

## 0.4.0

Architectural synthesis, visual desktop UI, and interactive CLI.

### New

- **Interactive TUI** — `noum` with no arguments enters a menu-driven interface. Browse commands by category, select repositories/sessions/queries from live data. Smart arg collection for all commands including introspect sub-actions.
- **Visual desktop UI** — Electron + ClojureScript app with force-directed graph visualization. Three-level drill-down (components, files, segments), floating Ask overlay with streamed reasoning, @-mention autocomplete. Launch with `noum open`.
- **`synthesize` command** — Identifies logical components from file summaries, import edges, and directory structure. Maps component dependencies, layers, and categories. Language-agnostic.
- **Component entities** — `component/name`, `component/summary`, `component/layer`, `component/category`, `component/depends-on`. Files link via `arch/component`.
- **9 new named queries** — `components`, `component-files`, `component-dependencies`, `component-dependents`, `component-authors`, `component-churn`, `component-bus-factor`, `cross-component-imports`, `subsystems`.
- **`noum demo`** — Pre-built knowledge graph for instant querying without credentials.
- **Top-down query strategy** — Ask agent starts at component level for architectural questions.

### Security

- Electron renderer uses contextBridge (no executeJavaScript)
- CORS restricted to Electron origin
- Bounded `edn/read-string` on LLM-sourced strings

### Fixes

- Inline markdown parser duplication bugs
- Concurrent SSE submission guard
- Electron namespace collision with Replicant fragments
- Unbounded memoize memory leak in graph builders

## 0.3.1

Security and UX hardening.

- **Path traversal fix** on `DELETE /api/databases/:name`
- **Constant-time token comparison** for auth
- **HTTPS by default** for remote `--host` connections
- **Token via env var** instead of CLI arg (hidden from `ps aux`)
- **SSE error propagation** — errors surface correctly instead of wrapping as success
- **Delete confirmation** with `--force` to skip
- **Relative path resolution** for all commands
- 20+ additional security, UX, and robustness fixes — see [git history](https://github.com/leifericf/noumenon/commits/main)

## 0.3.0

`noum` CLI launcher, HTTP daemon, and Docker image.

- **`noum` binary** — Self-contained Babashka launcher. Auto-downloads JRE and backend. 30 commands with custom TUI (spinner, menu, progress bar, table).
- **HTTP daemon** — 22 REST endpoints, bearer token auth, SSE progress streaming.
- **Docker image** — 167MB Alpine, non-root, auth required for network access.
- **`noum setup`** — Auto-configures MCP for Claude Desktop and Claude Code.
- **OpenAPI spec** at `docs/openapi.yaml`

## 0.2.0

Introspect (autonomous self-improvement) and ML query routing.

- **Introspect loop** — LLM optimizer proposes changes to prompts, examples, rules, and code. Keeps improvements, reverts regressions. Multi-repo evaluation to prevent overfitting.
- **ML query routing** — On-device neural network predicts which Datalog queries to try. Trained locally at zero token cost.
- **Issue reference extraction** from commits (`#123`, `PROJ-456`)
- **Scoped re-analysis** — `--reanalyze` with `all`, `prompt-changed`, `model-changed`, `stale` modes

## 0.1.0

First public release.

- Import pipeline (git history into Datomic knowledge graph)
- LLM analysis (semantic metadata: complexity, safety, purpose)
- Import graph extraction (10+ languages)
- Named Datalog queries with parameterization and rules
- Agentic `ask` command (natural-language via iterative Datalog)
- MCP server (`noum serve`)
- Benchmark framework with checkpointing and resume
- Concurrent processing (configurable parallelism)
