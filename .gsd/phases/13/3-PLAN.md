# Plan 13.3 - Scenario API: signature types, catalog, distributions

Source: REQ-140, docs/api/simulation.md. Owns `services/api/.../scenarios`.

- `POST /api/scenarios` accepts optional `signature: {type, params}` (mutually exclusive with `overlay`); the engine
  expands it, so a bad parameter is a 400 with the field path `signature.params.<name>`.
- `GET /api/scenario-types` returns the catalog (parameters, bounds, fixture, provenance).
- A finished run's `results` gains `distributions` (Monte Carlo report) next to the comparison; the comparison hash is
  unchanged.
- Focused ITs (`SignatureScenarioApiIT`); docs updated.
- Recommendation generator: consumption documented as future work unless it proves small and safe (ADR-81).
