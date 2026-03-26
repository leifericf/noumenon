# UX / Usability Issue List

Scope: full repo under `src/`. Interfaces examined: CLI (all subcommands) and MCP server tools.

---

## CLI Issues

### 1. `--interval` and `--concurrency` validation errors not wired into `error-messages`

**Files:** `src/noumenon/main.clj` lines 616–646
**Severity:** Medium

`watch-command-spec` declares `:error-invalid :invalid-interval` and `:error-missing :missing-interval-value`. Neither keyword appears in the `error-messages` map. When a user passes `--interval bad`, the error keyword is returned, the `when-let` on line 673 finds no match, and the user receives a blank error message with no explanation. The same gap exists for `:invalid-interval` and `:missing-interval-value` — both absent from the map.

---

### 2. `--interval` and `--concurrency` validation errors missing from `errors-with-subcommand-usage`

**File:** `src/noumenon/main.clj` lines 651–653
**Severity:** Low

The `errors-with-subcommand-usage` set controls whether subcommand help is appended after an error. `:invalid-interval`, `:missing-interval-value`, `:invalid-concurrency`, and `:missing-concurrency-value` are all absent. Users who mistype `--interval` or `--concurrency` receive no usage hint to guide correction.

---

### 3. `--concurrency` default documented inconsistently across commands

**Files:** `src/noumenon/cli.clj` lines 69–74 (shared `concurrency-flags`), lines 122–126 (`postprocess-command-spec`), lines 141–143 (`sync-command-spec`), lines 163–165 (`watch-command-spec`)
**Severity:** Medium

The shared `concurrency-flags` definition documents "default: 3". The per-command overrides in `postprocess-command-spec`, `sync-command-spec`, and `watch-command-spec` each say "default: 8". Runtime defaults in `main.clj` confirm those three commands do default to 8, while `analyze` and `benchmark` use 3. A user running `--help` on different commands sees contradictory documented defaults. There is no unified note explaining that the default varies by command.

---

### 4. `sync` provides no "next step" hint after completion

**File:** `src/noumenon/main.clj` lines 168–189
**Severity:** Low

After `import` completes, the tool prints `"Next: run 'noumenon analyze <repo-path>'..."`. After `postprocess`, it prints a suggestion to run `query file-imports`. `sync` (which internally runs import + postprocess) prints no guidance, leaving users unaware that `--analyze` is needed for LLM enrichment.

---

### 5. `watch` silently swallows repeated sync errors with no escalation

**File:** `src/noumenon/main.clj` lines 213–218
**Severity:** Medium

```clojure
(catch Exception e
  (log! (str "Sync error: " (.getMessage e))))
```

When `sync-repo!` throws, `watch` logs a single line and continues polling. There is no count of consecutive failures, no suggestion to re-import after repeated errors, and no way for the user to distinguish a transient failure from a persistent broken state. A corrupted database produces a new error log every 30 seconds indefinitely.

---

### 6. `query list` sub-subcommand is undiscoverable — absent from help text

**Files:** `src/noumenon/cli.clj` lines 281–283, `src/noumenon/main.clj` lines 221–230
**Severity:** Medium

`query list` is handled via a special case in `parse-simple-args` (line 527) but the `command-registry` entry for `"query"` documents usage only as `query [options] <query-name> <repo-path>`. Running `noumenon query --help` shows no mention of `list`. Users cannot discover available query names without reading source or external documentation.

---

### 7. `agent` budget exhaustion exits 0, making non-answers indistinguishable from success

**File:** `src/noumenon/main.clj` line 283
**Severity:** Medium

`do-agent` returns `{:exit (if (= :budget-exhausted (:status result)) 2 0)}` — this does correctly set exit 2. However, the `run` function at line 703 does not exclude `"agent"` from the `prn` output path, so on budget exhaustion the result map `{:answer nil, :status :budget-exhausted, ...}` is still printed to stdout via `prn`. Stdout mixing data output with a non-zero exit code is unexpected; callers parsing the exit code should not also need to filter stdout.

---

### 8. `--max-cost` flag in `agent` is parsed but never enforced

**File:** `src/noumenon/cli.clj` (agent-command-spec does not include `--max-cost`), `src/noumenon/main.clj` lines 248–289
**Severity:** Medium

The `common-flags` definition includes `--max-cost`. The `agent-command-spec` does not use `common-flags`, so `--max-cost` is not in `agent`'s flag spec. However, the budget is still a usability concern: `do-agent` accepts `opts` map and calls `agent/ask` with only `invoke-fn`, `repo-name`, and `max-iterations`. There is no cost cap on the agent loop. The `benchmark` command has an explicit `:max-cost-usd` budget; `agent` has no equivalent, so a user with a costly provider has no stop mechanism beyond `--max-iterations`.

