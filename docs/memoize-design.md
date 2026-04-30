# Memoize Design for Noumenon

## Overview

Memoize is Noumenon's temporal memory layer for engineering work continuity.

- **Noumenon graph** answers: what exists in the codebase?
- **Memoize** answers: what have we learned while working on it?
- **Introspect** answers: how should the system improve based on outcomes?

Memoize stores time-bounded, revisable memories as first-class graph entities with provenance, scope, and validity windows.

## Goals

- Preserve project context across sessions, branches, and users.
- Ground memories in evidence (files, symbols, commits, PRs, sessions).
- Use hybrid retrieval (graph + lexical + vector + temporal + confidence).
- Keep memory behavior inspectable, auditable, and overrideable.

## Non-goals

- Replacing Noumenon's core code intelligence graph.
- Opaque memory behavior without explainability.
- Silent mutation of memory truth through in-place overwrites.

## Core Principles

- Memories are **fleeting facts**: useful in context and time, then superseded or invalidated.
- Memory records are append-first and history-preserving.
- Embeddings accelerate retrieval; they are not source of truth.
- Shared team memory must include attribution and ACL.

## Architecture

Memoize is implemented as a subgraph in Datomic with optional vector and lexical indexes.

## Digest Pipeline Integration

Memoize must integrate into Noumenon's existing `digest` pipeline as first-class stages.

### Revised digest flow

1. import
2. enrich
3. analyze
4. synthesize
5. memoize-ingest
6. memoize-embed
7. memoize-index
8. benchmark (optional)

### Stage responsibilities

- `memoize-ingest`: capture manual and automatic memory candidates from sessions, commits, and PR metadata.
- `memoize-embed`: compute memory vectors (async-capable) for semantic recall.
- `memoize-index`: refresh lexical/vector retrieval structures and derived relationship edges.

### Digest command behavior

- `noum digest` should run memoize stages by default once Memoize is enabled.
- Add controls:
  - `--memoize on|off|auto` (default `auto`)
  - `--memoize-reanalyze all|stale|none`
  - `--memoize-import-source session|commit|pr|automemory|all`

### Update/watch behavior

- `noum update` should include incremental memoize ingest and index refresh.
- `noum watch` should trigger memoize refresh after new commits or session summaries.
- All memoize pipeline steps must be idempotent and resumable like existing Noumenon stages.

### Write path

1. Create memory entity with type, scope, visibility, provenance.
2. Attach anchors to existing entities (file/symbol/component/commit/issue/session).
3. Persist lifecycle and audit fields.
4. Enrich memory with embeddings asynchronously.

### Read path

1. Build candidate set from scope + anchors + time filters.
2. Retrieve from graph neighborhood, lexical index, and vector index.
3. Fuse and rerank by relevance, scope, validity, confidence, and recency.
4. Emit token-budgeted memory pack with citations and selection rationale.

## Identity, Auth, and Team Model

### Lightweight user model

Add first-class actors:

- `:user/id` (stable)
- `:user/type` (`:human`, `:agent`, `:service`)
- `:user/name` (display)
- `:user/roles` (`:admin`, `:member`, `:reader`)
- `:user/status` (`:active`, `:disabled`)

### API key model

- Use one Noumenon API key system for all capabilities, including Memoize.
- Each human user gets their own key(s).
- Each service/CI/agent gets its own service key.
- Avoid shared team keys.

### Headless team visibility layers

Memoize supports one project memory store with overlays:

- `:shared` (team-visible durable memory)
- `:private` (owner-visible)
- `:session` (ephemeral, short-lived)

Default retrieval precedence:

1. session
2. private
3. branch/task shared
4. project shared

## Data Model

Suggested memory entity shape:

```clojure
{:memoize/id             uuid
 :memoize/type           :decision|:constraint|:preference|:experiment|:pitfall|:rationale
 :memoize/text           string
 :memoize/summary        string?
 :memoize/status         :candidate|:active|:superseded|:invalid|:archived
 :memoize/visibility     :shared|:private|:session
 :memoize/confidence     double ; 0..1
 :memoize/pinned?        boolean
 :memoize/priority       :low|:medium|:high|:critical

 :memoize/scope-project  string
 :memoize/scope-branch   string?
 :memoize/scope-task     string?
 :memoize/scope-agent    string?
 :memoize/scope-env      :dev|:staging|:prod|:any
 :memoize/team-id        string?
 :memoize/owner-user-id  string?

 :memoize/valid-at       instant
 :memoize/invalid-at     instant?
 :memoize/supersedes     [ref*]
 :memoize/conflicts      [ref*]
 :memoize/related        [ref*]

 :memoize/source-kind    :manual|:session|:commit|:pr|:issue|:introspect|:automemory
 :memoize/source-ref     string?
 :memoize/source-uri     string?
 :memoize/anchors        [ref*]
 :memoize/tags           [keyword*]

 :memoize/created-by     :user/id
 :memoize/updated-by     :user/id
 :memoize/change-reason  string?
 :memoize/created-at     instant
 :memoize/updated-at     instant}
```

