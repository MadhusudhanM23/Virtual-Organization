# Scenario library: four signature scenarios (Phase 13, REQ-140)

What it shows: the Simulate lens gains four typed what-if scenarios on the Bengaluru Warehouse 01 baseline, each
compared against the baseline with the same random numbers (paired CRN, ADR-49), plus Monte Carlo distributions of every
KPI over the seeded replications. Everything on this page is a **SIMULATION** result (`data_mode SIMULATED`, lens
SIMULATE); none of it is live or measured. Decisions: ADR-79 (types), ADR-80 (distributions), all Proposed.

## Where every number comes from

| Input | Source |
|---|---|
| Layout, docks, zones, forklifts (10), worker headcounts, initial stock | SYNTHETIC world seed `vorg-seed/bengaluru-warehouse-01/v1` ([page](bengaluru-warehouse-01.md)) |
| Arrival rates, service times, speeds | The labelled ASSUMPTIONs of the baseline model (`bengaluru-warehouse-01.model.v1.json`, each with its rationale). Not measured. One of them, `forklift_speed_m_per_s`, has a calibration check: [calibration.md](calibration.md) |
| Scenario parameters (factor, count, zone, detour, window) | **Chosen by the person building the scenario.** The fixtures below use fixed values labelled ASSUMPTION, picked so the effect is visible, not taken from any observed event |
| Random numbers | Seed 20261012, replication `i` seeded by `sha256("vorg-des/v1\|" + seed + "\|" + i)` (ADR-47); baseline and scenario share them |

No statistic is invented: every KPI below is computed by the engine from those inputs and is reproducible byte for byte.

## The four types

Each has the same window parameters `start_min` and `end_min` (minutes from run start, `[start, end)`, start before the
horizon) and a SIMULATION-labelled builder in the Simulate UI (Digital Twin, Simulation, New scenario, Scenario type).

| Type | Parameters | Model effect |
|---|---|---|
| `demand_surge` | `factor` in (1, 10] | Order arrival rate x `factor` in the window (non-homogeneous Poisson by thinning). Inbound trucks are unchanged: only customer demand surges |
| `forklift_outage` | `count` 1..10 | The highest-numbered `count` forklifts (FL-10 downwards) take no new work in the window; a task in progress finishes |
| `route_blockage` | `zone` (`STORAGE_A`, `STORAGE_B`), `detour_factor` in (1, 10], required | Putaway into and retrieval from the zone that start in the window take `detour_factor` x their layout travel time. A multiplier on travel time: it does not reroute individual forklifts or model congestion |
| `shift_shortage` | `role` (`RECEIVING`, `PICKING`, `PACKING`, `DISPATCH`), `count` 1..headcount | The highest-numbered `count` workers of the role are unavailable in the window; a task in progress finishes |

The API expands a type to the ordinary overlay ([simulation.md](../api/simulation.md)); the engine is unchanged apart from
the one new primitive `route_blockages`.

## Fixtures and reproducing them

Each fixture runs 10 replications, 480 min horizon, 30 min warm-up, seed 20261012, window 120 to 300 min.

| Fixture | Parameters (ASSUMPTION) | `comparison_hash` |
|---|---|---|
| demand surge | `factor` 1.8 | `52e891531ed828681173d447a9a7d64fe8b1bc9e0528b596d4b3bad66561b05b` |
| forklift outage | `count` 4 (FL-07 to FL-10) | `9764143fd30bb43443bd386542a5d2b7736d41c46e6e2aa850d86336c3ea26db` |
| route blockage | `zone` STORAGE_A, `detour_factor` 4 | `a453c7c6f78970ea2afd2713f34f13d8d1895b4ae99c702f404ca496191a7140` |
| shift shortage | `role` PICKING, `count` 6 | `68efcb8966d211592afe6851669667b2a6fd4f6d33c67eef1bd8e8e5260ed9df` |

Reproduce:

```powershell
# engine only: the fixtures are asserted in SignatureScenariosTest (byte-identical twice, KPIs move)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/simulation test "-Dtest=SignatureScenariosTest,MonteCarloTest"
# through the API (ScenarioApiIT style): POST /api/scenarios with the signature, then POST /api/scenarios/{id}/runs
curl -s -X POST http://localhost:8080/api/scenarios -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: lib-rb-1" \
  -H "Content-Type: application/json" -d '{"site_id":"<SITE_BLR>","name":"Route blockage, storage A",
  "run":{"master_seed":20261012,"replications":10,"horizon_min":480,"warmup_min":30},
  "signature":{"type":"route_blockage","params":{"zone":"STORAGE_A","detour_factor":4,"start_min":120,"end_min":300}}}'
```

The catalog with these fixtures is `GET /api/scenario-types`. The engine's `SignatureScenarios.fixture(type)` returns the
same scenarios. Hashes depend on the engine logic version: a change to the model changes them, which is the point.

## Measured effects (10 replications, only KPIs with a significant paired delta)

