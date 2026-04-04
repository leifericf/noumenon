# Changelog

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
