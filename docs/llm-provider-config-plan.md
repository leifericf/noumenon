# Provider-Agnostic LLM Config Plan (Local + Service)

## Goal

Implement a provider-agnostic configuration system for LLM endpoint URL + API key that works for both:

- local CLI usage
- headless/hosted service deployments

while preserving backward compatibility with existing provider-specific env vars.

---

## Target Outcome

- One canonical config model for all providers.
- No one-off provider code additions for new gateways.
- Service-safe behavior (no implicit local credential file fallback).
- Existing users continue to work without immediate migration.

---

## Canonical Config Contract

Primary env var:

- `NOUMENON_LLM_PROVIDERS_EDN`

EDN map shape:

```clojure
{:glm        {:base-url "https://api.z.ai/api/anthropic" :api-key "xxx"}
 :claude-api {:base-url "https://api.anthropic.com"      :api-key "yyy"}
 :tencent    {:base-url "https://your-litellm-gateway"   :api-key "zzz"}}
```

Notes:

- map keys are provider ids used by `--provider`
- future-safe optional fields may include `:headers`, `:timeout-ms`, `:max-retries`
- for API providers, `:api-key` is required; `:base-url` defaults only when known

---

## Runtime Mode Policy

Introduce runtime mode:

- `NOUMENON_RUNTIME_MODE=local|service` (default: `local`)

Behavior:

- `local`
  - allow env + existing file-based credential fallback behavior
- `service`
  - disable file-based secret fallback (`~/.noumenon/credentials`, project `.env`)
  - only use process env / secret-injected env
  - stricter URL validation (`https`)

---

## Resolution Precedence

For selected provider, resolve config in this order:

1. explicit runtime override (if supported/permitted)
2. entry in `NOUMENON_LLM_PROVIDERS_EDN`
3. legacy env vars (compatibility)
4. built-in default URL only (never default API key)

Legacy compatibility mappings:

- `glm` key fallback: `NOUMENON_ZAI_TOKEN`
- `claude-api` key fallback: `ANTHROPIC_API_KEY`

---

## Implementation Steps

1. **Refactor provider resolution in `src/noumenon/llm.clj`**
   - add `resolve-provider-config` returning normalized:
     - `{:base-url ... :api-key ...}`
   - route all API-provider invocation through this resolver

2. **Add mode-aware secret loading**
   - mode gate in env-reading path
   - preserve current behavior in local mode
   - disable file-based fallback in service mode

3. **Validation and hardening**
   - fail clearly when API key missing
   - validate base URL (absolute URL; `https` required in service mode)
   - optional allowlist support via:
     - `NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN` (hosts or patterns)

4. **Secret-safe logging**
   - never log `:api-key` or auth headers
   - redact sensitive values in exceptions/debug output

5. **HTTP-only provider behavior**
   - support API providers only (`glm`, `claude-api`)
   - keep provider invocation on HTTP APIs only

6. **Docs/help updates**
   - update `README.md` config section
   - document local vs service mode differences
   - include LiteLLM/tencent gateway example
   - add migration note for legacy env vars

7. **Tests**
   - precedence tests (new map > legacy > defaults)
   - local vs service fallback behavior
   - legacy variable compatibility
   - missing key and URL validation errors
   - redaction assertions

---

## Minimal First Slice (MVP)

Ship first in a focused slice:

- canonical EDN provider map support
- resolver refactor in `llm.clj`
- local/service mode switch for credential fallback
- core validation + tests
- docs for setup and migration

This delivers immediate provider-agnostic endpoint/key support with minimal churn.

---

## Rollout Strategy

- **Phase 1**: Introduce canonical map + compatibility fallback.
- **Phase 2**: Recommend service-hardening defaults (allowlist, strict mode) in docs.
- **Phase 3**: Deprecate legacy vars (warn-only first, removal later).

---

## Open Decision

Decide strictness in service mode when canonical map is missing:

- **Recommended now**: still allow legacy env fallback for smoother migration.
- **Future tighten**: require canonical provider map in service mode.

---
