# Memoize Implementation Plan

## Scope

Implement Memoize as a first-class temporal memory subsystem in Noumenon, including identity, ACL, MCP/CLI APIs, retrieval integration, and digest pipeline stages.

## Success Criteria

- Memory entities are stored with provenance, visibility, temporal validity, and anchors.
- Team mode supports shared/private/session memory with ACL enforcement.
- `ask` can retrieve and explain memory context.
- `digest`, `update`, and `watch` include memoize stages.
- Documentation and public website pages ship in the same release.

## Phase 0: Foundations (Identity and Auth)

### Deliverables

- Add lightweight `:user/*` entities and API key owner mapping.
- Add actor attribution fields to memory and memory-change events.
- Add CLI auth commands: `login`, `logout`, `whoami` (headless-aware).

### Rationale

Shared memory without identity makes attribution, ACL, and auditing unreliable.

### Acceptance checks

- Each write operation records `created-by` and `updated-by`.
- API key revocation immediately blocks new writes.

## Phase 1: Schema and Lifecycle

### Deliverables

- Add `:memoize/*` schema attributes.
- Implement lifecycle transitions: `candidate -> active -> superseded|invalid -> archived`.
- Implement append-only change logging with reasons.

### Rationale

Memories are fleeting facts; lifecycle and lineage prevent stale or conflicting memory corruption.

### Acceptance checks

- `supersede` does not mutate old record content.
- `as-of` timeline replay is possible for a memory and scope.

## Phase 2: Queries, CLI, and MCP

### Deliverables

- Add named Datalog queries from design doc.
- Add CLI surface: `noum memoize add|recall|show|list|invalidate|supersede|pin|unpin|promote|history|timeline|gc|import-automemory|summarize-session`.
- Add MCP tools: add, recall, invalidate, supersede, promote, explain, summarize-session.

### Rationale

Explicit read/write APIs keep memory behavior deterministic and auditable.

### Acceptance checks

- `memoize recall --explain` returns provenance and selection reasons.
- ACL blocks unauthorized reads of private memories.

## Phase 3: Retrieval Fusion and Ask Integration

### Deliverables

- Build hybrid retrieval pipeline (graph + lexical + vector + temporal + confidence).
- Integrate memory candidate selection into `noumenon_ask` with `--memoize auto|on|off`.
- Implement context pack assembly with token budgeting.

### Rationale

Hybrid ranking reduces irrelevant memory injection and improves practical answer quality.

### Acceptance checks

- `ask --memoize on` consistently returns memory citations when relevant.
- Invalid/superseded memories are excluded by default.

## Phase 4: Digest/Update/Watch Integration

### Deliverables

- Extend `digest` with memoize stages:
  1. `memoize-ingest`
  2. `memoize-embed`
  3. `memoize-index`
- Extend `update` to run incremental memoize sync.
- Extend `watch` to refresh memoize after commits/session summaries.
- Add digest flags:
  - `--memoize on|off|auto`
  - `--memoize-reanalyze all|stale|none`
  - `--memoize-import-source session|commit|pr|automemory|all`
- Implement stable source event identity for idempotent ingest:
  - canonical envelope builder
  - `source-event-id` hash generation
  - unique identity constraint in schema
- Persist and advance per-source cursors/watermarks for incremental sync.

### Rationale

Digest is Noumenon's canonical pipeline; Memoize must live there to stay consistent, idempotent, and operationally simple.

### Acceptance checks

- `digest` reruns are idempotent (no duplicate memory entities from same source event).
- Incremental updates process only changed sources.
- Re-ingesting unchanged textual events produces identical `source-event-id` values.
- Unique `source-event-id` constraint prevents duplicate writes under retry/race conditions.

### File-level implementation checklist

- `src/noumenon/main.clj`
  - Extend `do-digest` orchestration with memoize stages and flags.
  - Extend `do-update` and watch loop to run memoize incremental sync.
- `src/noumenon/sync.clj`
  - Add memoize incremental ingestion path and cursor handling.
- `src/noumenon/mcp.clj`
  - Update `noumenon_digest` tool schema/description with memoize options.
  - Add memoize MCP tools and handlers.
- `src/noumenon/cli.clj`
  - Add parser specs and validation for memoize flags on digest/ask/update.
- `resources/schema/*.edn`
  - Add memoize schema including unique `:memoize/source-event-id` and lifecycle attrs.
- `resources/queries/*.edn`
  - Add queries for source-event lookup, cursor checkpoints, and idempotency audits.
- `launcher/src/noum/cli.clj`
  - Update launcher command summaries/usage for digest and new memoize commands.
- `launcher/src/noum/main.clj`
  - Ensure new commands route to API endpoints consistently.

## Phase 5: Hooks and Workflow Enforcement

### Deliverables

- Keep existing MCP-first pre-read gate.
- Add optional memoize enforcement mode requiring `memoize_recall` before read/search.
- Add session-end write-back hook via `memoize_summarize_session`.
- Update install/setup path so `CLAUDE.md` is automatically patched with Memoize read/write instructions for agents.

Implementation details:

- Extend setup/install writer to manage a marker-based `CLAUDE.md` Memoize block.
- Ensure idempotent updates (no duplicate blocks across repeated installs).
- Keep instruction text versioned so future install runs can refresh outdated guidance.

### Rationale

Hooks provide enforcement where prompt guidance alone is insufficient.

