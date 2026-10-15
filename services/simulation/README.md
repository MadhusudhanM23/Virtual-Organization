# services/simulation - deterministic discrete-event simulation (DES)

Pure Java 21 library (`io.vorg:vorg-simulation`, package `io.vorg.simulation`) with **no runtime dependencies**: no
Spring, no database, no Jackson (ADR-22, ADR-47). The API module (Plan 6.3) calls it in-process; one run = one thread.
Everything it produces is **SIMULATED** model output (`data_mode: SIMULATED`, `lens: SIMULATE`, `source: SIMULATION`),
never an observation of the real warehouse, and every model parameter is a labeled **ASSUMPTION**.

Decisions: [ADR-04, ADR-22, ADR-47 (engine contract), ADR-48 (warehouse model), ADR-49 (overlay, replications,
labels)](../../docs/adr/ADR-REGISTER.md). Scenario page: [dock closure](../../docs/scenarios/dock-closure.md).

## Packages

| Package | Contents |
|---|---|
| `engine` | `Simulation` (run control, warm-up, scheduling), `SimClock`, `SimTime` (ms), `EventQueue`, `ScheduledEvent`, `EventTrace` (SHA-256), `RunControl`, `Resettable`, `Entity` |
| `random` | `RngStreams` (named `L64X128MixRandom` streams), `StreamSeeds` (seed derivation), `RandomStream`, `Distribution` (constant, uniform, exponential, triangular, normal, lognormal, erlang, discrete_uniform, discrete), `Distributions.fromSpec` |
| `resource` | `UnitPool` (named units, priority/FIFO queue, downtime windows, utilization), `Store` (FIFO buffer with FIFO takers) |
| `stats` | `Tally`, `TimeWeighted`, `Counter`, `ReplicationSummary` (mean, sd, Student-t 95% CI), `StudentT` |
| `json` | `Json`: parser and canonical writer, `sha256` |
| `warehouse` | `ModelInput`, `Assumption`, `Baselines`, `Scenario`, `Overlay`, `RunSettings`, `WarehouseFlowModel`, `Kpis`, `SimulationRunner`, `SimulationResults`, `Comparison` |
| `cli` | `SimulationCli` (debugging main) |

## Using it (Plan 6.3)

```java
Scenario s = Scenario.parse(scenarioJson);                 // validates; InvalidInputException names the path
SimulationResults r = SimulationRunner.run(s);              // N replications, sequential, pure
String json = r.toJson();                                    // canonical bytes; r.resultsHash()
Comparison c = SimulationRunner.runWithBaseline(s);          // baseline counterpart + scenario, same seeds
c.toJson(); c.delta("inbound_truck_wait_mean_min"); c.verdict("inbound_truck_wait_mean_min");
```

`Scenario.baseline()` / `effectiveModel()` expose the model inputs (`hash()` is the SHA-256 of the canonical form),
`Overlay.affectedEntities()` the dock/forklift ids and `ROLE:<role>` entries to highlight in the SIMULATE lens.
A long-running caller should run this on a bounded executor (ADR-22); 10 replications of an 8-hour shift take well
under a second (see Performance).

### Scenario JSON (`vorg.simulation.scenario.v1`)

```json
{
  "schema": "vorg.simulation.scenario.v1",
  "scenario_id": "dock-closure-in-02",
  "name": "Close inbound dock IN-02 for three hours",
  "description": "optional",
  "baseline": "bengaluru-warehouse-01",
  "run": { "master_seed": 20261012, "replications": 10, "horizon_min": 480, "warmup_min": 30, "trajectory_sample_min": 15 },
  "overlay": {
    "dock_closures":     [ { "dock_id": "IN-02", "start_min": 60, "end_min": 240, "reason": "leveller repair" } ],
    "forklift_outages":  [ { "forklift_id": "FL-10", "start_min": 120, "end_min": 300, "reason": "battery fault" } ],
    "worker_reductions": [ { "role": "PICKING", "count": 2, "start_min": 0, "end_min": 240, "reason": "absence" } ],
    "demand_multiplier": { "factor": 1.25, "start_min": 180, "end_min": 360 },
    "route_blockages":   [ { "zone": "STORAGE_A", "start_min": 120, "end_min": 300, "detour_factor": 4.0, "reason": "pallet spill" } ],
    "parameter_overrides": { "inbound_truck_arrival_rate_per_hour": 4.0,
                             "unload_time_per_pallet_min": { "dist": "triangular", "min": 1.4, "mode": 1.9, "max": 3.0 } }
  }
}
```