---

### 9. `databases --delete` is immediately destructive with no confirmation

**File:** `src/noumenon/main.clj` lines 373–378
**Severity:** Medium

`do-databases` deletes the named database immediately upon finding it, with no confirmation prompt. The post-deletion message says `"Re-import: noumenon import <repo-path>"` but does not mention the loss of all LLM analysis cost and that re-running `analyze` will be required. A typo in the database name could destroy the wrong database.

---

### 10. `status` output goes to stdout via `println` while all other informational messages use stderr

**File:** `src/noumenon/main.clj` lines 305–307
**Severity:** Low

`do-status` uses `println` (stdout) for the human-readable counts line, while all other informational messages use `log!` (stderr). The result map is excluded from `prn` output (line 703), so `println` is the sole output — but on stdout. In piped or MCP contexts, this intermixes human text into the machine-readable output channel.

---

### 11. `analyze` and `do-sync` errors from `ExceptionInfo` produce no subcommand usage hint

**Files:** `src/noumenon/main.clj` lines 149–151, lines 168–189
**Severity:** Low

When `do-analyze` catches `clojure.lang.ExceptionInfo`, it prints the message and returns `{:exit 1}`. This path is outside the `error-messages`/`errors-with-subcommand-usage` dispatch, so `run` never appends a subcommand help hint. Users seeing a bare error message (e.g. from an invalid model alias) get no guidance on which flags to correct.

---

### 12. `serve` help omits an explanation of what auto-sync does

**File:** `src/noumenon/cli.clj` lines 308–310
**Severity:** Low

`--no-auto-sync` is described as "Disable automatic sync before queries (default: enabled)". The help text does not explain what "automatic sync" entails (git HEAD comparison + `sync-repo!` call on every query), making the trade-off (latency vs. freshness) opaque to users deciding whether to disable it.

---

### 13. `--resume` optional-string parser behavior is underdocumented and surprising

**File:** `src/noumenon/cli.clj` lines 84–87
**Severity:** Low

The `resume-flag` description says "Place before `<repo-path>` to avoid ambiguity." The actual parser in `parse-flags` consumes the next non-flag token as the checkpoint value. Writing `benchmark --resume /path/to/repo` consumes the repo path as a checkpoint file name. The resulting error is `"No checkpoint files found"` — the user gets no indication that the repo-path was consumed. The description's advice is insufficient without explaining the mechanism.

---

## MCP Server Issues

### 14. `noumenon_ask` returns `null` text when agent finds no answer

**File:** `src/noumenon/mcp.clj` lines 224–226
**Severity:** High

`handle-ask` returns `(tool-result (:answer result))`. When the agent exhausts its budget, `(:answer result)` is `nil`. The `tool-result` function wraps it as `{:content [{:type "text" :text nil}]}`, which JSON-encodes as `"text":null`. MCP clients handling this inconsistently may crash or silently discard the response. The `:budget-exhausted` status is also silently lost — the caller receives no indication the question went unanswered.

---

### 15. `noumenon_sync` returns raw Clojure EDN instead of human-readable text

**File:** `src/noumenon/mcp.clj` lines 207–208
**Severity:** Medium

`handle-sync` returns `(tool-result (pr-str result))`. The caller receives Clojure syntax like `{:status :synced, :added 3, :modified 1, ...}`. Compare with `handle-import` which produces a natural-language sentence. The inconsistency forces LLM callers to parse EDN rather than read a natural-language summary.

---

### 16. `noumenon_query` returns raw EDN tuples with no schema context

**File:** `src/noumenon/mcp.clj` lines 166–172
**Severity:** Low

`handle-query` returns `(tool-result (pr-str (:ok result)))`. The caller receives raw Clojure data tuples or maps with no column headers, no description of what each field means, and no suggestion to call `noumenon_schema` or `noumenon_list_queries` for interpretation.

---

### 17. `noumenon_ask` tool schema omits "claude" as a valid provider alias

**File:** `src/noumenon/mcp.clj` line 117
**Severity:** Low

The `provider` parameter description lists "glm, claude-api, or claude-cli" but omits "claude", which normalizes to `claude-cli` via `normalize-provider-name`. An LLM caller reading the tool schema would not know "claude" is accepted.

---

### 18. Auto-sync in `with-conn` blocks the query response with no timeout or progress signal

**File:** `src/noumenon/mcp.clj` lines 133–139
**Severity:** Medium

When `auto-sync` triggers (HEAD changed), `sync-repo!` is called synchronously before any tool result is returned. For a large repository this can take many seconds to minutes. The MCP caller receives no progress indication and may time out. There is no configurable timeout and no way for the caller to detect that the tool is still working.