## Graph Synergies

Anchor memory to existing graph entities and use neighborhood traversal for relevance.

- Direct anchors: strongest signal.
- One-hop neighbors: callers/importers/related components.
- Two-hop optional expansion for sparse areas.

This creates a practical notion of "closeness" without rigid topic folders.

## Topic and Spatial Organization

Use faceted topics as secondary organization, not primary storage partitions.

- Primary: anchors + scope + temporal validity.
- Secondary: `:memoize/tags` for filtering and ranking boosts.
- Derived clusters: dynamic topic "rooms" from co-anchor and co-retrieval patterns.

## Temporal Semantics

Memoize should leverage Datomic time directly.

- `as-of` queries reconstruct what was believed at decision time.
- `since` queries show memory deltas after releases/incidents.
- contradictions are modeled as new facts plus supersession links.

Lifecycle:

`candidate -> active -> superseded|invalid -> archived`

## Ingestion Policy

### Sources

- Manual (`memoize_add`)
- Session summaries
- Commit and PR metadata
- Optional automemory cold-load
- Optional LLM extraction from conversations

### Trust policy

- Automemory imports default to `:candidate`.
- LLM direct writes must include structured provenance and anchors.
- Promotion to `:active` requires explicit confirmation and/or evidence.

### Watch and update workflow

Memoize should ingest from explicit, observable sources rather than background free-form extraction.

1. Task/session writes (`memoize_add`, `memoize_summarize_session`) create direct memory events.
2. `update` and `watch` detect new source events (commits, PR metadata, session summaries, automemory batches).
3. Ingestion processes unseen events only and writes `candidate` or `active` entries per policy.
4. `memoize-embed` and `memoize-index` refresh retrieval structures incrementally.

This keeps memory sync predictable and compatible with headless service operation.

## Idempotency and Stable Event IDs

Memoize ingest must be idempotent across `digest`, `update`, and `watch` reruns.

### Stable ID strategy

- Prefer source-native stable identifiers where available:
  - commit SHA + file path + hunk index
  - PR comment/review ID
  - session ID + message/tool-call index
- For all ingestion paths, compute `:memoize/source-event-id` from a canonical envelope hash.

Canonical envelope fields:

- `source-kind`
- `source-ref`
- scope fields
- actor ID
- anchor references
- normalized text payload
- coarse event time bucket (if needed)

`source-event-id = sha256(canonical-json(envelope))`

### Canonicalization rules

- Deterministic key ordering for map serialization.
- Whitespace and unicode normalization for textual payloads.
- Exclude volatile fields (ephemeral IDs, jitter timestamps, token usage).

### Datomic constraints and behavior

- Add unique identity on `:memoize/source-event-id`.
- Existing `source-event-id` on ingest -> no duplicate memory entity creation.
- Semantic changes create new memory entities linked via `supersedes`.
- Maintain per-source cursors/watermarks for incremental watch/update scanning.

### Strict and fuzzy dedupe

- Strict ID enforces idempotency.
- Optional fuzzy similarity (simhash/minhash/vector-near-dup) is advisory for review, not identity.

## Retrieval and Ranking

Candidate channels:

- Graph anchor retrieval
- Graph neighborhood retrieval
- Lexical retrieval (BM25/keyword)
- Semantic retrieval (vector)
- Pinned memory inclusion

Example fused ranking factors:

- semantic relevance
- lexical relevance
- graph proximity
- scope match
- temporal recency
- confidence
- pin boost
- stale/invalid penalties

Hard filters by default:

- Exclude invalid/archived/superseded unless explicitly requested.
- Enforce visibility and ACL.

## Context Packing

Default token budget split:

- 60-70% code + graph context
- 20-30% memoize context
- 10% instructions/overhead

Pack order:

1. pinned constraints + active task memories
2. nearest decisions and pitfalls
3. optional background history

Each memory in pack includes ID, source, anchors, confidence, and why-selected.

## MCP and CLI Surface

### MCP tools

