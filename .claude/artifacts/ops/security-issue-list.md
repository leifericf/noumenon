# Security Issue Discovery — Round 3 (Post-Fix Audit)

**Scope:** Full repo (src/noumenon/*.clj)
**Commit:** f4ff045acef240ea951c0fbdf41d3249fe5c63f1
**Date:** 2026-03-27
**Purpose:** Verify Round 2 fixes, check for regressions and new issues in commits since e63cd59

---

## Fix Verification (Round 2 fixes)

All 7 Round 2 fixes carried forward from the prior audit remain correct. No regressions were found in:
- `imports.clj` — shell injection fix verified still present
- `benchmark.clj` — XML path injection fix verified still present
- `mcp.clj` — model/provider length validation wired to all relevant handlers
- `util.clj` — all-dots guard in `derive-db-name` still present
- `mcp.clj` — `handle-update` path canonicalization still present
- `analyze.clj` — `valid-git-path?` leading-dash guard still present
- `mcp.clj` — `validate-layers` allowlist still correct

---

## New Issues Found in Round 3

### SEC-014: MCP run_id fields have no length bound

`handle-benchmark-results` and `handle-benchmark-compare` accept `run_id`, `run_id_a`, and `run_id_b` as free-form strings with no length validation. These are passed directly to Datomic parameterized queries. The `max-line-bytes` guard (10 MB) limits overall request size, but there is no per-field cap comparable to `max-repo-path-len` or `max-model-len`.

- **Severity:** Minor
- **Priority:** Low
- **Fix Complexity:** Trivial
- **Category:** Input Validation/Injection
- **Area:** mcp.clj — handle-benchmark-results, handle-benchmark-compare
- **Evidence:** `mcp.clj:423` — `(let [run-id (args "run_id") ...])` — no `validate-string-length!` call before use. `mcp.clj:460-461` — same for `id-a`/`id-b`. The `validate-run-id` guard used in `checkpoint-read` and `find-checkpoint` is not called in MCP handlers.
- **Threat scenario:** An MCP caller passes a multi-megabyte string as `run_id`. Datomic processes it as a query parameter; no match is returned but excess memory is allocated during query evaluation. Low-impact DoS under heavy load.
- **Suggested mitigation:** Add `(validate-string-length! "run_id" run-id 256)` before each Datomic query in `handle-benchmark-results` and `handle-benchmark-compare`, consistent with other handler patterns. The `validate-run-id` format check could also be applied here for defense-in-depth (no early error leakage since a non-matching run_id just returns "No benchmark runs found").

---

## Re-examined Areas (Pass 2 Saturation)

The following were re-examined for regressions or new vectors introduced by commits since e63cd59:

- **agent.clj** — System prompt is now prepended once at init as the first user message (commit 3661216). The `sanitize-repo-name` allowlist guard still runs before `{{repo-name}}` substitution. No injection regression.
- **mcp.clj** — `handle-update` now canonicalizes path via `.getCanonicalPath` before calling `validate-repo-path!`. Correct. `with-conn` also canonicalizes. No double-canonicalization issue.
- **benchmark.clj** — skip-judge behavior change is functional only; no security impact. `validate-run-id` is used for checkpoint file path construction and `find-checkpoint`; correct scope.
- **pipeline.clj** — Added default `stop-flag`/`error-atom` atoms. No security impact.
- **analyze.clj** — `valid-git-path?` guard unchanged. `files-needing-analysis` correctly skips sensitive files.
- **main.clj** — No new input paths; carried-forward SSRF via git URLs unchanged.
- **llm.clj** — `invoke-claude-cli` still passes full flattened conversation history as `-p` argument without length bound (SEC-012, carried forward). No regression.
- **sync.clj** — `git diff` missing `--` separator (SEC-006, carried forward). No regression.

---

## Carried-Forward Issues (Not Fixed, Not Regressed)

### SEC-002: SSRF via unvalidated Git URLs (CLI only)
- **Severity:** High
- **Priority:** Medium
- **Fix Complexity:** Easy
- **Category:** Input Validation/Injection
- **Area:** `git.clj:29-36`, `main.clj:69-81`
- **Status:** Accepted risk (CLI-only, no MCP surface). Only the CLI `import`/`update`/`digest`/`watch` subcommands expose this; MCP `noumenon_import` requires a local path and runs `validate-repo-path!`.
- **Mitigation available:** Resolve hostname to IP before `git clone!` and reject RFC-1918/loopback ranges. Alternatively document that this is a trusted-user CLI tool and the SSRF risk is accepted.

### SEC-004: Datalog query denial-of-service in agent.clj
- **Severity:** Normal
- **Priority:** Low
- **Fix Complexity:** Challenging
- **Category:** Input Validation/Injection
- **Area:** `agent.clj:139-147`
- **Status:** **FIXED.** Query already ran in a `future` with 30s `deref` timeout and `future-cancel` on timeout. Added `OutOfMemoryError` catch inside the future to gracefully handle heap exhaustion from cartesian-join queries.

### SEC-006: `git diff` missing `--` separator
- **Severity:** Minor
- **Priority:** Very Low
- **Fix Complexity:** Trivial
- **Category:** Input Validation/Injection
- **Area:** `sync.clj:44-47`
- **Status:** Carried forward. `valid-sha?` guards the call; adding `"--"` before `old-sha` adds defense-in-depth at negligible cost.

### SEC-007: API error body logged to stderr
- **Severity:** Minor
- **Priority:** Very Low
- **Fix Complexity:** Trivial
- **Category:** Monitoring/Detection/Response
- **Area:** `llm.clj:117-119`
- **Status:** Carried forward. Up to 200 chars of raw API error body are logged to stderr. Low exploitability since stderr is not returned to MCP callers.

### SEC-009: `escape-template-vars` naming is misleading
- **Severity:** Minor
- **Priority:** Very Low
- **Fix Complexity:** Trivial
- **Category:** Security Misconfiguration
- **Area:** `util.clj:18-21`
- **Status:** Carried forward. Documentation/naming issue only; the function does what it needs to. Rename to `escape-double-mustache` or add a docstring clarifying it only escapes `{{`.

### SEC-012: Claude CLI argument length unbounded for large prompts
- **Severity:** Minor
- **Priority:** Low
- **Fix Complexity:** Easy
- **Category:** Input Validation/Injection
- **Area:** `llm.clj:154-158`
- **Status:** Carried forward. Flattened conversation history passed as a `-p` argument can grow to hundreds of KB across agent iterations. OS argument-length limits (~2 MB on macOS/Linux) provide a hard floor.

---

## Summary

| Category                    | Count |
|-----------------------------|-------|
| Round 2 fixes verified correct | 7  |
| Net-new issues (Round 3)     | 1     |
| Regressions                  | 0     |
| Carried-forward issues       | 6     |

---

## Issue Table

| severity | priority | fix_complexity | category | area | summary | threat_scenario | evidence | suggested_mitigation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Minor | Low | Trivial | Input Validation/Injection | mcp.clj | run_id fields lack length bound in MCP handlers | MCP caller passes multi-megabyte run_id string; excess memory allocated during Datomic query parameter handling | `mcp.clj:423` — no `validate-string-length!` before `(d/q ... db run-id)`; same for `mcp.clj:460-461` | Add `(validate-string-length! "run_id" run-id 256)` in `handle-benchmark-results` and both run_id fields in `handle-benchmark-compare` |
| Minor | Very Low | Trivial | Input Validation/Injection | sync.clj | `git diff` missing `--` separator | If `valid-sha?` regex has edge-case bypass, a crafted SHA could be interpreted by git as a flag or refspec | `sync.clj:44-47` — `shell/sh "git" "-C" ... "diff" "--name-status" old-sha "HEAD"` — no `"--"` before `old-sha` | Insert `"--"` before `old-sha`: `(shell/sh "git" "-C" ... "diff" "--name-status" "--" old-sha "HEAD")` |
| Minor | Very Low | Trivial | Monitoring/Detection/Response | llm.clj | API error body logged to stderr | Raw LLM API error body (up to 200 chars) written to stderr; could leak internal API error messages or partial response data | `llm.clj:117-119` — `(log! (str "API error response (HTTP " status "): " (truncate (str body) 200)))` | Log only the HTTP status code; omit the raw body, or log only a sanitized subset (e.g., error code field from parsed JSON) |
| Minor | Very Low | Trivial | Security Misconfiguration | util.clj | `escape-template-vars` function name misleading | Developer adds a new `{{variable}}` to a prompt template using a different syntax (e.g., `<%var%>`), assumes `escape-template-vars` covers it, and a prompt injection path opens | `util.clj:18-21` — function replaces only `{{` with `{ {`, name implies broader coverage | Rename to `escape-double-mustache` or add a docstring: "Only escapes `{{` — verify any new template syntax is also escaped at injection points" |
| Minor | Low | Easy | Input Validation/Injection | llm.clj | Claude CLI -p argument unbounded for long agent conversations | Agent runs many iterations; flattened conversation history grows to MB-scale and is passed as single `-p` argument to `claude` CLI process; OS arg-length limit (~2 MB) causes process failure | `llm.clj:154-158` — `(into ["-p" prompt])` where `prompt` is `flatten-messages` of full history; no length cap | Truncate oldest messages from conversation history before flattening, or split into `--resume`/pipe input instead of `-p` argument |
