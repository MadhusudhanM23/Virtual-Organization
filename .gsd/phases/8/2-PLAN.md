# Plan 8.2 — OR-Tools dock & forklift re-assignment optimizer

Working plan (brief). Source: REQ-091; BLUEPRINT §8, §9; `.gsd/STACK.md` (OR-Tools Java). New: ADR-62 (Proposed).
Wave A — parallel with Plan 8.1. Owns `services/optimization/` only (+ one `<module>` line in the root `pom.xml`).

## Shape

New pure-Java Maven module `services/optimization`, artifact `vorg-optimization`, package `io.vorg.optimization`.
It mirrors `services/simulation`: no Spring, no DB, no I/O beyond reading its own fixtures in tests. Dependency is
`com.google.ortools:ortools-java`; pick the latest stable on Maven Central and verify that the Windows native library
loads (`Loader.loadNativeLibraries()`) in a test on this machine.

**Input** (`DockReassignmentProblem`, a record, parsed from snake_case JSON):
- `docks[]`: `{code, kind, closures[]{start_min, end_min, reason}}`
- `trips[]`: `{trip_code, truck_code, direction, current_dock, arrival_min, service_min}`
- `forklifts[]`: `{code, current_dock}`
- `horizon_min`
- `params`: `{reassignment_penalty_min, max_solve_ms, random_seed}`

Every number comes from the caller. The module never invents defaults beyond documented params.

**Model** (CP-SAT):
- each trip goes to exactly one dock of its direction's kind (optional intervals);
- `NoOverlap` per dock;
- closure windows become fixed intervals;
- start ≥ arrival;
- objective: minimize the sum of waits (start − arrival) + `reassignment_penalty_min` × moved trips;
- forklifts: each forklift goes to one open dock, minimizing the max (trips at dock ÷ forklifts at dock), linearized.

**Output** (`OptimizationResult`):
- `feasibility{status: OPTIMAL|FEASIBLE|INFEASIBLE|MODEL_INVALID|UNKNOWN, solver: "or-tools-cp-sat <ver>",
  optimality_gap, solve_time_ms}`
- `objective_before` (the current assignment evaluated under the closures by the same model with assignments fixed)
- `objective_after`, each as `{name: "inbound_wait_total_min", unit: "min", value}`
- `constraints[]{name, kind, satisfied, detail}`, which includes the violations of the *current* plan, e.g. trips
  booked into a closed window
- `trip_moves[]{trip_code, from_dock, to_dock, planned_start_min}`
- `forklift_moves[]{code, from_dock, to_dock}`
- `input_hash`, `result_hash` (canonical JSON, same approach as `services/simulation`)

Never a confidence or probability field.

## Tasks

1. **Module scaffold + problem model** — `pom.xml` (parent = root, Java 21), the root `<module>` entry, the records,
   JSON parse/validate (problem-detail-style field errors), canonical JSON + hashing, and the native-load test.
2. **CP-SAT solver** — `DockReassignmentSolver.solve(problem)`. Deterministic: `num_workers=1`, fixed `random_seed`,
   time limit from params. The status mapping must stay honest: report FEASIBLE with a nonzero gap when the time
   limit hits.

   Tests:
   - the IN-02 closure fixture (`src/test/resources/dock-closure-in-02.problem.json`, built from the seeded dock
     names in `services/simulation/scenarios/dock-closure-in-02.json` / the world seed) gives
     `objective_after < objective_before` and no trips in the closed window;
   - an infeasible fixture returns INFEASIBLE;
   - determinism: the same input solved twice gives an identical `result_hash`;
   - a property-style check over ~20 seeded random instances: every solution satisfies capacity, arrival and closure
     constraints (checked by an independent validator, not the solver).
3. **Docs** — `services/optimization/README.md` covers the model, the input/output schema, determinism and the
   honesty rules. Add **ADR-62 (Proposed)**: CP-SAT choice, determinism settings, gap reporting, why there is no
   confidence %, and the native-library packaging risk.

One commit per task, `feat(phase-8): <task>`, each preceded by a green
`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify`.
