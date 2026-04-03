# Noumenon Perforce Support: Git-P4 Bridge

**Date:** 2026-04-03
**Operator:** Claude Opus 4.6 (automated)
**Branch:** `feature/perforce-support`

---

## 1. The Problem

### 1.1 Game studios use Perforce, not Git

Perforce Helix Core is the dominant VCS for game development, visual effects, and large-scale software projects. These teams need code knowledge graphs too, but Noumenon only supported Git repositories.

### 1.2 Binary asset bloat

Perforce depots contain game assets (3D models, textures, audio, Unreal/Unity binary files) alongside source code. A naive import would pull gigabytes of binary data that has no value in a code knowledge graph, wasting disk space and import time.

### 1.3 Multi-project depots

Unlike Git (one repo = one project), Perforce depots often contain multiple unrelated projects under paths like `//depot/ProjectA/...`, `//depot/ProjectB/...`. Users need to scope which parts of a depot they import.

### 1.4 Manual git-p4 workflow was clunky

The previous "support" was documentation telling users to run `git p4 clone` manually, then point Noumenon at the resulting directory. Syncing required a multi-step shell dance (`cd && git p4 sync && git p4 rebase && cd -`). Admins without Git experience found this confusing.

---

## 2. Design Decision: Git-P4 Bridge, Not Native P4

We use [git-p4](https://git-scm.com/docs/git-p4) as a bridge rather than implementing a native Perforce client. git-p4 converts P4 changelists into Git commits, creating a standard Git repository. This means:

- **Zero downstream changes** -- the entire pipeline (commits, files, diffs, enrichment, analysis, benchmarks) works unchanged on the Git mirror
- **Complexity concentrated at the edges** -- only clone/sync entry points needed new code
- **Schema unchanged** -- `:git/sha`, `:commit/*` attributes all apply because the data IS Git data

Key git-p4 capabilities (verified against [official docs](https://git-scm.com/docs/git-p4)):
- `-/` flag excludes depot-relative paths during clone (repeatable)
- `--use-client-spec` respects P4 workspace view mappings for file filtering
- No P4 workspace needed for clone/sync (uses `p4 print` directly)
- Built-in Git LFS support via `git-p4.largeFileSystem` config

---

## 3. Implementation Log

### Phase 1: Default binary exclusions (`resources/p4-excludes.edn`)

Created an EDN resource with 88 binary exclusion patterns across 10 categories: 3D models, textures, audio, video, Unreal Engine, Unity, compiled binaries, archives, fonts, and documents. Loaded lazily via `(delay (io/resource ...))` following existing resource patterns.

### Phase 2: P4 functions in `git.clj`

Added to `src/noumenon/git.clj`:
- `p4-depot-path?` -- detects `//` prefix paths
- `p4-available?` -- checks if `git p4` subcommand works
- `p4-clone?` -- detects existing git-p4 clones via `refs/remotes/p4/` refs
- `p4-depot->clone-name` -- derives local directory name from depot path (`//depot/ProjectA/main/...` -> `ProjectA-main`)
- `p4-clone-path` -- builds `data/repos/<name>` path
- `p4-exclude-patterns` -- computes final exclusion list with defaults, extras, includes
- `p4-clone!` -- runs `git p4 clone` with `-/` exclusions, supports `--use-client-spec` and `--max-changes`
- `p4-sync!` -- runs `git p4 sync` then `git p4 rebase`

These live in `git.clj` (not a separate namespace) because the result is a Git repo -- it's Git tooling that happens to pull from P4.

### Phase 3: Repo resolution (`repo.clj`)

Extended `resolve-repo` to detect `//depot/...` paths as first priority (before Git URL check). Dispatches to new `clone-or-reuse-p4` helper. Accepts `:p4-opts` in options map for exclude/include customization.

### Phase 4: Auto P4-sync (`sync.clj`)

Updated `update-repo!` to detect git-p4 clones (via `p4-clone?`) and run `p4-sync!` before the existing staleness check. Placed before the `let` block that reads `head-sha` so we get the updated HEAD after syncing.

### Phase 5: Server-managed repos (`repo_manager.clj`)

- `url->db-name` now handles depot paths (delegates to `p4-depot->clone-name`)
- `register-repo!` detects depot paths and uses `p4-clone!` instead of `clone-bare!`
- `refresh-repo!` detects git-p4 clones and uses `p4-sync!` instead of `git fetch`

### Phase 6: CLI and MCP descriptions

- `mcp.clj`: `repo-path-prop` description updated to mention Perforce depot paths; `noumenon_import` and `noumenon_update` descriptions updated
- `cli.clj`: import and update command summaries, usage, and epilog updated
- `launcher/src/noum/cli.clj`: import and update command descriptions updated

### Phase 7: Documentation

- `DEVELOPMENT.md`: Expanded Perforce section from 4 lines to comprehensive guide covering prerequisites, quick start, automatic binary exclusions, workspace views, syncing, and Helix4Git
- `README.md`: Updated tagline to mention Perforce support

### Phase 8: Tests

Added 10 unit tests to `git_test.clj` covering all pure P4 functions:
- Depot path detection (positive and negative cases)
- Clone name derivation from various depot path formats
- Clone path construction
- P4-clone detection on non-P4 repos
- Default exclude pattern loading
- Exclude pattern customization (no-defaults, extras, includes, combined)

---

## 4. What Did NOT Change

- **Schema** (`resources/schema/core.edn`) -- no changes needed
- **files.clj** -- `git-ls-tree`, `git-line-counts` work on the git-p4 mirror
- **analyze.clj** -- `git-show` works on the mirror
- **imports.clj** -- enrichment is VCS-agnostic
- **benchmark.clj** -- works on the mirror unchanged
- **Datalog queries** -- all existing queries work unchanged

---

## 5. Pending: E2E Testing (requires P4 connection)

These cannot be verified without a Perforce server:
1. `noum import //depot/project/main/...` end-to-end
2. `noum update` auto-sync on git-p4 clones
3. `--use-client-spec` workspace filtering
4. Custom `--p4-include` / `--p4-exclude` flags
5. Server-mode `register-repo!` with depot paths
6. Large game dev depot with real binary assets
