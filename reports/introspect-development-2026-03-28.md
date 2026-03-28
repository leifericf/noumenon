# Noumenon `introspect`: Autonomous Self-Improvement Loop

**Date:** 2026-03-28
**Operator:** Claude Opus 4.6 (automated)
**LLM Provider:** GLM (Z.ai proxy) for all evaluation and optimizer calls
**Branch:** `feat/introspect` (74 commits, ~2,300 lines added across 19 source/test/resource files)
**Test suite:** 467 tests, 1,552 assertions, 0 failures

---

## 1. The Problem

### 1.1 Manual optimization is slow and ad hoc

Noumenon's ask agent answers questions about codebases by iteratively querying a [Datomic](https://www.datomic.com) knowledge graph via [Datalog](https://en.wikipedia.org/wiki/Datalog). The quality of its answers depends on several artifacts: the system prompt (which instructs the agent's behavior), the example query selection (which teaches it Datalog patterns), and the Datalog rules (which provide reusable query building blocks). Today, improving these artifacts is a manual process — run the benchmark, examine failures, guess what to change, change it, re-benchmark, evaluate. This is slow, doesn't scale, and doesn't accumulate learnings across sessions.

### 1.2 The autoresearch insight

Andrej Karpathy's [autoresearch](https://github.com/karpathy/autoresearch) demonstrates that this kind of optimization loop can be automated: give an AI agent a single editable artifact (a GPT training script), a fixed evaluation metric (validation bits-per-byte), and a time-budgeted training step — then let it run overnight, autonomously proposing and evaluating changes. Improvements are kept, regressions are discarded. The agent loops until interrupted.

The key insight: the "research loop" — hypothesize, implement, measure, decide — can be automated when the evaluation function is deterministic and cheap relative to the search.

### 1.3 Noumenon already has the infrastructure

This pattern maps directly onto what Noumenon already has:

