# UX/UI Issue Discovery — Pass 1

**Scope:** Full repo — CLI and MCP interfaces
**Commit:** e63cd59
**Date:** 2026-03-26

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0     |
| Major    | 6     |
| Minor    | 11    |
| Cosmetic | 2     |

## Issues

### UX-001: `watch` error loop — repeated sync failures silently accumulate with no escalation
- **Severity:** Major
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:196-200`
- **Description:** When `sync-repo!` throws inside the `watch` poll loop, the error is caught, logged once, and the loop continues. There is no failure counter, no backoff, and no stop condition. A broken database or network failure produces a new error log line every 30 seconds forever with no suggestion of what to do.
- **Evidence:** `(catch Exception e (log! (str "Update error: " (.getMessage e))))` followed immediately by `(Thread/sleep ...)` and `(recur)` — no state tracking, no escalation.
- **Suggestion:** Track consecutive failure count. After N failures (e.g. 5), log a prominent actionable message: "Repeated errors — check logs. Run `noumenon import <repo-path>` to reset." Consider exponential backoff.
- **Confidence:** High

### UX-002: `query list` sub-subcommand is absent from help text — undiscoverable
- **Severity:** Major
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/cli.clj:277` and `/Users/leif/Code/noumenon/src/noumenon/main.clj:203-212`
- **Description:** `query list` is handled via a special case in `parse-simple-args` but the `command-registry` entry for `query` documents usage only as `query [options] <query-name> <repo-path>`. Running `clj -M:run query --help` shows no mention of the `list` subform. Users cannot discover available named queries without reading source code.
- **Evidence:** `command-registry` "query" entry has `:usage "query [options] <query-name> <repo-path>\n       query list"` — the `query list` form is included in the usage string but the epilog and flag descriptions provide no further guidance on what the list shows or when to use it.
- **Suggestion:** Add an epilog: "Use `query list` (no repo-path) to show available named queries with descriptions."
- **Confidence:** High

### UX-003: `ask` command requires `-q` / `--question` but gives no hint when missing
- **Severity:** Major
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:558-559` and `/Users/leif/Code/noumenon/src/noumenon/cli.clj:225-251`
- **Description:** Running `clj -M:run ask /path/to/repo` without `-q` produces `"Error: Missing -q <question> argument."` The `ask` usage string is `ask -q <question> [options] <repo-path>`, which means the question flag must come before the repo path — a non-obvious constraint. The error message `:ask-missing-question` is in `errors-with-subcommand-usage` so subcommand help is shown, but the help does not explain that `-q` must precede positionals.
- **Evidence:** `validate-ask-question` returns `{:error :ask-missing-question}` with no positional hint. The `--resume` flag has explicit placement guidance in its description; `-q` does not.
- **Suggestion:** Add placement note to flag description: `"Question to ask (must come before <repo-path>)"`.
- **Confidence:** High

### UX-004: `list-databases --delete` is immediately destructive with no confirmation or cost warning
- **Severity:** Major
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:344-357`
- **Description:** `do-list-databases` deletes the named database immediately upon finding it. The success message says `"Re-import: clj -M:run import <repo-path>"` but does not mention that all LLM analysis data (potentially costing $10s–$100s of dollars in API calls) is destroyed and must be re-run. A typo in the database name could silently destroy a wrong database entry (though derive-db-name sanitization reduces that risk).
- **Evidence:** `(db/delete-db client db-name)` is called unconditionally after the `db-exists?` check. No `--force` flag, no `--dry-run`, no prompt.
- **Suggestion:** Add a warning line before deletion listing the cost/analyze stats for the database being deleted. Consider requiring `--force` flag or printing "Pass --force to confirm deletion." A `--dry-run` flag showing what would be deleted is also useful.
- **Confidence:** High

