# Plan 8.1 — Recommendation & approval domain

Working plan (brief). Source: REQ-090, REQ-092 (contract half), REQ-093, REQ-094; BLUEPRINT §9, §16; ADR-08/09/28.
New: ADR-61 (Proposed). Wave A — runs in parallel with Plan 8.2 (disjoint: 8.2 owns `services/optimization/`).

## Contract (binding for 8.3 and 8.4 — do not rename fields)

`GET /api/recommendations/{id}` returns exactly the §9 fields plus bookkeeping:

```json
{
  "recommendation_id": "<uuid>", "site_id": "<uuid>", "type": "DOCK_REASSIGNMENT",
  "status": "PROPOSED|APPROVED|REJECTED|EXPIRED|STALE|EXECUTING|VERIFIED|PARTIAL|FAILED",
  "rationale": "...",
  "evidence_refs": [{"kind": "SIMULATION_RUN|EVENT|INCIDENT|METRIC|CAMERA", "ref": "<id>", "label": "..."}],
  "feasibility": {"status": "OPTIMAL|FEASIBLE|INFEASIBLE|MODEL_INVALID|UNKNOWN", "solver": "...",
                  "optimality_gap": 0.0, "solve_time_ms": 0},
  "objective_before": {"name": "...", "unit": "min", "value": 0.0},
  "objective_after":  {"name": "...", "unit": "min", "value": 0.0},
  "constraints": [{"name": "...", "kind": "CAPACITY|SLA|POLICY|RULE", "satisfied": true, "detail": "..."}],
  "affected_entities": ["IN-02", "TRIP-00003"],
  "baseline_entity_versions": {"IN-02": 1, "TRIP-00003": 4},
  "policy_versions": {"permission-matrix": 1, "dock-reassignment": 1},
  "execution_plan": {"steps": [{"seq": 1, "command_type": "...", "target": "...", "params": {},
                                "compensation": {"command_type": "...", "params": {}}}]},
  "requires_approval": {"required": true, "approval_class": "SENSITIVE", "separation_of_duties": true},
  "expires_at": "...", "approval_id": "<uuid>", "proposed_by": "<uuid>", "created_at": "...",
  "updated_at": "...", "version": 1, "data_mode": "SIMULATED", "supersedes_id": null,
  "execution": null
}
```

Never a "confidence %" anywhere (REQ-091). `execution` is reserved for 8.3 (`null` until executed).

## Tasks

1. **Schema + domain** — migration `V8_1_01__recommendations_and_approvals.sql` in the reserved `rec` schema:
   `rec.recommendations`, `rec.approvals` (`PENDING|APPROVED|REJECTED|EXPIRED`, `decided_by`, `decided_at`,
   `reason`, `expires_at`), forced RLS `tenant_isolation` like `V4_1_01`, `version` columns, no DELETE grants.
   Package `io.vorg.api.recommendations` (`RecommendationService`, `RecommendationQueryService`,
   `ApprovalService`). Policy versions: expose the matrix `"version"` from `permission-matrix.v1.json` through
   `PermissionMatrix` (it is parsed but not exposed today) plus a `dock-reassignment` policy (config record: approval
   class `SENSITIVE`, approver roles, expiry minutes; version 1). A `PolicyVersions` bean returns the current map;
   8.3's stale check compares against it.
2. **Propose + read APIs** — a `RecommendationGenerator` port (interface) that turns a SUCCEEDED simulation run into
   a `RecommendationDraft`; 8.3 supplies the OR-Tools-backed implementation. Ship a test-only fake here; with no
   implementation bean, `POST /api/recommendations` returns 503 problem `generator_unavailable`.
   `POST /api/recommendations` (command, `PROPOSE_RECOMMENDATION` on the site, body `{site_id, simulation_run_id}`)
   reads the run through `ScenarioAccess`-equivalent checks; it never writes to `sim`. It creates the recommendation
   plus a PENDING approval in one transaction. `GET /api/recommendations?site_id=&status=` (keyset paging like
   scenarios) and `GET /api/recommendations/{id}`. `GET /api/recommendations/{id}/dry-run` (REQ-093) returns
   `{recommendation_id, computed_at, diff: [{entity, field, before, after}], stale: [...]}`: the plan applied to the
   *current* values, which are read through a `CurrentStateReader` port that 8.3 implements. The test fake lives here.
3. **Approvals** — `GET /api/approvals?site_id=&status=`, `GET /api/approvals/{id}`,
   `POST /api/approvals/{id}/approve` and `POST /api/approvals/{id}/reject` (body `{reason}` is required for reject).
   Both are commands with `APPROVE_CONSEQUENTIAL_ACTION`. Rules:
   - scope check on the site;
   - SoD: if `separation_of_duties`, approver ≠ proposer, otherwise 403 `separation_of_duties`;
   - `ALLOW_SUBJECT_TO_POLICY` cells resolve through the dock-reassignment policy's approver roles; give
     `Decision.requiresPolicyCheck` its real meaning here and keep the gateway unchanged;
   - expiry: deciding after `expires_at` marks the approval and recommendation EXPIRED and returns 409
     `approval_expired` with `re_evaluate: {href: "/api/recommendations", body: {site_id, simulation_run_id}}`. A new
     proposal from the same run sets `supersedes_id`;
   - only PENDING can be decided, otherwise 409 `invalid_state`.

   Events (add to `packages/contracts/events/CATALOG.md` first): `RECOMMENDATION_PROPOSED`, `APPROVAL_REQUESTED`,
   `APPROVAL_GRANTED`, `APPROVAL_REJECTED`, `APPROVAL_EXPIRED`. Use `DataMode.SIMULATED`; the actor is the human
   subject.
4. **Tests + docs** —
   - Unit tests: state machine, SoD, expiry, JSON shape.
   - Integration tests with the fake generator: propose → get → approve by a second eligible user → APPROVED;
     same-user approve → 403; reject; expired → 409; tenant B cannot see it (404); a DRIVER cannot propose (403);
     idempotent replay of approve.
   - ArchUnit: `recommendations` never depends on `scenarios` write services.
   - Docs: `docs/api/README.md` gets an "Implemented: recommendations & approvals (Phase 8, plan 8.1)" section, a new
     `docs/api/recommendations.md`, and **ADR-61 (Proposed)**: approval classes, SoD, expiry/re-evaluation, and the
     meaning of policy versions.

One commit per task, `feat(phase-8): <task>`, each preceded by a green
`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify`.
