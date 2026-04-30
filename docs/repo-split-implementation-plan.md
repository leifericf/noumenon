# Noumenon Multi-Repo Split Plan (Core + App + Site)

## Goal

Split the current monorepo into clear product boundaries while preserving user experience and release continuity:

- `noumenon` (public): core backend/CLI/API (including `noum` CLI)
- `noumenon-app` (private during migration): Electron desktop app (from `ui/`)
- `noumenon-site` (private during migration): website/docs site (from `docs/`)

## Locked Decisions

1. Keep CLI in `noumenon` (do not split CLI into a separate repo now).
2. Move Electron CI/CD to `noumenon-app`.
3. Move website deploy pipeline to `noumenon-site`.
4. Keep OpenAPI canonical source in core (`noumenon`).
5. Preserve Electron release semantics/tags/artifact conventions.
6. Site cut-over strategy: dry-run first (`github.io`), then custom domain switch in GitHub Pages settings.
7. `noumenon-app` and `noumenon-site` become public at cut-over.
8. Temporary release freeze during migration window.
9. Keep Homebrew/Scoop tokens in `noumenon` (they are CLI distribution credentials).
10. Migrate relevant user-facing Markdown docs from core to `noumenon-site` as web content, then remove migrated copies from core to prevent drift.

---

## Current-State Notes (Already Confirmed)

- `ui/` is the Electron app and uses Replicant.
- `docs/` contains the website/docs assets.
- Core workflows currently include app/site-related jobs that must move or be removed from core after split.
- Branch hygiene check is clean for merge readiness.

---

## Target Repo Ownership

## `noumenon` (public)

Owns:

- Core libraries and backend logic
- Datomic/query/indexing pipeline
- `noum` CLI and launcher behavior
- CLI release/distribution (Homebrew/Scoop updates)
- Canonical OpenAPI spec (`docs/openapi.yaml` or chosen core path)
- Core CI/test/lint/format/release workflows
- Contributor/developer/operator docs needed for core development

Must not own after migration:

- Electron build/release jobs
- Site deployment jobs
- Duplicated user-facing docs migrated to `noumenon-site`

## `noumenon-app` (private -> public at cut-over)

Owns:

- Electron app code (split from `ui/`)
- App build/test/release workflows
- Packaging artifacts and updater metadata

## `noumenon-site` (private -> public at cut-over)

Owns:

- Website/docs presentation (split from `docs/`)
- User-facing product documentation pages
- GitHub Pages deployment workflow
- `CNAME` and custom domain settings at cut-over

---

## CI/CD and Secrets Mapping

## Keep in `noumenon` (CLI/core)

- `HOMEBREW_TAP_TOKEN` (or fallback `TAP_TOKEN`)
- `SCOOP_BUCKET_TOKEN` (or fallback `TAP_TOKEN`)
- `GITHUB_TOKEN` is built-in per repo (no manual migration)

## Move/add in `noumenon-app`

- Only app-specific secrets if needed by app release pipeline (signing/notarization/etc. if introduced)

## `noumenon-site`

- Usually no custom secrets needed for standard Pages deploy
- Use `GITHUB_TOKEN` and Pages permissions in workflow

---

## `noum ui` Cross-Repo Contract (Critical)

Keep `noum ui` command in `noumenon` so UX and scripts remain stable.

### Required Behavior

1. `noum ui` remains the same user-facing command.
2. It detects installed `noumenon-app` binary/app bundle.
3. It launches app via platform-native mechanism.
4. It passes stable context (cwd/project path/profile/endpoint) via flags/env.
5. If app missing, returns non-zero and prints deterministic install instructions.
6. If version mismatch, returns clear compatibility remediation.

### Ownership

- `noumenon`: launcher command + compatibility checks/policy
- `noumenon-app`: app runtime + accepted launch args/env contract

### Transitional Fallback (Optional, time-boxed)

- If local monorepo dev checkout has `ui/`, allow legacy local launch path for short migration period.
- Remove fallback after stabilization window.

---

## Compatibility Contract

Define a simple version contract between CLI and app:

- CLI provides: `--from-cli-version <version>` (or env var)
- App provides minimum supported CLI version (config/static metadata)
- CLI may also check app version before launch (if discoverable)

Failure message should include:

- detected CLI version
- detected app version
- required minimum
- one command/link to resolve (upgrade app or CLI)

---

## Site Cut-Over Plan (Custom Domain)

### Phase A: Parallel Dry-Run

1. Deploy `noumenon-site` to default `*.github.io` URL.
2. Validate content parity, links, assets, and performance.
3. Confirm Pages workflow reliability.

### Phase B: Settings Switch

1. Commit/verify `CNAME` in `noumenon-site`.
2. In GitHub Pages settings, set custom domain to production domain.
3. Verify cert/HTTPS status.
4. Confirm live traffic serving from new repo.
5. Keep DNS changes minimal/unnecessary if same owner/account setup supports direct settings switch.

---

## Markdown Docs Migration (Core -> Site)

### Scope

- Identify Markdown docs in `noumenon` that are user-facing and should move to `noumenon-site`.
- Keep only core-internal contributor/developer/operator docs in `noumenon`.
- Explicitly classify every doc as `web-content`, `core-internal`, or `archive/deprecate`.

### Migration Steps

