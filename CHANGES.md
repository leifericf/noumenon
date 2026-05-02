# Changelog

## Unreleased

### Added

- **Branch-aware graph foundation** — every database now records which branch it represents. New `:branch/name`, `:branch/kind` (`:trunk` / `:feature` / `:release` / `:unknown`), `:branch/vcs`, and a tuple identity `:branch/repo+name` are populated automatically on every `update`. Repos point to their current branch via `:repo/branch`.
- **Content-addressed file identity** — `:file/blob-sha` is now imported from `git ls-tree` for every file, enabling content-based comparisons and cache lookups. Existing files lazy-fill on next sync.
- **Local delta databases** — `noum delta-ensure <repo> --basis-sha <sha>` (or `POST /api/delta/ensure`) materializes a sparse Datomic DB at `~/.noumenon/deltas/<repo>__<branch>__<basis>` containing only files added/modified/deleted between the trunk basis and the current HEAD. Deletions are recorded as `:file/deleted? true` tombstones. Delta DBs link back to their parent via `:branch/basis-sha`, `:branch/parent-host`, and `:branch/parent-db-name`.
- **Federated trunk + delta queries** — a subset of named queries declare a `:federation-mode` and accept `:exclude_paths` so the daemon can return trunk results minus rows the launcher will overlay from a delta DB. New endpoint `POST /api/query-federated` does the merge in a single roundtrip; new flag `noum query <name> <repo> --federate --basis-sha <sha>` opts in. Two modes are supported: `:tombstone-only` (trunk minus tombstoned paths; no delta rows — the safe default for queries that join on commits, imports, analysis, or segments, none of which the sparse delta carries) and `:added-files-merge` (trunk plus delta rows for files added in the branch — opt-in for queries that join only on stable attrs like `:file/path` / `:file/lang`, validated at seed time). Federation-aware queries seeded so far: `orphan-files`, `complex-hotspots`, `import-hotspots`, `hotspots`, `ai-authored-segments`, `bug-hotspots`, `files-by-churn` — all `:tombstone-only`. Non-federation-aware queries return trunk-only with a banner.
- **Auto-federation in `noum query` / `noum ask`** — when the active connection is hosted and local HEAD has diverged from the trunk DB's `:repo/head-sha`, the launcher transparently rewrites a plain `noum query <name> <repo>` into the federated path against a local delta and emits a yellow banner. `noum ask` emits the banner but does not federate (no federated ask endpoint in v1). Per-call opt-out with `--no-auto-federate`; global opt-out with `noum settings set federation/auto-route false`.
- **`noumenon_query_federated` MCP tool** — exposes `/api/query-federated` to MCP clients. Materializes the delta on demand from `(repo_path, basis_sha)` and returns the merged result.
- **`noum analyze --no-promote` (and MCP `no_promote`)** — bypasses the content-addressed promotion cache and always invokes the LLM. Useful when re-validating the cache itself.
- **`bb prune-deltas`** — interactive GC for stale local delta DBs under `~/.noumenon/deltas/noumenon/`. Lists each delta with size, classifies as `:live` / `:trunk-missing` / `:unparseable`, and prompts before deleting trunk-missing entries.
- **Content-addressed analysis promotion** — when a file's `:file/blob-sha` equals a previously-analyzed blob in the same DB whose `:prov/prompt-hash` and `:prov/model-version` match the current run, `noum analyze` copies the donor's `:sem/*` and `:arch/*` attrs onto the recipient and skips the LLM call. Donor lineage is preserved via `:prov/promoted-from`. Pass `--no-promote` to bypass the cache. The analyze summary surfaces a `:files-promoted` counter alongside `:files-analyzed`.

### Changed

- **No `:file/deleted?` in trunk transactions** — trunk DBs hard-retract deleted files as before; only delta DBs use tombstones. A guard asserts this in `retract-deleted-files!`.
- **Schema files** — added `resources/schema/branch.edn` and `resources/schema/federation.edn` (which defines `:noumenon/scope`). New attrs `:file/blob-sha`, `:file/deleted?`, `:prov/promoted-from` in existing schema files. `:tx/op` doc lists `:promote`; `:tx/source` doc lists `:promoted`. Every data attribute carries an explicit `:noumenon/scope :stable | :trunk-only` tag.
- **Centralized input length caps** — `noumenon.util` now exports the shared length limits (`max-repo-path-len`, `max-question-len`, `max-query-name-len`, `max-branch-name-len`, `max-host-len`, `max-db-name-len`, `max-run-id-len`, `max-params-count`, `max-param-key-len`, `max-param-value-len`) plus a `validate-params!` helper. HTTP handlers and the MCP layer now consume them so the two surfaces stay in lockstep.
- **Schema-scoped federation modes** — replaced the boolean `:federation-safe?` query flag with an enum `:federation-mode`. A seed-time validator (`noumenon.artifacts/validate-federation-mode!`) rejects any `:added-files-merge` query that touches an attribute not tagged `:noumenon/scope :stable`, so a contributor cannot accidentally re-introduce the orphan-files-style false-positive merge by mistakenly opting a query that joins on `:file/imports` or `:commit/*` into the more permissive mode. The legacy `:artifact.query/federation-safe?` boolean is preserved as a derived value so the launcher's banner logic keeps working.

### Fixes