- Exactly one of `baseline` (a built-in name: `bengaluru-warehouse-01`) or `model` (an inline model input).
- `run.master_seed` is mandatory; defaults: 10 replications (max 200), 480 min horizon (max 7 days), 30 min warm-up,
  15 min trajectory samples.
- Every overlay section is optional. Windows are minutes from the run start, `[start_min, end_min)`; `start_min` must be
  before the horizon. `demand_multiplier` (factor in (0, 10]) scales **order** arrivals; `end_min` defaults to the
  horizon. `parameter_overrides` replaces assumption values (same kind: number or distribution); the output marks them
  `overridden: true`. Unknown fields, docks, forklifts, roles or assumption keys are errors, never ignored.
- `route_blockages` (Phase 13, ADR-79): putaway into and retrieval from the storage `zone` that *start* in the window take
  `detour_factor` (required, in (1, 10]) times their layout travel time; the worst overlapping blockage wins. Omitted from
  the normalized overlay when empty, so earlier hashes are unchanged.
- Examples: [`scenarios/`](scenarios) (`baseline.json`, `dock-closure-in-02.json`, `combined-disruption.json`).

### Model input JSON (`vorg.simulation.model-input.v1`)

`schema`, `model_id` (`warehouse-flow`), `model_version` (`1.0.0`), `site` (provenance, `data_mode` of the source data),
`layout` (`units: "m"`, `docks[] {dock_id, direction INBOUND|OUTBOUND, x, y}`, `zones[] {code, type, min_x, min_y, max_x,
max_y}`, `storage {zones[], racks, levels_per_rack, slots_per_level, note}`), `resources` (`forklifts[]`, `workers
{role: count}`), `initial_state` (`stored_pallets`, `receiving_staged_pallets`, `dispatch_staged_pallets`) and
`assumptions {key: {value, unit, rationale, label: "ASSUMPTION"}}` where `value` is a number or a distribution spec
`{"dist": "triangular", "min": .., "mode": .., "max": ..}` (see `Distribution`). Print the built-in baseline with
`--print-baseline`. Source: [`bengaluru-warehouse-01.model.v1.json`](src/main/resources/io/vorg/simulation/warehouse/bengaluru-warehouse-01.model.v1.json).

### Results JSON (`vorg.simulation.results.v1`)

```text
schema, data_mode "SIMULATED", lens "SIMULATE", source "SIMULATION",
engine   {name "vorg-des", version, rng_algorithm "L64X128MixRandom", seed_scheme "sha256-name-v1", time_unit "ms"}
model    {model_id, model_version, logic_version "warehouse-flow/1.0.0", site_code, site_data_mode, input_hash}
scenario {scenario_id, name, description, baseline, baseline_hash, overlay{...normalized}, affected_entities[]}
run      {master_seed, replications, horizon_min, warmup_min, trajectory_sample_min, replication_seeds[]}
kpis[]   {key, label, unit, definition, better lower|higher|neutral, n, mean, sd, ci95_half_width, ci95_low, ci95_high,
          per_replication[]}
conservation_ok
replications[] {index, seed, trace_hash, event_count, kpis{key: value}, conservation{pallets, inbound_trucks,
          outbound_trucks, orders}, constraints[{code, message, value}]}
trajectory {replication 0, sample_interval_min, samples[{t_min, inbound_trucks_waiting, inbound_docks_occupied,
          inbound_docks_open, outbound_trucks_waiting, outbound_docks_occupied, forklifts_busy, forklifts_available,
          forklift_queue, receiving_pallets, stored_pallets, dispatch_staged_pallets, open_orders, pallets_shipped_total}]}
assumptions {key: {value, unit, rationale, label "ASSUMPTION", overridden}}
results_hash  SHA-256 of the canonical JSON of everything above
```