Values are means over the replications; the delta is scenario minus baseline with its 95% paired-t CI; the verdict is the
direction of the KPI, not a judgement of the situation (more orders shipped under a surge is "better" throughput, not a
better day).

**Demand surge (x1.8, 120 to 300 min):** order cycle time mean 59.36 to 96.79 min (+37.43 [23.96, 50.90], worse); p95
98.71 to 160.31 (+61.60 [41.83, 81.37], worse); open-order backlog at the end 33.80 to 81.70 (+47.90 [27.83, 67.97],
worse); forklift wait 2.53 to 5.76 min (+3.22 [0.69, 5.76], worse); forklift utilization 0.74 to 0.80; picking worker
utilization 0.49 to 0.60; pallets shipped 55.55 to 62.19 per hour (better: more orders arrive).

**Forklift outage (4 forklifts, 120 to 300 min):** forklift request wait 2.53 to 14.97 min (+12.44 [5.73, 19.14], worse);
pallets received 58.00 to 55.08 per hour (-2.92 [-5.84, -0.00], worse); inbound truck dock wait mean 3.70 to 4.49 min
(+0.79 [0.14, 1.45], worse); order cycle time mean 59.36 to 69.46 min (+10.09 [4.31, 15.88], worse); forklift utilization
0.74 to 0.86.

**Route blockage (STORAGE_A, detour x4, 120 to 300 min):** forklift wait 2.53 to 5.46 min (+2.93 [1.02, 4.83], worse);
inbound wait p95 17.35 to 18.36 min (+1.01 [0.10, 1.92], worse); inbound turnaround mean 33.01 to 33.70 min (+0.69 [0.33,
1.04], worse); forklift utilization 0.74 to 0.79. A modest effect: only the share of travel time that depends on one zone
is stretched, and the model's travel time is small next to handling time.

**Shift shortage (6 of 10 pickers, 120 to 300 min):** order cycle time mean 59.36 to 69.42 min (+10.06 [2.21, 17.91],
worse); p95 98.71 to 116.23 (+17.52 [0.61, 34.42], worse); picking utilization 0.49 to 0.65 (+0.16 [0.14, 0.17]);
forklift wait 2.53 to 2.21 min (-0.32 [-0.65, -0.00], better; the mechanism was not analysed here, the interval just touches zero).

## Monte Carlo distributions (REQ-140)

`results.distributions` of a finished run (`vorg.simulation.distributions.v1`, ADR-80) reports, per KPI and for baseline,
scenario and the paired delta: `n`, `mean`, `median`, `p5`, `p95` (linear interpolation, type 7), `min`, `max`, `sd`,
`ci95_low`, `ci95_high`, plus `replication_seeds` and `distributions_hash`. For the route-blockage fixture and forklift
wait (min):

```json
{ "key": "forklift_wait_mean_min",
  "baseline": { "n": 10, "mean": 2.531098, "median": 1.648783, "p5": 0.610022, "p95": 6.793166, "min": 0.191676, "max": 9.38871, "sd": 2.588839, "ci95_low": 0.679282, "ci95_high": 4.382913 },
  "scenario": { "n": 10, "mean": 5.459064, "median": 3.613615, "p5": 0.862049, "p95": 12.928264, "min": 0.283924, "max": 15.970137, "sd": 4.849936, "ci95_low": 1.98987, "ci95_high": 8.928258 },
  "delta":    { "n": 10, "mean": 2.927967, "median": 2.302128, "p5": 0.233519, "p95": 6.635222, "min": 0.092248, "max": 6.679236, "sd": 2.663726, "ci95_low": 1.022585, "ci95_high": 4.833349 } }
```

The replication seeds are `[-8127220021944049344, 5596611154391504892, -5317940507958540015, -8989746568709310085,
-1031878200589473123, 7926449431305345254, 3438600858154905291, -1491953482874987409, -656932454645619231,
4625164038317720971]`; `distributions_hash` of this fixture is
`5fea0d16245917b14870bab25df1af6c68a25db126d3612f4b3952600e2a28a2`. The scenario beats the baseline in every one of the 10
paired seeds here (paired `min` > 0), which the 5-95 band of the delta also shows.

How to read it: p5 and p95 of 10 values are interpolated between the lowest two and highest two, so they describe this
sample, not a tight estimate of the population tails; use more replications (up to 200) for tails. The 95% CI is of the
mean, not of a single day.

In the UI: the compare view's "Distribution over seeded replications" table (median with the p5 to p95 band for baseline
and scenario, the paired delta mean with its CI, a filter for KPIs whose paired band excludes zero) and a small band plot
per row that repeats what the cells say.

## Not done (future work)

- The recommendation generator (Phase 8) still reads only `dock_closures`: none of the four types has a dock or forklift
  re-assignment lever for the OR-Tools model. A forklift-outage-aware re-balance is the natural first step (ADR-79).
- Route blockage is a travel-time multiplier, not a lane-graph edge removal.
- Scenario types are not stored on the scenario record (the overlay is the truth); the list shows the overlay summary.
