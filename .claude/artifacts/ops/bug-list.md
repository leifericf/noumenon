# Bug Discovery

**Scope**: Full repo
**Current Commit**: 3432391
**Date**: 2026-04-02

---

## Status Summary (as of commit 3432391)

| Bug | Commit First Seen | Status |
|-----|-------------------|--------|
| BUG-06 | bb174fd | Fixed |
| BUG-07 | bb174fd | Fixed |
| BUG-08 | bb174fd | Open |
| BUG-09 | bb174fd | Fixed |
| BUG-N01 | 5fd1db3 | Fixed |
| BUG-N02 | 5fd1db3 | Fixed |
| BUG-N03 | 5fd1db3 | Fixed |
| BUG-N04 | 5fd1db3 | Fixed |
| BUG-N05 | 5fd1db3 | Fixed |
| BUG-N06 | 5fd1db3 | Open |
| BUG-N07 | 5fd1db3 | Fixed |
| BUG-N08 | 5fd1db3 | Fixed |
| BUG-N09 | 5fd1db3 | Fixed |
| BUG-N10 | 5fd1db3 | Fixed |
| BUG-N11 | 5fd1db3 | Fixed |
| BUG-N12 | 5fd1db3 | Fixed |
| BUG-N13 | 5fd1db3 | Fixed |
| BUG-N14 | 5fd1db3 | Fixed |
| BUG-N15 | 5fd1db3 | Open |
| BUG-N16 | 5fd1db3 | Fixed |
| BUG-N17 | 8b9b477 | Open |
| BUG-N18 | 8b9b477 | Fixed |
| BUG-N19 | 8b9b477 | Fixed |
| BUG-N20 | 8b9b477 | Fixed |
| BUG-N21 | 8b9b477 | Fixed |
| BUG-N22 | 8b9b477 | Fixed |
| BUG-N23 | 8b9b477 | Fixed |
| BUG-N24 | 8b9b477 | Fixed |
| BUG-N25 | 3432391 | Open |
| BUG-N26 | 3432391 | Fixed |
| BUG-N27 | 3432391 | Fixed |
| BUG-N28 | 3432391 | Fixed |
| BUG-N29 | 3432391 | Fixed |
| BUG-N30 | 3432391 | Fixed |

---

## Open Bugs

| severity | priority | fix_complexity | category | area | summary | repro | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| High | High | Easy | Core Functional Correctness | ui/state | `maybe-cache-file-card` caches partial cards — importers/authors/history never re-fetched | Click file node quickly after imports arrive; revisit node; observe importers/authors/history absent | BUG-N17: fix for N09 added caching calls in all 5 handlers but condition still only checks `:summary` and `:imports`; cards cached with 2/5 fields populated |
| Normal | Medium | Easy | Core Functional Correctness | benchmark | `:q37` deterministic-score returns `:skipped` on all non-empty-answer paths when no cross-directory imports exist | Run benchmark on repo with no cross-dir imports; any answer scores `:skipped` regardless of whether answer acknowledges absence | BUG-N25: NEEDS_REPRO: Code at lines 429-432 already returns `:correct` and `:wrong` (not `:skipped`). The described bug pattern does not exist in the current code. Line numbers 439-442 referenced in the report are actually in `:q38`, not `:q37`. |
| Normal | Medium | Easy | Core Functional Correctness | ui/state | Graph segment `graph-segment-cluster-ready` overwrites nodes/edges without staleness guard | Expand file; navigate away before all 4 concurrent requests complete; stale file's graph appears | BUG-N06: `state.cljs:721-726` — no guard that `file-path` still matches `(:graph/expanded-file state)` |
| Normal | Low | Easy | Core Functional Correctness | ui/state | `action/ask-cancel` does not clear `:ask/steps` — stale reasoning shown on next run | Start ask; cancel mid-run; submit new question; old step traces visible in "Show reasoning" | BUG-08: `ui/src/noumenon/ui/state.cljs:377-379` |
| Minor | Low | Easy | Observability/Operator Experience | imports | C/C++ enrichment skipped silently when compiler absent — no per-file error in tally | Import a repo with C/C++ files on system without gcc/clang; no warning that C files were skipped | BUG-N15: `src/noumenon/imports.clj:661-665` — `c-results` is nil when compiler absent; `into std-results nil` is safe but silent |

---

## Fixed Bugs (reference)

