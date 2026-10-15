# Scenario and simulation-run API (Plan 6.3)

REQ-070..074, NFR-06; decisions ADR-47/48/49 (engine), ADR-60 (baseline choice, isolation). The engine itself —
determinism, warehouse flow model, scenario/overlay JSON shapes — is documented in
[`services/simulation/README.md`](../../services/simulation/README.md) and demonstrated end to end in
[`docs/scenarios/dock-closure.md`](../scenarios/dock-closure.md); this page covers only what the HTTP API adds on top.

## Isolation (REQ-074)

A scenario and its runs live in the `sim` Postgres schema. The application reaches that schema through its own
connection, authenticated as `vo_sim` — a role with `NOBYPASSRLS` and, since V1_2_01, default privileges on `sim`
tables **only**. It has no grant at all on `world`, `ops`, `evt` or `cmd`. Every class in `io.vorg.api.scenarios`
uses that connection exclusively (`SimConfig`); an ArchUnit rule (`ModuleBoundariesTest`) fails the build if the
package ever depends on a live-write module or `DomainEvents`. The result: a bug in this module cannot write to live
tenant state, independent of any application-level check — Postgres itself refuses the statement. This is checked
directly in `ScenarioApiIT.simulationRunTouchesNoLiveTable`: it runs a full scenario, asserts the row counts of
`ops.tasks`, `world.entities`, `evt.events` and `ops.task_issues` are unchanged, and asserts
`has_table_privilege('vo_sim', 'world.sites', 'SELECT')` is false.

A run executes on a small bounded executor (`vo.sim.executor.pool-size`, default 2) so it never blocks the request
thread; NFR-06 allows up to 60 s per run (the warehouse-flow model's default 10 replications over an 8-hour shift take
well under a second).

## Scenarios

`POST /api/scenarios` (command, `Idempotency-Key` required):

```json
{
  "site_id": "<uuid>",
  "name": "Close inbound dock IN-02 for three hours",
  "description": "optional",
  "run": { "master_seed": 20261012, "replications": 10, "horizon_min": 480, "warmup_min": 30 },
  "overlay": { "dock_closures": [ { "dock_id": "IN-02", "start_min": 60, "end_min": 240, "reason": "leveller repair" } ] }
}
```

- `site_id` and `name` are required; `description`, `run` and `overlay` are optional.
- The baseline is always the built-in `bengaluru-warehouse-01` model (ADR-60) — there is no `baseline`/`model` field
  to choose; Plan 6.3 has exactly one demo site.
- `run` accepts the engine's run-settings fields (`services/simulation` README); `master_seed` may be omitted, in
  which case one is derived deterministically from the server-assigned scenario id (REQ-072 requires a stored seed,
  not that the caller supply it — the same scenario always re-derives the same default seed).
- `overlay` accepts the engine's overlay fields verbatim (`dock_closures`, `forklift_outages`, `worker_reductions`,
  `demand_multiplier`, `route_blockages`, `parameter_overrides`); an unknown field, dock, forklift, role or zone is
  rejected (see Errors below). Instead of `overlay` a request may carry a typed `signature` (next section).
- The command result, and `GET /api/scenarios/{id}`, return:

```json
{
  "id": "<uuid>", "site_id": "<uuid>", "name": "...", "description": "...",
  "baseline_ref": "bengaluru-warehouse-01",
  "overlay": { "...normalized, as the engine stored it..." },
  "run_settings": { "master_seed": 20261012, "replications": 10, "horizon_min": 480, "warmup_min": 30, "trajectory_sample_min": 15 },
  "affected_entities": ["IN-02"],
  "created_by": "<actor_ref>", "created_at": "...", "updated_at": "...", "version": 1
}
```

`GET /api/scenarios?site_id=[&limit=&cursor=]` lists scenarios of a site, newest first.

## Signature scenarios (Phase 13, REQ-140, ADR-79)

Instead of an `overlay`, `POST /api/scenarios` accepts `signature: {type, params}` (exclusive with `overlay`; both is a
`400` on field `signature`). The API validates the parameters and expands them to the ordinary overlay, which is what is
stored and returned, so a signature scenario is run, compared and listed exactly like any other.

```json
{ "site_id": "<uuid>", "name": "Block storage A", "run": { "master_seed": 20261012, "replications": 10 },
  "signature": { "type": "route_blockage",
                 "params": { "zone": "STORAGE_A", "detour_factor": 4, "start_min": 120, "end_min": 300 } } }
```

| `type` | `params` (all also take `start_min`, `end_min`) | Expands to |
|---|---|---|
| `demand_surge` | `factor` in (1, 10] | `demand_multiplier` |
| `forklift_outage` | `count` 1..10 (the highest-numbered forklifts) | `forklift_outages` x count |
| `route_blockage` | `zone` (`STORAGE_A`, `STORAGE_B`), `detour_factor` in (1, 10] (required) | `route_blockages` |
| `shift_shortage` | `role` (`RECEIVING`, `PICKING`, `PACKING`, `DISPATCH`), `count` 1..headcount | `worker_reductions` |

A bad parameter is `400` with the field path `signature.params.<name>` (`signature.type` for an unknown type); a window
outside the run horizon is reported on the expanded overlay path, for example `overlay.route_blockages[0].start_min`.
`overlay.route_blockages` items are `{zone, start_min, end_min, detour_factor, reason?}`.