- **Confirm prompts re-prompt on garbage input** — `tui.confirm/ask` returned `default-val` on any non-y/n input. With `default-val=true` (no current caller, but future ones) a typo would silently confirm a destructive action. Garbage now triggers a re-prompt with a "Please answer y/n." hint; empty input still falls back to the default as before.
- **`noum settings` rejects extra positionals** — `noum settings retry/limit 5 typo-extra` used to silently discard the third positional and POST `(key, value)` as if only two args were passed. Now produces `Error: Too many arguments.` (exit 1).
- **`noum help <unknown>` exits 1** — previously printed "Unknown command: …" but exited 0, inconsistent with `noum <unknown>` which correctly exits 1.
- **`noum connect <ip-literal>` derives a useful saved-connection name** — `noum connect 127.0.0.1:7895` used to save the connection as `'127'` (the first dot-segment), so `127.0.0.1` and `127.0.0.2` collided. The auto-naming now detects IP literals (and `localhost`) and keeps `host:port` joined by `-` (e.g. `127.0.0.1-7895`, `localhost-7895`); real hostnames still use the first dot-segment (`api.example.com` → `api`).
- **`noum introspect` rejects mutually exclusive flag combinations** — `--status`, `--stop`, and `--history` target different sub-actions, but the cond order silently picked the first match. `noum introspect --status run-a --stop run-b` acted on `--status run-a` only without warning. Two or more of the three flags now produce `Error: --status, --stop, and --history are mutually exclusive.` (exit 1).
- **`noum introspect --status` (no value) gives a clean error instead of a misleading databases hint** — `--status` / `--stop` without a following value booleanized to `true`, fell through `(string? …)` checks to `do-api-command`, and emitted "Use `noum databases` to see imported repositories" — the user wanted a run-id, not a repo. Both flags now produce `Error: --status requires a run-id.` / `Error: --stop requires a run-id.` (exit 1) at the boundary.
- **`--as-of`, `--raw`, and `--basis-sha` validate at the launcher boundary** — `noum query --as-of ""`, `query --raw ""`, `delta-ensure --basis-sha` (no value → boolean true), `query --federate --basis-sha not-hex` all used to flow through to the server, which rejected after a network round-trip. The launcher now rejects blank `--as-of` / `--raw` and enforces a 40-char lowercase hex shape on `--basis-sha` (used by both `query --federate` and `delta-ensure`) up front; valid inputs still pass through unchanged.
- **`noum serve --host X` now produces a clean error instead of silently ignoring `--host`** — `do-serve` only forwarded `--db-dir`, `--provider`, `--model`, `--token` to the spawned MCP process; `--host` and `--insecure` were dropped, so users targeting a remote ended up running an MCP server colocated with the local daemon. Reject the combination explicitly with a hint to run `noum connect <url>` first; the MCP server already proxies to the saved active connection.
- **`noum ping` and `noum version` honor `--host` and the active named connection** — both used to call `daemon/connection` directly, which only consults `~/.noumenon/daemon.edn` and ignores every other connection signal. `noum ping --host X --token Y` silently checked the local daemon instead. Both commands now go through a new `ping-target` helper that checks `--host` first, then the saved active connection, then the local daemon — without spawning anything as a side effect.
- **Interactive menus no longer hang on a bare ESC press** — `choose/select` matched the ESC byte and then unconditionally read two more bytes to consume the CSI follow-up (`[ <arrow-code>`). With no follow-up bytes (the user pressed ESC alone, not as part of an arrow sequence), the second `.read` blocked forever — the only escape was Ctrl-C, which killed the JVM mid-cleanup and left the terminal in raw mode. The new `read-arrow!` helper sleeps 20ms after the ESC byte and only consumes the next two bytes if `InputStream/available` is positive; bare ESC returns nil, which the caller treats as cancel (same as Q / Ctrl-C). Arrow keys behave identically to before.
- **`noum settings <key> <huge-int>` no longer silently nulls the value** — `parse-setting-value` matched any digit-only string with `#"-?\d+"` and called `parse-long`, which returns `nil` on overflow. The cond returned the nil result, so the daemon got `:value nil` and replied "key and value are required" — the user typed a value, the launcher silently erased it. Now falls back to the raw string when `parse-long` overflows; the daemon's settings store accepts strings as-is.
- **Uncaught launcher exceptions surface as clean errors, not stack traces** — when `daemon/start!` timed out (e.g. slow JVM startup, missing JRE, port conflict) the bb runtime dumped a 30-line Clojure stack trace with internal source paths. Same shape for any other uncaught exception inside a handler. `-main` now wraps `(handler parsed)` in a new `run-handler!` helper that catches every `Exception`, prints `Error: <message>` (in red) with a hint to set `NOUM_DEBUG=1` for the full trace, and exits 1.
- **Network failures surface as clean errors instead of stack traces** — `noum <cmd> --host <unreachable>` (no listener, DNS failure, timeout) used to dump a raw `java.net.ConnectException` stack trace from `api/get!` / `api/post!`. The HTTP client's `:throw false` only converts HTTP error responses into result maps; pre-response exceptions still bubbled. Both helpers now catch any exception and emit `Error: Could not reach <host>: <exception-class> — <message>` (exit 1) so the user knows where to look without seeing source paths.
- **Auth-failure path no longer leaks Datalog clauses or crashes the launcher** — when the meta-DB token query threw (e.g. a closed channel mid-request, transient backend error), the daemon's auth middleware bubbled the exception into the generic 500 handler, which echoed `processing clause: [?t :token/hash ?h], …` back as `text/plain`. The launcher then crashed with a raw `JsonParseException` because `parse-body` didn't tolerate non-JSON. Two-layer fix: (1) `check-auth` now wraps `auth/validate-token` in try/catch and returns a clean 401 JSON response on any internal failure, never leaking schema details; (2) the launcher's `parse-body` returns nil instead of throwing on non-JSON input, and `get!`/`post!` fall back to a `{:ok false :error "HTTP <status>: <body>"}` shape (truncated to 200 chars) so callers see a sensible message.
- **`noum watch --interval` rejects non-positive / non-numeric values up front** — `--interval -5` used to print "polling every -5s", attempt one update, then crash `Thread/sleep` with raw `IllegalArgumentException`; `--interval abc` silently fell back to 30 with no warning. The new `parse-watch-interval` helper validates before `ensure-backend!` runs and emits `Error: --interval must be a positive integer (got <value>)` with exit 1.
- **`noum history prompt` (no name) no longer NPEs** — the no-name branch tried to enumerate prompt files via `(io/resource "prompts/")`, but the daemon's `resources/prompts/` lives in the JVM-side `noumenon.jar`, not the launcher's bb classpath. The resource lookup returned nil and `(io/file nil)` threw NPE. Same NPE in the interactive `collect-history` menu. Both now skip the listing: the one-shot path emits a Usage message with the common prompt names, and the interactive path asks the user to type the name as free text. (No daemon endpoint exists today to enumerate prompts; if one is added later, the launcher can call it.)
- **Blank / NUL-only repo args no longer silently resolve to the cwd** — `noum status ""`, `status "   "`, `status "\x00"`, and `ask "" "<question>"` all used to flow through `path->db-name` / `canonicalize-path`, which delegated to `(java.io.File. "")` — the JDK normalized that to the current working directory and `last` produced the cwd basename. Users running noum from a directory whose basename happened to collide with a real DB silently got the wrong DB. `cli/parse-args` now drops blank positionals (after stripping NUL bytes) so the existing min-args / Usage-error paths fire as intended. `noum status .` still works as a current-directory shorthand; only empty / whitespace-only / NUL strings are dropped.
- **`noum query --param` is repeatable as documented** — `cli/extract-flags` stored every flag in a single-valued map, so a second `--param k2=v2` overwrote the first. Only the last `key=value` reached the daemon, despite the help text claiming "(repeat command as needed)". `--param` now accumulates into a vector of strings; `build-api-body` (and the `--as-of` / `--federate` branches of `do-query`) merge the vector into the request's `:params` map. Single `--param k=v` calls still work — they produce a 1-element vector that flattens to the same body as before.
- **SSRF check no longer crashes on IPv6-resolving hosts** — bb's native-image build doesn't carry reflection metadata for `Inet6Address`'s instance methods (`.isLoopbackAddress`, `.getAddress`, etc.), so any host whose DNS lookup returned an IPv6 address — including everyday public hostnames like `google.com` — surfaced as `MissingReflectionRegistrationError` with a 30-line stack trace instead of either connecting or returning a clean blocked-private response. The classifier now reads `getHostAddress` (the one Inet6Address method bb does carry) and matches the canonical full IPv6 form (`0:0:0:0:0:0:0:1`) alongside the compressed form (`::1`); the `^fe80:`/`^fc00:`/`^fd` prefix patterns expand to cover all of `fe80::/10` and `fc00::/7` as well, so the regex-only path is at least as strict as the prior `.isLinkLocalAddress`/`.isSiteLocalAddress` calls. IPv4-mapped IPv6 (e.g. `::ffff:127.0.0.1`) is auto-converted to `Inet4Address` by the JDK, so the existing IPv4 patterns catch it.
- **`noum connect http://localhost:N` no longer SSRF-blocked** — `base-url`'s loopback allowlist regex was anchored at the start of the host string, so the bare form `localhost:7895` matched but the scheme-prefixed `http://localhost:7895` (and `http://127.0.0.1:7895`, `https://localhost:7895`) didn't, falling through to `private-address?`. That helper then split on `:`, took `"http"` as the host, failed DNS, and the catch-all returned `true` — every scheme-prefixed local URL got rejected as "private/internal". `base-url` now strips the scheme up front, applies the loopback check on the bare host, and reuses an explicit scheme when present so https://… and http://… both round-trip cleanly. Identical inputs with and without scheme produce the same final URL.
- **Delta DB collision on look-alike branch names** — `feat/foo` and `feat-foo` both sanitize to `feat-foo` for the on-disk db-name; without disambiguation, branch-switching between the two would silently overwrite the same delta DB. The db-name now appends `sha256(branch-name)[0..6]` so collisions resolve to different DBs.
- **Federation merge keeps trunk history for modified files** — the earlier "exclude all delta paths from trunk + append delta rows" merge made modified files vanish from churn-based queries because the delta DB has no commits to carry their history. Tombstone-only merge keeps trunk's authoritative history while still respecting branch deletions.
- **`bb prune-deltas` walks the right directory** — Datomic-Local stores DBs under `<storage>/<system>/<db-name>/`, so the actual deltas live under `~/.noumenon/deltas/noumenon/`. The previous parent-dir walk would have surfaced the system dir itself as a single "unparseable" entry — and a `y` at the prompt would have nuked every delta DB on the machine.
- **Empty / dot-only branch names** — `delta-db-name` falls back to `detached` for nil, blank, or dot-only branch inputs (which would otherwise produce empty / `.` / `..` directory names — the latter resolve to parent dirs in tools that aren't expecting them as literal path components).
- **`ensure-private!` uses 700 on directories** — the launcher's owner-only permission helper was applying `600` (no execute bit) to directories, making them unenterable. Now `rwx------` for dirs, `rw-------` for files.
- **`validate-string-length!` returns 400, not 500** — the `:status` key on the thrown ex-info now lets the HTTP handler surface a clean `400 Bad Request` instead of falling through to a generic `500`.
- **Idempotent branch upsert** — `update-head-and-branch!` resolves the existing repo + branch eids before transacting, so re-running `delta-ensure` (or any sync) doesn't trip the `:branch/repo+name` unique constraint with a fresh tempid.
- **Branch / parent_host / parent_db_name / query_name length caps on `/api/delta/ensure` and `/api/query-federated`** — overlong values now return `400` instead of being persisted or echoed back unchecked.
- **Bogus `basis_sha` is now a clean error** — a 40-char-hex SHA that doesn't resolve to a real commit used to silently produce an empty diff, and `delta-ensure` / `query-federated` would respond `synced` with zero counts. `changed-files` now throws on non-zero `git diff` exit so HTTP surfaces a 400 with the actual git error; `update-repo!` catches the throw and falls back to a fresh sync, so a force-pushed trunk DB still recovers.
- **`bb prune-deltas` parses branch names containing `__`** — the old split-on-`__` parser misclassified delta DBs whose branch contained a double underscore (e.g. `feat__under`) as `:trunk-missing` and offered them for deletion. Anchored regex on the trailing `-<hash6>__<basis7>` suffix preserves the branch correctly. Pre-disambiguator on-disk names (no `-<hash6>` suffix) are not parsed by the new code; re-create them by running `delta-ensure` or `query-federated` against the same basis.
- **`bb prune-deltas` classifies repo basenames containing `__`** — the parser's branch-favoring heuristic attributes every `__`-segment in the on-disk name to the branch, which is the right call for the common case (`noumenon__feat__under-...`) but misclassified the symmetric one: a real repo basename like `my__repo` parsed as `repo=my, branch=repo__feat`, and `~/.noumenon/data/noumenon/my/` doesn't exist, so `classify` flagged the delta `:trunk-missing` and offered it for deletion. `classify` now walks the `__` boundaries between parsed repo and branch and reports `:live` if any candidate split has an existing trunk dir; the displayed row shows the resolved repo/branch instead of the misparse.
- **`:added-files-merge` queries must put `?path` first in `:find`** — the merge code filters delta rows by `(first row)` matched against added paths, so the first column has to bind `:file/path`. The contract was implicit; a query whose `:find` started with anything else (e.g. `[:find ?lang ?path …]`) would have silently lost every delta row, and a non-path column that coincidentally equalled a path string would have leaked false rows through. The seed-time validator now rejects an `:added-files-merge` query whose first `:find` element isn't the symbol `?path`. No shipped query was affected — the new check makes the contract explicit at the boundary instead of relying on an undocumented convention.
- **`connect` recovers from a stale system-catalog entry** — Datomic-Local's system catalog persists database entries to its own metadata files; if the on-disk db directory is removed externally (e.g. `bb prune-deltas` wipes a delta, or the user deletes one while the daemon is down) the catalog still says the db exists. `create-database` is then a no-op (catalog says exists) and `connect` throws `:cognitect.anomalies/not-found` — surfacing as a 500 like `Db not found: <name>` even though `ensure-delta-db!` had just logged success. `create-db` now catches that exact anomaly, drops the stale catalog entry, and recreates cleanly so the next caller gets a working connection. Centralized in one place so cache misses, fresh connects, and schema-ensure paths all share the same recovery.
- **`validate-string-length!` rejects non-strings with 400, not 500** — the validator's old guard `(when (and (string? s) ...))` silently let any non-string value through, so a JSON request like `{"branch": ["a"]}` or `{"branch": 123}` flowed past every HTTP and MCP boundary that relied on it as a type+length check. Downstream code (e.g. `sanitize-branch` calling `str/trim` on a vector) then crashed as a 500. Non-nil non-strings now produce a clean 400 "X must be a string" at the validation boundary; nil still passes silently for optional fields.
- **`validate-repo-path` checks type and length** — the validator went straight to `(io/file repo-path)`, so a JSON request like `{"repo_path": 42}` threw `IllegalArgumentException` (no `as-file` impl for `Long`) and surfaced as a 500. Long strings also walked all the way through `.exists`/`.isDirectory` without a cap. Non-nil non-strings now return `must be a string`, strings over `max-repo-path-len` (4096) return an `exceeds maximum length` reason, and the existing FS-shape reasons are unchanged. Callers' `when-let + throw` pattern produces a clean 400 in every case.
- **MCP proxy surfaces unreachable-daemon errors clearly** — when `~/.noumenon/config.edn` pointed at a host with no daemon listening, every MCP tool call came back with `Remote proxy error: JSON error (end-of-file)` because curl exited non-zero with empty stdout and the proxy then `json/read-str`'d the empty string. The proxy now checks curl's exit code first and surfaces `Cannot reach daemon at <host>. Start it with `noum daemon`, or update ~/.noumenon/config.edn to point at a running host.` — including curl's stderr when present. Empty bodies on a zero exit (204 / premature close) get a similar host-naming message instead of bubbling up the JSON parse error.
- **Branch-name cap is FS-derived, not human-name-derived** — `max-branch-name-len` was 256, so a 256-char branch passed validation and then crashed Datomic-Local's `mkdir` with `File name too long` because the synthesized db-name (`<repo>__<safe-branch>-<hash6>__<basis7>`) overflowed the POSIX 255-byte path-component limit. Cap is now 200 — leaves ~37 bytes of headroom for the repo basename, which covers virtually every real-world case. `delta-db-name` does a final 255-byte check too so the long-repo edge case (where repo + cap can still overflow) surfaces as a 400 at the boundary instead of a 500 from the FS layer.
- **`analyze` truncates long strings at the writer boundary** — Datomic-Local rejects single string values around the 4 KB mark with `Item too large`. The parse-time `clamp` already limits `:summary` / `:purpose` to 4096 chars, but `:sem/synthesis-hints` is a `pr-str` of `purpose` + `architectural-notes` + patterns + layer + category, so the result can easily overflow even when each input was clamped. `build-file-tx` now caps every string attribute it writes at 4000 chars (matching `artifacts/chunk-size`'s headroom for UTF-8 multi-byte chars), so a verbose LLM response can't blow up the transact and lose the analysis.
- **`query-federated` is now a no-op when basis + HEAD haven't moved** — every call used to write a head/branch tx (and re-transact every schema file via `ensure-delta-db!`), growing the delta's `db.log` ~2.3 KB per 5 read-shaped requests. The handler short-circuits to `:up-to-date` when the stored basis-sha and HEAD already match, and `ensure-delta-db!` now routes through the cached connection helper.
- **`query-federated` records parent metadata on the delta** — auto-derives `:branch/parent-db-name` from the resolved trunk DB and `:branch/parent-host` from the request's Host header (HTTP) or `"local"` (MCP). Previously, only the explicit `delta-ensure` path set these, so deltas materialized via auto-federation lost the lineage breadcrumb.
- **Trim branch name before disambiguator hash** — `sanitize-branch` already trimmed before producing the on-disk label, but the disambiguator hashed the raw input, so `"foo"` and `"foo "` ended up in different delta DBs for one logical branch. Both paths now agree on the canonical branch.
- **Uniform `query_name` length cap across query endpoints** — `POST /api/query` and `POST /api/query-as-of` now reject overlong `query_name` with the same 400 the federated endpoint already produced. The shared `util/max-query-name-len` is the single source.
- **OpenAPI doc reflects the actual delta-DB path** — `/api/delta/ensure` description now shows `~/.noumenon/deltas/noumenon/<repo>__<safe-branch>-<hash6>__<basis7>/` (was missing both the `noumenon/` Datomic system subdir and the `-<hash6>` disambiguator).
- **Cross-DB promotion guard** — `find-cached-analysis` rejects `:donor-db` without a matching `:donor-db-name`, and now also the symmetric `:donor-db-name` without `:donor-db`. The two predicates that decide same-DB vs cross-DB used to disagree; either partial form would have written a dangling `:prov/promoted-from` ref or fabricated cross-DB provenance for a donor that actually came from the recipient. Currently dormant (no production caller wires either) but lands defensively before the cross-DB-promotion path gets enabled.
- **`DELETE /api/databases/noumenon-internal` now rejected** — the meta database stores tokens, settings, prompts, rules, ask sessions, and benchmark/introspect history, and `:meta-conn` is cached at daemon startup. Letting a caller delete it via the public delete endpoint silently corrupted the daemon: the cached connection became a closed channel, so `/api/tokens`, `/api/repos`, etc. all 500'd until restart, while lazy-`ensure-meta-db` callers (settings, queries) silently re-seeded into a fresh DB and hid the breakage. The meta-DB name lives as `noumenon.db/meta-db-name`, and `handle-delete-database` rejects it up front with a 400 "Cannot delete reserved database".
- **`file://` and other non-network URL schemes blocked at clone** — `validate-clone-url!` only ran the SSRF private-IP check, and that check short-circuited on URLs without a host (`file://`, `ssh://`, raw paths). An authenticated admin posting `{"url":"file:///some/local/repo"}` to `/api/repos` could clone an arbitrary readable git repo on the daemon's filesystem and then query it via `/api/ask`. The validator now rejects anything that doesn't match `git-url?` (`https?://` or `git@host:path`) before the host lookup runs; Perforce depot paths still go through their own clone path and are unaffected.
- **Uniform `repo_path` validation across the bulk endpoints** — `with-repo` (the wrapper used by `/api/import`, `/api/analyze`, `/api/enrich`, `/api/update`, `/api/synthesize`, `/api/digest`, `/api/ask`, `/api/query`, `/api/query-raw`, `/api/query-as-of`, `/api/query-federated`, `/api/benchmark`, `/api/introspect`, `/api/completions`, etc.) only checked for the field's presence, so a JSON body like `{"repo_path": 42}` / `["a"]` / `{"a":1}` / `true` reached `(io/file …)` and surfaced as a 500 with a leaking ClassCastException. Empty string fell through to the bare-db-name branch and shelled out to `git log` against `db://`. A 5MB string walked the FS-shape checks and got reflected back in the 404 message (small request-amplification vector). New `util/validate-repo-path-input!` does the type+length+blank gate; `with-repo` now calls it after the missing-field check, so all of the above surface as a clean 400. The earlier `delta-ensure`-only fix is now uniform.
- **Malformed JSON body returns 400, not 500** — `parse-json-body` let `json/read-str`'s parse exception flow up into `make-handler`'s catch-all, so a typo'd request body became a generic "Internal server error" even though the fault was entirely client-side. The parser is now wrapped in a try/catch that re-throws an ex-info with `:status 400` and a clean "Invalid JSON body" message; the underlying parser detail (offset, character) is logged to stderr for daemon-side debugging but not exposed in the response.
- **`ask-session-feedback` rejects unknown session ids** — `POST /api/ask/sessions/<unknown>/feedback` used to call `set-feedback!` regardless and return 200, writing feedback attrs against a non-existent session id and lying to the caller about it. The handler now looks up the session via `ask-store/get-session` first and returns 404 "Session not found" on miss, matching the shape of `handle-ask-session-detail`.
- **`/api/repos/:name` remove and refresh return 404 for unknown names** — both handlers used to call into `repo-mgr` without an existence check, so an unknown name surfaced as a generic 500 "Internal server error" (with a leaked filesystem path on the daemon side). A shared `registered-repo!` helper does the meta-DB lookup and 404s with "Repo not registered: <name>" before any disk-touching work, so callers can distinguish "not registered" from a real server error and stop seeing the 500.
- **`/api/ask` rejects empty / whitespace question with 400** — the `(when-not (:question params))` gate only caught nil; an empty string passed all of nil-check + length-cap and reached `agent/ask`, where the LLM loop crashed and surfaced as 500 "Internal server error". The gate now also rejects blank strings (after trim), so `{"question":""}` and `{"question":"   "}` both produce 400 "question is required" the same as omitting the field.
- **`validate-db-name!` is now a positive allowlist** — the validator only rejected `/`, `\`, blank, and pure-dot names, so null bytes / newlines / tabs / spaces / non-ASCII slipped through and propagated into `(io/file …)` lookups. Tightened to `[a-zA-Z0-9._-]+` (matching `derive-db-name`'s sanitizer and the synthesized delta-DB naming), so exotic characters fail at the boundary with a 400 instead of leaking into the storage layer. Pure-dot names still get the explicit dot-only rejection.
- **`/api/query-as-of` no longer leaks JVM class names in `as_of` errors** — sending `{"as_of": [123]}` / `true` / `{}` triggered the parse path's `(long …)` cast, and the catch wrapped the JVM `ClassCastException` message ("class clojure.lang.PersistentVector cannot be cast to class java.lang.Number …") into the 400 body. The handler now type-checks `as_of` (string or number) up front and produces a clean "as_of must be an ISO-8601 string or epoch milliseconds". The string-but-unparseable branch ("not-a-date") still surfaces the actual `Instant/parse` complaint so users see the real reason. Validation also now runs before `with-repo`, so a bad `as_of` fails fast even when the repo doesn't exist.
- **`/api/settings` strings stay strings** — the handler used to run every string value through `edn/read-string`, silently re-typing `"42"` to `42`, `"true"` to `true`, `":foo"` to a keyword, etc. Cross-language callers (Electron UI, future GUIs) had no way to store an actual string that happened to parse as EDN. The handler now stores values as-is: typed callers send JSON-typed values (`{"value": 42}` for an int), string callers send strings (`{"value": "42"}` for a string). The `noum settings <k> <v>` CLI keeps its existing UX by pre-parsing the CLI string in the launcher (`parse-setting-value` now also runs for daemon-side settings, symmetric with how launcher-local settings already worked).
- **Daemon logs no longer leak absolute db-dir paths in clone errors** — `repo-mgr/refresh-repo!`'s "Clone not found: /abs/path/<x>.git" message, the "Cloning <url> into /abs/path/<x>.git ..." log line, the "Removing clone /abs/path/<x>.git" log, and the git stderr embedded in clone-failure errors all referenced the absolute filesystem path. Logs and surfaced messages now reference only the db-name (e.g. "Clone not found for ghost", "Cloning <url> into ghost.git ..."); the absolute path stays in `:ex-data` for daemon-side debugging. `git/clone!` and `git/clone-bare!` redact the target-dir absolute path from git's own stderr before embedding it in the error message.

### Notes

- Datomic schema is additive: no migrations runner, no version stamps. `ensure-schema` re-transacts every connect; existing DBs pick up new attrs and queries pick up `:federation-safe?` on next start.
- Delta DBs require a co-located daemon in this release. Cross-machine federation (remote daemon, launcher-side delta) is deferred.
- Promotion is same-DB only in this release. Cross-DB promotion (delta lookups against trunk's history) is deferred.

## 0.7.0

### Changed

- **Repo split** — The Electron desktop app moved to [`leifericf/noumenon-app`](https://github.com/leifericf/noumenon-app); the website moved to [`leifericf/noumenon-site`](https://github.com/leifericf/noumenon-site). This repo keeps the daemon, `noum` CLI, and OpenAPI spec. History was preserved on both new repos via `git filter-repo`.
- **OpenAPI spec relocated** — Canonical source moved from `docs/openapi.yaml` to `resources/openapi.yaml` so it ships inside the daemon JAR and can be served via `io/resource`. The website mirrors it daily via a cron-pull workflow.
- **`noum ui` auto-updater** — Now downloads the packaged Electron app from `leifericf/noumenon-app` releases (was `leifericf/noumenon`).
- **`noum ui` dev mode** — Resolves a noumenon-app source checkout in this order: `$NOUMENON_APP_ROOT` → `$NOUMENON_ROOT/../noumenon-app` sibling → fall back to installed app. The previous `ui/` child directory is no longer valid.
- **Core CI/Release workflows** — Removed the `ui` job and the `build-electron`/`deploy-pages` release jobs. CLI distribution (`update-homebrew`, `update-scoop`) and Docker publish remain in this repo.

## 0.6.2

### Changed

- **HTTP-only provider support** — Removed Claude CLI provider support entirely. Supported providers are now API-based (`glm`, `claude-api`, with `claude` aliasing to `claude-api`).
- **Strict model selection** — LLM operations now require an explicit model source: pass `--model` or configure provider `:default-model`; no implicit fallback model is selected.
- **Provider credential policy** — Removed legacy file-based credential fallback; provider credentials now resolve from `NOUMENON_LLM_PROVIDERS_EDN` and process environment variables.
- **Analysis/synthesis provenance** — LLM transactions now record provider and model provenance via `:tx/provider` and `:tx/model-source` metadata.

### Fixes

- **Provider migration errors** — Using removed `claude-cli` now fails with explicit migration guidance to `claude-api`/`claude`.
- **API schema/docs alignment** — OpenAPI and provider-config docs now reflect API-only provider support.

## 0.6.1

### New

- **Provider/model catalog commands** — Added `noum llm-providers` and `noum llm-models` for discovering configured providers, provider defaults, and available models.
- **MCP provider/model catalog tools** — Added `noumenon_llm_providers` and `noumenon_llm_models` with help/schema metadata so agents can inspect defaults and model availability without reading config files.

### Changed

- **Provider default selection** — Noumenon now resolves one global default provider via `NOUMENON_DEFAULT_PROVIDER`, then `:default-provider` in `NOUMENON_LLM_PROVIDERS_EDN`, then built-in fallback.
- **Provider model policy** — Each provider can declare `:models` plus a single `:default-model`; model selection now resolves per-provider defaults when `--model` is omitted.
- **Dynamic model discovery** — `llm-models`/`noumenon_llm_models` prefer provider API discovery (`:models-path`, with known defaults) and fall back to configured `:models` when discovery is unavailable.

## 0.6.0

### New

- **Provider-agnostic LLM config** — Added `NOUMENON_LLM_PROVIDERS_EDN` support for API providers, allowing per-provider `:base-url` and `:api-key` configuration (for example: `:glm`, `:claude-api`, gateway-backed providers) through one canonical EDN map.

### Changed

- **Runtime mode policy for secrets** — Added `NOUMENON_RUNTIME_MODE=local|service` (default `local`). In `service` mode, file-based credential fallback is disabled and only process env secrets are used.
- **Provider resolution precedence** — API providers now resolve config in this order: canonical EDN map entry, legacy env var fallback, then built-in default base URL (API keys are never defaulted).
- **Centralized provider resolution** — API-provider invocation now routes through a normalized resolver in `src/noumenon/llm.clj` returning `{:base-url :api-key}` to reduce provider-specific branching.

### Fixes

- **Service URL hardening** — API provider base URLs are now validated as absolute URLs, and `service` mode requires `https`.
- **Safe error handling for credentials** — Missing-key failures are explicit while avoiding secret value leakage in error messages.
- **Optional base URL allowlist** — Added `NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN` support to restrict provider base URL hosts/patterns.

## 0.5.6

### New

- **Pipeline selectors** — `analyze`, `enrich`, `update`, and `digest` now accept `--path`, `--include`, `--exclude`, and `--lang` to scope work to selected files/directories/languages. Added parity across JVM CLI, launcher (`noum`), HTTP API, and MCP tool schemas.
- **OpenAPI selector schema** — Added `PathSelectors` to `docs/openapi.yaml` and wired it into analyze/enrich/update/digest endpoint request bodies.

### Changed

- **Prompt/model drift behavior** — Drift is now advisory by default. Noumenon logs recommended re-analysis counts but does not auto re-analyze unless you explicitly pass `--reanalyze prompt-changed` or `--reanalyze model-changed`.

### Fixes

- **MCP repo path mapping** — Remote MCP proxy now derives database names from local path semantics (e.g. `mino`) instead of org-repo remote URL synthesis (e.g. `leifericf-mino`), preventing status/query failures on path-to-db translation.
- **Launcher command help** — `noum help <command>` now renders command options (including `analyze`) so users can discover flags without leaving the CLI.

## 0.5.5

### New

- **Multi-repo introspect evaluation** — `extra_repos` parameter on MCP, HTTP, and CLI introspect commands. Evaluates prompt changes across multiple repos to reduce overfitting. Averages scores from primary + extra repos.
- **`introspect-skipped` query** — New named query exposes skipped iterations (parse failures, validation errors, gate failures) for diagnosing introspect issues.
- **Introspect status progress** — `noumenon_introspect_status` now shows current iteration number and last outcome message, not just elapsed time.

### Fixes

- **Cascading template expansion** — All prompt renderers (`agent`, `introspect`, `benchmark`, `analyze`, `synthesize`) switched from sequential `str/replace` to single-pass regex substitution. Previously, inserting a template that contained `{{placeholder}}` strings caused subsequent replacements to cascade, bloating prompts from 5K to 924K.
- **Stale chunked prompts** — `reseed` / bootstrap now uses `save-prompt!` which properly retracts old chunks before writing. Previously, a prompt bloated by introspect and stored as chunks survived reseeds because the raw upsert added `:template` without retracting stale `:chunks`.
- **EDN extraction from prose** — Introspect proposal parser now extracts the outermost `{...}` EDN map from optimizer responses that wrap the proposal in explanatory prose. Previously, the entire response was parsed as EDN, failing on any surrounding text.
- **Git commit on Datomic-only changes** — `git-commit-improvement!` no longer throws when introspect improves a Datomic-only target (examples, system-prompt, rules) that produces no filesystem changes. Previously, `git commit` exited 1 with "nothing to commit", which propagated as an exception inside `with-modification`, reverting the improvement.
- **Introspect error persistence** — Skipped iterations now store the raw optimizer response or error message in `:introspect.iter/error` for post-hoc diagnosis.

## 0.5.4

### Fixes

- **MCP daemon lock contention** — `noum serve` now auto-detects a running local daemon via `daemon.edn` and proxies tool calls to it instead of opening the database directly. Previously, the daemon's exclusive file lock caused every MCP tool call to fail with a generic "unexpected internal error."
- **MCP error messages** — Tool call errors now include the actual cause and tool name instead of "An unexpected internal error occurred." Database lock errors include actionable kill instructions and explicitly tell AI agents not to retry.
- **MCP proxy auth header** — Proxy mode no longer sends `Authorization: Bearer null` when connecting to a local daemon without a token.
- **Setup binary path** — `noum setup code` now resolves the `noum` binary via `PATH` (e.g. Homebrew at `/opt/homebrew/bin/noum`) instead of always hardcoding `~/.local/bin/noum`.
- **Demo release fallback** — `noum demo` now searches the 5 most recent GitHub releases for a demo tarball instead of only checking the latest. Prevents "not found" errors when a patch release ships without a new demo database.
- **Progress bar lifecycle** — The launcher's progress handler now resets the bar on completion and creates a new bar when the total changes. Fixes the flashing green bar during digest benchmark and spurious "✓ digest done." lines between steps.
- **Progress bar step labels** — Digest sub-steps (analyze, benchmark) tag their SSE progress events with `:step`, so the bar shows "✓ analyze done." instead of "✓ digest done."
- **Synthesize progress event** — Added missing `:current`/`:total` keys to the synthesize progress event, preventing NPE in the launcher handler.
- **Digest output formatting** — Nested result maps (analyze, benchmark, synthesize) are now printed as an indented tree with floats rounded to 2 decimal places, instead of raw EDN.

## 0.5.3

### Fixes

- **Stale JAR auto-update** — `jar/ensure!` now reads `version.edn` from the installed JAR and compares against the launcher version. On mismatch, stops the daemon, downloads the matching release, and restarts fresh. Previously, an existing JAR was never re-checked, so Homebrew launcher updates silently ran against an old backend.
- **Daemon bounce on upgrade** — `noum upgrade` now stops the running daemon after downloading a new JAR, so the next command starts with the updated code.
- **Version def shared** — Moved from `main.clj` (private) to `paths.clj` so both `main` and `api` pass it to `jar/ensure!`.

## 0.5.2

Security hardening, bug fixes, and UX polish.

### Security

- **EDN read-eval disabled** — `*read-eval*` bound to false in introspect code verification; `{:readers {}}` added to all `edn/read-string` calls parsing LLM responses, checkpoints, and external data
- **CORS restricted** — `file://` origins now require explicit `NOUMENON_ALLOW_FILE_ORIGIN` env var
- **Admin-only endpoints** — `/api/query-raw` and `/api/ask/sessions` added to admin-only prefixes
- **SSRF hardening** — CGN range `100.64.0.0/10` added to blocked IP patterns; `--` separator in git clone commands; proxy host URL validation
- **Subprocess timeouts** — Python, Node, C, and Elixir import extractors now timeout after 30 seconds
- **Hook state directory** — Moved from world-writable `/tmp` to user-private `~/.noumenon/tmp/`
- **CI tag validation** — `GITHUB_REF_NAME` validated as semver before shell substitution in release workflow
- **Credential handling** — Directory permissions set before writing config; warning on `--token` + `--insecure`
- **MCP proxy** — Admin tool forwarding logged; read-only flag respected for `git_commit`; SSRF check on proxy host
- **Electron navigation** — Restricted to exact daemon port instead of any localhost port

### Fixes

- **MCP digest skip flag** — Synthesize step was gated on `skip_analyze` instead of `skip_synthesize`
- **Merge retry usage** — `invoke-merge` now accumulates LLM token usage from both attempts
- **Agent nil dispatch** — Guard against nil tool dispatch when LLM sends only `:reflect`
- **Benchmark stop-flag** — `run-benchmark!` accepts external stop-flag for HTTP introspect sessions
- **Database deletion** — Removed post-Datomic filesystem deletion that could corrupt shared storage
- **Session limit race** — `register-ask-session!` enforced atomically via single `swap!`
- **Leaf file re-enrichment** — Files with no imports now get empty `[]` for `:file/imports` to prevent redundant re-processing
- **Test speed** — 429 retry test binds `*max-retries*` to avoid 6-second sleep
- **Limit param coercion** — HTTP query endpoints coerce string `:limit` to long
- **History help text** — Replaced hardcoded prompt names with dynamic hint

### UX Improvements

- **CLI** — Spinner cleanup on API errors; actionable watch failure messages; dynamic prompt listing; post-setup instructions; upgrade progress spinner; explicit "Daemon: not running" message
- **TUI** — Non-interactive auto-select warns to stderr; confirm defaults to false for safety
- **UI** — Feedback polarity from event data; in-app delete confirmation; active nav indicator; flex layout for ask results; theme cached in localStorage; graph loading skeleton; empty table/history states; truncation with tooltips; formatted introspect deltas; error state on network failure
- **MCP** — Digest description lists all pipeline steps; `skip_synthesize` in schema; search clarifies embed prerequisite; list_queries mentions required parameters
- **Sidebar** — Unicode icons replace ambiguous single letters
- **Benchmark** — "Select 2 runs to compare" hint text

## 0.5.1

TUI hotfix.

### Fixes

- **Arrow key navigation** — Menu selector now uses `cond` instead of `case` for escape sequence matching (Babashka's `case` doesn't resolve var references)
- **Menu line breaks** — Raw terminal mode uses `\r\n` instead of `\n` for correct vertical layout
- **Back navigation** — Selecting "← Back" no longer leaves a stray line in the console
- **Key input** — Reads from `/dev/tty` directly instead of `System/in` for reliable raw-mode input

### New

- **`embed` command** in launcher — help text and Pipeline menu entry

## 0.5.0

TF-IDF vector search, hierarchical synthesis, and cross-repo benchmarks.

### New

- **TF-IDF vector search** — `embed` pipeline stage builds a vocabulary and vector index from file and component summaries. Pure Clojure, no external dependencies beyond Nippy for serialization.
- **`noumenon_search` MCP tool** — Semantic file/component search without the agent loop. Zero LLM calls, millisecond responses.
- **Ask agent seeding** — The ask agent is seeded with TF-IDF search results before querying the knowledge graph, giving it a warm start on relevant files and components.
- **`embedded` benchmark layer** — Measures TF-IDF retrieval quality alongside raw and full KG layers.
- **`:full` layer enriched** — Benchmark's full layer now includes both KG query results and TF-IDF search results when available — representing everything Noumenon has.
- **Hierarchical map-reduce synthesis** — Repos with 250+ files are synthesized per directory partition, then merged. Fixes guava (3,333 files) and redis (1,754 files) which previously returned 0 components.
- **Session seed logging** — Ask sessions persist TF-IDF seed results to Datomic for analytics.

### Changed

- **Neural net input** — Query routing model now uses TF-IDF vectors instead of bag-of-words, giving it term-importance weighting. Existing trained models require retraining.
- **MCP digest handler** — Now includes synthesize and embed steps (was missing both).
- **Raw context limit** — Reduced from 800K to 500K chars to stay within the ~200K token API limit.
- **Default benchmark provider** — Falls back to GLM instead of Claude CLI.

### Fixes

- MCP benchmark handler wasn't passing model-config, causing raw layer to silently fail via Claude CLI
- Synthesize retraction + creation in same Datomic transaction caused datoms-conflict on re-synthesis
- MCP synthesize and digest handlers weren't seeding new prompt templates
- Recursive directory partitioning caused StackOverflowError on flat directory structures (redis)
- Merge synthesis validator rejected components with `:source-components` instead of `:files`

### Benchmarks

Cross-repo benchmark (8 repos, 7 languages, 22 deterministic questions each):

| Metric | Without Noumenon | With Noumenon | Improvement |
|--------|-----------------|---------------|-------------|
| Accuracy | 20% | 53% | 2.7x |
| Token cost | 37K | 7K | 80% cheaper |
| Speed | 13.6s | 6.1s | 55% faster |

## 0.4.0

Architectural synthesis, visual desktop UI, and interactive CLI.

### New

- **Interactive TUI** — `noum` with no arguments enters a menu-driven interface. Browse commands by category, select repositories/sessions/queries from live data. Smart arg collection for all commands including introspect sub-actions.
- **Visual desktop UI** — Electron + ClojureScript app with force-directed graph visualization. Three-level drill-down (components, files, segments), floating Ask overlay with streamed reasoning, @-mention autocomplete. Launch with `noum open`.
- **`synthesize` command** — Identifies logical components from file summaries, import edges, and directory structure. Maps component dependencies, layers, and categories. Language-agnostic.
- **Component entities** — `component/name`, `component/summary`, `component/layer`, `component/category`, `component/depends-on`. Files link via `arch/component`.
- **9 new named queries** — `components`, `component-files`, `component-dependencies`, `component-dependents`, `component-authors`, `component-churn`, `component-bus-factor`, `cross-component-imports`, `subsystems`.
- **`noum demo`** — Pre-built knowledge graph for instant querying without credentials.
- **Top-down query strategy** — Ask agent starts at component level for architectural questions.

### Security

- Electron renderer uses contextBridge (no executeJavaScript)
- CORS restricted to Electron origin
- Bounded `edn/read-string` on LLM-sourced strings

### Fixes

- Inline markdown parser duplication bugs
- Concurrent SSE submission guard
- Electron namespace collision with Replicant fragments
- Unbounded memoize memory leak in graph builders

## 0.3.1

Security and UX hardening.

- **Path traversal fix** on `DELETE /api/databases/:name`
- **Constant-time token comparison** for auth
- **HTTPS by default** for remote `--host` connections
- **Token via env var** instead of CLI arg (hidden from `ps aux`)
- **SSE error propagation** — errors surface correctly instead of wrapping as success
- **Delete confirmation** with `--force` to skip
- **Relative path resolution** for all commands
- 20+ additional security, UX, and robustness fixes — see [git history](https://github.com/leifericf/noumenon/commits/main)

## 0.3.0

`noum` CLI launcher, HTTP daemon, and Docker image.

- **`noum` binary** — Self-contained Babashka launcher. Auto-downloads JRE and backend. 30 commands with custom TUI (spinner, menu, progress bar, table).
- **HTTP daemon** — 22 REST endpoints, bearer token auth, SSE progress streaming.
- **Docker image** — 167MB Alpine, non-root, auth required for network access.
- **`noum setup`** — Auto-configures MCP for Claude Desktop and Claude Code.
- **OpenAPI spec** at `docs/openapi.yaml`

## 0.2.0

Introspect (autonomous self-improvement) and ML query routing.

- **Introspect loop** — LLM optimizer proposes changes to prompts, examples, rules, and code. Keeps improvements, reverts regressions. Multi-repo evaluation to prevent overfitting.
- **ML query routing** — On-device neural network predicts which Datalog queries to try. Trained locally at zero token cost.
- **Issue reference extraction** from commits (`#123`, `PROJ-456`)
- **Scoped re-analysis** — `--reanalyze` with `all`, `prompt-changed`, `model-changed`, `stale` modes

## 0.1.0

First public release.

- Import pipeline (git history into Datomic knowledge graph)
- LLM analysis (semantic metadata: complexity, safety, purpose)
- Import graph extraction (10+ languages)
- Named Datalog queries with parameterization and rules
- Agentic `ask` command (natural-language via iterative Datalog)
- MCP server (`noum serve`)
- Benchmark framework with checkpointing and resume
- Concurrent processing (configurable parallelism)
