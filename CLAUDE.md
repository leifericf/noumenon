# Project Instructions

## Noumenon MCP — Use It First

This project has its own MCP server (`noumenon`) that provides a knowledge graph of the codebase. **Always query the Noumenon MCP before scanning or reading files ad hoc from the filesystem.** It is your primary tool for search, discovery, and understanding the codebase.

### When to use it

- **Before modifying a file** — Use `noumenon_query` or `noumenon_ask` to understand the file's role, its relationships to other files, and recent change history. Do not just open and read files cold.
- **When searching for code** — Query the knowledge graph instead of grepping or globbing across the filesystem. The graph knows about file structure, namespaces, dependencies, and commit history.
- **When exploring the codebase** — Use `noumenon_schema` to see what's in the graph, `noumenon_list_queries` to see available named queries, and `noumenon_ask` for open-ended questions.
- **When understanding impact** — Before a refactor or rename, ask the knowledge graph what depends on the thing you're changing.

### Available tools

| Tool | Purpose |
|------|---------|
| `noumenon_status` | Check entity counts — verify the repo has been imported |
| `noumenon_get_schema` | See all attributes and types in the knowledge graph |
| `noumenon_list_queries` | List available named Datalog queries |
| `noumenon_query` | Run a named Datalog query (some require params — check `list_queries` first) |
| `noumenon_ask` | Ask a natural-language question — AI-powered iterative querying |
| `noumenon_import` | Import git history and file structure (idempotent — safe to re-run) |
| `noumenon_update` | Sync knowledge graph with latest git state (import + enrich; pass `analyze=true` for LLM analysis) |
| `noumenon_analyze` | Run LLM analysis on files — by default only unanalyzed; pass `reanalyze` for re-analysis (all, prompt-changed, model-changed, stale) |
| `noumenon_enrich` | Extract cross-file import/dependency graph deterministically (no LLM calls) |
| `noumenon_synthesize` | Identify architectural components from analyzed codebase data (macro analysis) |
| `noumenon_list_databases` | List all noumenon databases with entity counts, pipeline stages, and cost |
| `noumenon_digest` | Run the full pipeline: import, enrich, analyze, synthesize, benchmark (each step idempotent, skippable) |
| `noumenon_benchmark_run` | Run benchmark comparing LLM answers across knowledge graph layers (expensive) |
| `noumenon_benchmark_results` | Get benchmark results (latest run or by ID) |
| `noumenon_benchmark_compare` | Compare two benchmark runs by score differences per layer |

### Workflow

1. **Start** — Call `noumenon_status` to confirm the repo is imported. If not, call `noumenon_import`.
2. **Discover** — Use `noumenon_list_queries` and `noumenon_query` for structured questions, or `noumenon_ask` for open-ended exploration.
3. **Then read files** — Once you know *which* files matter and *why*, read them with the Read tool for the specific details you need.

**Do not skip this.** Filesystem scanning (Glob, Grep, Agent/Explore) is a fallback for when the knowledge graph doesn't have what you need — not the default approach.

## Security

Never read, write, display, or reference the contents of `.env` or `.env.local` files. These contain secrets. Use `.env.example` for documentation only.

## Clojure Style

### Design

- **Data-oriented**: Prefer plain maps and vectors over custom types. Data > functions > macros.
- **Pure core, impure shell**: Pure transformation functions in the core, side effects (I/O, Datomic transacts, HTTP) at the edges.
- **Minimal surface area**: Small functions that compose. No speculative abstractions.
- **EDN for configuration**: Queries, schema, prompts — all EDN resources, not code.
- **Functions under 10 LOC**: Most functions should be under 5 lines. If a function is long, break it up.
- **Max 3–4 positional params**: Beyond that, use an options map.
- **No deep nesting**: Deeply nested `if`/`cond`/`when` blocks are a sign a function is doing too much. Extract named helpers or use `cond`/`condp` to flatten decision trees. If a threading pipeline exceeds ~5–6 steps, break it into named intermediate values or extract a helper.