### UX-005: MCP `noumenon_ask` returns `nil` text when agent exhausts budget — breaks MCP protocol
- **Severity:** Major
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:303-304`
- **Description:** When the agent exhausts its iteration budget, `(:answer result)` is `nil`. The handler uses `(tool-result (or (:answer result) (str "No answer found (status: " (name (:status result)) ")")))` — this is actually already handled with the `or`. However, the `:budget-exhausted` status is not surfaced as an `isError` response. An AI assistant receives a success-looking tool result with "No answer found" text but no machine-readable signal that the budget was exhausted vs. a genuine "no information in graph" answer.
- **Evidence:** `(tool-result (or (:answer result) (str "No answer found (status: " (name (:status result)) ")")))` — `tool-result` always sets `:isError false`. A `tool-error` would be more appropriate for `:budget-exhausted`.
- **Suggestion:** When `(:status result)` is `:budget-exhausted`, return `(tool-error (str "Budget exhausted after " max-iter " iterations — no answer found. Try increasing max_iterations or narrowing the question."))` so the AI assistant knows to retry with different parameters.
- **Confidence:** High

### UX-006: MCP auto-update blocks all tool responses synchronously with no timeout or progress
- **Severity:** Major
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:200-207`
- **Description:** When `(sync/stale? db repo-path)` is true, `with-conn` calls `sync/update-repo!` synchronously before returning any result to the MCP client. For large repositories this can take minutes. The MCP client receives no keepalive, no progress indication, and may time out. The `log!` call goes to stderr (invisible to the MCP client). There is no configurable timeout.
- **Evidence:** `(when (:auto-update defaults true) (let [db (d/db conn)] (when (sync/stale? db repo-path) (log! "auto-update" "HEAD changed, updating...") (sync/update-repo! conn repo-path repo-uri {:concurrency 8}))))` — fully blocking, no timeout, no intermediate response.
- **Suggestion:** Either make auto-update asynchronous (update in background, return possibly-stale results immediately), or add a configurable `:auto-update-timeout-ms` that falls back to returning stale results with a warning if exceeded.
- **Confidence:** High

### UX-007: `--interval` and `--concurrency` validation errors missing from `error-messages` map — produce blank errors
- **Severity:** Major
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:534-565`
- **Description:** `watch-command-spec` declares `:error-invalid :invalid-interval` and `:error-missing :missing-interval-value`. Neither keyword appears in the `error-messages` map. When a user passes `--interval bad`, the `when-let` in `run` finds no match and produces no error message — the CLI fails silently (prints nothing, exits 1). Same gap: `:invalid-interval`, `:missing-interval-value` are missing.
- **Evidence:** `error-messages` map at lines 534–565 contains entries for `invalid-concurrency`, `missing-concurrency-value`, `invalid-min-delay`, `missing-min-delay-value`, `invalid-max-iterations`, but has no `:invalid-interval` or `:missing-interval-value` entries.
- **Suggestion:** Add to `error-messages`: `:invalid-interval #(str "Invalid --interval value: " (:value %) ". Must be a positive integer.")` and `:missing-interval-value "Missing value for --interval."`. Also add both to `errors-with-subcommand-usage`.
- **Confidence:** High

### UX-008: `--concurrency` default documented as "default varies: analyze=3, others=8" only in shared flag but per-command help says "default: 8" inconsistently
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/cli.clj:69-74`
- **Description:** The shared `concurrency-flags` description says "default varies: analyze=3, others=8". However `enrich-command-spec`, `update-command-spec`, and `watch-command-spec` each define their own `--concurrency` flag with `:desc "Parallel workers, 1-20 (default: 8)"`. Running `--help` on those subcommands shows "default: 8" without the caveat. Running `--help` on `benchmark` shows "default varies: analyze=3, others=8". The actual analyze default is 3 (set at line 126 of main.clj), benchmark is 3 (line 374). Users reading the enrich/update/watch help get an accurate "8" but users of analyze or benchmark must know to look elsewhere.
- **Evidence:** `concurrency-flags` at line 69: `"Parallel workers, 1-20 (default varies: analyze=3, others=8)"`. `enrich-command-spec` at line 127: `"Parallel workers, 1-20 (default: 8)"`.
- **Suggestion:** Each subcommand's `--concurrency` flag description should state its own specific default, not rely on cross-referencing the shared description.
- **Confidence:** High

### UX-009: `do-update` provides no "next step" hint after completing — unlike `import` and `enrich`
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:150-171`
- **Description:** After `import` completes, the tool prints a "Next: run enrich..." hint. After `enrich` completes, it prints "Next: run query file-imports..." hint. After `update` (which runs import + enrich internally), there is no next-step hint. Users who run `update` as their first command have no guidance that `--analyze` is needed for LLM enrichment or that `ask` and `query` are the natural next tools.
- **Evidence:** `do-update` at line 150 logs `"Update complete (N ms)"` from `sync/update-repo!` but adds no application-level guidance. `do-import` at line 96 explicitly adds a next-step message.
- **Suggestion:** After a fresh import (`:status :fresh-import`), print the same guidance as `do-import`. After an incremental sync, print "Run `ask` or `query` to explore the updated graph."
- **Confidence:** High

