# Security Issue Discovery — Pass 1

**Scope:** Full repo (src/noumenon/*.clj)
**Commit:** e63cd59
**Date:** 2026-03-26

## Summary

| Severity   | Count |
|------------|-------|
| Very High  | 0     |
| High       | 1     |
| Medium     | 2     |
| Low        | 0     |

---

## Issues

### SEC-002: SSRF via unvalidated Git URLs in `import` and `update` CLI commands
- **Severity:** High
- **File:** `/Users/leif/Code/noumenon/src/noumenon/git.clj:13-17`, `/Users/leif/Code/noumenon/src/noumenon/main.clj:69-81`
- **Threat:** The `git-url?` predicate accepts any string matching `https?://.+` or `git@.+`, including URLs that target internal network resources. A user or script invoking `noumenon import https://169.254.169.254/latest/meta-data/` or an internal IP would cause the server to clone from that address, creating a server-side request forgery channel. The process runs with the same network privileges as the server.
- **Evidence:**
  ```clojure
  (defn git-url?
    [s]
    (boolean (or (re-matches #"https?://.+" s)
                 (re-matches #"git@.+" s))))
  ```
  No block-list for loopback (`127.0.0.0/8`), link-local (`169.254.0.0/16`), or RFC-1918 private ranges. `resolve-repo-path` in `main.clj` calls `git/clone!` for any URL that passes this check, without any further host validation.
- **Mitigation:** Before cloning, resolve the URL hostname to an IP address and reject loopback, link-local, and RFC-1918 ranges. Alternatively, document that URL-based import is a trusted-user CLI feature only and is not exposed via the MCP server surface.
- **Confidence:** High (the MCP `noumenon_import` handler requires a local path and calls `validate-repo-path!`, so the MCP surface itself is not affected; the CLI `import` and `update` subcommands are the attack surface)

---

---

### SEC-004: Datalog query denial-of-service — unbounded `d/q` execution before result truncation
- **Severity:** Medium
- **File:** `/Users/leif/Code/noumenon/src/noumenon/agent.clj:139-147`
- **Threat:** A malicious MCP client using `noumenon_ask` can craft a Datalog query that passes `validate-query` (all symbols are on the allowlist) but performs a cartesian join across large relations. `d/q` runs to completion without a timeout before results are truncated. This can exhaust JVM heap and cause a denial of service.
- **Evidence:**
  ```clojure
  (let [limit  (min (or (:limit parsed-args) default-row-limit) max-row-limit)
        result (try
                 (d/q q db rules) ...)
        taken  (vec (take (inc limit) result))
  ```
  The full query executes before `take` applies truncation. A query like `[:find ?c ?f :where [?c :git/type :commit] [?f :file/path _]]` could return millions of tuples on a large repo before truncation occurs.
- **Mitigation:** Wrap `d/q` in a `future` with a `deref` timeout (e.g., 5 seconds), canceling the query on timeout. Alternatively, prepend a `:limit N` constraint to agent-generated queries if Datomic Local supports it.
- **Confidence:** Medium

---

### SEC-006: Stored HEAD SHA used in `git diff` without format validation in sync.clj
- **Severity:** Medium
- **File:** `/Users/leif/Code/noumenon/src/noumenon/sync.clj:41-46`
- **Threat:** The stored HEAD SHA retrieved from the Datomic database is passed directly to `git diff`. If the stored value were ever set to a non-SHA string (corrupted write, direct Datomic transact, or future code path), it could be interpreted as a git ref expression or flag by git. While `shell/sh` does not invoke a shell (no shell metacharacter risk), git itself accepts complex ref syntax like `HEAD~1`, `--option`, or `origin/main` as positional arguments.
- **Evidence:**
  ```clojure
  (defn changed-files [repo-path old-sha]
    (if-not (valid-sha? old-sha)
      (do (log! "WARNING" ...) nil)
      (let [{:keys [exit out]}
            (shell/sh "git" "-C" (str repo-path)
                      "diff" "--name-status" old-sha "HEAD")]
  ```
  `valid-sha?` is called and returns nil on invalid input, so this is currently guarded. The guard works correctly. This is a low-severity note that the guard is the only defense.
- **Mitigation:** The existing `valid-sha?` guard is sufficient. For defense in depth, add `--` before `old-sha` in the git argument list to prevent interpretation as a flag: `"diff" "--name-status" "--" old-sha "HEAD"`. However, `git diff` positional argument order means this would need to be `old-sha..HEAD` syntax.
- **Confidence:** Low (currently guarded; flag injection via `--` would require the SHA to start with `-`)

---

### SEC-007: API error response body logged to stderr — potential credential leakage
- **Severity:** Low
- **File:** `/Users/leif/Code/noumenon/src/noumenon/llm.clj:117-119`
- **Threat:** When an LLM API returns a non-200 status, up to 200 characters of the raw response body are logged to stderr. API error responses sometimes include request context or provider-specific error messages that may contain partial credential hints. In a shared-logging environment this could be observed.
- **Evidence:**
  ```clojure
  (log! (str "API error response (HTTP " status "): "
             (truncate (str body) 200)))
  ```
- **Mitigation:** Parse the response as JSON and log only specific safe fields (`error.type`, `error.code`) rather than the raw body. The thrown exception correctly omits the body.
- **Confidence:** Low

---

### SEC-009: `escape-template-vars` only escapes `{{` — misleading as a security primitive
- **Severity:** Low
- **File:** `/Users/leif/Code/noumenon/src/noumenon/util.clj:18-21`
- **Threat:** `escape-template-vars` is used throughout to "sanitize" untrusted file content before embedding in LLM prompts. It only replaces `{{` with `{ {`. This is sufficient for template-variable injection (preventing `{{repo-name}}` from being substituted) but provides no defense against LLM-level natural-language instruction injection from adversarial file content. The function name implies a broader security guarantee than it delivers.
- **Evidence:**
  ```clojure
  (defn escape-template-vars [s]
    (str/replace (or s "") "{{" "{ {"))
  ```
  Called in `analyze.clj:render-prompt` and `benchmark.clj:sanitize-file-content` on untrusted source file contents.
- **Mitigation:** No code change required for template injection (the current defense is correct for that threat). Rename to `escape-template-delimiters` or add a docstring clarifying the scope: "prevents `{{var}}` substitution; does not sanitize LLM instruction injection." The XML delimiters in `render-prompt` (`<file-content>`) serve as the primary LLM injection mitigation.
- **Confidence:** High (for the naming/documentation gap); Low (for exploitability given existing XML delimiters and the `answer-prompt` instruction note)

---

## Pass 2 — Saturation

**Files reviewed:** `deps.edn`, `llm.clj`, `db.clj`, `mcp.clj`, `analyze.clj`, `files.clj`, `imports.clj`, `sync.clj`, `agent.clj`, `benchmark.clj`, `util.clj`, `query.clj`, `git.clj`
**Date:** 2026-03-27

---

### SEC-012: `invoke-claude-cli` passes full prompt as a CLI argument — argument length not bounded
- **Severity:** Low
- **File:** `/Users/leif/Code/noumenon/src/noumenon/llm.clj:154-158`
- **Threat:** The full prompt string (which may include file content up to `max-file-content-chars` = 100,000 characters from `analyze.clj`, or entire flattened message histories from `agent.clj`) is passed as a single positional argument via `-p prompt` to the Claude CLI subprocess. On macOS/Linux the per-argument limit is typically 128 KB–2 MB depending on kernel configuration. A prompt approaching or exceeding this limit causes `ProcessBuilder` to throw, potentially with an unhelpful error or silent truncation in some JVM implementations. More importantly, in the context of `invoke-cli` called from the agent loop, the flattened conversation history grows unboundedly across iterations (up to 50 iterations) with no truncation before the CLI call.
- **Evidence:**
  ```clojure
  (let [cmd (cond-> ["claude" "--print" "--output-format" "json"]
               (:model opts) (into ["--model" (:model opts)])
               true          (into ["-p" prompt]))]
  ```
  `flatten-messages` in `invoke-cli` joins all messages with double newlines. After 50 iterations the accumulated prompt could easily reach several hundred kilobytes.
- **Mitigation:** Add a character length cap on the prompt before passing it to `ProcessBuilder` (e.g. truncate at 512 KB with a warning). Alternatively, use `stdin` for the prompt rather than a CLI argument — `ProcessBuilder` supports writing to the process's stdin stream, which avoids OS argument length limits entirely.
- **Confidence:** Medium (the issue is real; exploitability depends on how large the repository files and conversation history grow in practice)

---

