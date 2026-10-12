# Plan 6.2 - Warehouse flow model + KPIs (Bengaluru Warehouse 01, dock closure)

Requirements: REQ-071, REQ-072, REQ-073 (library side), REQ-076, NFR-06. ADRs: ADR-47, ADR-48, ADR-49.

## Tasks (one commit each, `feat(phase-6): ...`, each after a green `mvnw -B verify`)

1. **Model input, baseline and overlay** - `warehouse` model input (`vorg.simulation.model-input.v1`, `model_version`):
   layout (6 docks, zones, racks/positions), resources (10 forklifts, 30 workers by role), initial stock, demand and
   every parameter as a labeled ASSUMPTION; the Bengaluru Warehouse 01 baseline as a classpath resource (numbers copied
   from the world seed, no dependency on `services/api`); scenario (`vorg.simulation.scenario.v1`) with overlay:
   dock closures, forklift outages, worker reductions, demand multiplier, parameter overrides; validation + input hash.
2. **Flow model and KPIs** - trucks -> IN docks -> forklift unloading -> receiving staging -> putaway -> storage ->
   order retrieval -> picking -> packing -> dispatch staging -> OUT docks -> loading -> departure; KPIs (throughput,
   dock/forklift/worker utilization, truck wait/turnaround, order cycle time, backlog, SLA misses), conservation ledger,
   trajectory samples; replication runner (mean, sd, 95% CI) and paired baseline-vs-scenario comparison; results JSON
   (`data_mode SIMULATED`, `lens SIMULATE`). Tests: conservation, determinism (trace + results bytes), different seeds,
   warm-up, dock closure worse waits than baseline (CRN), overlay validation, performance (8 h x 10 replications, logged).
3. **CLI and documentation** - `io.vorg.simulation.cli.SimulationCli` (`java -jar` / `-cp`): scenario JSON in, results
   JSON out, optional baseline comparison; example scenarios (baseline, dock closure); `services/simulation/README.md`
   (API, determinism contract, assumptions table) and `docs/scenarios/dock-closure.md` (scenario, reproduce, real
   baseline-vs-closure numbers from a CLI run, labeled SIMULATED).