### UX-010: `benchmark` canary failure warning is emitted to stderr log only — not prominently surfaced
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/benchmark.clj:33-41`
- **Description:** `canary-evaluate` returns `{:status :warn}` when all canary questions score `:wrong`. The warning appears in benchmark logs but there is no prominent banner distinguishing it from normal progress lines. A user scanning terminal output may miss that the entire benchmark is likely invalid (data not analyzed, model is wrong, etc.).
- **Evidence:** `canary-evaluate` is a pure function returning a status map. How it is printed depends on the caller; the log output blends with `[N/M] q01:full:judge — 2.3s` style lines.
- **Suggestion:** If canary status is `:warn`, print a high-visibility block: `"*** CANARY WARNING: All canary questions failed. Results are unreliable. Check that analyze has been run and the correct model is specified. ***"`.
- **Confidence:** Medium

### UX-011: `digest` does not surface per-step results to the user during execution — appears hung
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:480-530`
- **Description:** `do-digest` runs import+enrich, then analyze, then benchmark sequentially. Each step can take minutes to hours. The only progress output is `"digest: import + enrich..."`, `"digest: analyze..."`, `"digest: benchmark..."`, `"digest: complete"`. Between each of these banners, the individual step's own progress is logged, but if any step produces no sub-progress (e.g. "All files already analyzed, nothing to do"), the user sees silence for the duration of that step.
- **Evidence:** Lines 494, 499, 509, 527 each log a single banner line. The analyze step's internal `log!` calls are still visible (they go to stderr), but the digest-level wrapper adds no elapsed time or completion percentages.
- **Suggestion:** After each step completes, log elapsed time and result summary: `"digest: import + enrich done (12s — 847 commits, 312 files)"`. This mirrors how each step reports individually.
- **Confidence:** High

### UX-012: MCP `noumenon_update` returns raw EDN `pr-str` output instead of a human-readable summary
- **Severity:** Minor
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:281-282`
- **Description:** `handle-update` returns `(tool-result (pr-str result))`. An AI assistant receives Clojure syntax: `{:status :synced, :added 3, :modified 1, :deleted 0, :commits 0, :files 3, ...}`. Compare with `handle-import` which returns a natural-language sentence. The inconsistency forces the AI to parse EDN rather than read a summary.
- **Evidence:** `(tool-result (pr-str result))` at line 282. `handle-import` at line 215: `(tool-result (format-import-summary git-r files-r))` — uses a natural-language formatting function.
- **Suggestion:** Format as natural language: `"Updated. 3 files added, 1 modified, 0 deleted. Commits: 2. Import graph refreshed."` Similar to `format-import-summary`.
- **Confidence:** High

### UX-013: MCP `noumenon_analyze` and `noumenon_enrich` return raw EDN — inconsistent with other tools
- **Severity:** Minor
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:318-319` and `326-327`
- **Description:** Both `handle-analyze` and `handle-enrich` return `(tool-result (pr-str result))`. `handle-analyze` result contains keys like `:files-analyzed`, `:files-parse-errored`, `:total-usage` with nested maps. An AI assistant receives raw Clojure data rather than a readable summary.
- **Evidence:** `(tool-result (pr-str result))` at lines 318 and 326. `handle-benchmark-run` at line 354 uses a formatted string with labeled fields.
- **Suggestion:** Format analyze results as: `"Analysis complete. 47 files analyzed, 2 parse errors, 1 error. Cost: $0.23. Run time: 8m."` Format enrich results with resolved import count.
- **Confidence:** High

