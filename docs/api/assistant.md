# AI operations assistant (Phase 9)

Requirements: REQ-100..108, NFR-07. Blueprint sections 13, 15 and 18. Decisions: ADR-12, ADR-17, **ADR-64** (gateway
placement), **ADR-65** (policy, redaction, exchange record) and **ADR-66** (tools, evidence, offline provider), all
three Proposed.

The assistant is a grounded, read-mostly layer. It reads the platform **as the signed-in user** (their bearer token is
forwarded unchanged; there is no service account), cites every value it uses, and points the twin at what it found. It
can **never approve, reject or execute** anything. At most it creates a simulation scenario or proposes a
recommendation through the existing commands, and only when the user asks and has the permission. A person still
decides on the Recommendations page.

| Part | Where | Port |
|---|---|---|
| Gateway (FastAPI, Python 3.12) | `services/ai` (`vorg_ai`) | 8090 |
| Exchange records (Java `ai` module) | `services/api` (`io.vorg.api.ai`), table `ai.exchanges` | 8080 |
| Assistant panel | `apps/web/src/features/assistant` | 5173 (proxies `/api/assistant` to 8090) |

## `POST /api/assistant/messages` (services/ai)

Headers: `Authorization: Bearer <access token>` (required), `X-Correlation-Id` (optional, echoed). Body
(`extra` fields are rejected with `422`, which includes a `tenant_id`, because the tenant always comes from the token):

```json
{ "site_id": "<uuid>", "message": "Why does the bottleneck matter?", "conversation_id": "<uuid, optional>" }
```

`200` response:

```json
{
  "exchange_id": "<uuid>",
  "conversation_id": "<uuid>",
  "answer": "Bottleneck: dock IN-02 closed from minute 60 to minute 240 in scenario \"...\" [c5].\n...",
  "citations": [
    { "id": "c5", "kind": "SCENARIO", "ref": "<scenario id>", "label": "...", "href": "/api/scenarios/<id>",
      "tool": "get_scenario", "data_mode": "SIMULATED", "values": { "dock_closures": [ ... ] } }
  ],
  "directives": [
    { "type": "highlight", "entity_ids": [], "zone_ids": ["<zone id>"], "dock_ids": ["<dock id>"],
      "floor_id": "<floor id>", "citation_ids": ["c6", "c7"] },
    { "type": "focus_camera", "target": { "x": 0.0, "y": 0.0, "z": 0.0 }, "floor_id": "<floor id>",
      "citation_id": "c6" }
  ],
  "provider": { "name": "offline", "model": "offline-rules-v1", "external": false,
                "reason": "tenant_external_disallowed", "region": null },
  "grounded": true,
  "data_modes": ["SIMULATED", "SYNTHETIC"],
  "flags": [],
  "tool_calls": [ { "tool": "list_simulation_runs", "status": "ok", "citation_ids": ["c1"] } ],
  "usage": { "prompt_tokens": 6, "completion_tokens": 160, "method": "regex_word_v1" },
  "audit": { "recorded": true },
  "latency_ms": 42
}
```

The values above show the shape only; they are not real data.