Automated `CLAUDE.md` updates ensure agents receive correct memory workflow instructions without manual documentation drift.

### Acceptance checks

- In `enforced` mode, read/search is denied without recall evidence.
- In `advisory` mode, warning is emitted but execution continues.
- After `install`/`setup`, `CLAUDE.md` contains current Memoize instructions.
- Re-running `install`/`setup` updates the same block in place (idempotent).

### File-level implementation checklist

- `launcher/src/noum/setup.clj`
  - Add a dedicated Memoize CLAUDE.md marker block and merge strategy.
  - Ensure `setup-code!` writes hook/settings/instructions together.
- `.claude/hooks/noumenon-mcp-first.sh`
  - Extend policy checks only if strict memoize enforcement mode is enabled.
  - Keep fast-path session cache behavior to avoid repeated transcript scans.

## Phase 6: Quality, Introspect, and Governance

### Deliverables

- Add quality metrics: recall@k, stale-hit rate, wrong-memory rate, contradiction rate.
- Integrate introspect outputs for memory policy/ranking improvements.
- Add governance queries and cleanup workflows (`stale-candidates`, `orphans`, `duplicates`).

### Rationale

Memory quality decays without measurement and remediation loops.

### Acceptance checks

- Introspect reports include memoize-specific findings.
- GC/archive policies reduce stale-hit rate over baseline.

## Phase 7: Documentation and Public Website

### In-repo docs updates

- `docs/memoize-design.md` (architecture and schema)
- `docs/memoize-operations.md` (runbook and troubleshooting)
- `docs/auth-and-users.md` (API key model, ACL, attribution)
- `README.md` and CLI reference updates
- `CHANGES.md` migration/release notes

### Public website updates

- New Memoize concepts page
- Team memory model page (shared/private/session)
- Auth/API key page
- MCP reference updates with examples
- Security/privacy and explainability section

### Rationale

Memory systems require clear operator guidance and user trust; docs are part of system correctness.

### Acceptance checks

- Every new command/tool has one runnable example.
- Public docs include an explainability example from `memoize_explain`.

### Documentation implementation checklist

- Update `README.md` command overview with memoize and login/whoami references.
- Update `CHANGES.md` with migration notes and behavior changes for setup/install.
- Ensure docs explain that install/setup updates `CLAUDE.md` for agent behavior.
- Publish matching public docs pages in same release cycle to avoid OSS/docs mismatch.

## Concrete End-to-End Examples

### Example A: Digest with memoize enabled

```bash
noum digest /Users/leif/Code/noumenon --memoize auto --memoize-import-source all
```

Expected:

- graph pipeline runs
- memoize ingest/embed/index runs
- no duplicate memory records for identical source events

### Example B: Headless team write and recall

```bash
noum login --server https://noum.acme.dev --api-key nk_live_u_leif_123

noum memoize add \
  --repo noumenon \
  --type decision \
  --visibility shared \
  --text "Use MCP-first hook before file reads in Claude Code." \
  --anchor file:.claude/hooks/noumenon-mcp-first.sh \
  --source-kind session \
  --source-ref sess_2026_04_27_001 \
  --promote

noum memoize recall --repo noumenon --query "How is MCP-first enforced?" --explain
```

Expected:

- memory attributed to authenticated user
- recall includes source and anchor-based ranking rationale

### Example C: Temporal supersession

```bash
noum memoize supersede mem_101 mem_204 --reason "Hook v2 changed session cache path"
noum memoize history mem_101
noum memoize timeline --repo noumenon --since 2026-04-01
```

Expected:

- old memory preserved and marked superseded
- timeline shows auditable transition

### Example C2: Stable ID no-op reingest

```bash
noum update /Users/leif/Code/noumenon --memoize-sync
noum update /Users/leif/Code/noumenon --memoize-sync
```

Expected:

- second run does not create duplicate memories for unchanged source events
- memory counts remain stable except for genuinely new source inputs

### Example D: Install updates agent instructions

```bash
noum setup
```

Expected:

- `.mcp.json` configured
- hooks installed
- `CLAUDE.md` includes current "Noumenon Memoize - Read/Write Workflow" block
- rerunning setup does not duplicate the block

## Rollout Strategy

- Release behind feature flag: `memoize.enabled`.
- Start with advisory hook mode.
- Enable strict enforcement after stability and docs adoption.
- Run migration/backfill for known sources with `candidate` default status.

## Minimal Verification Matrix for AI Implementers

Run these checks before considering the implementation complete:

1. `noum help` shows Memoize commands and updated digest description.
2. `noum setup code` writes/updates Memoize instructions in `CLAUDE.md` exactly once.
3. MCP tool list includes new `noumenon_memoize_*` tools.
4. `noum digest <repo> --memoize auto` completes and records memoize stage outputs.
5. Re-running digest does not duplicate memory entities for unchanged source events.
6. `noum update <repo> --memoize-sync` performs incremental memory updates only.
7. `noum memoize recall --explain` returns provenance and ranking reasons.
8. Re-running identical ingest input yields identical `source-event-id` values and no duplicates.

## Risks and Mitigations

- **Noise from auto-ingestion**: use candidate status + promotion gates.
- **Cross-user leakage**: enforce visibility ACL in query layer and MCP layer.
- **Stale memory drift**: periodic stale detection, invalidation workflows, and introspect feedback.
- **Operational complexity**: keep digest integration idempotent and observable.