- `noumenon_memoize_add`
- `noumenon_memoize_recall`
- `noumenon_memoize_invalidate`
- `noumenon_memoize_supersede`
- `noumenon_memoize_promote`
- `noumenon_memoize_explain`
- `noumenon_memoize_summarize_session`

### CLI commands

- `noum memoize add|recall|list|show`
- `noum memoize invalidate|supersede|pin|unpin|promote`
- `noum memoize history|timeline|gc|import-automemory|summarize-session`
- `noum login|logout|whoami`
- `noum digest --memoize on|off|auto`
- `noum update --memoize-sync`

## Query Additions (Named Datalog Queries)

Core:

- `memoize/create`
- `memoize/by-id`
- `memoize/search`
- `memoize/update-metadata`

Lifecycle and temporal:

- `memoize/invalidate`
- `memoize/supersede`
- `memoize/active-as-of`
- `memoize/changed-since`
- `memoize/timeline-for-scope`

Graph synergy:

- `memoize/by-anchor`
- `memoize/near-anchor-neighborhood`
- `memoize/conflicts-for-anchor`
- `memoize/cooccurring-memories`

Context assembly:

- `memoize/candidate-set-for-ask`
- `memoize/pinned-for-scope`
- `memoize/context-pack`
- `memoize/explain-selection`

Governance:

- `memoize/stale-candidates`
- `memoize/duplicates-likely`
- `memoize/orphans`
- `memoize/coverage-report`

## Hooks and Enforcement

Reuse the current enforced workflow pattern.

- Keep MCP-first gate for graph access before file reads.
- Add optional Memoize strict mode that requires `memoize_recall` before read/search.
- Add post-session hook for `memoize_summarize_session` and write-back.
- Ensure install/setup automation updates `CLAUDE.md` with explicit agent instructions for memory recall/write behavior.

Modes:

- `off`
- `advisory`
- `enforced`

## Agent Instructions via Install/Setup

The install/setup flow must keep `CLAUDE.md` aligned with Memoize capabilities so agents follow a consistent memory workflow.

Required behavior:

- `install`/`setup` appends or updates a Memoize instruction block in `CLAUDE.md`.
- The block must be idempotent (safe to run repeatedly) and marker-based (update existing block instead of duplicating).
- The block must describe when to read memory, when to write memory, and how to handle visibility/promotion.

Required instruction content for agents:

1. At task start, call `noumenon_status` then `noumenon_query`/`noumenon_ask` and `noumenon_memoize_recall`.
2. Before file reads/search, ensure graph query and memoize recall have already happened for the task.
3. During execution, write new memories only through structured tools (`noumenon_memoize_add`) with scope, anchors, source, and confidence.
4. Default new extracted memories to `candidate`; only promote to `active/shared` when evidence or user confirmation exists.
5. On important decisions, write decision/rationale memories and link to commits/files/symbols.
6. At session end or before compaction, call `noumenon_memoize_summarize_session` and persist outcomes.
7. Never overwrite memory in place; use invalidate/supersede with a reason.

Example `CLAUDE.md` block installed by setup:

```markdown
## Noumenon Memoize — Read/Write Workflow

Before reading files, retrieve context from Noumenon first:

1. `noumenon_status`
2. `noumenon_query` or `noumenon_ask`
3. `noumenon_memoize_recall`

While working:

- Use `noumenon_memoize_add` for durable decisions, constraints, and pitfalls.
- Include anchors (file/symbol/component/commit), scope, source, and confidence.
- Keep auto-extracted items as `candidate` until promoted.

When facts change:

- Use `noumenon_memoize_supersede` or `noumenon_memoize_invalidate` with reason.

At session end:

- Run `noumenon_memoize_summarize_session` and persist important outcomes.
```

### Implementation notes for installer/setup

- Current Claude Code setup logic lives in `launcher/src/noum/setup.clj`.
- Existing hook and CLAUDE.md write path already exists in:
  - `launcher/src/noum/setup.clj` (`write-hook!`, `write-settings!`, `write-claude-md!`)
  - `.claude/hooks/noumenon-mcp-first.sh`
- Extend the existing marker-based CLAUDE.md merge to include a dedicated Memoize block marker.
- Keep behavior idempotent: rerunning `noum setup code` should update in place, not append duplicate blocks.
- Keep installer behavior consistent with launcher behavior:
  - if shell installer invokes setup, ensure it triggers the same `setup-code!` path.
  - if shell installer writes files directly, align wording and markers with `setup.clj` to avoid drift.

## Downstream Agent Implementation Guide

These notes are for AI agents implementing Memoize in this repository.

