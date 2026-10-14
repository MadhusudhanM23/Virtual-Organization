# services/optimization - dock & forklift re-assignment optimizer (OR-Tools CP-SAT)

Pure Java 21 library (`io.vorg:vorg-optimization`, package `io.vorg.optimization`) for REQ-091: when a dock closes,
re-assign the inbound (and outbound) trips to the remaining docks of their kind and re-station the forklifts. Its one
runtime dependency is Google OR-Tools (`com.google.ortools:ortools-java` 9.15.6755, CP-SAT); no Spring, no database,
no I/O. The API module (Plan 8.3) calls it in-process and maps the result onto a recommendation (Plan 8.1 contract).

Decision: [ADR-62](../../docs/adr/ADR-REGISTER.md) (CP-SAT, determinism settings, gap reporting, no confidence %,
native-library packaging risk). Canonical JSON and hashing follow ADR-47 (`services/simulation`).

## Using it (Plan 8.3)

```java
DockReassignmentProblem p = DockReassignmentProblem.parse(problemJson);   // or new DockReassignmentProblem(records...)
OptimizationResult r = DockReassignmentSolver.solve(p);                   // validates first, then 3 CP-SAT solves
r.feasibility().status();      // OPTIMAL | FEASIBLE | INFEASIBLE | MODEL_INVALID | UNKNOWN
r.objectiveBefore(); r.objectiveAfter(); r.constraints(); r.tripMoves(); r.forkliftMoves();
String json = r.toJson();      // canonical snake_case JSON; r.resultHash(), r.inputHash()
```

Invalid input throws `InvalidProblemException`; `errors()` lists **every** `FieldError(field, message)` with a JSON
path (`trips[3].current_dock`), the same shape as the API's problem-detail field errors (map it to HTTP 422).
`solve` is thread-safe (each call builds its own models); it blocks for up to 3 x `max_solve_ms`, so run it on a
bounded executor. `Solvers.solverName()` gives `"or-tools-cp-sat <version>"`.

## Model

All times are **integer minutes from the plan start**; windows are half-open `[start, end)`.

| Stage (`solver_stages[].stage`) | What | Objective (minimize) |
|---|---|---|
| `current_plan` | every trip fixed to its `current_dock`, start times free | sum of waits |
| `reassignment` | free choice among the docks of the trip's direction | sum of waits + `reassignment_penalty_min` x moved trips |
| `forklift_balance` | trip counts per dock from `reassignment` fixed; each forklift at one open dock | max trips per forklift, then moved forklifts |

Trip model (stages 1 and 2 are the **same model**, stage 1 adds `at[trip][current_dock] = 1`):

