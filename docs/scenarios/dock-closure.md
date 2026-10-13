# Scenario: dock closure what-if (Phase 6 simulation)

What it shows: inbound dock **IN-02** of Bengaluru Warehouse 01 is closed for three hours of an eight-hour shift (60 to
240 minutes, e.g. a leveller repair). The deterministic discrete-event simulation (`services/simulation`) runs the shift
with and without the closure on the **same seeds** and reports the baseline-vs-scenario KPI deltas with 95% confidence
intervals. It supports MCD step 9 (run a deterministic simulation; compare baseline vs scenario throughput/queue
outcomes) and is the input the scenario API (Plan 6.3), the Simulate UI (Plan 6.4) and the recovery recommendation
(Phase 8) build on.

**Truth label:** every number on this page is **SIMULATED** model output (`data_mode: SIMULATED`, `lens: SIMULATE`,
`source: SIMULATION`, shown as SIMULATION in the UI, ADR-27). The model input is derived from the **SYNTHETIC** world
seed, and every behavioural parameter is a labeled **ASSUMPTION** (no calibration data exists yet, REQ-077). Nothing here
describes a real warehouse.

## Provenance: where every number comes from

| Input | Value | Source / label |
|---|---|---|
| Layout and resources | 6 docks (IN-01..03, OUT-01..03), 6 ground-floor zones, 70 racks x 4 levels x 6 slots, 10 forklifts, 30 workers by role, 120 pallets | copied from the world seed v1 ([bengaluru-warehouse-01.md](bengaluru-warehouse-01.md)), SYNTHETIC |
| Behaviour (arrival rates, service times, speeds, targets) | 21 parameters, table in [services/simulation/README.md](../../services/simulation/README.md#assumptions-baseline-bengaluru-warehouse-01-model_version-100) | ASSUMPTION, each with unit and rationale; embedded in every results document |
| Scenario | [`services/simulation/scenarios/dock-closure-in-02.json`](../../services/simulation/scenarios/dock-closure-in-02.json) | overlay `dock_closures: [IN-02, 60-240 min]` |
| Run | master seed `20261012`, 10 replications, 480 min horizon, 30 min warm-up | stored in the results with the 10 derived replication seeds |
| Engine | `vorg-des` 1.0.0, `L64X128MixRandom`, seed scheme `sha256-name-v1`, logic `warehouse-flow/1.0.0` | ADR-22, ADR-47, ADR-48 |

Closure semantics (ADR-48): from `start_min` the dock takes no new truck; a truck already at the door finishes and leaves,
then the dock stays closed until `end_min`. Trucks queue FIFO in the yard for the remaining inbound docks.

## Reproduce

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/simulation package "-DskipTests"
java -jar services/simulation/target/vorg-simulation-0.1.0-SNAPSHOT.jar --compare --summary services/simulation/scenarios/dock-closure-in-02.json
```

`--summary` prints the KPI table below as JSON; without it the full comparison and both results documents are printed.
The output is byte-identical on every run (the wall-clock time is printed to stderr only); the results hashes below
identify it.

## Result (SIMULATED, 10 paired replications)

| KPI | Baseline mean | Scenario mean | Delta (95% CI) | Verdict |
|---|---|---|---|---|
| Inbound truck dock wait, mean (min) | 3.70 | 10.18 | +6.48 [1.52, 11.44] | **worse** |
| Inbound truck dock wait, p95 (min) | 17.35 | 31.37 | +14.02 [3.01, 25.03] | **worse** |
| Inbound truck turnaround, mean (min) | 33.01 | 39.19 | +6.18 [1.53, 10.84] | **worse** |
| Inbound yard queue, time-average (trucks) | 0.22 | 0.61 | +0.39 [0.09, 0.68] | **worse** |
| Inbound dock utilization (ratio) | 0.538 | 0.613 | +0.075 [0.063, 0.087] | increased |
| Inbound dock closed fraction (ratio) | 0.000 | 0.127 | +0.127 [0.120, 0.133] | increased |
| Pallets received per hour | 58.00 | 58.05 | +0.05 [-0.01, 0.12] | no_significant_change |
| Pallets shipped per hour | 55.55 | 55.59 | +0.04 [-0.02, 0.10] | no_significant_change |
| Orders shipped per hour | 25.57 | 25.60 | +0.03 [-0.01, 0.07] | no_significant_change |
| Outbound dock utilization (ratio) | 0.656 | 0.657 | +0.001 [-0.001, 0.003] | no_significant_change |
| Forklift utilization (ratio) | 0.737 | 0.738 | +0.0002 [0.0000, 0.0004] | increased |
| Order cycle time, mean (min) | 59.36 | 59.07 | -0.29 [-0.53, -0.05] | better |
| Inbound turnaround SLA misses (trucks) | 0.0 | 0.1 | +0.1 [-0.13, 0.33] | no_significant_change |

Run: master seed `20261012`, 10 paired replications, common random numbers. Baseline results hash
`22d8550abeb04caeee8982b69c3e67a31748bd53fe674b40325a4468b1459980`, scenario results hash
`f62e6577cb3f3c892b236d7c6d413a806c129340d0720843d17e5a54782e0b1b` (from `--compare --summary`, this build of
`vorg-des` 1.0.0 / `warehouse-flow` 1.0.0).

Reading it: closing one of three inbound doors for three hours makes inbound trucks wait longer for a dock and stay
longer at the site, and the remaining inbound docks are busier, while nothing downstream changes significantly in this
shift: the docks still have spare capacity at the assumed arrival rate, so the closure delays trucks rather than
pallets. "worse"/"better" is stated only where the paired 95% CI of the difference excludes zero; everything else is
`no_significant_change`. Absolute values depend on the uncalibrated assumptions; the paired difference is the intended
reading.

## Variations

- Other docks/windows, forklift outages, fewer workers by role, demand peaks and parameter overrides use the same
  overlay format; [`combined-disruption.json`](../../services/simulation/scenarios/combined-disruption.json) exercises
  every overlay type.
- The determinism contract (same inputs + seed + engine/logic version => same bytes) is described in the
  [simulation README](../../services/simulation/README.md#determinism-contract-adr-47) and tested in
  `WarehouseFlowModelTest`.