### BUG-N01 (fixed) — `validate-string-length!` in MCP crashed with NPE on nil input
`mcp.clj` — now has `(and (string? s) ...)` guard matching the HTTP version. `with-conn` also added an explicit `(when-not (string? raw-path) (throw …))` guard.

### BUG-N02 (fixed) — `handle-update` ignored `provider`/`model` args when `analyze=true` (MCP)
`mcp.clj:410` — now uses `(or (args "provider") (:provider defaults))` and `(or (args "model") (:model defaults))`.

### BUG-N03 (fixed) — Same `handle-update` bug in HTTP layer
`http.clj` — now uses `(resolve-provider params config)`.

### BUG-N04 (fixed) — `derive-db-name` could return nil causing downstream NPE
`util.clj:58-61` — now throws `ex-info` instead of returning nil.

### BUG-N05 (fixed) — `retract-synthesis!` erased analyze-written `:sem/purpose` for unclassified files
`synthesize.clj:218` — `retraction-tx-data` now only queries `[?e :arch/component _]` instead of the broad OR clause.

### BUG-N07 / BUG-07 (fixed) — introspect status/stop endpoints always returned 404
`http.clj` — `run_id` now generated, stored in `sessions`, and returned in response. `handle-introspect-status` / `handle-introspect-stop` look up by `run_id` from session store.

### BUG-N08 (fixed) — `handle-delete-database` did not evict cached connection
`http.clj:561` — now calls `(db/evict-conn! db-dir db-name)` after deletion.

### BUG-N09 (fixed) — `maybe-cache-file-card` caching broken; all five handlers now call it
Partial fix applied: caching calls added to all five response handlers. However BUG-N17 (incomplete card cached) was introduced.

### BUG-N10 (fixed) — `dispatch-query` always passed rules regardless of `uses-rules`
`agent.clj:177-184` — now uses `query-uses-rules?` predicate before passing rules.

### BUG-N11 (fixed) — `db-stats` called `schema/ensure-schema` on every invocation
`db.clj:87-105` — `db-stats` no longer calls `schema/ensure-schema`; receives a pre-created `client` argument.

### BUG-N12 (fixed) — `do-watch` captured stale `meta-db` snapshot at startup
`main.clj:274-275` — watch loop now takes fresh `(d/db meta-conn)` on each iteration.

### BUG-N13 (fixed) — `:q01` scored `:correct` when no complex files exist
`benchmark.clj:189-190` — added explicit `(zero? total)` guard returning `:skipped`.

### BUG-N14 / BUG-N22 (fixed) — malformed commit dates aborted import silently
`git.clj:108-116` — `parse-iso-instant` now wrapped in try/catch with warning log; `parse-record` skips bad dates.

### BUG-N16 (fixed) — `synthesize-repo!` retracted data before validating new write
`synthesize.clj:267-284` — retraction and new data now combined in a single `d/transact` call.

### BUG-N18 (fixed) — `retraction-tx-data` used stale snapshot before LLM call
`synthesize.clj:267` — now calls `(retraction-tx-data (d/db conn))` after the LLM call, not before.

### BUG-N19 (fixed) — `edn/read-string` in `handle-settings-post` lacked tagged-literal protection
`http.clj:880` — now uses `(edn/read-string {:readers {}} value)`.

### BUG-N20 (fixed) — `edn/read-string` in `handle-query-raw` lacked tagged-literal protection
`http.clj:449` — now uses `(edn/read-string {:readers {}} query-edn)`.

### BUG-N26 (fixed) — `edn/read-string` in agent.clj and introspect.clj lacked tagged-literal protection
`agent.clj:127` and `introspect.clj:323,29,188,201,213,380` — all `edn/read-string` calls now use `{:readers {}}`.

### BUG-N27 (fixed) — `edn/read-string` in artifacts.clj lacked tagged-literal protection
`artifacts.clj:112,114,133` — all `edn/read-string` calls now use `{:readers {}}`.

### BUG-N28 (fixed) — `ensure-meta-db` bypasses connection cache
`db.clj:53-59` — now delegates to `get-or-create-conn` instead of calling `connect-and-ensure-schema` directly.

### BUG-06 (fixed) — `set-feedback!` hardcoded `:negative` polarity
`ask_store.clj:122-128` — now accepts a `polarity` parameter. HTTP handler reads `:feedback` from request body and validates it as `:positive` or `:negative`.