- one start variable per trip in `[arrival_min, horizon_min - service_min]`, and `start + service_min <= horizon_min`;
- one optional fixed-size interval per (trip, dock of the trip's kind), presence literal = "trip uses this dock";
  `ExactlyOne` per trip;
- per dock a `NoOverlap` over its trip intervals and its closure windows as fixed intervals (closures are clipped to
  the horizon and merged, so overlapping closures are allowed);
- wait = `start - arrival_min`.

Forklift model: one literal per (forklift, open dock), `ExactlyOne` per forklift; a dock is *open* unless its closures
cover the whole horizon. For each dock with `n > 0` trips, literals "this dock has exactly k forklifts" (k = 1..F)
and `T >= ceil(S * n / k)` when the literal holds, `S = F^2`. This linearization is exact: two different ratios with
denominators up to F differ by at least 1/F^2, so the ceiling never merges them. Objective `T * (F + 1) + moves` is
lexicographic (at most F forklifts can move). A dock with trips needs at least one forklift, so fewer forklifts than
busy docks is INFEASIBLE. With an empty `forklifts[]` the stage is skipped.

Outbound trips are scheduled with the same rules; since a trip only uses docks of its own kind, inbound and outbound
are independent sub-problems and the inbound optimum is not traded against outbound waits.

## Problem JSON (`vorg.optimization.dock-reassignment.problem.v1`)

```json
{
  "schema": "vorg.optimization.dock-reassignment.problem.v1",
  "description": "optional free text, not part of the model or the hash",
  "docks":     [ { "code": "IN-02", "kind": "INBOUND", "closures": [ { "start_min": 60, "end_min": 240, "reason": "leveller repair" } ] } ],
  "trips":     [ { "trip_code": "TRIP-00010", "truck_code": "TR-10", "direction": "INBOUND", "current_dock": "IN-02",
                   "arrival_min": 69, "service_min": 25 } ],
  "forklifts": [ { "code": "FL-03", "current_dock": "IN-02" } ],
  "horizon_min": 480,
  "params": { "reassignment_penalty_min": 15, "max_solve_ms": 10000, "random_seed": 20261012 }
}
```

Every number comes from the caller; the module adds **no defaults** (all `params` are mandatory). Rules: unknown,
missing or mistyped fields are errors; `schema` and `description` are optional, `closures` defaults to `[]`;
`kind`/`direction` are `INBOUND|OUTBOUND`; codes are non-blank and unique (dock, trip, forklift); a trip's and a
forklift's `current_dock` must exist, and a trip's must be of the trip's direction; `horizon_min` 1..10080;
`arrival_min` 0..10080; `service_min` 1..10080; closures `0 <= start_min < end_min <= 10080`, non-blank `reason`;
`reassignment_penalty_min` 0..10080; `max_solve_ms` 1..600000 (**per CP-SAT solve**); `random_seed` a non-negative
32-bit integer; at most 100 docks, 2000 trips, 500 forklifts. `input_hash` is the SHA-256 of the canonical form
(`toCanonicalMap()`: fixed field order, **input order kept** because it is part of the model).

## Result JSON (`vorg.optimization.dock-reassignment.result.v1`)

```text
schema
feasibility      {status, solver "or-tools-cp-sat 9.15.6755", optimality_gap, solve_time_ms}
objective_before {name "inbound_wait_total_min", unit "min", value}   current assignment, best schedule (stage 1)
objective_after  {name "inbound_wait_total_min", unit "min", value}   optimized plan (stage 2)
constraints[]    {name, kind CAPACITY|SLA|RULE, satisfied, detail}
trip_moves[]     {trip_code, from_dock, to_dock, planned_start_min}  trips whose dock changes
forklift_moves[] {code, from_dock, to_dock}
schedule[]       {trip_code, truck_code, direction, current_dock, dock, start_min, end_min, wait_min}  every trip
solver_stages[]  {stage, status, objective_value, best_bound, optimality_gap}
input_hash, result_hash
```

- `objective_*.value` is the **pure** inbound wait (sum of `start - arrival` over INBOUND trips, integer minutes);
  the reassignment penalty is in `solver_stages[1].objective_value` only. Because penalties are >= 0 and the current
  assignment is a feasible point of stage 2, `objective_after <= objective_before` whenever both stages are OPTIMAL.
- `objective_before` is the *best* the current assignment can do around the closures (CP-SAT re-sequences it), so the
  reported improvement is conservative.
- `constraints[]`, checked in plain Java on the plans (`PlanChecks`), not read back from CP-SAT:
  `current_plan.closure_window` (RULE, one entry per trip that, served on arrival at its current dock, falls into a
  closure), `current_plan.horizon` (SLA, can the current assignment serve everything in the horizon), then for the
  optimized plan `plan.dock_kind`, `plan.arrival`, `plan.closure_window` (RULE), `plan.dock_capacity` (CAPACITY),
  `plan.horizon` (SLA), `plan.forklift_coverage` (CAPACITY, with the max trips per forklift). Without a solution there
  is a single `plan.feasibility` (or `plan.forklift_coverage`) entry saying why.
- `trip_moves`, `forklift_moves`, `schedule` and `objective_after.value` are present only when `feasibility.status`
  is OPTIMAL or FEASIBLE; otherwise they are empty / `null`.

## Honesty rules

- **Status is CP-SAT's, never upgraded.** A solve cut by the time limit with a solution is FEASIBLE, without one
  UNKNOWN (never INFEASIBLE). `feasibility.status` is the worse of `reassignment` and `forklift_balance` (order
  OPTIMAL < FEASIBLE < UNKNOWN < INFEASIBLE < MODEL_INVALID).
- **Gap** = `|objective - best_bound| / max(1, |objective|)` per stage: 0 for OPTIMAL, the measured value for FEASIBLE,
  `null` without a solution; `feasibility.optimality_gap` is the larger of the two recommendation stages' gaps.
- **No confidence or probability field**, anywhere. The optimizer is deterministic given its inputs; the uncertainty
  is in the inputs (arrival and service times), which a solver gap does not measure. Use the simulation module's
  confidence intervals for that.
- `null` means "not available", never 0.

## Determinism

- CP-SAT `num_workers = 1`, `random_seed` from params, `max_time_in_seconds = max_solve_ms / 1000`, no logging;
  models are built in input order with no hash-ordered collections.
- `result_hash` = SHA-256 of the canonical JSON of the whole result **except** `feasibility.solve_time_ms` (wall clock)
  and the hash itself. Same input + same OR-Tools version, solved to completion (OPTIMAL / INFEASIBLE) => identical
  `result_hash`. A solve cut by the wall-clock limit depends on machine speed and may differ between runs.
- `solve_time_ms` covers model building and all stages; the one-time native-library load is excluded.

## Native library (packaging risk, ADR-62)

`ortools-java` depends at runtime on `ortools-{linux,darwin}-{x86-64,aarch64}` and `ortools-win32-x86-64` (tens of
MB of native libraries per platform); `Loader.loadNativeLibraries()` (called once via `Solvers.ensureLoaded()`) unpacks the
matching one to a temp directory. `NativeLibraryTest` proves the load and a CP-SAT solve on the build machine (verified
on Windows 11 x64 with Temurin 21). Linux musl (Alpine) images are not supported by these jars; use a glibc base image.
In the API module, Spring Boot's dependency management may raise `protobuf-java` above the version OR-Tools was built
against; a newer runtime than gencode within the same major version is supported by protobuf. Check with
`mvnw dependency:tree` when upgrading either side.

## IN-02 closure fixture (`src/test/resources/dock-closure-in-02.problem.json`)

| Value | Source |
|---|---|
| docks IN-01, IN-02, IN-03 (INBOUND) | SYNTHETIC world seed v1 (`BengaluruWarehouse01`, `bengaluru-warehouse-01.model.v1.json`) |
| IN-02 closed `[60, 240)`, "leveller repair"; `horizon_min` 480; `random_seed` 20261012 | `services/simulation/scenarios/dock-closure-in-02.json` (closure, run horizon, master seed) |
| TRIP-00001/TR-01 at IN-01, TRIP-00002/TR-02 at IN-02, arrival 0 | world seed: TR-01/TR-02 DOCKED at IN-01/IN-02; SIM-TMS refs TRIP-00001/00002 |
| TRIP-00005/TR-05 at IN-03, arrival 0 | world seed: TR-05 WAITING inbound (TRIP-00005); booked to IN-03, the seed's only AVAILABLE inbound door |
| TRIP-00007..TRIP-00032 (TR-07..TR-32), arrival `round(k x 60 / 3.5)` for k = 1..26, booked round-robin IN-02, IN-03, IN-01 | **SYNTHETIC**: a deterministic arrival stream at the simulation ASSUMPTION `inbound_truck_arrival_rate_per_hour` = 3.5; these trips and trucks are not in the seed |
| `service_min` 25 | derived from simulation ASSUMPTION modes: dock-in 6 + 18 pallets (midpoint of 14..22) x 1.6 min / 2 lanes + dock-out 5 = 25.4, rounded |
| FL-01..FL-06, two per inbound door | seed forklift codes; two per door from ASSUMPTION `inbound_unload_lanes_per_truck` = 2 (the other 4 forklifts are left out) |
| `reassignment_penalty_min` 15 | **test ASSUMPTION** (no cost data for a dock change exists) |
| `max_solve_ms` 10000 | test setting |

The outbound docks and trips (TR-03/04/06) are left out: the closure does not touch them. What the tests assert on
this fixture: `objective_after < objective_before`, no trip of the optimized plan inside IN-02's closed window, and
the current plan's closure violations (the trips booked at IN-02 that arrive inside `[60, 240)`) listed in
`constraints[]`. The concrete objective values are model outputs on synthetic data, not a forecast for the real site;
read them from `toPrettyJson()` of a run rather than from this README.

## Tests

`mvnw -B -pl services/optimization verify`: native load + trivial CP-SAT solve; canonical JSON codec; problem parsing
and validation (all field errors with paths, records built in Java validated too, input hash); the IN-02 fixture
(`objective_after < objective_before`, the closed window empty, the current plan's four violations listed, every
`plan.*` check satisfied); an INFEASIBLE fixture; identical `result_hash` across two solves; contract field names and
no `confidence`/`probability`; status and gap mapping; a 1 ms limit (UNKNOWN, no plan) and a solve stopped after its
first solution (FEASIBLE with its real, non-zero gap, still reproducible); forklift balancing and too few forklifts;
and a property check over 20 seeded **synthetic** random instances, each validated by `IndependentValidator`, which
shares no code with the solver (dock kind, arrival, horizon, no overlap per dock, closures, moves consistent with the
schedule, recomputed objective, forklift coverage).
