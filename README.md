# Noumenon

> **Experimental, early beta** — data model and interfaces are unstable. Expect breaking changes between releases.

**Precise, grounded answers about your codebase.**

Noumenon compiles repositories into a [Datomic](https://www.datomic.com) knowledge graph — commits, code segments, files, and architectural components — so AI agents answer codebase questions more accurately, faster, and cheaper than scanning raw source into context windows. Supports Git repositories and Perforce depots (via [git-p4](https://git-scm.com/docs/git-p4)).

See [noumenon.leifericf.com](https://noumenon.leifericf.com) for an overview.

## Install

```bash
curl -sSL https://noumenon.leifericf.com/install | bash
```

Or via [Homebrew](https://brew.sh): `brew install leifericf/noumenon/noumenon`

Or via [Scoop](https://scoop.sh) (Windows): `scoop bucket add noumenon https://github.com/leifericf/scoop-noumenon && scoop install noumenon`

Or download a binary from [GitHub Releases](https://github.com/leifericf/noumenon/releases).

The `noum` binary is self-contained — it downloads a JRE and the backend on first use.

### LLM providers

| Provider | What you need |
|---|---|
| `glm` (default) | `NOUMENON_ZAI_TOKEN` |
| `claude-api` | [`ANTHROPIC_API_KEY`](https://console.anthropic.com/settings/keys) |

Provider API configuration also supports a provider-agnostic EDN map:

```clojure
NOUMENON_LLM_PROVIDERS_EDN='{:glm {:base-url "https://api.z.ai/api/anthropic" :api-key "..."}
                              :claude-api {:base-url "https://api.anthropic.com" :api-key "..."}
                              :default-provider :gateway
                              :gateway {:base-url "https://your-litellm-gateway"
                                        :api-key "..."
                                        :models-path "/v1/models"
                                        :models ["gpt-4.1-mini" "gpt-4.1"]
                                        :default-model "gpt-4.1-mini"}}'
```

` :models` is optional; when present, selected model must be one of the listed values.
`:default-model` is optional; when present, it is used when `--model` is omitted.
`:default-provider` is optional; when present, it is used when `--provider` is omitted.
You can also set `NOUMENON_DEFAULT_PROVIDER` (takes precedence over `:default-provider`).
`:models-path` is optional; used for dynamic model discovery (defaults are built in for known providers).

Inspect current provider/model defaults with:

```bash
noum llm-providers
noum llm-models --provider gateway
```

Provider config resolution precedence for API providers is:

1. `NOUMENON_LLM_PROVIDERS_EDN` provider entry
2. Legacy provider env vars (`NOUMENON_ZAI_TOKEN`, `ANTHROPIC_API_KEY`)
3. Built-in default base URL (API key is never defaulted)

Runtime mode controls secret fallback behavior:

- `NOUMENON_RUNTIME_MODE=local` (default): allows process env and trusted local credentials fallback
- `NOUMENON_RUNTIME_MODE=service`: process env only, disables local credentials fallback, requires `https` base URLs

Migration note: existing legacy env vars continue to work. You can migrate incrementally by adding entries to `NOUMENON_LLM_PROVIDERS_EDN`.

## Quick Start

### Interactive mode

Run `noum` with no arguments for a menu-driven TUI. Browse commands by category, select repositories and sessions from live data — no memorizing commands or IDs.

```bash
noum
```

### Try the demo (no credentials needed)

```bash
noum demo
noum ask noumenon "Describe the architecture"
```

### Build your own knowledge graph

```bash
noum digest /path/to/repo --provider glm    # full pipeline
noum ask /path/to/repo "Which files are the biggest risk hotspots?"
```

Or run stages individually: `noum import`, `noum enrich`, `noum analyze`, `noum synthesize`, `noum embed`.

Scope pipeline work with selectors on analyze/enrich/update/digest:

```bash
noum analyze /path/to/repo --include "src/**/*.clj" --exclude "**/*_test.clj"
noum update /path/to/repo --path src/noumenon --lang clojure
```

Prompt/model drift is advisory by default. Noumenon recommends re-analysis, but only
re-analyzes when you opt in with `--reanalyze prompt-changed` or `--reanalyze model-changed`.

### Visual UI

```bash
noum open
```

Electron desktop app with force-directed graph visualization, three-level drill-down, and a floating Ask overlay. [Node.js](https://nodejs.org) required.

## How It Works

Five pipeline stages compile raw git data into a queryable knowledge graph:

| Stage | What it does | LLM? |
|-------|-------------|------|
| **Import** | Commits, files, authors, diffs, directories | No |
| **Enrich** | Cross-file import edges (10+ languages) | No |
| **Analyze** | Per-file summaries, code segments, complexity, smells | Yes |
| **Synthesize** | Components, layers, architectural dependencies | Yes |
| **Embed** | TF-IDF vector index for semantic search | No |

Three query interfaces sit on top: Datalog queries, natural-language Ask, and MCP tools. The Ask agent uses TF-IDF vector search to seed relevant files before querying the graph. `introspect` uses benchmarks to autonomously improve the Ask agent.

## MCP Server

```bash
noum setup desktop    # Claude Desktop
noum setup code       # Claude Code (MCP + hook + CLAUDE.md)
```

Run `noum serve` to start the MCP server manually. See `noum help setup` for details.

## Server Mode

Run Noumenon as a shared service for your team or organization:

```bash
docker compose up -d
noum token create --role reader --label alice   # generate user tokens
```

Users connect with `noum connect https://noumenon.example.com --token <token>`. All CLI and MCP tools work transparently against the remote instance. Up to 200 concurrent users, role-based access (reader/admin), auto-refresh from git.

See [DEPLOY.md](DEPLOY.md) for configuration, reverse proxy setup, and monitoring.

## Commands

```bash
noum                       # interactive mode
noum <command> [options]    # single-shot mode
noum help <command>         # command-specific help
```

Run `noum help` for the full command list. The CLI and MCP server expose the same capabilities. See [`resources/openapi.yaml`](resources/openapi.yaml) for the HTTP API spec.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for setup, architecture, and project layout.

## License

MIT. See `LICENSE`.
