# Recommendations, approvals and execution (Phase 8, plans 8.1 and 8.3)

Requirements: REQ-090 (decision package), REQ-091 (no confidence %), REQ-092 (approval workflow), REQ-093 (dry-run),
REQ-094, REQ-095 (execution), REQ-096 (verification). Blueprint sections 9, 16 and 22. Decisions: ADR-08, ADR-09,
ADR-28, **ADR-61 (Proposed)**, ADR-62 (optimizer), **ADR-63 (Proposed)** (execution, stale check, verification).

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
- `execution` is `null` until the plan is executed (Plan 8.3); its shape is under "Execution" below.

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
- `503 generator_unavailable` when no `RecommendationGenerator` bean is configured. Plan 8.3 registers the OR-Tools
  implementation (see "Generation" below), so a normal server always has one.
- `422 not_actionable` when the solver found no plan: the feasibility is neither `OPTIMAL` nor `FEASIBLE`, or the
  plan has no steps. The problem includes `feasibility_status`.
- `409 proposal_pending` when the run's latest proposal is still `PENDING` and hasn't expired. The problem includes
  `recommendation_id` and `approval_id`.

Proposals from one run are serialized. A later proposal from the same run sets `supersedes_id` to the previous one.

### Dry-run

The dry-run applies each step to the **current** values, which it reads through the `CurrentStateReader` port. Plan
8.3 implements that port over the dock and forklift assignment projections and `world.docks` (`AssignmentStateReader`). When no reader is configured, the dry-run returns `503 current_state_unavailable`.

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
| 409 | `approval_expired` | `approval_id`, `recommendation_id`, `re_evaluate {href, body {site_id, simulation_run_id}}` (from execute: `approval_id`, `recommendation_id` only) |
| 422 | `optimization_input_missing` | `missing` (e.g. `scenario.run.horizon_min`, `sim_tms.trip_plan`, `ops.dock_assignments`) |
| 422 | `optimization_input_invalid` | `errors [{field, message}]` (every optimizer validation error, with JSON paths) |
| 503 | `optimizer_busy` | none |
| 503 | `optimizer_unavailable` | none |
| 409 | `not_approved` | `recommendation_status`, `approval_status` |
| 409 | `stale_baseline` | `recommendation_id`, `stale [...]`, `resimulate {scenario_id, href}` |
| 422 | `live_source_refused` | `command_type`, `source_system?` |

## Events

`RECOMMENDATION_PROPOSED`, `APPROVAL_REQUESTED`, `APPROVAL_GRANTED`, `APPROVAL_REJECTED` and `APPROVAL_EXPIRED`
(plan 8.1); `RECOMMENDATION_STALE`, `RECOMMENDATION_EXECUTION_STARTED`, `RECOMMENDATION_STEP_APPLIED`,
`RECOMMENDATION_STEP_COMPENSATED`, `RECOMMENDATION_EXECUTION_FAILED`, `RECOMMENDATION_VERIFIED`,
`DOCK_ASSIGNMENT_CHANGED` and `FORKLIFT_ASSIGNMENT_CHANGED` (plan 8.3). Their payloads are listed in
[`packages/contracts/events/CATALOG.md`](../../packages/contracts/events/CATALOG.md). The command events have
`source = API`, `data_mode = SIMULATED`, actor `HUMAN` (the caller) and `location` = the site. The verifier's events
have `source = SYSTEM` and actor `SERVICE svc:recommendation-verifier`. The assignment events come from the mirror
(`source = SIMULATION`, actor `SERVICE svc:adapter:<source_system>`).

## Configuration

`vo.recommendations.policies.dock-reassignment.*` controls the `dock-reassignment` policy, which is version 1:

| Key | Default |
|---|---|
| `version` | `1` |
| `approval-class` | `SENSITIVE` |
| `approver-roles` | `WAREHOUSE_MANAGER, REGIONAL_MANAGER` |
| `expiry-minutes` | `30` |

Bump `version` whenever any other key changes. A version change is what marks an approved plan stale.

## Generation (plan 8.3)

`OrToolsRecommendationGenerator` builds a `vorg-optimization` dock re-assignment problem (ADR-62) and maps the result
onto the recommendation shape (ADR-63). Every number comes from observed state, the scenario or required
configuration:

