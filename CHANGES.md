# Changelog

## Unreleased

### Added

- **Branch-aware graph foundation** — every database now records which branch it represents. New `:branch/name`, `:branch/kind` (`:trunk` / `:feature` / `:release` / `:unknown`), `:branch/vcs`, and a tuple identity `:branch/repo+name` are populated automatically on every `update`. Repos point to their current branch via `:repo/branch`.
- **Content-addressed file identity** — `:file/blob-sha` is now imported from `git ls-tree` for every file, enabling content-based comparisons and cache lookups. Existing files lazy-fill on next sync.
- **Local delta databases** — `noum delta-ensure <repo> --basis-sha <sha>` (or `POST /api/delta/ensure`) materializes a sparse Datomic DB at `~/.noumenon/deltas/<repo>__<branch>__<basis>` containing only files added/modified/deleted between the trunk basis and the current HEAD. Deletions are recorded as `:file/deleted? true` tombstones. Delta DBs link back to their parent via `:branch/basis-sha`, `:branch/parent-host`, and `:branch/parent-db-name`.
- **Federated trunk + delta queries** — a subset of named queries are tagged `:federation-safe?` and accept `:exclude_paths` so the daemon can return trunk results minus rows the launcher will overlay from a delta DB. New endpoint `POST /api/query-federated` does the merge in a single roundtrip; new flag `noum query <name> <repo> --federate --basis-sha <sha>` opts in. Federation-safe queries seeded so far: `orphan-files`, `complex-hotspots`, `import-hotspots`, `hotspots`, `ai-authored-segments`, `bug-hotspots`, `files-by-churn`. Non-federation-safe queries return trunk-only with a banner.
- **Auto-federation in `noum query` / `noum ask`** — when the active connection is hosted and local HEAD has diverged from the trunk DB's `:repo/head-sha`, the launcher transparently rewrites a plain `noum query <name> <repo>` into the federated path against a local delta and emits a yellow banner. `noum ask` emits the banner but does not federate (no federated ask endpoint in v1). Per-call opt-out with `--no-auto-federate`; global opt-out with `noum settings set federation/auto-route false`.
- **`noumenon_query_federated` MCP tool** — exposes `/api/query-federated` to MCP clients. Materializes the delta on demand from `(repo_path, basis_sha)` and returns the merged result.
- **`noum analyze --no-promote` (and MCP `no_promote`)** — bypasses the content-addressed promotion cache and always invokes the LLM. Useful when re-validating the cache itself.
- **`bb prune-deltas`** — interactive GC for stale local delta DBs under `~/.noumenon/deltas/noumenon/`. Lists each delta with size, classifies as `:live` / `:trunk-missing` / `:unparseable`, and prompts before deleting trunk-missing entries.
- **Content-addressed analysis promotion** — when a file's `:file/blob-sha` equals a previously-analyzed blob in the same DB whose `:prov/prompt-hash` and `:prov/model-version` match the current run, `noum analyze` copies the donor's `:sem/*` and `:arch/*` attrs onto the recipient and skips the LLM call. Donor lineage is preserved via `:prov/promoted-from`. Pass `--no-promote` to bypass the cache. The analyze summary surfaces a `:files-promoted` counter alongside `:files-analyzed`.

### Changed

- **No `:file/deleted?` in trunk transactions** — trunk DBs hard-retract deleted files as before; only delta DBs use tombstones. A guard asserts this in `retract-deleted-files!`.
- **Schema files** — added `resources/schema/branch.edn`. New attrs `:file/blob-sha`, `:file/deleted?`, `:prov/promoted-from` in existing schema files. `:tx/op` doc lists `:promote`; `:tx/source` doc lists `:promoted`. New `:artifact.query/federation-safe?` flag.
- **Centralized input length caps** — `noumenon.util` now exports the shared length limits (`max-repo-path-len`, `max-question-len`, `max-query-name-len`, `max-branch-name-len`, `max-host-len`, `max-db-name-len`, `max-run-id-len`, `max-params-count`, `max-param-key-len`, `max-param-value-len`) plus a `validate-params!` helper. HTTP handlers and the MCP layer now consume them so the two surfaces stay in lockstep.

### Fixes

