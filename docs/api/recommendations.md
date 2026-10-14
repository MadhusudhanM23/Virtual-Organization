# Recommendations and approvals (Phase 8, plan 8.1)

Requirements: REQ-090 (decision package), REQ-091 (no confidence %), REQ-092 (approval workflow), REQ-093 (dry-run),
REQ-094. Blueprint sections 9 and 16. Decisions: ADR-08, ADR-09, ADR-28, **ADR-61 (Proposed)**.

Code: `services/api/src/main/java/io/vorg/api/recommendations/` (`RecommendationService`, `RecommendationQueryService`,
`ApprovalService`, `web/RecommendationController`, `web/ApprovalController`). Storage: the `rec` schema
(`V8_1_01__recommendations_and_approvals.sql`): `rec.recommendations` and `rec.approvals`, forced RLS
(`tenant_isolation`), a `version` column on each, no DELETE grants.

Everything a recommendation says about the plan is derived from a simulation run, so every recommendation, approval
and event is `data_mode = SIMULATED`.

## Authorization

| Operation | Action (permission matrix) | Notes |
|---|---|---|
| Read recommendations, approvals, dry-run | `PROPOSE_RECOMMENDATION` on the site | every role that may approve may also propose (ADR-61) |
| `POST /api/recommendations` | `PROPOSE_RECOMMENDATION` on the site | command through the gateway |
| `POST /api/approvals/{id}/approve` and `/reject` | `APPROVE_CONSEQUENTIAL_ACTION` on the approval's site | command through the gateway, then the approval rules below |

An unknown site is `404`. Another tenant's recommendation or approval is `404` (RLS plus an explicit tenant filter). A
site outside the caller's role or scope is `403`. Denials are audited. Every command needs an `Idempotency-Key`
header. A repeat with the same key and payload returns the stored ack. The same key with a different payload is `409`.

## Recommendation shape (binding contract)

`GET /api/recommendations/{id}` returns exactly these fields, in this order. Plans 8.3 and 8.4 depend on them, so the
field names must not change. Every member is always present, and `supersedes_id` and `execution` are `null` when they
don't apply.

```json
{
  "recommendation_id": "<uuid>",
  "site_id": "<uuid>",
  "type": "DOCK_REASSIGNMENT",
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
  "expires_at": "...",
  "approval_id": "<uuid>",
  "proposed_by": "<uuid>",
  "created_at": "...",
  "updated_at": "...",
  "version": 1,
  "data_mode": "SIMULATED",
  "supersedes_id": null,
  "execution": null
}
```

The values in the example are placeholders that show types and units. They are not real data.

- There is **no confidence percentage** anywhere (REQ-091). Objectives are computed quantities with a unit. A draft
  whose step parameters carry a `confidence` key is rejected.
- `evidence_refs[0]` is always the `SIMULATION_RUN` that the proposal was generated from.
- `feasibility.optimality_gap` and `feasibility.solve_time_ms` are `null` when the solver doesn't report them.
- `policy_versions` records the versions in force at proposal time (see ADR-61). Plan 8.3's stale check compares it
  with the current versions.
- `execution` is reserved for Plan 8.3 and stays `null` until the plan runs.

### Lifecycle

`PROPOSED -> APPROVED | REJECTED | EXPIRED | STALE`, `APPROVED -> EXECUTING | EXPIRED | STALE`,
`EXECUTING -> VERIFIED | PARTIAL | FAILED`. Every other state is terminal. To move on from a terminal state, make a
new proposal, which records the old one in `supersedes_id`.

## Endpoints

| Endpoint | Notes |
|---|---|
| `POST /api/recommendations` | command; body `{site_id, simulation_run_id}`; creates the recommendation, its `PENDING` approval and the `RECOMMENDATION_PROPOSED` and `APPROVAL_REQUESTED` events in one transaction; result `{recommendation_id, approval_id, status: "PROPOSED", version, expires_at, supersedes_id?, event_id}` |
| `GET /api/recommendations?site_id=[&status=][&limit=&cursor=]` | `{items[<recommendation>], next_cursor}`, newest first (keyset paging like scenarios) |
| `GET /api/recommendations/{id}` | the recommendation shape above |
| `GET /api/recommendations/{id}/dry-run` | `{recommendation_id, computed_at, diff[{entity, field, before, after}], stale[{kind: ENTITY/POLICY, entity, baseline_version, current_version}]}`; changes nothing |
| `GET /api/approvals?site_id=[&status=][&limit=&cursor=]` | `{items[<approval>], next_cursor}`; `status` is `PENDING/APPROVED/REJECTED/EXPIRED` |
| `GET /api/approvals/{id}` | the approval shape below |
| `POST /api/approvals/{id}/approve` | command; optional body `{reason?}`; result `{outcome: "DECIDED", approval_id, recommendation_id, status: "APPROVED", recommendation_status: "APPROVED", version, recommendation_version, event_id}` |
| `POST /api/approvals/{id}/reject` | command; body `{reason}` (required, at most 500 characters); result as for approve, with `REJECTED` |

### Proposing

The server reads the simulation run through the scenarios module's query services and never writes to `sim`. It
returns:

- `400` when `site_id` or `simulation_run_id` is missing, or the run belongs to a different site.
- `404` when the run is unknown.
- `409 run_not_succeeded` when the run isn't `SUCCEEDED`. The problem includes `run_status`.
- `503 generator_unavailable` when no `RecommendationGenerator` bean is configured. Plan 8.3 supplies the OR-Tools
  implementation.
