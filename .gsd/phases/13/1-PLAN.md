# Plan 13.1 - Signature scenarios in the DES engine

Source: REQ-140; BLUEPRINT section 8; ADR-47/48/49 (engine, overlay, comparison), ADR-79 (Proposed).
Owns `services/simulation` (scenario part).

## Shape

The engine already has generic overlay primitives (`dock_closures`, `forklift_outages`, `worker_reductions`,
`demand_multiplier`). Phase 13 adds one primitive and a typed layer on top:

- **New primitive `route_blockages`**: `{zone, start_min, end_min, detour_factor, reason}`. While a storage zone's route
  is blocked, every forklift putaway into / retrieval from that zone that *starts* in the window takes
  `detour_factor` times its layout travel time. `zone` must be a storage zone of the model, `detour_factor` is
  required, in (1, 10] (no default: the caller states the detour, nothing is invented).
- **`SignatureScenarios`**: four typed scenario types (`demand_surge`, `forklift_outage`, `route_blockage`,
  `shift_shortage`), each with a documented parameter list, validation (path-style errors like the rest of the engine),
  an `expand(type, params, model, run)` to an overlay map, and a deterministic **fixture** (fixed seed, fixed
  parameters, labelled ASSUMPTION) used by tests, docs and the UI catalog.

## Tasks

1. Overlay `route_blockages` + model travel factor + validation + `toMap` + affected entities (zone ids).
2. `SignatureScenarios` (types, params, validation, expansion, fixtures, catalog map) + unit and determinism tests.
3. Engine README section.

Never a fabricated number: model inputs come from the seeded world; fixture parameters are scenario inputs, labelled.