### UX-014: `noumenon_ask` tool description does not explain what "iterative Datalog querying" means
- **Severity:** Minor
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:117-125`
- **Description:** The tool description reads "Ask a question about a repository using AI-powered iterative Datalog querying". An AI assistant using this tool has no context on what "iterative Datalog querying" means, when this tool is appropriate vs. `noumenon_query`, or what its limitations are (e.g., only works after import, uses LLM API credits).
- **Evidence:** Description at line 118: `"Ask a question about a repository using AI-powered iterative Datalog querying"` — opaque for any caller unfamiliar with the system.
- **Suggestion:** Expand: `"Ask a natural-language question about a repository. The agent runs iterative Datalog queries against the knowledge graph to find an answer. Requires prior import. Uses LLM API calls (costs money). For structured queries, prefer noumenon_query."`.
- **Confidence:** High

### UX-015: MCP `noumenon_digest` tool description does not warn about cost or duration
- **Severity:** Minor
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:173-186`
- **Description:** `noumenon_digest` runs the full pipeline including LLM analysis and benchmark, which can cost $10–$100+ in API calls and take 30+ minutes. The description says "Run the full Noumenon pipeline" and mentions it is idempotent, but gives no cost or time warning. `noumenon_benchmark_run` has "WARNING: Expensive" in its description; `noumenon_digest` does not.
- **Evidence:** `noumenon_benchmark_run` description: `"WARNING: Expensive — uses many LLM calls."` `noumenon_digest` description has no equivalent warning.
- **Suggestion:** Add: `"WARNING: Expensive and slow — runs LLM analysis (cost varies by repo size) and benchmarking. May take 30+ minutes. Use skip_analyze=true and skip_benchmark=true for a quick structural import."`.
- **Confidence:** High

### UX-016: `--resume` placement constraint is documented but the failure mode is misleading
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/cli.clj:84-87`
- **Description:** The `--resume` flag description says "Place before `<repo-path>` to avoid ambiguity." If a user writes `benchmark /path/to/repo --resume`, the `--resume` flag's `optional-string` parser will see `/path/to/repo` already consumed as a positional, then `--resume` has no following positional to consume (end of args), so it defaults to `"latest"`. But if they write `benchmark --resume /path/to/repo`, the parser sees `/path/to/repo` following `--resume` and consumes it as the checkpoint value — leaving no repo-path positional, producing `"Missing <repo-path> argument."` with no explanation that `--resume` consumed the path.
- **Evidence:** `:parse :optional-string` logic at lines 398-401 of cli.clj: consumes next non-flag token as the value. `resume-flag` description: "The next non-flag argument is consumed as the run ID."
- **Suggestion:** Make the behavior explicit in the error: when `--resume` is followed by a path-like value and repo-path is missing, warn: `"Did --resume consume your repo-path? Use --resume latest <repo-path> or just --resume after <repo-path>."`.
- **Confidence:** Medium

### UX-017: `benchmark` checkpoint incompatibility error message exposes internal field names without explanation
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:415-431`
- **Description:** When a checkpoint is incompatible, the error lists fields like `question-set-hash`, `rubric-hash`, `answer-prompt-hash`. These are internal implementation details that are meaningless to a user. The error says "Incompatible checkpoint" and lists mismatches but doesn't explain what action to take.
- **Evidence:** `field-labels` map at line 415 maps `:question-set-hash` to `"Question set"`, `:rubric-hash` to `"Rubric"`, etc. — these are slightly better but the message still ends with a dump of checkpoint vs. current hash values.
- **Suggestion:** After the mismatch list, always append: `"Start a fresh run: clj -M:run benchmark <repo-path>"`. Replace hash values with human-readable description: "Question set changed (benchmark questions were updated)" rather than showing hex hashes.
- **Confidence:** High

### UX-018: `do-status` uses `println` to stdout while all other informational output uses stderr
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:307-312`
- **Description:** `do-status` uses `log!` which writes to stderr, consistent with the rest of the codebase. However, `print-db-stats` in `do-list-databases` uses `println` (stdout). This creates inconsistency: `status` output is on stderr; `list-databases` output is on stdout. Scripts capturing stdout get database listing but not status output.
- **Evidence:** `print-db-stats` at line 333: `(println (format ...))`. `do-status` at line 307: `(log! (str ...))`. Both are human-readable informational output.
- **Suggestion:** Standardize: both should use `log!` (stderr) for human-readable summary lines, since neither command's result goes through the `prn` data-output path (both are in the exclusion set at line 627-629).
- **Confidence:** High

### UX-019: `benchmark` cost warning `*** COST WARNING` uses Unicode `***` that renders inconsistently
- **Severity:** Cosmetic
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/benchmark.clj:971-976`
- **Description:** `log-cost-estimate!` outputs `"  *** COST WARNING: ..."`. This is ASCII-safe but the triple-asterisk style is visually similar to Markdown formatting, which some terminal log viewers or CI systems render as bold/italic, making the line harder to spot in plain text. The rest of the codebase uses plain bracketed prefixes like `[1/22]`.
- **Evidence:** `(log! (str "  *** COST WARNING: Benchmarks are expensive. ..."))` at line 971.
- **Suggestion:** Minor: keep the warning prominent but use a consistent prefix style: `"[COST WARNING] Benchmarks are expensive. ..."`.
- **Confidence:** Low

