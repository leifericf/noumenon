# Noumenon

**Precise, grounded answers about your codebase.**

Noumenon compiles git repositories into a multi-level [Datomic](https://www.datomic.com) knowledge graph — from git objects up through code segments, files, and architectural components — so that AI agents can answer codebase questions more accurately, faster, and at lower cost than scanning raw source files into context windows. Pre-compiled knowledge means fewer tokens, fewer iterations, and grounded facts instead of hallucinations.

See [noumenon.leifericf.com](https://noumenon.leifericf.com) for an overview.

## Installation

### Quick install (macOS / Linux)

```bash
curl -sSL https://noumenon.leifericf.com/install | bash
```

Or via Homebrew (macOS / Linux):

```bash
brew install leifericf/noumenon/noumenon
```

Or via Scoop (Windows):

```powershell
scoop bucket add noumenon https://github.com/leifericf/scoop-noumenon
scoop install noumenon
```

Or download a binary directly from [GitHub Releases](https://github.com/leifericf/noumenon/releases):
`noum-macos-arm64`, `noum-linux-arm64`, `noum-linux-x86_64`, `noum-windows-x86_64.exe`

The `noum` binary is self-contained — it automatically downloads a JRE and the Noumenon backend on first use. No Java knowledge required.

### Provider setup

Noumenon supports three LLM provider modes:

| Provider | Mode | What you need |
|---|---|---|
| `glm` (default) | HTTP API | `NOUMENON_ZAI_TOKEN` |
| `claude-api` | HTTP API | [`ANTHROPIC_API_KEY`](https://console.anthropic.com/settings/keys) |
| `claude-cli` (alias: `claude`) | Local CLI | [Claude Code](https://claude.ai/claude-code) installed and authenticated |

### Development setup

For contributing or running from source, see the [Development](#development) section.

## Quick Start

### Interactive mode

Run `noum` with no arguments to enter interactive mode — a menu-driven TUI that guides you through every command:

```bash
noum
```

Interactive mode presents a grouped command menu, then prompts for any required arguments (repository, query name, session, etc.) using selection menus populated from the daemon. No need to memorize commands or look up IDs.

### Try instantly (no LLM credentials needed)

```bash
noum demo
noum ask noumenon "Describe the architecture"
```

Downloads a pre-built knowledge graph of the Noumenon repo itself. Or build your own:

### 1) Import deterministic facts

```bash
noum import /path/to/repo
# or:
noum import https://github.com/ring-clojure/ring.git
```

### 2) Run semantic analysis

```bash
noum analyze /path/to/repo --provider glm --model sonnet
```

### 3) Build deterministic import graph

```bash
noum enrich /path/to/repo
```

### 4) Identify architectural components

```bash
noum synthesize /path/to/repo --provider glm
```

Or run the full pipeline at once: `noum digest /path/to/repo --provider glm`

### Open the visual UI

```bash
noum open
```

Launches the Electron desktop app with a force-directed graph visualization, three-level drill-down (components, files, code segments), and a floating Ask overlay. [Node.js](https://nodejs.org) must be installed.

### Keep the graph in sync

As the codebase changes, update the knowledge graph with the latest git state:

```bash
noum update /path/to/repo
noum watch /path/to/repo
```

`update` also works for first-time setup — it runs the full import if no database exists. On subsequent runs it detects HEAD changes and incrementally updates. The MCP server auto-syncs before queries.

### 5) Inspect status, databases, and queries

```bash
noum databases                              # list all imported repos
noum status myrepo                          # entity counts (use database name)
noum schema myrepo                          # show schema
noum queries                                # list available queries
noum query files-by-complexity /path/to/repo
```

### 6) Ask the graph a natural-language question

```bash
noum ask /path/to/repo "Which files are the biggest risk hotspots?"
```

## How It Works

Noumenon compiles a codebase into a queryable knowledge graph through four pipeline stages, each building on the last:

```mermaid
flowchart LR
  subgraph compile["Knowledge Compiler"]
    direction LR
    A[Import] --> B[Enrich]
    B --> C[Analyze]
    C --> D[Synthesize]
  end

  subgraph use["Query Interfaces"]
    direction TB
    E[Datalog queries]
    F[Ask]
    G[MCP tools]
  end

  D --> E
  D --> F
  D --> G

  subgraph improve["Self-Improvement"]
    H[Benchmark] --> I[Introspect]
  end

  D --> H
  I -.->|optimizes| F
```

| Stage | Sees | Produces | LLM? |
|-------|------|----------|------|
| **Import** | Git repository | Commits, files, authors, diffs, directories | No |
| **Enrich** | Source code | Resolved file-to-file import edges | No |
| **Analyze** (micro) | One file at a time | Summaries, code segments, complexity, smells, safety concerns | Yes |
| **Synthesize** (macro) | Entire knowledge graph | Components, architectural layers, dependencies between components | Yes |

The pipeline builds understanding **bottom-up** — git objects, files, code segments, semantic metadata, architectural components — so that **top-down** questions ("what's the blast radius?", "describe the architecture") can be answered from pre-built knowledge rather than ad-hoc scanning.

### Three levels of abstraction

The knowledge graph has three entity levels. Queries naturally traverse up and down:

```mermaid
flowchart TB
  C1[Component] <-->|depends-on| C2[Component]
  C1 ---|arch/component| F1[File]
  F1 <-->|imports| F2[File]
  F1 ---|code/file| S1[Code Segment]
  S1 <-->|calls| S2[Code Segment]
```

| Level | Entity | Identity | Example query |
|-------|--------|----------|---------------|
| **Macro** | Component | `component/name` | "What depends on the query engine?" |
| **Mid** | File | `file/path` | "Which files have the most safety concerns?" |
| **Micro** | Code Segment | `code/file+name` | "Which functions are complex and impure?" |

`update` keeps the graph in sync incrementally. `watch` polls continuously. `introspect` uses benchmark results to autonomously improve the ask agent's prompts, examples, rules, and code.

## Command Reference

```bash
noum                       # interactive mode (menu-driven TUI)
noum <command> [options]    # single-shot mode
noum help <command>         # command-specific help
```

The `noum` CLI and [MCP](https://modelcontextprotocol.io) server expose the same capabilities.

| `noum` | MCP tool | Description |
|---|---|---|
| `import <repo>` | `noumenon_import` | Import git history and file structure |
| `analyze <repo>` | `noumenon_analyze` | Enrich files with LLM semantic metadata |
| `enrich <repo>` | `noumenon_enrich` | Extract cross-file import graph (no LLM) |
| `synthesize <repo>` | `noumenon_synthesize` | Identify architectural components (macro analysis) |
| `update <repo>` | `noumenon_update` | Sync knowledge graph with latest git state |
| `digest <repo>` | `noumenon_digest` | Full pipeline: import, enrich, analyze, synthesize, benchmark |
| `ask <repo> "question"` | `noumenon_ask` | Ask a question using iterative Datalog querying |
| `query <name> <repo>` | `noumenon_query` | Run a named Datalog query |
| `queries` | `noumenon_list_queries` | List available named queries |
| `schema <repo>` | `noumenon_get_schema` | Show database schema |
| `status <repo>` | `noumenon_status` | Show entity counts |
| `databases` | `noumenon_list_databases` | List all databases with stats |
| `delete <name>` | -- | Delete a database |
| `bench <repo>` | `noumenon_benchmark_run` | Evaluate knowledge graph efficacy |
| `results [id]` | `noumenon_benchmark_results` | Get benchmark results |
| `compare <a> <b>` | `noumenon_benchmark_compare` | Compare two benchmark runs |
| `introspect <repo>` | `noumenon_introspect_start` | Autonomous self-improvement loop |
| `reseed` | `noumenon_reseed` | Reload prompts, queries, and rules |
| `history --type <t>` | `noumenon_artifact_history` | Show artifact change history |
| `open` | -- | Launch visual desktop UI (Electron) |
| `watch <repo>` | -- | Auto-sync on new commits |
| `serve` | -- | Start MCP server (stdin/stdout) |
| `setup desktop` | -- | Configure MCP for Claude Desktop |
| `setup code` | -- | Write `.mcp.json` for Claude Code |
| `install claude` | -- | Install Claude Desktop and/or Code |
| `start` / `stop` / `ping` | -- | Manage the HTTP daemon |
| `upgrade` | -- | Update noumenon.jar and launcher |

## Named Queries

65+ named Datalog queries live in `resources/queries/` (EDN), covering hotspots, ownership, dependencies, complexity, churn, impact analysis, issue tracking, LLM cost tracking, benchmarks, introspect history, and architectural components. Run `noum queries` to see them all.

## Data Model

### Entity Hierarchy

```mermaid
flowchart TB
  subgraph macro["Macro (synthesize)"]
    Component[Component]
  end

  subgraph mid["Mid (import + analyze)"]
    File[File]
    Commit[Commit]
    Person[Person]
  end

  subgraph micro["Micro (analyze)"]
    Segment[Code Segment]
  end

  Component -->|depends-on| Component
  Component ---|arch/component| File
  File -->|imports| File
  Commit -->|changed-files| File
  Commit -->|author| Person
  File ---|code/file| Segment
  Segment -->|calls| Segment
```

### Relationship Graph

```mermaid
flowchart LR
  Repo[repo] -->|:repo/commits| Commit[commit]
  Commit -->|:commit/author| Author[person]
  Commit -->|:commit/changed-files| File[file]
  Commit -.->|:commit/issue-refs| IssueRef["issue ref"]

  File -->|:file/directory| Dir[directory]
  File -->|:file/imports| ImportedFile[file]
  File -->|:arch/component| Comp[component]
  Comp -->|:component/depends-on| DepComp[component]

  File -->|:code/_file| Code[code segment]
  Code -->|:code/calls| CalledCode[code segment]
```

Every analysis transaction carries provenance: `:prov/model-version`, `:prov/prompt-hash`, `:prov/analyzed-at`, and token/cost tracking. This enables selective re-analysis when prompts or models change.

## Language Support

Import and LLM analysis work with any language. `enrich` adds deterministic import extraction:

| Tier | Languages | Method | External tool |
|---|---|---|---|
| Full | Clojure | `tools.namespace` parsing + test mapping | none |
| Import extraction | Elixir | AST parser via `Code.string_to_quoted` | `elixir` |
| Import extraction | Python | `ast` parser | `python3` |
| Import extraction | JavaScript / TypeScript | Regex-based import extraction via Node runtime | `node` |
| Import extraction | C / C++ | compiler dependency output | `clang` or `gcc` |
| Import extraction | C# | `using` directive detection | none (regex) |
| Import extraction | Rust | `mod` detection | none (regex) |
| Import extraction | Java | `import` detection | none (regex) |
| Import extraction | Erlang | `-include` / `-include_lib` detection | none (regex) |
| Analysis only | many others | LLM-only semantics | n/a |

Markdown files are imported as entities but not analyzed.

### Sensitive File Protection

Files matching known sensitive patterns are **never read or sent to any AI provider**:

| Pattern | Examples |
|---|---|
| Environment files | `.env`, `.env.local`, `.env.production` (not `.env.example`) |
| Crypto keys | `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.keystore`, `*.jks`, `*.cert` |
| Credential files | `credentials.json`, `token.json`, `.npmrc`, `.pypirc`, `.netrc`, `.htpasswd`, `.pgpass` |
| SSH material | `.ssh/*`, `id_rsa*`, `id_ed25519*`, `id_ecdsa*` |

These files are still tracked as entities (path, size, extension) but their contents are never accessed.

**Note:** This covers well-known secret *files*. Secrets hardcoded in source code will still be sent to the LLM. Use [git-secrets](https://github.com/awslabs/git-secrets) or [gitleaks](https://github.com/gitleaks/gitleaks) to prevent secrets from entering your repo.

## MCP Server

Run Noumenon as an [MCP](https://modelcontextprotocol.io) server so AI agents can call it as a tool.

### Automatic setup

```bash
noum setup desktop    # Configure Claude Desktop
noum setup code       # Configure Claude Code (MCP + hook + CLAUDE.md)
```

`noum setup code` writes four files in the current directory:

| File | Purpose |
|------|---------|
| `.mcp.json` | MCP server registration for Claude Code |
| `.claude/hooks/noumenon-mcp-first.sh` | PreToolUse hook enforcing MCP-first workflow |
| `.claude/settings.local.json` | Hook activation (merged with existing settings) |
| `CLAUDE.md` | MCP-first instructions for AI agents (appended to existing) |

All writes are idempotent — safe to re-run.

### Manual configuration

#### [Claude Desktop](https://claude.ai/download)

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "noumenon": {
      "command": "noum",
      "args": ["serve"]
    }
  }
}
```

#### [Claude Code](https://claude.ai/claude-code)

Add to `.mcp.json` (per-project) or `~/.claude/settings.json` (global):

```json
{
  "mcpServers": {
    "noumenon": {
      "command": "noum",
      "args": ["serve"]
    }
  }
}
```

## HTTP API

The daemon exposes a REST-ish HTTP API on localhost for the `noum` CLI and future GUI app. See [`docs/openapi.yaml`](docs/openapi.yaml) for the full OpenAPI 3.1 specification.

All long-running endpoints support [SSE](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) progress streaming via `Accept: text/event-stream`.

## Benchmarks

Run the benchmark on your own repo to measure whether the knowledge graph improves LLM answers about your codebase. See [reports/digest-run-2026-03-27.md](reports/digest-run-2026-03-27.md) for results from a full run across 9 repos and 8 languages.

## Cost Estimates

`analyze` averages roughly `~4,500` input + `~750` output tokens per file. Example projections using [Anthropic Sonnet pricing](https://docs.anthropic.com/en/docs/about-claude/models) (`$3/M` input, `$15/M` output):

| Repo size | Source files | Estimated cost |
|---|---:|---:|
| Small (Ring-scale) | 90 | ~$2 |
| Medium | 500 | ~$12 |
| Large (Redis-scale) | 1,350 | ~$34 |
| Very large (Guava-scale) | 3,300 | ~$82 |

`benchmark` costs ~$0.25-$1.30 per run depending on mode. Providers without per-token pricing (e.g. `glm`) still track token counts but report `$0.00`.

## Data Storage & Backup

Noumenon uses [Datomic Local](https://docs.datomic.com/datomic-local.html) — an embedded database that stores everything as files on disk. No external database server required.

**Default location:** `~/.noumenon/data/` (when using `noum`) or `data/datomic/` (when developing from source).

**Backup:** Copy the database directory. That's the [official approach](https://docs.datomic.com/datomic-local.html). Stop the daemon first for consistency (`noum stop`, copy, `noum start`).

**Docker:** Mount a volume at the data directory to persist databases across container restarts. A token is required for network access:

```bash
docker run -d -p 7891:7891 \
  -e NOUMENON_TOKEN=<your-token> \
  -v /host/data:/data \
  ghcr.io/leifericf/noumenon
```

Then connect from any machine: `noum --host server:7891 --token <your-token> status myrepo`

The image is 167MB (Alpine + custom jlink JRE), runs as non-root, and refuses to start without auth when network-accessible. For TLS, put a reverse proxy (Caddy, nginx) in front.

**Enterprise:** [Datomic Pro](https://www.datomic.com/get-datomic.html) (free) is available for deployments requiring a proper transactor with PostgreSQL or DynamoDB storage. The Noumenon codebase uses the same Datomic client API — switching is a configuration change, not a code change.

## Development

Requires [JDK 21+](https://adoptium.net), [Clojure CLI](https://clojure.org/guides/install_clojure), and [Babashka](https://github.com/babashka/babashka).

```bash
git clone https://github.com/leifericf/noumenon.git
cd noumenon
clj -M:test              # run test suite
clj -M:lint              # lint
clj -M:fmt check         # check formatting
clj -T:build uber        # build backend JAR
cd launcher && bb -cp src:resources -m noum.main help  # run launcher from source
```

## Architecture

```mermaid
flowchart TB
  noum["noum CLI\n(Babashka TUI)"]
  mcp["noum serve\n(MCP)"]
  gui["noum open\n(Electron UI)"]
  daemon["JVM Daemon\nDatomic + LLM engine"]

  noum -->|HTTP| daemon
  mcp -->|stdin/stdout| daemon
  gui -->|HTTP| daemon
```

## Project Layout

- `src/noumenon/` - JVM backend (Datomic, LLM, MCP, HTTP daemon)
- `launcher/` - Babashka CLI launcher (`noum` binary)
- `launcher/src/noum/tui/` - custom TUI library (JLine3)
- `ui/` - Electron + ClojureScript visual UI (Replicant, d3-force, Garden)
- `resources/schema/` - Datomic schema (EDN)
- `resources/queries/` - named Datalog queries and rules
- `resources/prompts/` - prompt templates
- `docs/` - GitHub Pages site + OpenAPI spec
- `test/` - test suite
- `data/` - local runtime artifacts (ignored)

## Using with Perforce

Works with Helix Core via [git-p4](https://git-scm.com/docs/git-p4), which creates a local Git mirror from a P4 depot path.

**Requirements**: `git`, `p4`, and `git-p4` on PATH. Perforce environment (`P4PORT`, `P4USER`, `P4CLIENT`) configured.

### Import a Perforce depot

```bash
git p4 clone //depot/project/main/... data/repos/project
noum import data/repos/project
```

### Sync with new changelists

```bash
cd data/repos/project && git p4 sync && git p4 rebase && cd -
noum update data/repos/project
```

If your server has [Helix4Git](https://www.perforce.com/products/helix-core-git-connector), point Noumenon at the Git URL directly — no `git-p4` needed.

## Limitations & Workarounds

### AI agents may not use MCP tools consistently

AI agents — including Claude — have strong trained-in preferences for reading files directly (Read, Glob, Grep) and may ignore MCP tools even when configured and explicitly instructed via CLAUDE.md. This is not a Noumenon bug; it reflects current LLM behavior with runtime-discovered tools.

**What helps:**
- **Enable the enforcement hook** (Claude Code): Noumenon includes a PreToolUse hook that blocks file-reading tools until at least one Noumenon query has been made in the session. Run `noum setup code` to configure it.
- **Be explicit in prompts**: Say "use the noumenon MCP tools" or "query the knowledge graph first" rather than generic phrasing like "explore the codebase," which triggers file-reading defaults.
- **Prefer `noumenon_ask`**: Natural-language questions have the lowest friction. Agents are more likely to use a tool that takes a plain question than one requiring query names and parameters.

The intended workflow is: **query the knowledge graph first** to understand which files matter and why, **then** use Read/Glob/Grep surgically on specific files for the details the graph doesn't contain (e.g., exact implementation, line-level edits).

### Benchmark measures context quality, not agent behavior

The benchmark compares LLM answers given raw source files vs. pre-fetched knowledge graph data. It does **not** test whether an agent can effectively discover and use MCP tools interactively. The benchmark score is a floor estimate — real-world value is higher when agents use the tools iteratively.

## Status

**Experimental — early beta, under rapid development.** APIs, data formats, and CLI interfaces may change between releases. This project is currently optimized for CLI workflows.

This project was developed using [leifericf's Claude Code Toolkit](https://github.com/leifericf/claude-code-toolkit).

## License

MIT. See `LICENSE`.