### Primary touchpoints

- Core command dispatch and subcommands: `src/noumenon/main.clj`
- CLI option parsing and help text: `src/noumenon/cli.clj`
- MCP tool registry and handlers: `src/noumenon/mcp.clj`
- Digest/update orchestration entrypoints: `src/noumenon/main.clj`, `src/noumenon/sync.clj`
- Shared pipeline utility: `src/noumenon/pipeline.clj`
- Datomic schema seed files: `resources/schema/*.edn`
- Named query seed files: `resources/queries/*.edn`
- Launcher command catalog/help: `launcher/src/noum/cli.clj`
- Launcher setup/install wiring for Claude Code: `launcher/src/noum/setup.clj`

### Digest integration expectations

- `noumenon_digest` tool description in `src/noumenon/mcp.clj` must mention Memoize stages.
- `digest` command help in `launcher/src/noum/cli.clj` should include Memoize flags and stage semantics.
- `do-update` and `watch` paths in `src/noumenon/main.clj` should invoke incremental memoize sync.

### Ask and MCP behavior expectations

- `ask` should support memory mode flags (`auto|on|off`) and surface memory citations in explain mode.
- MCP tools should expose explicit memory lifecycle operations instead of implicit side effects.
- Tool descriptions should state when to call recall before file reads/search.

### Schema/query seeding expectations

- Add memoize schema files under `resources/schema/` and load through existing schema bootstrap.
- Add named memoize queries under `resources/queries/` and ensure they appear in query listings.
- Preserve repository conventions: EDN-driven schema/query definitions rather than hardcoded Datalog in code paths where avoidable.

### Verification expectations for implementers

- CLI help reflects new commands and flags (`noum help`, `noum help digest`).
- `noum setup code` installs or updates Memoize instructions in `CLAUDE.md` idempotently.
- `noumenon_memoize_*` tools are listed via MCP tool discovery.
- `digest` and `update` run with memoize stages enabled and no duplicate ingestion on rerun.

## Introspect Relationship

- Memoize feeds introspect with stale/conflict/miss patterns.
- Introspect proposes ranking/extraction/policy improvements.
- Policy changes remain reviewable artifacts, not silent runtime mutation.

## Security and Governance

- Enforce ACL on visibility and scopes.
- Keep append-only memory history.
- Require provenance for shared memory writes.
- Provide rotation/revocation for API keys.
- Redact or reject sensitive content via ingestion guardrails.

## Concrete Examples

### Example 1: Add shared decision memory

```bash
noum memoize add \
  --repo noumenon \
  --type decision \
  --visibility shared \
  --text "Query noumenon MCP before Read/Glob/Grep in Claude Code." \
  --anchor file:.claude/hooks/noumenon-mcp-first.sh \
  --anchor file:CLAUDE.md \
  --source-kind session \
  --source-ref sess_2026_04_27_001 \
  --confidence 0.9 \
  --promote
```

### Example 2: Recall with explanation

```bash
noum memoize recall \
  --repo noumenon \
  --query "How is MCP-first enforced?" \
  --scope-branch main \
  --top-k 8 \
  --explain
```

Expected rationale includes anchor match, scope match, and recency.

### Example 3: Supersede outdated memory

```bash
noum memoize supersede mem_101 mem_204 --reason "Hook v2 changed session cache path"
```

### Example 4: Team overlay and promotion

1. Developer stores a private experiment memory.
2. After successful PR merge, promotes to shared.
3. Team retrieval sees the shared active memory by default.

### Example 5: Idempotent watch cycle

1. `noum watch` detects commit `abc123` and session event `sess_9/msg_42`.
2. Ingestion computes canonical `source-event-id` values and writes memories.
3. Next poll sees the same events; unique `source-event-id` makes ingest no-op.
4. A later change creates a new memory that supersedes the prior memory.

## Documentation and Public Website Requirements

### In-repo docs

- `docs/memoize-design.md` (this doc)
- `docs/memoize-operations.md`
- `docs/auth-and-users.md`
- README/CLI reference updates for new commands
- changelog and migration notes

### Public website/docs

- Memoize concept page
- Team memory model page (shared/private/session)
- Auth and API key guidance page
- MCP reference updates with examples
- Security/privacy and explainability sections

## Rationale Summary

- Team memory without identity creates trust and audit problems.
- Pure vector memory misses graph and temporal context.
- Hard topic folders drift; graph-anchored facets stay practical.
- Temporal lineage prevents stale-memory corruption and improves debugging.
- Enforced hooks make usage reliable in agent workflows.