- `422 not_actionable` when the solver found no plan: the feasibility is neither `OPTIMAL` nor `FEASIBLE`, or the
  plan has no steps. The problem includes `feasibility_status`.
- `409 proposal_pending` when the run's latest proposal is still `PENDING` and hasn't expired. The problem includes
  `recommendation_id` and `approval_id`.

Proposals from one run are serialized. A later proposal from the same run sets `supersedes_id` to the previous one.

### Dry-run

The dry-run applies each step to the **current** values, which it reads through the `CurrentStateReader` port. Plan
8.3 implements that port. When no reader is configured, the dry-run returns `503 current_state_unavailable`.

When several steps change the same entity, each step sees the result of the one before it. Only command types with a
known effect can be dry-run: `REASSIGN_TRIP_DOCK` and `REASSIGN_FORKLIFT_DOCK` both set `dock_code` from `to_dock`.
Any other command type returns `422 dry_run_unsupported`.

`stale` lists:

- affected entities whose current version differs from `baseline_entity_versions`, or that no longer exist
  (`current_version: null`);
- policies whose current version differs from `policy_versions`.

### Approval shape

```json
{
  "approval_id": "<uuid>", "recommendation_id": "<uuid>", "site_id": "<uuid>",
  "status": "PENDING|APPROVED|REJECTED|EXPIRED",
  "approval_class": "STANDARD|SENSITIVE", "separation_of_duties": true,
  "policy": {"name": "dock-reassignment", "version": 1},
  "approver_roles": ["WAREHOUSE_MANAGER", "REGIONAL_MANAGER"],
  "requested_by": "<uuid>", "decided_by": null, "decided_at": null, "reason": null,
  "expires_at": "...", "created_at": "...", "updated_at": "...", "version": 1
}
```

### Approval rules (ADR-61)

The gateway first authorizes `APPROVE_CONSEQUENTIAL_ACTION` on the site. Then, under a row lock, the rules apply in
this order:

1. **State.** Only a `PENDING` approval can be decided. Any other status returns `409 invalid_state` with
   `approval_status`.
2. **Expiry.** At or after `expires_at`, the approval and its recommendation become `EXPIRED`, and
   `APPROVAL_EXPIRED` is recorded and committed. The caller gets `409 approval_expired`:

   ```json
   {"type": "about:blank", "title": "Approval expired", "status": 409, "code": "approval_expired",
    "approval_id": "<uuid>", "recommendation_id": "<uuid>",
    "re_evaluate": {"href": "/api/recommendations", "body": {"site_id": "<uuid>", "simulation_run_id": "<uuid>"}}}
   ```

   Posting `re_evaluate.body` to `re_evaluate.href` creates a fresh proposal from the same run, with `supersedes_id`
   set. A replay of the same command key returns the same `409`.
3. **Separation of duties.** When `separation_of_duties` is true (class `SENSITIVE`), the decider must not be the
   proposer. Otherwise the call returns `403 separation_of_duties`.
4. **Eligibility.** Each of the caller's role assignments on the site is evaluated on its own. An `ALLOW` cell
   qualifies as it is. An `ALLOW_SUBJECT_TO_POLICY` (R\*) cell qualifies only when the approval's policy lists that
   role in `approver_roles`. When no assignment qualifies, the call returns `403 approver_not_eligible`. This is
   where `Decision.requiresPolicyCheck` gets its meaning; the gateway itself is unchanged.

Approve and reject follow the same rules. Approving records `APPROVAL_GRANTED` and rejecting records
`APPROVAL_REJECTED`. Each also moves the recommendation to `APPROVED` or `REJECTED`. Nothing is ever deleted: every
change increments `version`.

## Typed problems

Problems are `application/problem+json` (problem.v1). A typed problem adds `code`. Extension members are snake_case.

| Status | `code` | Extension members |
|---|---|---|
| 503 | `generator_unavailable` | none |
| 503 | `current_state_unavailable` | none |
| 409 | `run_not_succeeded` | `run_status` |
| 422 | `not_actionable` | `feasibility_status` |
| 409 | `proposal_pending` | `recommendation_id`, `approval_id` |
| 422 | `dry_run_unsupported` | `command_type` |
| 409 | `invalid_state` | `approval_status` |
| 403 | `separation_of_duties` | none |
| 403 | `approver_not_eligible` | none |
| 409 | `approval_expired` | `approval_id`, `recommendation_id`, `re_evaluate {href, body {site_id, simulation_run_id}}` |

## Events

`RECOMMENDATION_PROPOSED`, `APPROVAL_REQUESTED`, `APPROVAL_GRANTED`, `APPROVAL_REJECTED` and `APPROVAL_EXPIRED`. Their
payloads are listed in [`packages/contracts/events/CATALOG.md`](../../packages/contracts/events/CATALOG.md). All of
them have `source = API`, `data_mode = SIMULATED`, actor `HUMAN` (the caller) and `location` = the site.

## Configuration

`vo.recommendations.policies.dock-reassignment.*` controls the `dock-reassignment` policy, which is version 1:

| Key | Default |
|---|---|
| `version` | `1` |
| `approval-class` | `SENSITIVE` |
| `approver-roles` | `WAREHOUSE_MANAGER, REGIONAL_MANAGER` |
| `expiry-minutes` | `30` |

Bump `version` whenever any other key changes. A version change is what marks an approved plan stale.