Undefined values (e.g. a CI with fewer than 2 replications, a cycle time when no order shipped) are `null`.
The 95% CI is the plain Student-t interval over replication values; it is not clipped, so for counts and ratios it can
extend past the feasible range (e.g. below 0) when replications vary a lot - a sign that more replications are needed,
not a model value.
The comparison (`vorg.simulation.comparison.v1`) has `pairing: "common_random_numbers"`, `baseline`/`scenario`
`{scenario_id, name, input_hash, results_hash}`, `affected_entities`, `deltas[] {key, label, unit, better,
baseline_mean, scenario_mean, n_pairs, delta_mean, delta_ci95_low, delta_ci95_high, verdict}` and `comparison_hash`.
`verdict` is `worse`/`better` (or `increased`/`decreased` for neutral KPIs) only when the paired 95% CI excludes 0,
else `no_significant_change`.

## Determinism contract (ADR-47)

Same model input + scenario + master seed + engine version + logic version => byte-identical results JSON and identical
per-replication trace hashes, across runs, JVM restarts and machines on the same Java release. How:

- Time is a `long` in ms, never the wall clock. Durations in minutes are converted with `Math.round`.
- Calendar order `(time, priority, insertion_sequence)`: total, independent of heap layout or hashing.
- Seeds: stream seed = first 8 bytes of `SHA-256("vorg-des/v1|<parent>|<name>")`; replication `i` uses the name
  `replication/<i>`. Streams are created by name, so adding a stream never shifts another.
- Generator `L64X128MixRandom` (JDK 21 `RandomGeneratorFactory`); only `nextDouble()` is used. Variates use `StrictMath`
  and a fixed number of uniforms. A golden test pins the derivation and the first draw (`RngStreamsTest`).
- Single-threaded runs; no `HashMap` iteration influences the schedule or the output (insertion-ordered or sorted maps).
- Resources pick the lowest-index free unit; queues are priority then FIFO.
- Output: canonical JSON (fixed field order, doubles rounded half-even to 6 decimals, no exponents, NaN as `null`).
  Wall-clock timings are printed to stderr by the CLI only and are never part of results.
- Each executed event feeds `EventTrace` (time, priority, sequence, label). Event labels are part of the contract.

Common random numbers: every process has its own stream (`inbound.arrivals`, `inbound.content`, `orders.arrivals`,
`orders.thinning`, `orders.content`, `outbound.arrivals`, `outbound.content`, `initial.content`), and an entity's work
content (pallet count, every service time, storage zone) is drawn when it arrives. A dock closure therefore leaves
arrivals and work content identical, and baseline-vs-scenario differences are due to the change, not to noise.

## Warehouse flow model (ADR-48)

```text
inbound truck (Poisson) -> yard FIFO -> IN dock (lowest free) -> dock-in -> unloading, up to 2 forklifts in parallel
  -> receiving check (RECEIVING worker) -> putaway (forklift, travel from layout) -> storage
order (Poisson, thinning for demand multiplier) -> takes stored pallets FIFO -> retrieval (forklift) -> picking
  (PICKING worker) -> packing (PACKING worker) -> dispatch staging
outbound truck (Poisson) -> yard FIFO -> OUT dock -> dock-in -> loading (DISPATCH worker + forklift, 2 lanes) until full
  or max dwell -> dock-out -> departure; an order completes when its last pallet departs
```

- Pallet-level: one storage pallet becomes one outbound pallet; SKUs, case picking, slotting and congestion are not
  modeled. The 3 STORAGE-role workers have no modeled task; the mezzanine is excluded from pallet storage.
