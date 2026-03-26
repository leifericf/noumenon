# Pre-Release Issues Found

Issues discovered during E2E testing across all supported languages.

## Bug: Import fails on repos with merge commits

**Severity:** High — blocks importing many real-world repos
**Component:** `noumenon.git/import-commits!`
**File:** `src/noumenon/git.clj`

**Symptom:** Import crashes mid-way with one of two Datomic errors:
1. `:db.error/not-an-entity` — "Unable to resolve entity: [:git/sha "abc..."] in datom [...commit/parents...]"
2. `:db.error/datoms-conflict` — "Two datoms in the same transaction conflict"

**Affected repos (all failed):**
- Rust: ripgrep, anyhow, serde, snafu, log, regex, clap
- Java: gson
- Erlang: cowboy, OTP

**Working repos (all succeeded):**
- Clojure: Ring (1288 commits)
- Elixir: Jason (249 commits)
- Python: MarkupSafe (844 commits)
- JavaScript: is-number (62 commits)
- Java: jsoup (2401 commits)
- Erlang: recon (300 commits)

**Root cause:** Commits are imported in batches using `git log` order (reverse chronological). Each commit's `:commit/parents` uses Datomic lookup refs (`[:git/sha "..."]`) to reference parent commits. When a merge commit references a parent on a side branch, that parent SHA may not have been imported yet (it appears later in `git log` output or is in a batch that hasn't been transacted). Datomic requires lookup ref targets to already exist.

**Pattern:** Repos that fail tend to have frequent merge commits (merge-based PR workflows). Repos with linear histories (rebase/squash workflows) tend to succeed.

**Additional observation from `list-databases`:** Failed repos imported some commits (the ones before the crash) but 0 files and 0 dirs. This means the commit import crashes before the file import phase even starts. The partial commits remain in the database as orphaned data. Example: `anyhow` shows 470 commits but 0 files.

**Evidence:** `git log --oneline --graph` on affected repos shows merge commits (`Merge pull request #...`) with side-branch parents. The parent SHA in the error message is always on a side branch, not the main line.

**Fix options (in order of preference):**
1. Import commits in topological order (`git log --topo-order` or `git rev-list --topo-order`) so parents always precede children
2. Two-pass: first create all commit entities (without parents), then add parent edges in a second pass
3. Use Datomic tempids within the same transaction batch for commits that reference each other
4. Catch `:db.error/not-an-entity` and retry the failed batch after more commits are imported

---

## Bug: Postprocess resolves 0 import edges for Ring (Clojure) and jsoup (Java)

**Severity:** Medium — import graph extraction appears non-functional for some repos
**Component:** `noumenon.imports/postprocess-repo!`
**File:** `src/noumenon/imports.clj`

**Symptom:** `postprocess` completes successfully but reports 0 import edges resolved for repos that clearly have internal imports.

**E2E postprocess results:**

| Repo | Language | Files processed | Import edges resolved |
|------|----------|----------------:|----------------------:|
| Ring | Clojure | 89 | 0 |
| Jason | Elixir | 378 | 2 |
| MarkupSafe | Python | 23 | 0 |
| is-number | JavaScript | 7 | 1 |
| jsoup | Java | 220 | 0 |
| recon | Erlang | 18 | 0 |

**Expected:** Ring (Clojure) should show many import edges — Ring has extensive inter-namespace requires. Java (jsoup) has hundreds of import statements across classes. Even Python (MarkupSafe) has internal module imports.

**Possible causes:**
1. The resolver may be matching on exact file paths from the DB but the DB stores paths differently than what the resolver generates (e.g., relative vs absolute, different prefix)
2. For Clojure: `tools.namespace` extraction may need the file content but the postprocessor reads from disk using repo-relative paths that don't match
3. For Java/Python/Erlang: the regex-based extractors may work in unit tests but fail on real file contents (encoding, line endings, etc.)

**Reproduction:**
```bash
rm -rf data/datomic/
clj -M:run import https://github.com/ring-clojure/ring.git
clj -M:run postprocess data/repos/ring
# Observe: 0 import edges resolved
```

**Next steps:** Debug by running postprocess with verbose logging on a single known file that has imports, trace what `extract-imports` returns vs what `resolve-import` matches against the file path set in the DB.

---

## Enhancement: `analyze` command lacks `--max-cost` and `--max-files` flags

**Severity:** Low — quality-of-life improvement
**Component:** `noumenon.cli` / `noumenon.main/do-analyze`

**Symptom:** The `analyze` command has no way to limit how many files it processes or how much it spends. The `--max-cost` flag exists on `benchmark` but not on `analyze`. There is no `--max-files` flag on any command.

**Impact:** When doing a quick validation run (e.g., testing that analysis works on a new language), you're forced to analyze all files in the repo. For large repos (500+ files), this is expensive and slow when you only need a small sample.

**Desired behavior:**
- `--max-cost` on `analyze` — stop when cumulative LLM cost exceeds threshold
- `--max-files` on `analyze` — stop after analyzing N files (useful for sampling)
- Consider also `--max-percent` to analyze a percentage of files (e.g., `--max-percent 10`)

---

## Cosmetic: `list-databases` does not show postprocess stage for repos where 0 edges were resolved

**Severity:** Low — cosmetic/informational
**Component:** `noumenon.db/db-stats` or `noumenon.main/format-pipeline`

**Symptom:** After running `postprocess` on jsoup, markupsafe, recon, and ring (all reporting 0 import edges), the `list-databases` output shows no `postprocess:N` entry for those repos. Only is-number (1 edge) and jason (2 edges) show the postprocess stage. But postprocess DID run — it just didn't find any edges.

**Expected:** `list-databases` should show `postprocess:1` (or similar) to indicate that postprocess was run, even if 0 edges were resolved. Otherwise users can't tell if postprocess was skipped or just found nothing.

**Actual `list-databases` output (selected):**
```
is-number    62 commits, 15 files, 2 dirs  [import:63 analyze:7 postprocess:1]
jason       249 commits, 384 files, 9 dirs  [import:250 analyze:376 postprocess:1]
jsoup      2401 commits, 294 files, 46 dirs [import:2402 analyze:220]           ← no postprocess shown
ring       1288 commits, 117 files, 85 dirs [import:1289 analyze:89]            ← no postprocess shown
```