- **Delta DB collision on look-alike branch names** — `feat/foo` and `feat-foo` both sanitize to `feat-foo` for the on-disk db-name; without disambiguation, branch-switching between the two would silently overwrite the same delta DB. The db-name now appends `sha256(branch-name)[0..6]` so collisions resolve to different DBs.
- **Federation merge keeps trunk history for modified files** — the earlier "exclude all delta paths from trunk + append delta rows" merge made modified files vanish from churn-based queries because the delta DB has no commits to carry their history. Tombstone-only merge keeps trunk's authoritative history while still respecting branch deletions.
- **`bb prune-deltas` walks the right directory** — Datomic-Local stores DBs under `<storage>/<system>/<db-name>/`, so the actual deltas live under `~/.noumenon/deltas/noumenon/`. The previous parent-dir walk would have surfaced the system dir itself as a single "unparseable" entry — and a `y` at the prompt would have nuked every delta DB on the machine.
- **Empty / dot-only branch names** — `delta-db-name` falls back to `detached` for nil, blank, or dot-only branch inputs (which would otherwise produce empty / `.` / `..` directory names — the latter resolve to parent dirs in tools that aren't expecting them as literal path components).
- **`ensure-private!` uses 700 on directories** — the launcher's owner-only permission helper was applying `600` (no execute bit) to directories, making them unenterable. Now `rwx------` for dirs, `rw-------` for files.
- **`validate-string-length!` returns 400, not 500** — the `:status` key on the thrown ex-info now lets the HTTP handler surface a clean `400 Bad Request` instead of falling through to a generic `500`.
- **Idempotent branch upsert** — `update-head-and-branch!` resolves the existing repo + branch eids before transacting, so re-running `delta-ensure` (or any sync) doesn't trip the `:branch/repo+name` unique constraint with a fresh tempid.
- **Branch / parent_host / parent_db_name / query_name length caps on `/api/delta/ensure` and `/api/query-federated`** — overlong values now return `400` instead of being persisted or echoed back unchecked.

### Notes

- Datomic schema is additive: no migrations runner, no version stamps. `ensure-schema` re-transacts every connect; existing DBs pick up new attrs and queries pick up `:federation-safe?` on next start.
- Delta DBs require a co-located daemon in this release. Cross-machine federation (remote daemon, launcher-side delta) is deferred.
- Promotion is same-DB only in this release. Cross-DB promotion (delta lookups against trunk's history) is deferred.

## 0.7.0

### Changed

- **Repo split** — The Electron desktop app moved to [`leifericf/noumenon-app`](https://github.com/leifericf/noumenon-app); the website moved to [`leifericf/noumenon-site`](https://github.com/leifericf/noumenon-site). This repo keeps the daemon, `noum` CLI, and OpenAPI spec. History was preserved on both new repos via `git filter-repo`.
- **OpenAPI spec relocated** — Canonical source moved from `docs/openapi.yaml` to `resources/openapi.yaml` so it ships inside the daemon JAR and can be served via `io/resource`. The website mirrors it daily via a cron-pull workflow.
- **`noum ui` auto-updater** — Now downloads the packaged Electron app from `leifericf/noumenon-app` releases (was `leifericf/noumenon`).
- **`noum ui` dev mode** — Resolves a noumenon-app source checkout in this order: `$NOUMENON_APP_ROOT` → `$NOUMENON_ROOT/../noumenon-app` sibling → fall back to installed app. The previous `ui/` child directory is no longer valid.
- **Core CI/Release workflows** — Removed the `ui` job and the `build-electron`/`deploy-pages` release jobs. CLI distribution (`update-homebrew`, `update-scoop`) and Docker publish remain in this repo.

## 0.6.2

### Changed

- **HTTP-only provider support** — Removed Claude CLI provider support entirely. Supported providers are now API-based (`glm`, `claude-api`, with `claude` aliasing to `claude-api`).
- **Strict model selection** — LLM operations now require an explicit model source: pass `--model` or configure provider `:default-model`; no implicit fallback model is selected.
- **Provider credential policy** — Removed legacy file-based credential fallback; provider credentials now resolve from `NOUMENON_LLM_PROVIDERS_EDN` and process environment variables.
- **Analysis/synthesis provenance** — LLM transactions now record provider and model provenance via `:tx/provider` and `:tx/model-source` metadata.

### Fixes

- **Provider migration errors** — Using removed `claude-cli` now fails with explicit migration guidance to `claude-api`/`claude`.
- **API schema/docs alignment** — OpenAPI and provider-config docs now reflect API-only provider support.

## 0.6.1

### New

- **Provider/model catalog commands** — Added `noum llm-providers` and `noum llm-models` for discovering configured providers, provider defaults, and available models.
- **MCP provider/model catalog tools** — Added `noumenon_llm_providers` and `noumenon_llm_models` with help/schema metadata so agents can inspect defaults and model availability without reading config files.

### Changed

- **Provider default selection** — Noumenon now resolves one global default provider via `NOUMENON_DEFAULT_PROVIDER`, then `:default-provider` in `NOUMENON_LLM_PROVIDERS_EDN`, then built-in fallback.
- **Provider model policy** — Each provider can declare `:models` plus a single `:default-model`; model selection now resolves per-provider defaults when `--model` is omitted.
- **Dynamic model discovery** — `llm-models`/`noumenon_llm_models` prefer provider API discovery (`:models-path`, with known defaults) and fall back to configured `:models` when discovery is unavailable.

## 0.6.0

### New

- **Provider-agnostic LLM config** — Added `NOUMENON_LLM_PROVIDERS_EDN` support for API providers, allowing per-provider `:base-url` and `:api-key` configuration (for example: `:glm`, `:claude-api`, gateway-backed providers) through one canonical EDN map.

### Changed

- **Runtime mode policy for secrets** — Added `NOUMENON_RUNTIME_MODE=local|service` (default `local`). In `service` mode, file-based credential fallback is disabled and only process env secrets are used.
- **Provider resolution precedence** — API providers now resolve config in this order: canonical EDN map entry, legacy env var fallback, then built-in default base URL (API keys are never defaulted).
- **Centralized provider resolution** — API-provider invocation now routes through a normalized resolver in `src/noumenon/llm.clj` returning `{:base-url :api-key}` to reduce provider-specific branching.

### Fixes

- **Service URL hardening** — API provider base URLs are now validated as absolute URLs, and `service` mode requires `https`.
- **Safe error handling for credentials** — Missing-key failures are explicit while avoiding secret value leakage in error messages.
- **Optional base URL allowlist** — Added `NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN` support to restrict provider base URL hosts/patterns.

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