1. Inventory Markdown files in `noumenon` and classify each file.
2. Define target IA in `noumenon-site` (slugs, nav grouping, section hierarchy).
3. Convert/adapt Markdown into site pages/templates.
4. Add redirects/aliases for changed paths where required.
5. Update links in `README.md`, `DEVELOPMENT.md`, and site navigation.
6. Remove migrated Markdown from core (or replace with short pointer stubs).
7. Add guardrail checks/policy to enforce single canonical location.

### Canonical Content Policy

- Canonical user docs live in `noumenon-site`.
- Core repo keeps only docs required to develop/operate core.
- No duplicated long-form user docs across core and site.

---

## Execution Order

1. **Prep + Freeze**
   - Announce and enforce temporary release freeze for app/site pipelines.
   - Record current release/tag conventions and updater expectations.

2. **Create Repos**
   - Create `noumenon-app` (private).
   - Create `noumenon-site` (private).

3. **History-Preserving Splits**
   - Split `ui/` history -> `noumenon-app`.
   - Split `docs/` history -> `noumenon-site`.

4. **Port Pipelines**
   - Move app CI/release workflows into `noumenon-app`.
   - Add Pages deploy workflow to `noumenon-site` (mino-site pattern).

5. **Refactor Core Workflows**
   - Remove app/site-specific jobs from `noumenon` workflows.
   - Keep only core + CLI workflows in `noumenon`.

6. **Implement `noum ui` Launcher Contract**
   - Replace monorepo-coupled launch logic with installed-app launcher.
   - Add missing-app and mismatch handling.
   - Add optional temporary fallback for local `ui/` dev path.

7. **OpenAPI and Docs Boundary**
   - Keep canonical OpenAPI in core.
   - Implement publish/sync step consumed by `noumenon-site`.

8. **Markdown Docs Migration**
   - Inventory/classify markdown in core.
   - Migrate user-facing content to site pages.
   - Update links/navigation/redirects.
   - Remove migrated docs from core (or replace with stubs).

9. **Validation**
   - Run repo-specific quality gates in each repo.
   - Validate release workflows in dry-run/sandbox mode where possible.
   - Run link checks across migrated docs.

10. **Site Cut-Over**
    - Complete dry-run on `github.io`.
    - Switch custom domain in Pages settings.
    - Verify HTTPS and live behavior.

11. **Visibility Flip**
    - Make `noumenon-app` and `noumenon-site` public at cut-over.

12. **Post-Cut-Over Cleanup**
    - Remove temporary compatibility fallback (if enabled).
    - Update all links/docs in all repos.
    - Lift freeze after successful verification.

---

## Acceptance Test Matrix (`noum ui`)

Test on macOS, Linux, Windows.

### Scenario Matrix

1. App installed, compatible versions.
   - Command: `noum ui`.
   - Expected: app launches successfully, exit code 0.

2. App not installed.
   - Command: `noum ui`.
   - Expected: no crash; actionable install instructions; exit code != 0.

3. App installed, incompatible (app too old).
   - Expected: clear mismatch message with detected versions and exact remediation; exit code != 0.

4. App installed, incompatible (CLI too old, if applicable).
   - Expected: clear remediation path; exit code != 0.

5. Context passing (cwd/project path).
   - Launch from different directories.
   - Expected: app opens with correct project/context each time.

6. Optional fallback path enabled (migration window only).
   - In monorepo with local `ui/`: legacy launch still works.
   - Outside monorepo: installed-app path used.
   - Expected: deterministic path selection documented and testable.

---

## Repo-Specific Definition of Done

## `noumenon`

- No Electron/site jobs in workflows.
- CLI release pipeline still updates Homebrew/Scoop.
- `noum ui` launcher works with compatibility/error handling.
- OpenAPI remains canonical and published for site consumption.
- Migrated user-facing Markdown is no longer duplicated in core.
- Remaining core Markdown is intentionally dev/internal or short stubs.
- `README.md`/`DEVELOPMENT.md` links point to canonical site docs.

## `noumenon-app`

- Full app code/history present from `ui/`.
- App CI/release workflows operational.
- Launch arg/env contract documented and implemented.

## `noumenon-site`

- Site code/history present from `docs/`.
- Pages deploy workflow operational.
- `CNAME` + custom domain switch validated at cut-over.
- Migrated Markdown content is published as first-class web pages.
- Navigation includes migrated content.
- Legacy doc paths have redirects/aliases where required.

---

## Risks and Mitigations

1. **Release break during migration**
   - Mitigation: temporary release freeze + staged validations.

2. **`noum ui` breakage for existing users**
   - Mitigation: command stability + deterministic missing-app instructions + compatibility checks.

3. **Domain/certificate disruption**
   - Mitigation: `github.io` dry-run before custom domain switch.

4. **Spec drift (`openapi`)**
   - Mitigation: single canonical source in core + explicit sync/publish path.

5. **Secret misplacement**
   - Mitigation: explicit ownership mapping; keep CLI distribution tokens in core repo.

6. **Broken links after Markdown migration**
   - Mitigation: redirect map + link-audit checks during validation.

7. **Content drift across repos**
   - Mitigation: single-canonical-location policy + cleanup in core + guardrail checks.

---

## Post-Migration Follow-Ups

1. Remove temporary legacy fallback for local `ui/` launch.
2. Add automated integration test for `noum ui` launcher contract.
3. Add CI guardrail that flags duplicated user-facing docs across core and site.
4. Optionally revisit whether CLI should split into separate repo in future, based on cadence divergence.