`GET /api/scenario-types` (any authenticated user; static) returns `{items: [{type, label, description, model_effect,
params[{name, kind, unit, description, fixture_value}], fixture{master_seed, replications, horizon_min, warmup_min,
params}, provenance}]}`. Fixtures and provenance: [docs/scenarios/library.md](../scenarios/library.md).

## Simulation runs

`POST /api/scenarios/{id}/runs` (command, `Idempotency-Key` required, no body) inserts a `QUEUED` run and submits it
to the executor; the command result is `{run_id, scenario_id, status: "QUEUED"}` (`200`, not `202` — the command
itself, creating the run row, completes synchronously; the simulation keeps going in the background). Poll:

`GET /api/simulation-runs/{id}`:

```json
{
  "run_id": "<uuid>", "scenario_id": "<uuid>", "site_id": "<uuid>", "status": "QUEUED|RUNNING|SUCCEEDED|FAILED",
  "requested_by": "<actor_ref>",
  "engine": { "name": "vorg-des", "version": "1.0.0" },
  "results": { "...vorg.simulation.comparison.v1, verbatim..." },
  "results_hash": "<sha-256 hex, 64 chars>",
  "error_message": "...",
  "created_at": "...", "started_at": "...", "finished_at": "...", "version": 1
}
```

`engine`/`results`/`results_hash` appear once the run has started/succeeded; `error_message` appears only on `FAILED`.
`results` is the engine's `vorg.simulation.comparison.v1` document unmodified — `data_mode: "SIMULATED"`,
`lens: "SIMULATE"`, `source: "SIMULATION"`, the baseline-vs-scenario KPI deltas with 95% CIs and `verdict`
(`worse`/`better`/`increased`/`decreased`/`no_significant_change`), and `affected_entities` for SIMULATE-lens
highlighting (REQ-075, Plan 6.4).

A succeeded run's `results` also carries `distributions` (ADR-80): the Monte Carlo report over the run's replications,

```json
{ "schema": "vorg.simulation.distributions.v1", "data_mode": "SIMULATED", "lens": "SIMULATE", "source": "SIMULATION",
  "seed_scheme": "sha256-name-v1", "quantile_method": "linear_interpolation_type7", "n_replications": 10,
  "replication_seeds": [ "..." ],
  "kpis": [ { "key": "forklift_wait_mean_min", "label": "...", "unit": "min", "better": "lower",
              "baseline": { "n": 10, "mean": 2.53, "median": 1.65, "p5": 0.61, "p95": 6.79, "min": 0.19, "max": 9.39,
                            "sd": 2.59, "ci95_low": 0.68, "ci95_high": 4.38 },
              "scenario": { "...": "same fields" }, "delta": { "...": "paired scenario - baseline, same fields" } } ],
  "distributions_hash": "<sha-256>" }
```

Same seeds give the same bytes. A KPI undefined in some replications is computed over the defined ones and `n` says how
many; with fewer than 2 values the spread fields are `null`. `results_hash` is still the hash of the comparison alone;
`distributions` is outside it and has its own `distributions_hash`. Runs stored before Phase 13 have no `distributions`.

`GET /api/simulation-runs?scenario_id=|site_id=[&limit=&cursor=]`: `scenario_id` is authorized through the scenario
(and implies its site); otherwise `site_id` is required.

A run never produces a partial result: `FAILED` sets `error_message` and leaves `results`/`results_hash` unset;
`SUCCEEDED` is the reverse (enforced by a database `CHECK` constraint, not just application code).

## Errors

- A scenario field the engine's strict parser rejects (unknown field, unknown dock/forklift id/zone, unknown role, bad
  window, bad signature parameter, missing baseline) is `400 application/problem+json` with `errors: [{"field": "<dotted path>", "message": "..."}]`
  — the field is exactly the engine's own path (e.g. `overlay.dock_closures[0].dock_id`), never a generic message.
- An unknown `site_id` is `404`. A known site outside the caller's role scope is `403`. A scenario or run of another
  tenant, or an unknown id, is `404` (RLS hides the row; there is no way to distinguish "not found" from "not yours").
- A Driver/Operator or Platform Administrator may not create or view scenarios/runs at all (`403`) — the permission
  matrix gives `CREATE_SIMULATION` the same role set as `ANALYZE_QUERY`.

## Reproducing the dock-closure demo through the API

```bash
curl -s -X POST http://localhost:8080/api/scenarios -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-dock-closure-1" -H "Content-Type: application/json" -d '{
    "site_id": "<SITE_BLR>", "name": "Close inbound dock IN-02 for three hours",
    "run": {"master_seed": 20261012, "replications": 10, "horizon_min": 480, "warmup_min": 30},
    "overlay": {"dock_closures": [{"dock_id": "IN-02", "start_min": 60, "end_min": 240, "reason": "leveller repair"}]}
  }'
# -> {"command_id":"...","status":"SUCCEEDED","correlation_id":"...","result":{"id":"<scenario_id>", ...}}

curl -s -X POST http://localhost:8080/api/scenarios/<scenario_id>/runs -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-dock-closure-1-run"
# -> {"result":{"run_id":"<run_id>","scenario_id":"<scenario_id>","status":"QUEUED"}}

curl -s http://localhost:8080/api/simulation-runs/<run_id> -H "Authorization: Bearer $TOKEN"
# poll until "status":"SUCCEEDED"; "results.deltas" then shows the same worse/better verdicts as
# docs/scenarios/dock-closure.md (byte-identical inputs and seed => byte-identical results, ADR-47).
```