- **Editable artifacts** — the agent system prompt, example query selection, Datalog rules, and source code
- **A deterministic evaluation function** — the benchmark suite scores the ask agent's answers against ground truth
- **An LLM-powered optimizer** — the same LLM that powers the ask agent can analyze benchmark gaps and propose improvements
- **[MCP](https://modelcontextprotocol.io) exposure** — an external agent can trigger and monitor the loop

### 1.4 Goals

1. **Closed-loop self-improvement** — no human in the loop during iteration
2. **Optimizer-directed goal discovery** — the optimizer analyzes benchmark gaps and chooses what to improve, not just how
3. **Multi-target optimization** — prompts, examples, rules, code, and ML model hyperparameters
4. **Safe rollback** — modifications that don't improve the benchmark are reverted automatically
5. **Budget controls** — cap by iterations, wall-clock hours, or cost
6. **MCP-first design** — usable by both humans (CLI) and AI agents (MCP tool)

The vision: tell Noumenon to improve itself, walk away, and come back to a measurably better system. An incidental benefit: Claude Code seems to do better when developing Noumenon now that we're using Noumenon on Noumenon. Dogfooding for the win.

---

## 2. Design Constraints

Before describing the technical design, these are the constraints that shaped it:

### 2.1 Evaluation must test the actual system

The existing benchmark tests a direct LLM prompt (question + context), not the agent loop. But the artifacts being optimized (system prompt, examples, rules) affect the *agent's iterative querying behavior*, not direct prompting. The evaluation must run questions through `agent/ask` — the actual system being optimized. This is more expensive (~130 LLM calls per evaluation vs. ~40 for the standard benchmark) but tests the right thing.

### 2.2 Modifications must be safely reversible

The optimizer LLM can propose bad changes — broken [EDN](https://edn-format.org), invalid Datalog, code that doesn't compile. These must never corrupt the system. Every modification must be reversible, and the original state must be restored exactly (not approximately — file formatting, comments, and whitespace matter). Clojure's immutable data structures help here — "save the original" is just binding a value, not defensively deep-copying a mutable object. The saved value cannot be accidentally modified by later code, a guarantee that no amount of discipline can provide in languages with mutable defaults.

### 2.3 LLM output is unreliable

The optimizer might return unparseable text, propose changes to nonexistent files, suggest path traversals, or produce modifications that violate constraints (missing template placeholders, invalid query names). Every proposal must be validated before application. Optimism is an occupational hazard of programming; it is doubly hazardous when the programmer is an LLM.

### 2.4 Evaluation has variance

The agent's answers vary across runs even with the same prompt (the LLM chooses different query strategies each time). A score improvement of +0.02 might be noise. The system must tolerate this without false-positive improvements corrupting the artifacts.

### 2.5 Results must be reproducible and queryable

A flat EDN history file is not sufficient for cross-run analysis, trend tracking, or self-referential querying. Introspect results are about Noumenon itself — they cross-cut repositories and accumulate over time. They need the same structured persistence as benchmark results.

### 2.6 The internal database must be separate from repo databases

Benchmark scores for Ring are facts about Ring's knowledge graph. Introspect iterations are facts about Noumenon's own prompts and code. These are different subjects with different lifecycles. Storing introspect data in a repo database would fragment cross-repo insights and create a dependency on having a specific repo imported.

---

## 3. How It Differs from autoresearch

### 3.1 What autoresearch does well

- **Real ML training** — trains an actual neural network with GPU compute and measures wall-clock-normalized performance
- **Lower variance** — `val_bpb` is deterministic; Noumenon's agent-mode evaluation has LLM non-determinism
- **Simpler** — one file, one metric, one loop

### 3.2 What this approach does differently

| Dimension | autoresearch | introspect |
|-----------|-------------|------------|
| Search space | 1 file (`train.py`) | 5 target types (prompts, examples, rules, code, model config) |
| Objective | Single scalar (`val_bpb`) | Per-question gap analysis with categorized failures |
| Goal selection | Fixed (minimize metric) | Optimizer-directed: analyzes gaps and chooses what to improve; human sets budget constraints |
| Safety | Git reset on failure | Pre-evaluation gates (lint + tests for code), path traversal blocks |
| Rollback | `git reset --hard` | Raw file byte restore (preserves formatting) |
| Awareness | Reads own code + results.tsv | Queries own knowledge graph via MCP |
| Composability | Standalone loop | MCP tool — external agents can orchestrate |

---

## 4. Technical Design

### 4.1 Architecture

```mermaid
flowchart LR
  G[Gap\nAnalysis] --> P[Propose\nLLM optimizer]
  P --> A[Apply +\nGate]
  A --> E[Evaluate\nagent/ask\n+ scoring]
  E --> D{Improved?}
  D -->|yes| K[Keep\nmodification]
  D -->|no| R[Revert\nraw bytes]
  K --> G
  R --> G

  B[Budget\niterations / hours / cost] -.->|stop| D
  H[History\nDatomic] -.-> G
  K -.-> H
  R -.-> H
```

### 4.2 Optimization targets

The optimizer LLM chooses which target to modify based on gap analysis:

| Target | Artifact | Risk Level | Gate |
|--------|----------|------------|------|
| `:examples` | `agent-examples.edn` (query selection) | Low | Benchmark |
| `:system-prompt` | `agent-system.edn` (agent instructions) | Medium | Benchmark |
| `:rules` | `rules.edn` (Datalog rules) | Medium | Benchmark |
| `:code` | `src/noumenon/*.clj` (source code) | High | Lint + tests + benchmark |
| `:train` | `model/config.edn` (ML hyperparameters) | Medium | Training + benchmark |

Every one of these artifacts is an EDN file — the same data format. A Python equivalent would need YAML for config, JSON for prompts, SQL for rules, Python source for code, and a TOML or INI file for hyperparameters, each with its own parser, validator, and serializer. In Clojure, one function (`edn/read-string`) reads them all. One function (`pr-str`) writes them all. The `with-modification` macro that handles apply-and-revert works identically across all five target types because they are all just data.

The apply and revert operations for each target are implemented as [multimethods](https://clojure.org/reference/multimethods) — Clojure's open dispatch mechanism. Adding a sixth target type means adding two `defmethod` forms (one for apply, one for revert). No switch statement to modify, no interface to implement, no base class to inherit from. Unlike Java's visitor pattern (which requires modifying every visitor when you add a case) or Haskell's type classes (which require the type to be defined at the same time as the class), Clojure multimethods can be extended at any time, from any namespace, without touching existing code. This is the kind of pragmatic extensibility that matters when an AI optimizer might discover new target types that the original developer never anticipated.

### 4.3 Evaluation function

Each evaluation runs 22 deterministic benchmark questions through `agent/ask` with a reduced iteration budget (6 per question). Answers are scored against ground truth using exact-match `deterministic-score` (no LLM judge — eliminates judge variance). The primary metric is the mean score. Cost per evaluation: ~130 LLM calls.

### 4.4 The meta-prompt

The optimizer LLM receives:

1. The current system prompt template (full text)
2. The current example query selection (19 query names)
3. The full catalog of 56 available named queries
4. The current Datalog rules
5. Per-question benchmark scores with failure reasoning
6. Gap analysis (categorized wrong/partial answers)
7. History of all prior iterations (from Datomic)
8. Descriptions of all five target types with output format specs

It proposes exactly one EDN map per iteration with `:target`, `:modification`, `:rationale`, and `:goal`. Because EDN is both Clojure's native data literal and a readable serialization format, the LLM's output is parsed with a single call to `edn/read-string` — no JSON library, no deserialization framework, no schema validation layer. The data the LLM produces is the data the program consumes. In most languages, LLM output requires a chain of JSON parsing, schema validation, and type coercion before it can be used. In Clojure, the distance between "text from the wire" and "data in your program" is one function call.

### 4.5 Code self-modification

The `:code` target has the strictest safety constraints, because broken code can prevent recovery:

- Files must be under `src/noumenon/` and end in `.clj`
- Path traversal (`..`) is blocked
- Proposed code is verified in-process via `read-string` (syntax) and `require :reload` (compilation)
- The linter must pass
- Failure at any gate triggers immediate revert

The in-process verification is where Clojure's homoiconicity pays off concretely. Because Clojure source code is data (lists, vectors, maps, symbols), `read-string` can parse proposed code without invoking a compiler — it just reads data structures. If the syntax is valid, `require :reload` compiles and loads the namespace in the running JVM. The entire gate check takes ~2 seconds in-process, compared to ~60 seconds when shelling out to separate JVM processes for lint and test. This is not possible in compiled languages like Go or Rust, where verifying proposed code requires a full build toolchain invocation. Even in other dynamic languages like Python, `ast.parse` only checks syntax — it cannot verify that imports resolve or that functions exist. Clojure's `require :reload` does full compilation including dependency resolution, macro expansion, and var resolution, all within the running process.

The core apply-evaluate-revert logic is encapsulated in a `with-modification` macro — a custom language extension that works like Clojure's built-in `with-open` (or Python's `with` statement, or C#'s `using`). It applies the modification, runs the body, and automatically reverts if the outcome is not `:improved` or if an exception is thrown:

```clojure
(with-modification proposal
  ;; body — if it returns non-:improved or throws, original is restored
  (if (not (:pass? (run-code-gate! file content)))
    {:outcome :gate-failed ...}
    (let [eval-result (evaluate-agent! db repo-name invoke-fn-factory)]
      (if (> delta 0.001)
        {:outcome :improved ...}
        {:outcome :reverted ...}))))
```

In most languages, this would be a convention ("remember to call `revert()` in every error path") or a clunky try/finally block duplicated at each call site. In Clojure, the macro generates the try/catch/revert boilerplate at compile time. The caller writes only the decision logic. This is not Haskell-style type-level abstraction that requires a PhD to follow — it is a practical labor-saving tool that eliminates an entire class of bugs (forgotten reverts) by making the correct pattern the only pattern available.

### 4.6 ML model

A pure-Clojure feedforward network for query routing — predicting which Datalog patterns to try for a given question. It runs entirely on-device with zero token cost:

- **Architecture**: bag-of-words (64-dim) -> dense (128) -> ReLU -> dense (48) -> softmax
- **Training**: numerical gradient descent with a fixed time budget (like autoresearch's 5-minute limit)
- **Config**: `resources/model/config.edn` is the "train.py equivalent" — the optimizer proposes hyperparameter changes
- **Integration**: the ask agent checks for a trained model at startup and appends its top-3 query suggestions as a hint in the user message. The agent is free to ignore the suggestions — they are guidance, not constraints. When no model is available, behavior is unchanged.

The model, its training, and its inference are all local computation — no API calls, no tokens consumed. The token cost of the introspect loop comes entirely from the evaluation step (running questions through `agent/ask`) and the optimizer step (asking the LLM for proposals). The model training itself is free.

Model weights are saved alongside a vocabulary snapshot so that tokenization is consistent between training and inference. The load order is: local trained model (`data/models/latest.edn`) > bundled pre-trained model (`resources/model/weights.edn`) > no model (graceful degradation). Once the model is good enough to ship, copying the weights file to `resources/model/` is all that's needed — it becomes a classpath resource, versioned in git like any other configuration.

[Deep Diamond](https://github.com/uncomplicate/deep-diamond) and [Neanderthal](https://github.com/uncomplicate/neanderthal) are included as dependencies for future GPU-accelerated training. The current implementation is pure [Clojure](https://clojure.org) with Java `double-array` operations — type-hinted for primitive performance, no boxing overhead. This is where Clojure's JVM hosting pays off practically: the high-level code (data pipelines, configuration, orchestration) stays in idiomatic Clojure, while the hot inner loop (matrix multiply, softmax) drops down to Java primitive arrays with `^doubles` type hints. No FFI, no separate C extension, no build toolchain — just a type annotation that tells the JVM compiler to use unboxed doubles. Haskell's foreign function interface or Python's ctypes/Cython would require far more ceremony for the same result.

---

## 5. Persistence: The Internal Meta Database

### 5.1 The problem

Introspect results need to be:
- **Queryable** — "which target type produces the most improvements?" should be a Datalog query, not a script parsing a flat file
- **Cross-cutting** — a prompt change affects all repos, so results shouldn't be fragmented per-repo
- **Self-referential** — the optimizer's gap analysis should include past introspect results, queryable via Datalog
- **Reproducible** — each run must capture the exact state of every artifact and database involved

A flat EDN history file satisfies none of these.

### 5.2 The design: a separate internal database

Introspect results are persisted to a dedicated Datomic database (`noumenon-internal`), separate from per-repo databases:

| Database | Contains | Identity |
|----------|----------|----------|
| Per-repo databases | Facts about code (commits, files, imports, analysis, benchmarks) | Derived from repo path |
| Internal meta database | Facts about Noumenon itself (introspect runs, improvement history) | Fixed: `noumenon-internal` |

**Why separate?**

1. **Introspect data is cross-cutting.** A prompt change affects every repo. Storing history per-repo fragments the signal.
2. **No import dependency.** The meta database exists automatically — you don't need to import Noumenon's own repo to use introspect.
3. **Future meta-data has a home.** Cost tracking, provider config, user preferences, model weights — all belong here, not sprinkled across repo databases.
4. **Benchmarks stay in repo databases.** A benchmark score for Ring is a fact about Ring's KG quality. An introspect iteration is a fact about Noumenon's prompt quality. Different subjects, different databases.

### 5.3 Schema

Two entity types following the benchmark pattern (`resources/schema/introspect.edn`):

- `introspect.run/*` — run-level metadata with identity, config, reproducibility hashes (`prompt-hash`, `examples-hash`, `rules-hash`, `db-basis-t`), aggregate scores, and component ref to iterations
- `introspect.iter/*` — per-iteration results (target, goal, outcome, scores, delta, modification text)

### 5.4 Named queries

Five new Datalog queries for the meta database:

| Query | Purpose |
|-------|---------|
| `introspect-runs` | List all runs with scores, newest first |
| `introspect-improvements` | Only kept improvements, with deltas |
| `introspect-by-target` | Group outcomes by target type |
| `introspect-score-trend` | Score progression over time |
| `introspect-failed-approaches` | What was tried and didn't work |

### 5.5 Reproducibility

Each run captures SHA-256 hashes of the agent system prompt, example selection, and Datalog rules at the start of the run, plus the Datomic `basis-t` of the target repo's database. Any run can be exactly reproduced by restoring these artifacts to the hashed state and replaying against the same database version.

### 5.6 Why this was easy — [Clojure](https://clojure.org) and [Datomic](https://www.datomic.com)'s design decisions

Adding a separate internal database alongside the per-repo databases required changing exactly two lines of production code: one call to `db/connect-and-ensure-schema` in `main.clj` and one in `mcp.clj`. No new infrastructure, no configuration files, no connection pool setup, no migration tooling. This is worth pausing on, because it reflects several deliberate design decisions by [Rich Hickey](https://github.com/richhickey) and the Datomic/Clojure teams that compound in exactly this kind of scenario:

*Databases are values, not servers.* [Datomic Local](https://docs.datomic.com/datomic-local.html) runs in-process — there is no separate database server to configure, no ports to manage, no Docker containers. Creating a new database is a function call: `(d/create-database client {:db-name "noumenon-internal"})`. It returns immediately. The database is just another directory on disk, co-located with the existing repo databases under the same storage root. This is why Noumenon can create one database per repository without any operational overhead — each `import` just creates a new database by name.

*Schema is data, not DDL.* The introspect schema is a 167-line EDN file — the same data format used for everything else in Clojure. There is no SQL, no migration framework, no schema versioning tool. `ensure-schema` transacts the schema attributes idempotently: new attributes are added, existing ones are skipped. Adding the introspect schema to the system was one line in `schema.clj`: appending `"schema/introspect.edn"` to the `schema-files` vector. The schema is transacted into every database on connect, so the internal database and repo databases share the same schema without any additional plumbing.

*Immutable history by default.* Every Datomic transaction is an immutable fact with a timestamp. There is no `UPDATE` or `DELETE` — only accretion. This means introspect runs are automatically versioned. You can query what the database looked like at any past transaction point. The `db-basis-t` attribute we capture on each run is not an afterthought bolted on for reproducibility — it is the native way Datomic identifies database states. Asking "what did the knowledge graph look like when this introspect run scored 0.59?" is a one-liner: `(d/as-of db basis-t)`.

*The database is a value you can pass around.* In Clojure, `(d/db conn)` returns an immutable snapshot — a value — not a mutable reference. The introspect loop passes the repo's `db` value and the meta database's `meta-conn` as ordinary function arguments. There is no global state, no singleton connection manager, no dependency injection framework. The `evaluate-agent!` function receives `db` and queries it; `run-loop!` receives `meta-conn` and transacts to it. Two databases, two arguments, zero ceremony.

*Datalog queries are data too.* The five new introspect queries are EDN files in `resources/queries/`. They are not compiled, not generated, not wrapped in macros. The same `query/run-named-query` function that answers "which files are most imported?" against a repo database can answer "which introspect iterations improved the score?" against the meta database. The query engine does not know or care which database it is querying.

These are not incidental features. They are consequences of Clojure's core philosophy — immutable values, data-oriented programming, and the relentless elimination of incidental complexity. The fact that adding a second database to a running system was a two-line change is not because the system is simple. It is because the tools were designed by someone who understood that the cost of infrastructure should be proportional to the complexity of the problem, not to the number of moving parts.

It is worth remembering that Clojure is a [Lisp](https://en.wikipedia.org/wiki/Lisp_(programming_language)) — a descendant of the language [John McCarthy](https://en.wikipedia.org/wiki/John_McCarthy_(computer_scientist)) designed in 1958 specifically for artificial intelligence research. Lisp introduced ideas that the rest of the industry is still catching up to: code as data, the REPL, garbage collection, dynamic typing, higher-order functions, and homoiconicity (programs that can inspect and rewrite themselves). That a system for autonomous self-improvement — an AI agent that modifies its own prompts, rules, and source code, evaluates the results, and decides what to keep — runs naturally on a Lisp is not a coincidence. These languages were *designed* for exactly this kind of work, over sixty years ago.

Clojure brings these ideas into the modern era: immutable persistent data structures, first-class concurrency, seamless Java interop, and a data-oriented philosophy that treats everything — schema, queries, configuration, even database transactions — as plain data that can be inspected, transformed, and composed. Datomic extends this philosophy to the database itself. Together they form a foundation where building a self-improving system feels less like engineering and more like assembly from well-designed parts.

Standing on the shoulders of giants makes it possible to refine good ideas to make them great, and then unify these ideas into something greater than each constituent part.

> *"If I have seen further it is by standing on the shoulders of Giants."*
> — Isaac Newton, 1675

### 5.7 Testing with in-memory databases

Datomic Local supports two storage modes through the same API: directory-backed (persistent, used in production) and in-memory (ephemeral, used in tests). The distinction is a single argument — a path string or the keyword `:mem`:

```clojure
;; Production: directory-backed, persistent
(db/connect-and-ensure-schema "/path/to/data/datomic" "noumenon")

;; Tests: in-memory, disposable
(db/connect-and-ensure-schema :mem "introspect-test-abc123")
```

The test helper `th/make-test-conn` creates a fresh in-memory database with a random UUID name for each test. This gives every test complete isolation — no shared state, no disk I/O, no lock files, no cleanup. The database is garbage collected when the test ends.

The introspect Datomic tests use this pattern to verify round-trip persistence without touching the real database. For example, the `run-tx-data-round-trip` test creates an in-memory meta database, transacts a complete run with two iteration entities, then queries it back with `d/pull` to verify all attributes survived the trip:

```clojure
(deftest run-tx-data-round-trip
  (let [conn    (th/make-test-conn "introspect-txdata")
        tx-data (intro/run->tx-data {:run-id "test-run-42" ...})]
    (d/transact conn {:tx-data tx-data})
    (let [run (d/pull (d/db conn) '[*] [:introspect.run/id "test-run-42"])]
      (is (= 0.5 (:introspect.run/baseline-mean run)))
      (is (= 2 (count (:introspect.run/iterations run)))))))
```

This is the same Datomic API, same schema, same queries — just no persistence. It's why 467 tests run in seconds, and why the only test errors in the suite are the 2 pre-existing DB lock conflicts from the few tests that hit the on-disk database.

---

## 6. Preliminary Test Results

### 6.1End-to-end runs

Four end-to-end runs were completed during development, exercising all major code paths. All runs targeted the Noumenon repository itself using the GLM provider with Sonnet.

| Run | Baseline | After | Delta | Outcome | Target | Path Exercised |
|-----|----------|-------|-------|---------|--------|----------------|
| 1 | 0.523 | 0.591 | +0.068 | Kept | `:system-prompt` | Improvement accepted |
| 2 | 0.682 | 0.636 | -0.045 | Reverted | `:examples` | Regression reverted |
| 3 | 0.659 | -- | -- | Skipped | -- | LLM parse failure |
| 4 | 0.636 | -- | -- | Skipped | -- | LLM parse failure |

### 6.2Run 1: Successful improvement (+6.8%)

**Optimizer's gap analysis input** (excerpt from the actual meta-prompt sent to the LLM):

```
Score distribution: 5 correct, 6 partial, 11 wrong

WRONG answers (highest priority):
  q02: Expected layer core not found in answer
  q06: 0/1 components with most transitive deps listed
  q27: 0/3 top most-imported files listed
  q29: 0/3 top circular import groups listed
  q36: 0/1 shared dependency files listed
  q37: 0/3 top cross-directory import pairs listed
  ...
```

**Optimizer's response** (verbatim from the LLM):

> **Goal:** Fix silent failures on empty result sets by requiring explicit negative reporting.
>
> **Rationale:** The benchmark shows frequent failures when queries return empty results (e.g., q02, q06, q27, q29, q36, q37). The current prompt doesn't explicitly instruct the agent to handle zero-result cases, leading to silent omissions or hallucinations. Adding explicit instructions to report empty results as 'No X found' and to handle partial data should directly address 8 wrong answers.

**Target:** `:system-prompt`

**Modification:** Three new instructions appended to the system prompt:

```
13. **CRITICAL: Always verify that your query found data.** If you expect a list of items
    (e.g., "top 3 files") but get zero results, explicitly state "No X found" in your
    answer. Do not omit the information or leave the list blank.
14. **CRITICAL: Handle partial data gracefully.** If you asked for 5 items but only got 3,
    list those 3 accurately. Do not hallucinate missing items or claim the list is complete
    if it isn't.
15. **CRITICAL: Be explicit about negative results.** If a query returns zero results for a
    non-trivial search (e.g., orphan files, circular imports), explicitly say "No X found"
    rather than omitting the answer.
```

**Result:** Mean score improved from **0.523 to 0.591** (+6.8 percentage points). The system kept the modification.

### 6.3Run 2: Correctly reverted regression (-4.5%)

**Optimizer's response** (verbatim):

> **Goal:** Provide direct query patterns for dependency analysis and contributor metrics to fix wrong/partial answers on import lookups, shared dependencies, and author rankings.
>
> **Rationale:** The current selection is missing critical patterns for 'wrong' benchmarks like q30 (imports), q36 (shared dependencies), and q03 (contributors), and 'partial' benchmarks like q12 (fix authors) and q25 (dependency-heavy). Adding 'file-importers', 'shared-dependencies', 'dependency-hotspots', 'fix-authors', and 'cross-dir-imports' directly provides the building blocks for these failing queries.

**Target:** `:examples`

**Modification:** Swapped 5 queries in the example selection — added `file-importers`, `shared-dependencies`, `dependency-hotspots`, `fix-authors`, `cross-dir-imports`; removed `ai-authored-segments`, `benchmark-score-trend`, and others.

**Result:** Mean score dropped from **0.682 to 0.636** (-4.5 percentage points). The system correctly reverted the change, restoring the original example selection with exact byte-level fidelity.

### 6.4Runs 3-4: Graceful parse failure handling

The optimizer LLM returned malformed EDN. Actual error message:

```
introspect: parse error: Map literal must contain an even number of forms
introspect: failed to parse proposal, skipping
```

The parse error was caught, the iteration was logged as `:skipped`, no files were modified, and the loop completed normally. This validates the nil-handling and error recovery paths.

### 6.5Datomic persistence verified

After the e2e run, the meta database was queried to confirm persistence:

```
Runs: 1
   1774699945215-38dba31b-... baseline=0.659 final=0.659
```

The run ID, baseline, final score, and all iteration records survived the Datomic round-trip.

### 6.6Baseline variability

The baseline scores varied across runs (0.523, 0.682, 0.659, 0.636) despite using the same database and prompt configuration. This is because the evaluation runs each question through `agent/ask`, which makes multiple LLM calls. The agent may choose different query strategies each run, and the LLM's output varies even at temperature 0 due to server-side batching effects.

This variability is the main technical risk for the introspect loop: a modification that appears to improve the score by +0.02 might just be noise. The current threshold of +0.001 is conservative in the wrong direction — it catches true improvements but also false positives. Future work should either run evaluations multiple times or increase the threshold.

---

## 7. User and Agent Affordances

### 7.1The need

Two audiences use Noumenon: humans via the CLI, and AI agents via MCP. Both need to be able to trigger self-improvement runs, control their cost, and inspect results. The CLI user might run an overnight optimization session. The MCP agent might trigger introspect when it notices the ask agent performing poorly on a particular class of questions.

### 7.2CLI interface

```bash
# Run 10 iterations with default settings
clj -M:run introspect .

# Overnight run with budget controls
clj -M:run introspect --max-hours 8 --max-cost 50.0 --provider glm .

# Auto-commit improvements to git
clj -M:run introspect --max-iterations 20 --git-commit .
```

| Flag | Purpose |
|------|---------|
| `--max-iterations N` | Stop after N iterations (default: 10) |
| `--max-hours N` | Stop after N hours of wall-clock time |
| `--max-cost N` | Stop when cumulative cost exceeds $N |
| `--provider` | LLM provider (glm, claude-api, claude-cli) |
| `--model` | Model alias (sonnet, haiku, opus) |
| `--git-commit` | Auto-commit each improvement |
| `--verbose` | Log verbose output to stderr |

### 7.3MCP tools

The `noumenon_introspect_start` tool launches an async run and returns a run ID. `noumenon_introspect_status` and `noumenon_introspect_stop` monitor and control it. `noumenon_introspect_history` routes introspect queries to the internal meta database.

### 7.4Queryable history

All iterations are persisted to the internal Datomic meta database as component entities of the run. This enables Datalog queries for post-hoc analysis — `introspect-improvements` shows all kept improvements with deltas, `introspect-failed-approaches` shows what was tried and didn't work (so the optimizer can avoid repeating failures), and `introspect-score-trend` tracks progress over time.

---

## 8. Known Limitations

### 8.1 No statistical significance testing

The evaluation runs each question once per iteration (or N times with `--eval-runs`). With LLM non-determinism, small deltas may be noise. The current improvement threshold of +0.001 catches true improvements but also false positives. Increasing `--eval-runs` helps but also increases cost.

### 8.2 No human review gate for code changes

The `:code` target auto-reverts on lint or test failure, but there is no mechanism for human review before applying code changes. For production use, code changes should be proposed on a branch.

### 8.3 No prompt caching across evaluations

Each question creates a fresh `agent/ask` session. The system prompt is re-sent with every LLM call. [Anthropic API](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching) prompt caching is used within a single agent session but not across questions.

---

## 9. Implemented Since Initial Development

The following items were originally listed as future directions and have since been implemented:

- **Evaluation variance reduction** (`--eval-runs N`) — runs each evaluation N times, takes the median score
- **Multi-repo evaluation** (`:extra-repos` parameter) — evaluates across multiple repos, averages scores to prevent overfitting
- **Async MCP tools** — `noumenon_introspect_start` (background), `noumenon_introspect_status`, `noumenon_introspect_stop`
- **Cross-iteration model warm-start** — loads previous best weights when config matches
- **Multi-objective optimization** — rejects changes that improve accuracy but increase agent iteration cost by >50%
- **Meta-database queries via MCP** — `noumenon_introspect_history` routes introspect-* queries to the internal database
- **Human target constraint** (`--target examples,rules`) — restricts which targets the optimizer may choose
- **Pre-trained weight shipping** — documented workflow: `cp data/models/latest.edn resources/model/weights.edn`

## 10. Remaining Future Directions

1. **[Deep Diamond](https://github.com/uncomplicate/deep-diamond) GPU training** — swap the pure-Clojure model for GPU-accelerated training when the model grows beyond toy size (requires a feasibility spike)
2. **Prompts and queries in Datomic** — store prompt templates and named queries in the meta database instead of classpath resources, enabling transactional modification with automatic rollback via Datomic's immutable history (significant refactor, better as its own branch)

---

## Appendix A: Files

### New files

| File | Lines | Purpose |
|------|-------|---------|
| `src/noumenon/introspect.clj` | 728 | Core loop, `with-modification` macro, multimethods, gap analysis, Datomic persistence |
| `src/noumenon/model.clj` | 293 | Neural network: init, forward pass, training, persistence |
| `src/noumenon/training_data.clj` | 105 | Tokenization, vocabulary, dataset generation from benchmark |
| `resources/prompts/introspect.edn` | 80 | Meta-prompt template for the optimizer LLM |
| `resources/schema/introspect.edn` | 165 | Datomic schema for introspect runs and iterations |
| `resources/model/config.edn` | 21 | Model hyperparameter configuration |
| `resources/queries/introspect-*.edn` | 58 | 5 named Datalog queries for introspect data |
| `test/noumenon/introspect_test.clj` | 332 | 41 tests: parsing, validation, Datomic round-trips, gap analysis, security, code verification, multimethod round-trips |
| `test/noumenon/model_test.clj` | 161 | 20 tests for model, training, tokenization, round-trips |

### Modified files

| File | Change |
|------|--------|
| `src/noumenon/agent.clj` | Added `reset-prompt-cache!` to invalidate delay-cached prompt resources |
| `src/noumenon/cli.clj` | Added `introspect` command spec, parser, and help |
| `src/noumenon/main.clj` | Added `do-introspect` dispatcher; creates meta-conn for internal database |
| `src/noumenon/mcp.clj` | Added `noumenon_introspect` tool; creates meta-conn via connection cache |
| `src/noumenon/schema.clj` | Added `introspect.edn` to schema file list |
| `resources/queries/index.edn` | Registered 5 new introspect queries (total: 56) |
| `deps.edn` | Added `uncomplicate/deep-diamond` and `uncomplicate/neanderthal` |

## Appendix B: Test Suite

61 new tests across two test files (467 total in suite, 1,552 assertions):

- **`introspect_test.clj`** (41 tests) — parsing, validation, Datomic round-trips, gap analysis, security, code verification, multimethod round-trips
- **`model_test.clj`** (20 tests) — model init, forward pass, training, tokenization, save/load round-trips

---

## A Personal Note from the Puny Human Behind the Curtains

I have a busy life with my wife, three-year-old son, and dog. I've been thinking about this idea for many months — and several decades, if I include my obsession with epistemology and learning. But it was implemented in a single weekend, almost entirely from my iPhone in short stints while folding laundry, washing the dishes (yay, voice chat!), vacuuming the apartment, and walking the dog. That would not have been possible without the assistance of [Claude Code](https://claude.ai/code).