---

### 19. MCP server returns errors for `ping` and `resources/list` methods used by some clients

**File:** `src/noumenon/mcp.clj` lines 314–329
**Severity:** Low

Unknown methods produce JSON-RPC error `-32601 "Method not found: <method>"`. Some MCP clients (e.g. Claude Desktop) send `ping` keepalives and `resources/list` discovery probes. These generate error responses the client logs as failures, creating noise. Adding silent `nil` responses (like `notifications/initialized`) or an empty-list response for `resources/list` would eliminate the noise.

---

### 20. `noumenon_import` description does not state re-import is idempotent

**File:** `src/noumenon/mcp.clj` lines 79–83
**Severity:** Low

The description does not mention that importing an already-imported repository is safe and only processes new commits and files. Callers may either avoid calling it on an already-imported repo (causing staleness) or worry a second call will duplicate data.

---

## Pass 2 Findings

### 21. `benchmark` and `longbench results` output all goes to stdout via `println`, bypassing stderr discipline

**Files:** `src/noumenon/main.clj` lines 567–592 (`do-longbench-results`), lines 229 (`do-query-list`), lines 361–365 (`print-db-stats`)
**Severity:** Medium

`do-longbench-results` prints its entire results table (run ID, question count, accuracy table, per-question detail) using `println` to stdout. `do-query-list` uses `println` for the query listing. `print-db-stats` uses `println` for the databases table. All other informational output in the codebase goes to stderr via `log!`. The `"longbench"`, `"databases"` subcommands are excluded from `prn` result output at line 703, so `println` becomes the sole output — on stdout. Scripts that capture stdout for machine-readable data receive human-formatted tables. This is inconsistent with the stdout = data / stderr = progress convention that the rest of the codebase follows.

---

### 22. `query` command silently accepts parameterized queries but provides no way to pass parameters via CLI

**Files:** `src/noumenon/main.clj` lines 232–246, `src/noumenon/query.clj` lines 82–95
**Severity:** Medium

Several named queries require `:inputs` (e.g. `file-imports` requires `:file-path`, `files-depending-on` requires `:dependency`, `file-history` requires `:file-path`). The `do-query` function at line 243 calls `run-named-query db query-name` with no `params` argument. When a user runs `noumenon query file-imports <repo>`, `run-named-query` sees that `:inputs [:file-path]` is non-empty but `params` is `nil`, and returns `{:error "Missing required inputs: [:file-path]"}`. The user receives an error with no guidance on how to supply inputs — there is no `--param` flag, no mention in help text, and no usage example. At least 9 of the 45 named queries in `resources/queries/` have `:inputs` fields. Discovery of which queries need parameters requires reading raw EDN files.

---

---

### 24. `benchmark` cost warning uses an emoji that may corrupt terminals or pipe output

**Files:** `src/noumenon/benchmark.clj` lines 625–630
**Severity:** Low

`log-cost-estimate!` outputs `"  ⚠ COST WARNING: ..."` containing a Unicode warning sign. While `log!` correctly directs this to stderr, some terminal emulators and CI log parsers mishandle non-ASCII characters in log streams. The rest of the codebase avoids emoji/Unicode symbols entirely. This is a minor inconsistency but can cause display corruption in restricted environments.

---

### 25. `longbench download` has no progress reporting during the potentially large download

**Files:** `src/noumenon/longbench.clj` lines 58–97, `src/noumenon/main.clj` lines 599–606
**Severity:** Low

`download-dataset!` uses a 30-minute HTTP timeout but provides no download progress indication beyond a single `"longbench/download-start"` log line at the beginning. The LongBench v2 dataset (`data.json`) is a multi-hundred-MB file. Users see no bytes-downloaded counter, no percentage, and no elapsed time. For a slow connection, the process appears hung for minutes. `main.clj` prints "Downloading LongBench dataset..." then nothing until completion.

---

### 26. `longbench` integrity-warning does not block execution, and the hardcoded SHA-256 is known-wrong

**Files:** `src/noumenon/longbench.clj` lines 29–56
**Severity:** Medium

`expected-sha256` at line 30 is documented inline as "Set on first verified download" and ends with the comment `"To update: sha256sum data/longbench/data.json"`, which strongly suggests it is a placeholder. The `verify-integrity!` function logs a warning on mismatch but explicitly does not abort (`"Logs warning on mismatch but does not abort"`). A user who downloads the dataset and sees the integrity warning has no actionable guidance: they cannot tell whether the file is corrupt, whether the expected hash needs updating, or whether to trust the download. The warning message itself contains the placeholder hash value, which will always mismatch, meaning every download produces a spurious integrity warning.