### UX-020: `noumenon_list_queries` output format is free-text — AI assistants cannot programmatically extract query names
- **Severity:** Cosmetic
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:249-255`
- **Description:** `handle-list-queries` returns a newline-joined string of `"name — description"` lines. While readable, an AI assistant that wants to pick a query name for a subsequent `noumenon_query` call must parse this text. A structured format (e.g. EDN or JSON of `[{:name ... :description ...}]`) would be more reliable.
- **Evidence:** `(->> ... (str/join "\n") tool-result)` at line 254. The CLI `do-query-list` similarly uses `(format "  %-28s %s" name description)`.
- **Suggestion:** Return a structured format — EDN map or a consistent `"name: description\n"` format that can be split on `\n` and then on the first `: `. Or simply prefix each line with the name in a fixed format like `[name] description`.
- **Confidence:** Medium

---

## Pass 2 — Saturation

**Date:** 2026-03-27
**Focus:** benchmark.clj report readability, analyze.clj progress, pipeline.clj error reporting, MCP error paths, CLI edge cases

### UX-022: `analyze-repo!` ETA is only shown at the start — no ETA update during long runs
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/analyze.clj:415-425` and `390-398`
- **Description:** `estimate-banner` prints a single pre-run estimate once before analysis starts. The per-file log line at line 390 shows `[N/total] path 2.1s ok tokens=1200/218` but no ETA for time remaining. On a 300-file repository at 18s/file (~90 minutes), users see elapsed time per file but cannot estimate completion. The ETA is computable from `avg-ms-per-file`, current elapsed, and remaining count but is never shown mid-run.
- **Evidence:** `analyze-one-file!` at line 390 logs path, duration, status, and tokens but does not include a running ETA. `format-eta` exists as a utility but is only called at the pre-run banner and final summary.
- **Suggestion:** Add a rolling ETA to the per-file log line using actual elapsed/completed stats: `"[N/total] path 2.1s ok — ETA ~12m"`. Recompute ETA from `(* (/ elapsed-ms started) remaining)` using the actual `stats-atom` values.
- **Confidence:** High

### UX-023: `benchmark` report `generate-report` shows `nil` for `deterministic-mean` and `llm-judged-mean` when those scoring methods produce no results
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/benchmark.clj:1200-1206`
- **Description:** In the "Results by Scoring Method" table, `generate-report` always emits rows for both `Deterministic` and `LLM-judged` even when one category has no questions (e.g. `--fast` mode runs deterministic-only). When there are no LLM-judged questions, `(:llm-judged-mean aggregate)` is `0.0` (from `aggregate-scores`) and `(:llm-judged-count aggregate)` is `0`, producing a row `| LLM-judged | 0.0% | 0 |` that implies zero performance rather than "not run". This is misleading for fast/deterministic-only runs.
- **Evidence:** `(.append sb (str "| LLM-judged | " (format-pct (:llm-judged-mean aggregate)) " | " (:llm-judged-count aggregate) " |"))` at line 1204 — no guard against `:llm-judged-count` being 0.
- **Suggestion:** Suppress a scoring method row when its count is 0: `(when (pos? (:llm-judged-count aggregate)) ...)`. Or replace the `0.0%` value with `"—"` and add a note `"(not run)"`.
- **Confidence:** High

### UX-024: `unknown-subcommand` error is not in `errors-with-global-usage` or `errors-with-subcommand-usage` — user gets error with no help
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/main.clj:536-537` and `567-576`
- **Description:** When a user types an unknown subcommand (e.g. `clj -M:run foobar`), `parse-args` returns `{:error :unknown-subcommand :subcommand "foobar"}`. In `run`, the error message is found (`error-messages` has `:unknown-subcommand`), so the message "Unknown subcommand: foobar. Run 'noumenon --help'..." is printed. However, `:unknown-subcommand` is not in `errors-with-global-usage` so global usage is not printed, and it is not in `errors-with-subcommand-usage` (no subcommand to look up). The user gets one line and then silence — no list of valid subcommands is shown.
- **Evidence:** `errors-with-global-usage` at line 567 contains only `#{:no-args}`. `errors-with-subcommand-usage` at line 570 does not include `:unknown-subcommand`. The error message references `--help` but does not print the global usage inline.
- **Suggestion:** Add `:unknown-subcommand` to `errors-with-global-usage` so the full command list is shown after the error message, or print `(format-global-help)` inline in the `:unknown-subcommand` branch.
- **Confidence:** High

