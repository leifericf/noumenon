# Noumenon

A Datomic-backed knowledge graph for codebase understanding.

Noumenon imports a Git repository into Datomic, enriches files with LLM-generated semantic metadata, and makes the result queryable through Datalog. The goal is to help both humans and AI agents reason about large codebases without loading raw source into context windows.

## Why this exists

Long-context prompting alone is not enough for reliable repository understanding at scale. Noumenon treats source code as structured data:

- Deterministic facts from Git and file structure (commits, authors, files, directories)
- Semantic annotations from LLM analysis (summaries, complexity, tags, architecture layer)
- Query-first workflows through Datomic + Datalog

This lets you ask targeted questions like:

- Which files are most complex?
- What files co-change most often?
- Which contributors touched a subsystem the most?

## Current status

This project is actively developed and currently CLI-first.

- JVM Clojure CLI entrypoint (`clj -M:run`)
- Datomic Local for storage
- One database per imported repository
- Named query support from EDN resources
- Benchmark tooling for custom benchmark and LongBench v2 code-repo tasks

## Requirements

- JDK 21+
- Clojure CLI (`clj`)
- Git
- Provider credentials depending on workflow:
  - `NOUMENON_ZAI_TOKEN` for GLM (HTTP API path)
  - `ANTHROPIC_API_KEY` for Claude API (HTTP API path)
  - Claude CLI installed for CLI provider path

## Installation

Clone the repository:

```bash
git clone <your-fork-or-this-repo-url>
cd noumenon
```

No additional build step is required.

## Quick start

Use the repo itself or another local Git repo as input.

### 1) Import deterministic facts

```bash
clj -M:run import /path/to/repo
```

This imports commit history plus file/directory structure into Datomic.

### 2) Run semantic analysis

```bash
clj -M:run analyze /path/to/repo --provider glm --model sonnet
```

This analyzes unanalyzed files and stores semantic metadata with provenance.

### 3) Check status

```bash
clj -M:run status /path/to/repo
```

### 4) Run a named query

```bash
clj -M:run query files-by-complexity /path/to/repo
```

## CLI reference

Run `clj -M:run --help` for full usage, or `clj -M:run <subcommand> --help` for subcommand-specific options.

```bash
clj -M:run <subcommand> [options] <repo-path>
```

Subcommands:

| Command | Purpose |
|---------|---------|
| `import` | Import git history and file structure |
| `analyze` | Enrich imported files with LLM-driven semantic analysis |
| `query` | Run a named Datalog query |
| `status` | Show repository counts in the graph |
| `agent` | Ask a question via iterative query + LLM workflow |
| `benchmark` | Run custom benchmark suite |
| `longbench` | Download/run/report LongBench v2 code-repo benchmark |

### Common options

- `--model <alias>` - Model alias (e.g. sonnet, haiku, opus)
- `--provider <name>` - Provider: `glm` (default), `claude-api`, `claude-cli` (alias: `claude`)
- `--max-cost <dollars>` - Stop when session cost exceeds threshold
- `--db-dir <dir>` - Override Datomic storage directory (default `data/datomic/`)
- `--verbose` / `-v` - Verbose output to stderr
- `--help` / `-h` - Show help (global or per-subcommand)
- `--version` - Print version

### Provider modes

Noumenon supports both CLI and HTTP provider paths. The default provider for all commands is `glm`.

- `glm` - HTTP API via Z.ai Anthropic-compatible endpoint
- `claude-api` - HTTP API via Anthropic Messages API
- `claude-cli` - local Claude CLI invocation (alias: `claude`)

## Named queries

Named queries are stored under `resources/queries/`.

Current query files include:

- `files-by-complexity`
- `files-by-layer`
- `top-contributors`
- `co-changed-files`
- `component-dependencies`

Each query is EDN and can optionally use shared rules from `resources/queries/rules.edn`.

## Development workflow

Useful commands:

```bash
# Run test suite
clj -M:test

# Lint
clj -M:lint

# Format check
clj -M:fmt check

# Auto-fix formatting
clj -M:fmt fix

# Start nREPL (port 7888)
clj -M:nrepl
```

## Benchmarks

Two benchmark paths exist:

- `benchmark` - project-specific benchmark flow
- `longbench` - LongBench v2 code repository understanding workflow

LongBench example:

```bash
clj -M:run longbench download
clj -M:run longbench run --provider glm --model sonnet
clj -M:run longbench results
```

## Agent examples (Ring)

Concrete examples using the local Ring checkout at `test-repos/ring`.

### 1) Highest complexity files

```bash
set -a && source .env && set +a && \
clj -M:run agent -q "Which files have the highest complexity?" \
  test-repos/ring --provider glm --max-iterations 20
```

Output (excerpt):

```edn
{:answer "...I cannot determine semantic complexity because :sem/complexity has not been populated...\n\nTop 10 Files by Line Count:\n1. ring-jetty-adapter/test/ring/adapter/test/jetty.clj — 1,027 lines\n2. CHANGELOG.md — 630 lines\n3. ring-core/src/ring/util/response.clj — 345 lines\n..."
 :status :answered
 :usage {:input-tokens 6835, :output-tokens 839, :iterations 13}}
```

### 2) Top contributors

```bash
set -a && source .env && set +a && \
clj -M:run agent -q "Who are the top contributors in Ring and what did they touch most?" \
  test-repos/ring --provider glm --max-iterations 20
```

Output (excerpt):

```edn
{:answer "Top Contributors in Ring\n\n1. James Reeves — 50 commits\n2. Eero Helenius — 4 commits\n...\nSummary: James Reeves is the clear primary author and maintainer..."
 :status :answered
 :usage {:input-tokens 5770, :output-tokens 724, :iterations 7}}
```

### 3) Available named queries

```bash
set -a && source .env && set +a && \
clj -M:run agent -q "What named queries are available in this repository?" \
  test-repos/ring --provider glm --max-iterations 20
```

Output (excerpt):

```edn
{:answer "...example queries include: co-changed-files, component-dependencies, files-by-complexity, files-by-layer, top-contributors..."
 :status :answered
 :usage {:input-tokens 106, :output-tokens 436, :iterations 1}}
```

Note: agent output quality depends on what has already been imported/analyzed into the Datomic database.

## Project structure

- `src/noumenon/` - application namespaces
- `resources/schema/` - Datomic schema EDN
- `resources/queries/` - named Datalog queries + reusable rules
- `resources/prompts/` - prompt templates
- `data/` - local runtime artifacts (Datomic and benchmark runs)
- `vision.md` - longer-form project vision and rationale

## License

MIT. See `LICENSE`.