---

### 27. `imports` tool-unavailability message goes only to stderr log; `postprocess` exit code is always 0 even when tools are missing

**Files:** `src/noumenon/imports.clj` lines 57–71, `src/noumenon/main.clj` lines 153–166
**Severity:** Low

`log-tool-availability!` logs which languages are skipped due to missing tools (e.g. "skipped: python (42 files)") via `log!` to stderr. The `do-postprocess` function at line 162 always returns `{:exit 0}` regardless. If a user has no `python3` or `node` on PATH and runs `postprocess` on a Python or JS repo, the command "succeeds" with exit 0 and zero import edges resolved for those languages. There is no `--require-tools` flag and no stderr summary distinguishing "no imports found" from "tool unavailable, imports not attempted". Users who miss the log message will silently have an incomplete import graph.

---

---

### 29. `longbench results` with no prior runs throws an exception instead of returning cleanly

**Files:** `src/noumenon/main.clj` lines 551–557
**Severity:** Low

When `run-id` is nil (no checkpoints found), `do-longbench-results` calls `print-error!` then `throw`s an `ExceptionInfo`. The throw is caught in the outer `try` at line 609 which calls `print-error!` again on the same message ("No runs found"). The user sees the error message printed twice. The correct pattern for a missing-state condition is to print once and return `{:exit 1}`, not throw.

---

### 30. `benchmark` resume compatibility mismatch error message exposes internal field names

**Files:** `src/noumenon/main.clj` lines 440–448
**Severity:** Low

When a checkpoint is incompatible with the current run config, the error lists field names like `repo-path`, `commit-sha`, `question-set-hash`, `model-config`, `mode`, `rubric-hash`, and `answer-prompt-hash`. Most of these are meaningful to a developer but opaque to a user. Specifically, `question-set-hash` and `rubric-hash` appear when the benchmark questions or rubric have changed since the checkpoint was created — but the message says only "Incompatible checkpoint. Mismatched fields: question-set-hash: checkpoint=abc... current=def..." with no explanation that this means the benchmark content changed and resuming is not possible. There is no suggestion of what the user should do (start a fresh run with `benchmark <repo-path>`).

---

## Summary Table

| # | File | Severity | Category |
|---|------|----------|----------|
| 1 | `main.clj` error-messages map | Medium | Error messages |
| 2 | `main.clj` errors-with-subcommand-usage | Low | Error messages |
| 3 | `cli.clj` concurrency-flags vs per-command specs | Medium | Help text / Defaults |
| 4 | `main.clj` do-sync | Low | Progress / Discoverability |
| 5 | `main.clj` do-watch | Medium | Error handling |
| 6 | `cli.clj` query command-registry | Medium | Discoverability |
| 7 | `main.clj` run / do-agent exit + prn | Medium | Exit codes / Output |
| 8 | `main.clj` do-agent, agent-command-spec | Medium | Missing feature / Flags |
| 9 | `main.clj` do-databases | Medium | Destructive actions |
| 10 | `main.clj` do-status | Low | Output consistency |
| 11 | `main.clj` do-analyze / do-sync | Low | Error messages |
| 12 | `cli.clj` serve spec | Low | Help text |
| 13 | `cli.clj` resume-flag | Low | Help text |
| 14 | `mcp.clj` handle-ask | High | MCP error responses |
| 15 | `mcp.clj` handle-sync | Medium | MCP output format |
| 16 | `mcp.clj` handle-query | Low | MCP output format |
| 17 | `mcp.clj` noumenon_ask schema | Low | MCP tool descriptions |
| 18 | `mcp.clj` with-conn auto-sync | Medium | MCP responsiveness |
| 19 | `mcp.clj` serve! loop | Low | MCP protocol compliance |
| 20 | `mcp.clj` noumenon_import description | Low | MCP tool descriptions |
| 21 | `main.clj` do-longbench-results / print-db-stats | Medium | stdout/stderr discipline |
| 22 | `main.clj` do-query / `query.clj` run-named-query | Medium | Parameterized query UX |
| 23 | FIXED | - | - |
| 24 | `benchmark.clj` log-cost-estimate! | Low | Output consistency |
| 25 | `longbench.clj` download-dataset! | Low | Progress reporting |
| 26 | `longbench.clj` expected-sha256 / verify-integrity! | Medium | Error reporting / Trust |
| 27 | `imports.clj` log-tool-availability! / `main.clj` do-postprocess | Low | Error reporting / Exit codes |
| 28 | FIXED | - | - |
| 29 | `main.clj` do-longbench-results | Low | Error handling |
| 30 | `main.clj` do-benchmark-resume | Low | Error messages |