### UX-025: `handle-digest` in MCP returns raw EDN result map — inconsistent with every other MCP handler
- **Severity:** Minor
- **Surface:** MCP
- **File:** `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:457`
- **Description:** `handle-digest` ends with `(tool-result (pr-str @results))`. The results atom accumulates `:update`, `:analyze`, and `:benchmark` sub-maps, each containing nested Clojure data. The AI assistant receives a raw `{:update {...} :analyze {...} :benchmark {...}}` EDN string. This is the most complex raw-EDN response in the entire MCP surface. All other completed tools return natural language or formatted strings (see UX-012, UX-013 for the related `update`/`analyze`/`enrich` issues).
- **Evidence:** `(tool-result (pr-str @results))` at line 457. By contrast, `handle-benchmark-run` at line 353 formats a natural-language summary.
- **Suggestion:** Format as a multi-line summary: `"Digest complete.\nImport: 3 files added. Enrich: 12 imports resolved.\nAnalysis: 47 files analyzed, cost $0.23.\nBenchmark run-id: abc123, full mean: 72.3%."` Each step's sub-result should be formatted using the same helpers as the individual tool handlers.
- **Confidence:** High

### UX-026: `benchmark` run-start log line and cost warning are the only pre-run output — no confirmation of what questions will run
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/benchmark.clj:1417-1426`
- **Description:** Before a benchmark starts, the user sees `bench/run-start run-id=... questions=22 stages=44 concurrency=3` and the cost warning. There is no human-readable summary of which question categories will run, which layers are active, or whether the run is in `--fast` (deterministic-only) or `--full` mode. A user running `--fast` vs default vs `--full` cannot verify at a glance whether the mode was applied correctly before committing to a long expensive run.
- **Evidence:** The pre-run log at line 1417 includes `questions=N` and `stages=N` but not `mode=fast` or `layers=[full]` in a user-friendly form. `(:deterministic-only mode)` and `(:layers mode)` are in the checkpoint metadata but not surfaced in the startup banner.
- **Suggestion:** Add a human-readable pre-run summary line: `"Running 22 deterministic questions across layers: full (fast mode). Estimated cost: $0.45."` This combines the existing cost banner with mode information.
- **Confidence:** Medium

### UX-027: `analyze-repo!` truncation warning is per-file but provides no aggregate summary at completion
- **Severity:** Minor
- **Surface:** CLI
- **File:** `/Users/leif/Code/noumenon/src/noumenon/analyze.clj:397-398` and `481-496`
- **Description:** When a file is truncated (content exceeds `max-file-content-chars` = 100,000 chars), `analyze-one-file!` logs `"Warning: truncated path/to/file"` inline. However, the final summary log at line 481 reports only `ok`, `parse-error`, and `error` counts — truncated files are not counted or mentioned in the summary even though truncation can degrade analysis quality on large files. A user running analyze on a large repo with many big files has no way to know how many truncations occurred without scanning all log lines.
- **Evidence:** `analyze-one-file!` at line 397: `(when truncated? (log! ...))` per file. The `stats-atom` at line 454 tracks `:ok`, `:parse-error`, `:error`, `:started`, `:elapsed-ms`, `:total-usage` — no `:truncated` counter. The final `Done.` log at line 481 does not include a truncation count.
- **Suggestion:** Add `:truncated 0` to the initial `stats-atom`, increment it in `analyze-one-file!` when `truncated?` is true, and include a truncation count in the final summary: `"Done. 47 analyzed, 3 truncated (analysis may be partial), 2 parse errors."` The `truncated?` flag is already computed and returned from `analyze-file!`.
- **Confidence:** High
