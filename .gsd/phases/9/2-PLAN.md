# Plan 9.2 — Tool allowlist, identity passthrough, evidence model, action safety

Working plan (brief). Source: REQ-102, REQ-103, REQ-104, REQ-105, REQ-106; BLUEPRINT §13 "AI tool architecture" and
"AI security"; ADR-12. New: ADR-66 (offline provider and tool contract, Proposed). Wave B — after 9.1.

## Tasks

1. **Platform client** — `platform_client.py`: an httpx client bound to the caller's bearer token and correlation id.
   No other credential exists in the service. 401/403/404 become tool errors that the answer reports; they are never
   retried with other rights. `site_id` is injected by the gateway from the request, so the model can never choose a
   site or a tenant.
2. **Allowlist** — `tools.py`: a registry of named tools, each with a pydantic argument model (`extra=forbid`), the
   API path it wraps, the matrix action it needs and its kind (`read` or `propose`). Read tools: `get_entity`,
   `find_entities`, `list_zones`, `list_docks`, `list_incidents`, `get_incident`, `get_kpis`, `list_scenarios`,
   `list_simulation_runs`, `get_simulation_run`, `list_recommendations`, `get_recommendation`. Write tools (proposals
   only): `create_scenario` (`CREATE_SIMULATION`) and `propose_recommendation` (`PROPOSE_RECOMMENDATION`), both through
   the existing commands with an `Idempotency-Key`. A write tool is offered only when the caller's roles can use its
   action (matrix contract) **and** the user's own message asks for it. Unknown tools and invalid arguments are
   rejected. There is no approve, reject or execute tool; a request to do so is refused with a pointer to the page.
3. **Evidence model** — `evidence.py`: every tool result becomes citations `{id, kind, ref, label, href, data_mode}`
   and the answer cites them as `[c1]`. A validator checks that every number in the answer appears in cited
   evidence, that every cited id exists and that directive targets come from evidence. An ungrounded external answer
   is replaced by the offline answer and flagged.
4. **Injection guards** — tool output is data. It is never parsed for instructions; free text (notes, descriptions,
   names) is scanned for instruction-like content, which is flagged in the response. Write intent comes only from the
   user's message, never from tool output. For OpenAI, tool results travel as `tool` messages under a system rule that
   they are untrusted data.
5. **Offline provider** — intent rules over the user text (bottleneck/why, incidents, KPIs, scenarios/runs,
   recommendations, entity code, zone name, create scenario, propose, refused actions, help). Templates fill in only
   values read from tool results.
6. **Endpoint** — `POST /api/assistant/messages` `{site_id, message, conversation_id?}` returns
   `{exchange_id, conversation_id, answer, citations[], directives[], provider{name, model, external, reason},
   grounded, flags[], tool_calls[], audit{recorded}}`. Directives: `highlight {entity_ids, zone_ids, dock_ids}` and
   `focus_camera {target{x,y,z}, floor_id}`. Also `GET /api/assistant/health`.
7. **Tests** — pytest with a fake platform API (MockTransport): identity passthrough (the token sent is the
   caller's), unknown tool rejected, proposal gated on role and intent, approve/execute refused.

Commit: `feat(phase-9): tool allowlist, identity passthrough, evidence model and offline assistant`.
