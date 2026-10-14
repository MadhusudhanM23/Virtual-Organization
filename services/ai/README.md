# services/ai — AI operations assistant gateway

Python 3.12 FastAPI service on port 8090 (ADR-64). A provider-neutral LLM gateway (ADR-17) with two providers:

- **offline** (default): deterministic rules and templates over allowlisted tools. No network calls, no key.
- **openai**: Chat Completions over raw HTTPS. Used only when `OPENAI_API_KEY` is set, `VO_AI_PROVIDER=auto` and the
  caller's tenant has `llm_external_allowed=true` (ADR-65).

Every tool call runs with the caller's own bearer token. The service holds no credentials of its own and never
connects to the database. Contract: [`docs/api/assistant.md`](../../docs/api/assistant.md).

## Layout (`src/vorg_ai`)

| Module | Role |
|---|---|
| `app.py` | FastAPI: `POST /api/assistant/messages`, `GET /api/assistant/health` |
| `gateway.py` | one exchange: identity, policy, intents, provider, grounding, directives, audit |
| `policy.py` | tenant LLM policy (which provider, and why) |
| `redaction.py` | secret removal and PII tokenization before any external call or record |
| `tools.py` | the allowlist, strict argument models, the per-exchange tool runtime |
| `evidence.py` | evidence ledger, citations, groundedness and directive validation |
| `guards.py` | user intents (write gating, refusals) and prompt-injection scanning |
| `providers/` | `base.py` (interface), `offline.py`, `openai_provider.py` (the only vendor code) |
| `audit.py` | exchange record, stored through `POST /api/ai/exchanges` with the caller's token |
| `platform_client.py`, `identity.py` | token-passthrough HTTP client, `GET /api/me` and the permission-matrix contract |
| `fake_platform.py` | test double of the platform API (tests and `tests/ai-eval` only) |

## Run (Windows PowerShell, one command per line)

```powershell
python -m venv services/ai/.venv
services/ai/.venv/Scripts/python.exe -m pip install -e "services/ai[dev]"
services/ai/.venv/Scripts/python.exe -m uvicorn vorg_ai.app:app --port 8090 --app-dir services/ai/src
```

The API must run on 8080 (`VO_API_BASE_URL`). The web dev server proxies `/api/assistant` to 8090 (`VITE_AI_ORIGIN`).

## Test

```powershell
services/ai/.venv/Scripts/python.exe -m pytest services/ai
services/ai/.venv/Scripts/python.exe -m ruff check services/ai/src services/ai/tests
services/ai/.venv/Scripts/python.exe tests/ai-eval/run_eval.py
```

## Configuration (environment only)

| Variable | Default | Meaning |
|---|---|---|
| `VO_API_BASE_URL` | `http://localhost:8080` | platform API |
| `VO_AI_PROVIDER` | `auto` | `auto` or `offline` (never external) |
| `OPENAI_API_KEY` | unset | enables the OpenAI provider (local `.env` only; never commit it) |
| `VO_AI_OPENAI_MODEL` | `gpt-4o-mini` | model name |
| `VO_AI_OPENAI_BASE_URL` | `https://api.openai.com/v1` | endpoint (an Azure/OpenAI-compatible proxy also works) |
| `VO_AI_PROVIDER_REGION` | `unspecified` | approved provider region, returned with external answers |
| `VO_AI_RETENTION` | `redacted` | `redacted` (store redacted text) or `none` (hashes and metadata only) |
| `VO_AI_API_TIMEOUT_S`, `VO_AI_LLM_TIMEOUT_S`, `VO_AI_MAX_TOOL_ROUNDS` | `8`, `20`, `4` | limits |
| `VO_AI_PERMISSION_MATRIX` | repo `packages/contracts/authz/permission-matrix.v1.json` | matrix contract path (containers) |

To enable OpenAI later, see "Enabling OpenAI later" in [`docs/api/assistant.md`](../../docs/api/assistant.md).
