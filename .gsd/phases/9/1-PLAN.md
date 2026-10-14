# Plan 9.1 — `services/ai` gateway, providers, tenant LLM policy, redaction, exchange audit

Working plan (brief). Source: REQ-100, REQ-101, REQ-106 (audit part), NFR-07; BLUEPRINT §13, §18 "External LLM data
policy"; ADR-12, ADR-15, ADR-17. New: ADR-64 (gateway placement), ADR-66 (redaction and exchange audit), both Proposed.
Wave A — first; 9.2 builds the tools on it.

## Key decision (ADR-64)

The gateway lives where STACK/ARCHITECTURE already put it: **`services/ai`, Python 3.12, FastAPI, port 8090**. It is a
stateless client of the platform API. It never connects to PostgreSQL and never holds a service account: every call it
makes carries the caller's bearer token (token passthrough). The web dev server proxies `/api/assistant` to 8090 and
everything else under `/api` to the Java API on 8080. Exchanges are persisted (REQ-106) through a Java `ai` module
endpoint, so tenancy (RLS) and audit stay in one place.

## Tasks

1. **Service skeleton** — `services/ai/pyproject.toml` (fastapi, httpx, pydantic; dev: pytest, ruff), package
   `vorg_ai`, `config.py` (env only: `VO_API_BASE_URL`, `VO_AI_PROVIDER` = `auto|offline`, `OPENAI_API_KEY`,
   `VO_AI_OPENAI_MODEL`, `VO_AI_OPENAI_BASE_URL`, `VO_AI_PROVIDER_REGION`, `VO_AI_RETENTION` = `redacted|none`,
   timeouts). A missing key never fails startup.
2. **Provider interface** — `providers/base.py`: `Provider.respond(turn, runtime) -> ProviderResult` (answer, cited
   evidence ids, directives, usage {prompt, completion, method}). Vendor code only in `providers/openai_provider.py`
   (raw HTTPS through httpx, no SDK; Chat Completions with function tools and a JSON answer). `providers/offline.py`
   is the default deterministic provider (rules and templates, no network).
3. **Tenant LLM policy** — `policy.py`: the external provider is selected only when (a) `OPENAI_API_KEY` is set,
   (b) `VO_AI_PROVIDER=auto`, and (c) the caller's tenant has `llm_external_allowed=true` (added to `GET /api/me`).
   Otherwise offline, with the reason in `provider.reason`. Demo tenant: false, so offline.
4. **Redaction** — `redaction.py`: secrets (bearer/JWT, `sk-` keys, `password=`) are removed for good. PII (email,
   phone, Aadhaar/PAN-like ids, card-like numbers) is tokenized `[EMAIL_1]` and restored only in the local answer.
   Applied to the user text and to every tool result before an external call. Counts per kind, never values.
5. **Exchange audit** — `audit.py` builds the record from redacted text only: provider, model, external flag, token
   counts (provider usage or a labeled regex count), tool calls, citation ids, redaction counts, flags, latency.
   Java: `V9_1_01__ai_exchanges.sql` (`ai.exchanges`, forced RLS, no UPDATE/DELETE), `POST /api/ai/exchanges`
   (command, `Idempotency-Key` = exchange id, `VIEW_OPERATIONAL_STATE` on the site, actor from the token, 400 when the
   body still contains a secret pattern), `GET /api/ai/exchanges?site_id=` (own exchanges only).
6. **Tests** — pytest: policy matrix, redaction round trip, no secret in an audit record, OpenAI provider against an
   `httpx.MockTransport` (never the network). Java compiles (`install -DskipTests`).

Commit: `feat(phase-9): ai gateway, providers, tenant LLM policy, redaction and exchange audit`.
