# Plan 6.1 - `services/simulation` DES engine core + determinism tests

Requirements: REQ-072, REQ-076, NFR-06. ADRs: ADR-04, ADR-22; new Proposed ADR-47 (engine contract), ADR-48/49
(recorded now, used by Plan 6.2).

## Tasks (one commit each, `feat(phase-6): ...`, each after a green `mvnw -B verify`)

1. **Engine core and decisions** - Maven module `services/simulation` (`vorg-simulation`, Java 21, no runtime
   dependencies, registered in the root pom before `services/api`); `engine` (SimTime in ms, SimClock, EventQueue with
   total order `(time, priority, sequence)`, Simulation run control with end time + warm-up reset, EventTrace SHA-256),
   `random` (named L64X128MixRandom streams, `sha256-name-v1` seed derivation, replication seeds, StrictMath
   distributions); ADR-47..49; this plan and 2-PLAN. Tests: ordering/ties, run control, warm-up, trace hash, stream
   independence/reproducibility, golden values, distribution moments.
2. **Resources, statistics, JSON** - `resource` (UnitPool: named units, priority-FIFO queue, lowest-index choice,
   non-preemptive downtime, busy/idle/down accounting; Store: FIFO buffer with FIFO takers), `stats` (Tally,
   TimeWeighted, Counter, ReplicationSummary with Student-t 95% CI), `json` (canonical writer + parser). Tests:
   hand-checked M/D/1-style queue cases, priorities, downtime, store hand-over, warm-up resets, CI values, JSON
   canonical bytes.

Out of scope: the warehouse model (6.2), APIs and persistence (6.3), UI (6.4).
