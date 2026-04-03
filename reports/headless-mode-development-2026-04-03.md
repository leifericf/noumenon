# Noumenon Headless Mode: Shared Service for Organizations

**Date:** 2026-04-03
**Operator:** Claude Opus 4.6 (automated)
**Branch:** `feat/headless-mode`

---

## 1. The Problem

### 1.1 Single-user ceiling

Noumenon runs as a local tool: the MCP server is a stdio process per user, the HTTP daemon binds to localhost, and the knowledge graph lives on one machine. This works well for individual developers, but organizations want a shared knowledge graph that 200+ people query simultaneously -- without each person running their own pipeline.

### 1.2 Operational friction

Deploying the current system requires Clojure knowledge: EDN configuration, manual `import`/`enrich`/`analyze` pipeline stages, and understanding Datomic Local's storage model. IT teams managing infrastructure for large organizations need Docker, env vars, health checks, and standard observability -- not EDN files and REPL-driven workflows.

### 1.3 Security gaps in multi-user context

The current auth model is a single bearer token with no role distinction. Every authenticated user can trigger imports, analyze (burning LLM tokens), delete databases, and run introspect (which does `git commit` on the repo). In a shared environment, most users should be read-only.

### 1.4 Goals

1. **Zero-Clojure deployment** -- Docker Compose + env vars, nothing else
2. **Transparent remote access** -- `noum connect <url>` makes CLI and MCP tools work against a remote instance
3. **Role-based access** -- reader vs. admin tokens, with the server managing its own repos
4. **Self-managing repos** -- the server clones, fetches, and indexes repos on its own schedule
5. **Production-grade ops** -- structured logging, Prometheus metrics, graceful shutdown, health checks

---

## 2. Design Constraints

### 2.1 Datomic Local is single-process

Datomic Local uses local file storage with no separate transactor. Two JVMs pointing at the same `storage-dir` will corrupt data. This means no horizontal scaling for v1 -- one container serves all 200 users. Datomic Local handles concurrent in-process reads well (immutable database values), so this is viable for read-heavy workloads. Write operations (import, enrich, analyze) are serialized through one transactor anyway.

### 2.2 repo_path is a server filesystem concept

Every tool/endpoint takes `repo_path` as an absolute path. Remote users send their local path (`/Users/alice/code/api`), which doesn't exist on the server. The MCP proxy must translate local paths to database names before forwarding to the remote server.

### 2.3 Git operations need local clones

Import, enrich, and sync shell out to `git` on `repo_path`. The server must maintain its own managed clones. The current code expects working trees (checks for `.git` subdirectory); bare clones need adaptation.

### 2.4 Introspect mutates the repo

`introspect.clj` runs `git add` and `git commit` on the analyzed repository. In shared mode, this would mutate the server's clone and affect all users. Must be isolated or disabled.

---

## 3. Edge Cases Identified During Planning

1. **Datomic Local single-process** -- no horizontal scaling, single point of failure
2. **repo_path translation** -- local paths don't exist on server; must map to db-names
3. **Git shell commands need local clones** -- bare vs. working tree adaptation
4. **Introspect mutates repo** -- git add/commit on shared clone is a landmine
5. **Concurrent LLM rate limits** -- analyze + ask compete for same API quota
6. **Token leakage** -- CLI args visible in `ps`; need file-based secret reading
7. **Database name collisions** -- two repos named "api" from different orgs collide
8. **SSE behind reverse proxy** -- Nginx buffers by default, killing event streams
9. **No graceful shutdown** -- 200 active connections get TCP RST on restart
10. **Stale conn-cache after delete** -- deleted database leaves stale connection in cache
11. **Clock skew** -- TTL-based session eviction uses wall clock, not monotonic time
12. **MCP proxy auth flow** -- sandboxed apps might not read `~/.noumenon/config.edn`
13. **Disk exhaustion** -- append-only Datomic grows continuously
14. **Thundering herd** -- 200 users hit unanalyzed repos on launch day
15. **Webhook security** -- must validate HMAC signatures
16. **clone-or-reuse path traversal** -- crafted Git URLs could escape repos directory

---

## 4. Implementation Log

### Phase 1: Environment-variable configuration

Added `util/env`, `util/env-bool`, `util/env-int` helpers that read env vars with Docker secrets support (`_FILE` suffix reads file contents). Extracted `resolve-server-config` from `http/start!` so every daemon setting has an env-var fallback. 13 env vars cover all server settings — no EDN config file needed for deployment.

### Phase 2: Role-based access control

New `auth.clj` module with token CRUD stored in Datomic meta database. Token format: `noum_<base62>` prefix for greppability in credential scans. SHA-256 hashed storage; raw token returned only at creation. Two roles: `:reader` (query/ask/status) and `:admin` (import/analyze/enrich/manage). Bootstrap admin token from `NOUMENON_TOKEN` env var. HTTP middleware checks roles per-endpoint. Read-only mode (`NOUMENON_READ_ONLY`) rejects all mutations with 503.

REST endpoints: `POST/GET/DELETE /api/tokens` for admin token management.

### Phase 3: Server-managed bare repos

Added `git/clone-bare!`, `git/fetch!`, `git/bare-repo?` and a public `git/git-dir-args` that returns `--git-dir` or `-C` depending on repo type. Adapted `files.clj`, `sync.clj`, `analyze.clj` to use `git-dir-args` so all git operations work on bare repos.

New `repo-manager.clj`: manages a repo registry in Datomic meta database. `register-repo!` clones bare, imports, and registers. `refresh-repo!` fetches and runs incremental update. `remove-repo!` cleans up clone, database, and connection cache. `url->db-name` uses org-repo format (`anthropics-claude-code`) to avoid collisions.

REST endpoints: `POST/GET/DELETE /api/repos`, `POST /api/repos/:name/refresh`.

### Phase 4: Safety guards

Introspect's `git-commit?` flag forced false for bare repos and read-only mode. Global LLM semaphore (`java.util.concurrent.Semaphore`, configurable via `NOUMENON_MAX_LLM_CONCURRENCY`). Per-token ask session limits (max 3 concurrent).

### Phase 5: Named connections and MCP proxy

Launcher gains `noum connect`, `noum connections`, `noum disconnect` commands. Connections stored in `~/.noumenon/config.edn` as named entries. `ensure-backend!` reads the active connection.

MCP server reads connection config on startup. In remote mode, all tool calls proxy to the HTTP API via curl. Key feature: `repo_path` → db-name translation. The local MCP process runs `git remote get-url origin` on the local repo and derives org-repo format, then sends that as the identifier. Users' local paths transparently resolve to the correct remote database.

### Phase 6: Deployment artifacts

Docker Compose with env-var config, health check, named volume. `.env.example` documenting all settings. Dockerfile gains `HEALTHCHECK` instruction. `DEPLOY.md` with quick start, config reference, reverse proxy examples (Nginx with SSE settings, Caddy), backup/restore, webhook setup.

### Phase 7: Documentation

README gains a Server Mode section. GitHub Pages gains a server-mode section with three feature cards and terminal demo.