- Forklift priority: loading (0) < unloading (1) < retrieval (2) < putaway (3); FIFO within a priority.
- Closures/outages are non-preemptive: work in progress finishes, no new work starts until the window ends. A worker
  reduction takes the highest-numbered workers of the role.
- Travel = 2 x Manhattan distance between zone centres / forklift speed (round trip).
- Start state: docks and yards empty; 80 pallets stored (alternating Storage A/B), 20 staged in receiving, 20 staged in
  dispatch without an order. Trucks the world seed shows as docked are not carried over.
- Warm-up: statistics restart at `warmup_min`; observations are recorded when they complete; backlog KPIs describe the
  state at the horizon, including trucks and orders that arrived during the warm-up.
- Conservation ledger per replication: pallets `initial + arrived_inbound = shipped + in_system` (with the count per
  location), inbound/outbound trucks `arrived = departed + in_system`, orders `arrived = shipped + open`.
- Constraint findings: `STORAGE_CAPACITY_EXCEEDED` (stored pallets above the 1 680 rack positions),
  `STOCK_SHORTAGE_AT_END` (order lines waiting for stock at the horizon).

### KPIs

See `Kpis.ALL` for the exact definitions (also embedded in every results document): throughput (pallets received /
shipped per hour, orders shipped per hour, inbound trucks completed), inbound truck dock wait (mean, p95, max),
turnaround, yard queue (time average), dock utilization and closed fraction, outbound dock utilization, wait and fill
rate, forklift utilization and request wait, worker utilization per role, order cycle time (mean, p95), backlog at the
horizon (trucks waiting / in system, oldest truck age, receiving pallets, open orders, dispatch staging) and SLA misses
(inbound turnaround, orders) with the order on-time rate. Utilization is busy / available time (closed time excluded).

### Assumptions (baseline `bengaluru-warehouse-01`, model_version 1.0.0)

Layout and resource **counts** are copied from the SYNTHETIC world seed v1 ([bengaluru-warehouse-01.md](../../docs/scenarios/bengaluru-warehouse-01.md)):
6 docks (IN-01..03 on the south wall at x = 12/27/42 m, OUT-01..03 on the north wall at x = 72/90/108 m), 7 zones (6
ground floor modeled), 70 ground-floor racks x 4 levels x 6 slots, 10 forklifts, 30 workers (RECEIVING 5, PICKING 10,
PACKING 8, DISPATCH 4, STORAGE 3), 120 pallets. Everything below is an **ASSUMPTION** (no calibration data, REQ-077):

| Key | Value | Unit | Rationale |
|---|---|---|---|
| `inbound_truck_arrival_rate_per_hour` | 3.5 | trucks/h | Poisson, constant over the shift; inbound docks occupied a little over half of the time |
| `inbound_pallets_per_truck` | discrete uniform 14..22 | pallets | mixed partial/full truckloads |
| `inbound_unload_lanes_per_truck` | 2 | forklifts | two forklifts per door at most |
| `dock_in_time_min` | triangular 4 / 6 / 10 | min | position, chock, leveller, documents |
| `dock_out_time_min` | triangular 3 / 5 / 8 | min | seal, sign, pull out |
| `unload_time_per_pallet_min` | triangular 1.2 / 1.6 / 2.5 | min | trailer to receiving lane cycle |
| `receiving_check_time_per_pallet_min` | triangular 0.5 / 1.0 / 2.0 | min | scan and quantity/damage check |
| `putaway_handling_time_min` | triangular 0.6 / 1.0 / 1.8 | min | lift and place (travel added) |
| `forklift_speed_m_per_s` | 2.0 | m/s | 7.2 km/h indoors with pedestrians |
| `inbound_turnaround_target_min` | 120 | min | SLA target, counting only |
| `order_arrival_rate_per_hour` | 28 | orders/h | Poisson; roughly balances inbound flow |
| `pallets_per_order` | 1/2/3/4 with weights 0.35/0.35/0.2/0.1 | pallets | small orders most common (mean 2.05) |
| `retrieval_handling_time_min` | triangular 0.6 / 1.0 / 1.8 | min | lower and set down (travel added) |
| `pick_time_per_pallet_min` | triangular 3 / 5 / 8 | min | build one outbound pallet |
| `pack_time_per_pallet_min` | triangular 2 / 4 / 6 | min | wrap, label, move to staging |
| `order_cycle_time_target_min` | 240 | min | same-shift dispatch target |
| `outbound_truck_arrival_rate_per_hour` | 3.0 | trucks/h | carrier pickups, about 25% spare capacity |
| `outbound_truck_capacity_pallets` | discrete uniform 22..26 | pallets | carrier trucks |
| `outbound_load_lanes_per_truck` | 2 | forklifts | two forklift + worker teams |
| `load_time_per_pallet_min` | triangular 1.0 / 1.5 / 2.5 | min | staging to trailer |
| `outbound_max_dwell_min` | 30 | min | loading window from loading start; leave when full or when it ends |