### Point-free / tacit style

Prefer point-free composition over naming intermediate arguments. The pipeline *is* the documentation.

```clojure
;; good — point-free pipeline, reads top-down
(->> (d/q query db)
     (remove (comp #{"db" "fressian"} namespace first))
     (sort-by first)
     (mapv make-attr-map))

;; avoid — unnecessary lambda wrapping
(filter #(even? %) coll)    ; bad
(filter even? coll)          ; good
```

Use `->` for entity-first transforms, `->>` for sequence-last transforms. Use `comp` and `partial` to build reusable transforms without naming throw-away arguments.

### Idioms

- `when` over single-branch `if`. `if-let`/`when-let` over `let` + `if`/`when`.
- `seq` to test for non-empty collections (nil punning), not `(not (empty? x))`.
- Keywords as functions: `(:name m)` not `(get m :name)`.
- Sets as predicates: `(filter #{:a :b} coll)`.
- `condp` when predicate and expression are fixed; `case` for compile-time constants.
- `#_` to comment out forms, not semicolons.
- `with-open` for resources, not manual `finally` blocks.
- Predicates end in `?`, side-effecting functions end in `!`.

### Naming

- `kebab-case` for everything. Namespaced keywords for Datomic attributes (`:file/path`, `:commit/message`).
- `->` for conversions (`f->c`), `?` for predicates (`valid?`), `!` for side effects (`save!`).
- `_` for unused bindings. Don't shadow `clojure.core` names.
- No `def` inside functions. No forward references (avoid `declare`).

## Project Commands

### User-facing CLI (`noum`)

The `noum` Babashka launcher is the primary interface. Run `noum help` for all commands.

### Development commands

| Command | Purpose |
|---------|---------|
| `clj -M:run` | JVM CLI — subcommands: digest, import, analyze, enrich, synthesize, update, watch, query, ask, show-schema, status, list-databases, serve, daemon, benchmark |
| `clj -M:test` | Run test suite (Cognitect test-runner) |
| `clj -M:lint` | Lint with clj-kondo |
| `clj -M:fmt check` | Check formatting with cljfmt |
| `clj -M:fmt fix` | Fix formatting with cljfmt |
| `clj -M:nrepl` | Start nREPL on port 7888 |
| `bb -cp src:resources -m noum.main` | Run launcher from source (in `launcher/` dir) |

## REPL-Driven Development

This is a Clojure project. Use a REPL-first workflow: prototype and eval in the REPL before writing to files.

### Eval command

```bash
clj-nrepl-eval --port 7888 '(+ 1 1)'
```

For multi-line expressions, use a heredoc:

```bash
clj-nrepl-eval --port 7888 "$(cat <<'EOF'
(let [x 1
      y 2]
  (+ x y))
EOF
)"
```

If the connection is refused, ask the user to start nREPL: `clj -M:nrepl`

### Workflow

1. **Before writing code** — Try expressions in the REPL first. Test Datalog queries, explore data shapes, verify API behavior before committing to an approach.
2. **During edits** — After editing a function, reload the namespace and eval it to verify:
   ```
   (require '[noumenon.foo] :reload)
   ```
3. **After edits** — Eval changed functions to confirm they work before moving on to the next task.

### Namespace loading

Require namespaces before using them:

```clojure
(require '[noumenon.db :as db] '[datomic.client.api :as d])
```

After editing a source file, reload it:

```clojure
(require '[noumenon.foo] :reload)
```

### Quality checks

- **Format**: Automatic — `clj-paren-repair-claude-hook --cljfmt` runs on every Edit/Write via hooks
- **Lint**: `clj -M:lint 2>&1 | tail -5`
- **Tests**: `clj -M:test 2>&1 | tail -3` — **NEVER** run tests without filtering output. The benchmark tests produce thousands of lines of stderr logging. Only read full output if the summary shows failures.
- **Compilation**: No build step. Requiring a namespace compiles it — use `(require '[noumenon.foo] :reload)` to verify