| Problem input | Source |
|---|---|
| docks | `world.docks` of the site (name, `dock_type`) |
| closures | the scenario overlay's `dock_closures` (`dock_id` = dock name or id, `start_min`, `end_min`, `reason`), plus `[0, horizon)` for a dock the twin shows `CLOSED` |
| `horizon_min` | the scenario's `run.horizon_min` |
| trips | SIM-TMS trips with an observed dock assignment (`ops.dock_assignments`); `current_dock` = the observed dock |
| `arrival_min`, `service_min` | the trip's next visit in the SIM-TMS trip plan (batches until at the dock, batches at the dock) x `minutes-per-batch` (ASSUMPTION); visits ending beyond the horizon are left out and named in the rationale |
| forklifts | observed forklift stations (`ops.forklift_assignments`) |
| params | `vo.recommendations.generator.*` (required) |

The solver runs on a bounded executor (`solver-threads`, `solver-queue`; more is `503 optimizer_busy`). The draft:

- `evidence_refs`: the simulation run, one `METRIC` per KPI delta of the run's comparison (`ref` `<run_id>#deltas/<key>`,
  label with the delta mean and its 95% CI), and the optimizer result (`ref` `optimization:<result_hash>`).
- `affected_entities`: the moved trips, the moved forklifts, then every dock a move leaves or enters.
- `baseline_entity_versions`: read in the propose transaction (assignment versions, `world.docks.version`).
- `execution_plan.steps`: one per move, in order: `REASSIGN_TRIP_DOCK {trip_code, from_dock, to_dock,
  planned_start_min}` or `REASSIGN_FORKLIFT_DOCK {forklift_code, from_dock, to_dock}`, each with the compensation
  `{trip_code|forklift_code, to_dock: <from_dock>}`.
- `rationale`: a template filled with measured and computed values only (closures, moves, objective before/after, gap,
  the trip-plan batch and the minutes-per-batch assumption).

Problems: `422 optimization_input_missing`, `422 optimization_input_invalid`, `422 not_actionable` (no plan, or a plan
without any move), `503 optimizer_busy`, `503 optimizer_unavailable`.

## Execution (plan 8.3)

`POST /api/recommendations/{id}/execute`: a command (`Idempotency-Key`, no body), authorized as
`EXECUTE_STANDARD_COMMAND` on the recommendation's site. It counts as part of an approved workflow
(`PolicyContext.approvedWorkflow()`) only when the recommendation is `APPROVED` with an `APPROVED` approval. A repeat
with the same key returns the stored ack (or the same `409`), and nothing is applied twice.

Under a row lock, in the command transaction:

1. **State.** Not `APPROVED` (or the approval not `APPROVED`): `409 not_approved`. At or after `expires_at`:
   `409 approval_expired`.
2. **Stale check** on the affected-entity versions and the policy versions only (ADR-63). Trips are versioned by
   their dock assignment, forklifts by their station, docks by `world.docks.version`; truck or forklift movement never
   changes these. On any mismatch the recommendation becomes `STALE`, `RECOMMENDATION_STALE` is committed and the call
   returns:

   ```json
   {"type": "about:blank", "title": "Stale baseline", "status": 409, "code": "stale_baseline", "detail": "...",
    "recommendation_id": "<uuid>",
    "stale": [{"kind": "ENTITY", "entity": "TRIP-00002", "baseline_version": 1, "current_version": 2},
              {"kind": "POLICY", "entity": "dock-reassignment", "baseline_version": 1, "current_version": 2}],
    "resimulate": {"scenario_id": "<uuid>", "href": "/api/scenarios/<uuid>/runs"}}
   ```

   The values are placeholders. `current_version` is `null` for an entity that no longer exists. The recommendation's
   new status (`STALE`) is visible through `GET /api/recommendations/{id}`.
3. **Source check.** Every step's command must go to a `SIMULATED` source (`AdapterCommandPort`); otherwise
   `422 live_source_refused` before anything is applied.
4. **Apply.** The recommendation becomes `EXECUTING` (`RECOMMENDATION_EXECUTION_STARTED`) and the steps are applied in
   order (`RECOMMENDATION_STEP_APPLIED` each). When a source refuses a step, the applied steps are compensated in
   reverse (`RECOMMENDATION_STEP_COMPENSATED` each) and the recommendation becomes `FAILED` (every compensation
   succeeded) or `PARTIAL` (one did not), with `RECOMMENDATION_EXECUTION_FAILED` and the failure as evidence.

Result (`command-ack.v1.result`):

