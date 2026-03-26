# Changelog

All notable changes to Noumenon are documented in this file.

## 0.1.0 — Initial Public Release

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
- **Incremental sync** — `sync` and `watch` commands detect HEAD changes and
  incrementally update the knowledge graph (import + postprocess, optionally
  re-analyze).
- **Named Datalog queries** — EDN-defined reusable queries with parameterization
  and a built-in rule library.
- **Agentic query layer** — `ask` command for natural-language questions answered
  via iterative Datalog querying with LLM-driven refinement.
- **MCP server** — `serve` command exposes all capabilities as MCP tools for
  external AI agents. Auto-syncs stale databases before queries.
- **Benchmark framework** — Evaluate knowledge graph efficacy with deterministic
  scoring and optional LLM-judged evaluation. Supports checkpointing, resume,
  and budget guardrails.
- **Concurrent processing** — Configurable parallelism for analysis (`--concurrency`,
  default 3) and postprocessing (`--concurrency`, default 8).
- **Database management** — `list-databases` command with entity counts, pipeline
  stage tracking, and cost reporting. `--delete` flag for cleanup.
- **Unified CLI and MCP interface** — Consistent naming and behavior across CLI
  subcommands and MCP tools. CLI flags map directly to MCP parameters.

### CLI Commands

`import`, `analyze`, `postprocess`, `sync`, `watch`, `query`, `ask`,
`show-schema`, `status`, `list-databases`, `serve`, `benchmark`

### Supported Languages

| Tier | Languages |
|------|-----------|
| Full (deterministic imports) | Clojure |
| Import extraction | Elixir, Erlang, Python, JavaScript/TypeScript, Rust, Java |
| Analysis only | All languages with text files |