The model has not been calibrated against historical outcomes; absolute numbers are illustrative, differences between
scenarios with common random numbers are the intended use.

## CLI (debugging)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/simulation package "-DskipTests"
java -jar services/simulation/target/vorg-simulation-0.1.0-SNAPSHOT.jar --compare --summary services/simulation/scenarios/dock-closure-in-02.json
java -jar services/simulation/target/vorg-simulation-0.1.0-SNAPSHOT.jar --pretty --out results.json services/simulation/scenarios/baseline.json
java -jar services/simulation/target/vorg-simulation-0.1.0-SNAPSHOT.jar --print-baseline
```

Options: `--compare` (baseline counterpart with the same seeds, prints the comparison plus both results), `--summary`
(KPI means/CIs only), `--pretty`, `--replications N`, `--seed S`, `--out FILE`. Stdout is deterministic; the wall-clock
time goes to stderr.

## Performance (NFR-06: MCD scenario run < 60 s)

`PerformanceTest` runs the dock-closure scenario and its baseline (2 x 10 replications x 480 min, about 160 000
events) and logs the time; on the reference dev machine it takes about 0.4-0.7 s.

## Signature scenarios, Monte Carlo and calibration (Phase 13, REQ-140)

- `SignatureScenarios`: typed parameters for `demand_surge`, `forklift_outage`, `route_blockage` and `shift_shortage`;
  `expand(type, params, model)` validates (errors are `signature.params.<name>: ...`) and returns an overlay map;
  `fixture(type)` is the deterministic fixture (seed 20261012, 10 replications, 480 min). `catalog()` documents them.
  See [docs/scenarios/library.md](../../docs/scenarios/library.md).
- `MonteCarlo.report(comparison)` (`vorg.simulation.distributions.v1`): per KPI mean, median, p5, p95, min, max, sd and
  the 95% CI, for baseline, scenario and the paired delta; `SimulationRunner.runSeeds` / `runWithBaseline(scenario, seeds)`
  run an explicit seed set. Deterministic for a seed set (ADR-80).
- `Calibration.fitScalar`: fits one scalar assumption to observed samples with a temporal holdout and reports the error
  before and after (ADR-82); see [docs/scenarios/calibration.md](../../docs/scenarios/calibration.md).

## Tests

`mvnw -B -pl services/simulation verify`: event ordering and ties, run control and warm-up, trace hashes, RNG stream
independence/reproducibility and golden values, distribution moments, hand-checked queueing cases, downtime semantics,
stores, statistics and CI, canonical JSON, model input/scenario validation, conservation laws, determinism (same seed =>
identical bytes and traces; different seed => different), warm-up invariance of the sample path, dock closure worse than
baseline with common random numbers, overlay effects, signature scenario validation and determinism, Monte Carlo quantiles and seed sets, calibration, performance, CLI.