```json
{"outcome": "EXECUTING|FAILED|PARTIAL", "recommendation_id": "<uuid>", "status": "EXECUTING|FAILED|PARTIAL",
 "version": 4, "execution": {"...": "see below"}, "event_id": "<uuid of RECOMMENDATION_EXECUTION_STARTED>"}
```

### `execution` on the recommendation

```json
{"command_id": "<uuid>", "started_at": "...", "finished_at": null,
 "steps": [{"seq": 1, "command_type": "REASSIGN_TRIP_DOCK", "target": "TRIP-00002",
            "status": "APPLIED|COMPENSATED|FAILED|VERIFIED|NOT_APPLIED", "observed_event_id": null}],
 "evidence": null}
```

`finished_at` is set when the execution reaches `VERIFIED`, `FAILED` or `PARTIAL`. `NOT_APPLIED` marks a step after a
failed one. `evidence` is `null` while executing and on `VERIFIED`; otherwise it is
`{reason: STEP_FAILED, failed_step {seq, target, source_system, code, message}, compensation_failures [...]}` or
`{reason: VERIFICATION_TIMEOUT, timeout_batches, batches_observed, observed [{seq, target, expected_dock,
observed_dock}]}`.

### Verification (REQ-096)

After every integration sync of the site, the verifier compares each applied step's target with what the source
systems now report through the mirror (the dock and forklift assignment projections). A step becomes `VERIFIED` only
when its target is **observed** at the step's `to_dock`; `observed_event_id` is the `DOCK_ASSIGNMENT_CHANGED` /
`FORKLIFT_ASSIGNMENT_CHANGED` event that recorded the change. When every step is verified the recommendation becomes
`VERIFIED` (`RECOMMENDATION_VERIFIED`). After `vo.recommendations.verification.timeout-batches` SIM-TMS batches with a
step still unobserved, it becomes `FAILED` (nothing observed) or `PARTIAL` (some steps observed), with the observed
state as evidence (`RECOMMENDATION_EXECUTION_FAILED`).

### Simulated source commands

| Command | Source | Effect | Compensation |
|---|---|---|---|
| `REASSIGN_TRIP_DOCK {trip_code, to_dock}` | SIM-TMS | stores an override in `integ.sim_tms_overrides`; from the next batch on, the trip's `SHIPMENT` record carries the new `dock_name` with a greater source version | the same command with the previous dock |
| `REASSIGN_FORKLIFT_DOCK {forklift_code, to_dock}` | SIM-WMS | stores an override in `integ.sim_wms_overrides`; SIM-WMS then reports a `FORKLIFT_STATION` record | the same command with the previous dock |

The source refuses an unknown trip, forklift or dock, a dock of the wrong direction, and a forklift that has no
observed station (there is nothing to compensate to).

### Demo control: external TMS change

`POST /api/demo/integration/tms-change` (command, `Idempotency-Key`; demo tenants only, `403` elsewhere;
`VIEW_OPERATIONAL_STATE` on the site, or a Platform Administrator), body `{site_id, trip_code, to_dock}`: the
simulated "external" TMS moves the trip by itself. After the next sync the trip's assignment version is bumped, so an
approved recommendation that touches the trip is stale on execute. Result: `{demo_control: true, source_system:
"SIM-TMS", trip_code, previous_dock, to_dock, override_version, data_mode: "SIMULATED"}`. A refused change (unknown
trip or dock, wrong direction) is `422` with the source's `code`.

### Configuration (plan 8.3)

All required; there are no defaults in code (application.yml carries these values).

| Key | Value | Note |
|---|---|---|
| `vo.recommendations.generator.minutes-per-batch` | `30` | ASSUMPTION: plan minutes per simulated adapter batch (ADR-63) |
| `vo.recommendations.generator.reassignment-penalty-min` | `15` | ASSUMPTION: no cost data for a dock change exists |
| `vo.recommendations.generator.max-solve-ms` | `10000` | per CP-SAT solve; the call waits up to 3 x this + 10 s |
| `vo.recommendations.generator.random-seed` | `20261012` | CP-SAT seed |
| `vo.recommendations.generator.solver-threads` | `2` | bounded solver executor |
| `vo.recommendations.generator.solver-queue` | `4` | pending solves beyond the running ones |
| `vo.recommendations.verification.timeout-batches` | `2` | SIM-TMS batches (15 s each in the live loop, so 30 s) |
