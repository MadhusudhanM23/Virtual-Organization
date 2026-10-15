# Plan 13.2 - Monte Carlo multi-seed distributions

Source: REQ-140; BLUEPRINT section 8 ("Monte Carlo"); ADR-47 (seeds), ADR-80 (Proposed, this plan).
Owns `services/simulation` (stats part).

## Shape

`Distributions`-style report over the N replications (seed set = `StreamSeeds.replicationSeed(master, i)`, or an
explicit list via `SimulationRunner.runSeeds`). For every KPI and for baseline and scenario: `n`, `mean`, `median`,
`p5`, `p95` (linear-interpolated, type 7), `min`, `max`, `sd`, `ci95_low/high` (Student-t of the mean), and the
paired-delta distribution. Output schema `vorg.simulation.distributions.v1`, with `seeds`, a `distributions_hash`.
Deterministic: same seed set, same bytes. Undefined KPIs (NaN) are excluded and `n` says how many were defined.

## Tasks

1. `Quantiles` helper + `MonteCarlo.of(Comparison, ...)` report + JSON.
2. Tests: quantile edge cases, determinism, explicit seed set equals master-seed derivation, NaN handling.
