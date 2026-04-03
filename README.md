# Noumenon

**Precise, grounded answers about your codebase.**

Noumenon compiles git repositories into a [Datomic](https://www.datomic.com) knowledge graph — git objects, code segments, files, and architectural components — so AI agents answer codebase questions more accurately, faster, and cheaper than scanning raw source into context windows.

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
| `claude-cli` | [Claude Code](https://claude.ai/claude-code) installed |

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

Or run stages individually: `noum import`, `noum enrich`, `noum analyze`, `noum synthesize`.

### Visual UI

```bash
noum open
```

Electron desktop app with force-directed graph visualization, three-level drill-down, and a floating Ask overlay. [Node.js](https://nodejs.org) required.

## How It Works

Four pipeline stages compile raw git data into a queryable knowledge graph:

| Stage | What it does | LLM? |
|-------|-------------|------|
| **Import** | Commits, files, authors, diffs, directories | No |
| **Enrich** | Cross-file import edges (10+ languages) | No |
| **Analyze** | Per-file summaries, code segments, complexity, smells | Yes |
| **Synthesize** | Components, layers, architectural dependencies | Yes |

Three query interfaces sit on top: Datalog queries, natural-language Ask, and MCP tools. `introspect` uses benchmarks to autonomously improve the Ask agent.

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

Run `noum help` for the full command list. The CLI and MCP server expose the same capabilities. See [`docs/openapi.yaml`](docs/openapi.yaml) for the HTTP API spec.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for setup, architecture, and project layout.

## Status

**Early beta, under rapid development.** APIs and CLI interfaces may change between releases.

Built with [Claude Code Toolkit](https://github.com/leifericf/claude-code-toolkit).

## License

MIT. See `LICENSE`.
