# Bug Discovery — Pass 1

**Scope:** Full repo (src/noumenon/*.clj)
**Commit:** e63cd59
**Date:** 2026-03-26

## Summary

| Severity | Count |
|----------|-------|
| Blocker  | 0     |
| Critical | 2     |
| Major    | 1     |
| Minor    | 2     |

## Issues

### BUG-001: MCP `handle-import` passes `repo-path` as `repo-uri`, linking commits to a non-canonical path
- **Severity:** Critical
- **File:** src/noumenon/mcp.clj:221-222
- **Description:** `handle-import` calls `git/import-commits!` and `files/import-files!` with `repo-path` as the third argument (`repo-uri`). Every other call site (e.g. `do-import` in main.clj:90-91, `do-update`, `handle-update`) first calls `.getCanonicalPath` on the path before using it as the repo URI. The repo URI is stored as `:repo/uri` on the repo entity and used for cross-commit linking. When `repo-path` is a symlink or has a trailing slash, the stored URI will not match the canonical form, so the repo entity is created with the wrong identity key, potentially creating duplicate repo entities and breaking all subsequent queries that rely on `:repo/uri` for lookup.
- **Evidence:**
  ```clojure
  ;; mcp.clj:221-222 — uses repo-path as repo-uri directly
  (git-r   (git/import-commits! conn repo-path repo-path))
  (files-r (files/import-files! conn repo-path repo-path))

  ;; main.clj:90-91 — correct: canonicalizes first
  (repo-uri (.getCanonicalPath (java.io.File. (str repo-path))))
  (git-r    (git/import-commits! conn repo-path repo-uri))
  ```
- **Impact:** Via the MCP `noumenon_import` tool, the repo entity is stored with an un-canonicalized URI. Subsequent calls through any path that canonicalizes (all other handlers) will create a second repo entity, doubling commit/file associations and corrupting the import graph. Queries joining on `:repo/uri` will produce incorrect results.
- **Confidence:** High

---

### BUG-002: MCP `noumenon_query` tool cannot pass parameters to parameterized queries — string keys vs keyword inputs
- **Severity:** Critical
- **File:** src/noumenon/mcp.clj:237-238, src/noumenon/query.clj:103
- **Description:** The MCP tool accepts `params` as a JSON object (`{"file-path": "foo"}`), which is deserialized by `clojure.data.json` as a map with **string keys**. `run-named-query` checks for missing inputs via `(remove (set (keys params)) (:inputs query-def))` where `:inputs` are **keywords** (e.g. `[:file-path]`). The set of string keys never contains keyword inputs, so every parameterized query invoked via MCP will return `{:error "Missing required inputs: [:file-path]"}`, regardless of what the caller sends in `params`.
- **Evidence:**
  ```clojure
  ;; query.clj:103 — inputs are keywords
  (if-let [missing (seq (remove (set (keys params)) (:inputs query-def)))]
  ;;                         ^^ keys are strings from JSON   ^^ keywords from EDN

  ;; e.g. file-imports.edn:3
  ;; :inputs [:file-path]

  ;; JSON → Clojure: {"file-path" "foo"} — string key
  ;; (remove #{} [:file-path]) → (:file-path) — never in string set
  ```
  Affected queries: `file-imports`, `file-importers`, `file-history`, `file-authors`, `test-impact`, `transitive-impact`, `files-depending-on`.
- **Impact:** All 7 parameterized named queries are permanently broken via the MCP interface. The tool always returns an error rather than results.
- **Confidence:** High

---

### BUG-006: `db/db-stats` creates a new connection per call, leaking Datomic connections when listing databases
- **Severity:** Minor
- **File:** src/noumenon/db.clj:53-54
- **Description:** `db-stats` calls `connect-and-ensure-schema` which creates a new `DatomicLocal` client and opens a new connection to the database. For `do-list-databases` in main.clj, this is called once per database. Each call creates a connection that is never closed. While Datomic Local connections are lightweight, repeated calls (e.g. from the MCP `noumenon_list_databases` tool called frequently) accumulate open connections.
- **Evidence:**
  ```clojure
  ;; db.clj:53-54
  (let [db (d/db (connect-and-ensure-schema db-dir db-name))
  ;; connect-and-ensure-schema creates a new client + connection each time
  ;; no with-open or .close call
  ```
- **Impact:** Connection leak on each `list-databases` invocation. In long-running MCP server sessions with frequent polling, this accumulates open Datomic file handles. Impact is low for typical usage but can cause resource exhaustion in long-running server environments.
- **Confidence:** Medium

---

### BUG-007: `analyze/analyze-repo!` retry on parse error double-counts usage when the retry also fails
- **Severity:** Minor
- **File:** src/noumenon/analyze.clj:344-348
- **Description:** On a parse error, the retry path does `(update r2 :usage #(llm/sum-usage (:usage result) %))` — it accumulates the first call's usage into `r2`'s usage. Then on line 349, `(reset! usage-atom (:usage result))` resets `usage-atom` to the **final** (possibly summed) usage. However if `result` on line 349 is `r2` (the retry result after the `let` rebinding on line 344), the `usage-atom` stores the summed usage correctly. But if both calls fail and `r2` has nil `:analysis`, the returned `:usage` is the summed value (first + second), which is correct. This is actually not a bug.

  The real issue: when the *first* call succeeds but produces an un-parseable response, `result` is rebounded to the retry result on line 344. The retry result's `:resolved-model` may differ from the first call's model (if the API returns a different resolved model on retry). The tx-data uses `(or (:resolved-model result) "unknown")` where `result` is the **retry** result — the first call's model is lost. This is minor: the stored model version may be wrong if the API returns different model versions across retries.
- **Evidence:**
  ```clojure
  ;; analyze.clj:344-349
  result   (if (:analysis result)
             result
             (do (log! "  Retrying (unparseable response)...")
                 (let [r2 (try-invoke)]
                   (update r2 :usage #(llm/sum-usage (:usage result) %)))))
  ```
  After rebinding, `(:resolved-model result)` is `r2`'s model, not the first attempt's model.
- **Impact:** Incorrect `:prov/model-version` stored in Datomic for files where the first LLM response was unparseable. Purely a provenance data quality issue, no functional impact.
- **Confidence:** Medium

---

## Pass 2 — Saturation

**Files examined:** benchmark.clj, sync.clj, llm.clj, imports.clj, schema.clj, and test files
**Commit:** e63cd59
**Date:** 2026-03-27

### BUG-008: `benchmark/canary-evaluate` hardcodes `:full-score` key, producing a silent false-pass when layers do not include `:full`
- **Severity:** Minor
- **File:** src/noumenon/benchmark.clj:37
- **Description:** `canary-evaluate` reads `(:full-score r)` from each result map in order to evaluate canary quality. Result maps are produced by `stages->results`, which keys scores dynamically as `:<layer>-score` for each layer in the run. When a benchmark run uses layers that exclude `:full` (e.g. `{:layers [:raw]}` or `{:layers [:import :enrich]}`), the result maps have no `:full-score` key; `(:full-score r)` returns nil for every result. `(every? (comp #{:wrong} :score) scores)` then evaluates `(#{:wrong} nil)` = nil (falsy) for each score, so `every?` returns false and `all-wrong?` is false. The function always returns `{:status :pass}` regardless of actual canary scores — suppressing the intended warning.
- **Evidence:**
  ```clojure
  ;; benchmark.clj:37 — hardcodes :full-score
  (let [scores (mapv (fn [r] {:id (:id r) :score (:full-score r)}) canary-results)
        all-wrong? (every? (comp #{:wrong} :score) scores)]

  ;; stages->results (line 931) — produces layer-keyed scores dynamically
  (assoc (keyword (str (name layer) "-score")) (or (:score judge) :wrong))
  ;; :full-score only exists when layer = :full
  ```
  The `canary-evaluate` function has no test coverage in benchmark_test.clj.
- **Impact:** When `run-benchmark!` is called with `canary true` and a custom mode that excludes `:full` (e.g. spot-check with `{:layers [:raw]}`), all canary warnings are silently suppressed. The canary mechanism fails open, defeating its purpose of detecting broken LLM configuration early.
- **Confidence:** High

---

### BUG-009: `benchmark/stages->results` defaults un-judged LLM questions to `:wrong` score in skip-judge mode, corrupting aggregate statistics
- **Severity:** Major
- **File:** src/noumenon/benchmark.clj:927-932
- **Description:** In `skip-judge` mode, LLM-judged questions have no judge stage — `mode-stage-keys` only includes `[:answer]` for them. `stages->results` nonetheless assocs `(or (:score judge) :wrong)` = `:wrong` for the score when no judge result exists. This causes every un-judged LLM question to count as `:wrong` (score 0.0) in the per-layer score keys and in the aggregate computed by `aggregate-scores`. The `llm-judged-mean` for a skip-judge run is always 0.0 regardless of answer quality.
- **Evidence:**
  ```clojure
  ;; benchmark.clj:927-932
  (reduce (fn [result layer]
            (let [judge  (get-in stages [[qid layer :judge] :result])  ;; nil for skip-judge LLM questions
                  answer (get-in stages [[qid layer :answer] :result])]
              (cond-> result
                answer (assoc (keyword (str (name layer) "-answer")) answer)
                true   (assoc (keyword (str (name layer) "-score"))
                              (or (:score judge) :wrong)   ;; always :wrong when judge is nil
                              ...))))
  ```
  `aggregate-scores` then calls `(score-value (get % :full-score))` = `(score-value :wrong)` = 0.0 for these questions, dragging `llm-judged-mean` to 0.0.
- **Impact:** Any `run-benchmark!` call with `mode {:skip-judge true}` (e.g. cost-saving answer-collection runs, or deterministic-only checks) will report `llm-judged-mean: 0.0` in the returned aggregate and in Datomic storage. The stored `bench.run/llm-judged-mean` and `bench.result/<layer>-score` values will be incorrect. This could mislead comparisons between runs if a skip-judge run is accidentally compared to a full run.
- **Confidence:** High