- `answer` cites evidence as `[cN]`, and `citations[]` contains exactly the cited items. Every number in the answer is
  a value of a cited item (or a number from the user's own question). `grounded` reports that check.
- `directives` drive the twin: `highlight` (entities, zones and docks, with their floor) and `focus_camera` (a
  site-local point in meters, +x east, +y north, +z up). Targets always belong to cited evidence.
- `provider.reason`: `no_api_key`, `tenant_external_disallowed`, `offline_mode_configured`, `tenant_external_allowed`
  (OpenAI used), `external_answer_ungrounded` and `external_provider_failed` (OpenAI answered but offline replaced
  it), and `refusal_is_deterministic`.
- `flags[].code`: `untrusted_instruction_in_tool_output` (retrieved text that looks like an instruction; treated as
  data), `ungrounded_numbers`, `ungrounded_external_answer_replaced`, `external_provider_failed` and
  `directive_targets_dropped`.
- `usage.method`: `provider` (vendor token counts) or `regex_word_v1` (local count of words and punctuation; the
  offline provider has no model).
- Errors (problem+json): `401` (missing token, or the platform rejected it), `422` (invalid body), `502`
  `platform_error`, and `503` `platform_unavailable`.

`GET /api/assistant/health` returns `{status, provider_mode, providers {offline, openai: configured|not_configured},
openai_model, retention}`. It never returns the key.

## Tools (allowlist)

Every tool runs with the caller's token. The site is bound from the request and never chosen by a model. A tool is
offered only when the caller's roles can use its action (permission-matrix contract). The API still authorizes every
call: `403` and `404` are reported in the answer and never retried with other rights.

| Tool | Wraps | Action |
|---|---|---|
| `get_entity` | `GET /api/entities/{id}` | `VIEW_OPERATIONAL_STATE` |
| `find_entities` (`code`, `type`, `zone_id`) | `GET /api/twin/entities` | `VIEW_OPERATIONAL_STATE` |
| `list_zones`, `list_docks` | `GET /api/twin/zones`, `GET /api/twin/docks` | `VIEW_OPERATIONAL_STATE` |
| `list_incidents` (default: open), `get_incident` | `GET /api/incidents[/{id}]` | `VIEW_OPERATIONAL_STATE` |
| `get_kpis` (`window`) | `GET /api/kpis` | `VIEW_OPERATIONAL_STATE` |
| `list_scenarios`, `get_scenario` | `GET /api/scenarios[/{id}]` | `CREATE_SIMULATION` |
| `list_simulation_runs`, `get_simulation_run` | `GET /api/simulation-runs[/{id}]` | `CREATE_SIMULATION` |
| `list_recommendations`, `get_recommendation` | `GET /api/recommendations[/{id}]` | `PROPOSE_RECOMMENDATION` |
| `create_scenario` (`dock_code`, `start_min`, `end_min`) | `POST /api/scenarios` (command) | `CREATE_SIMULATION` + explicit request |
| `propose_recommendation` (`simulation_run_id`) | `POST /api/recommendations` (command) | `PROPOSE_RECOMMENDATION` + explicit request |

There is no approve, reject, execute, incident-handling or e-stop tool. Such a request is refused before any provider
runs.

## Exchange records (services/api)

| Endpoint | Notes |
|---|---|
| `POST /api/ai/exchanges` | command (`Idempotency-Key` = `exchange_id`), `VIEW_OPERATIONAL_STATE` on `site_id`; body `{exchange_id, site_id, conversation_id, provider, model, external, provider_reason, retention, question_sha256, question_text?, answer_text?, prompt_tokens, completion_tokens, token_count_method, tool_calls[], citations[], redactions{}, flags[], grounded, latency_ms}`; the actor is the token subject; `400 secret_detected` when the body still looks like it contains a credential; result `{id, exchange_id, conversation_id, site_id, provider}` |
| `GET /api/ai/exchanges?site_id=[&limit=&cursor=]` | the caller's own records, newest first, `{items, next_cursor}` |

`GET /api/me` now includes `tenant.llm_external_allowed` (the tenant LLM policy flag the gateway reads).

## Enabling OpenAI later

1. Put the key in your local `.env` only (it is gitignored): `OPENAI_API_KEY=...`. Optionally set
   `VO_AI_OPENAI_MODEL` (default `gpt-4o-mini`) and `VO_AI_PROVIDER_REGION`. Keep `VO_AI_PROVIDER=auto`.
2. Allow external transmission for the tenant: `update iam.tenants set llm_external_allowed = true where slug = '...'`
   (as the owner role). The demo tenant ships with `false`, so without this step it stays offline even with a key.
3. Restart `services/ai`. `GET /api/assistant/health` shows `openai: configured`, and answers for that tenant show
   `provider.name = "openai"`. Any ungrounded answer or provider error falls back to offline.
4. Optional: run the eval suite against OpenAI (`tests/ai-eval`, `--provider openai`).
