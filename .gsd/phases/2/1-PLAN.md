---
phase: 2
plan: 1
wave: 1
requirements: [REQ-020, REQ-021, REQ-022, REQ-023]
---

# Plan 2.1: World schema, versions, canonical ID mapping, coordinate system (working doc)

Scope: `packages/twin-schema` (TS) + Flyway `V2_1_*` + `io.vorg.api.world` canonical-id service. Short working doc.

## Tasks
1. `packages/twin-schema` workspace: site-local coordinate types, world<->renderer conversion, heading + bbox helpers, tests.
2. World model contract in twin-schema: TS types, entity/zone enums, truth-label mapping (ADR-27), JSON Schema for
   world/entity responses.
3. Migration `V2_1_01__world_model.sql`: buildings, floors, zones, docks, storage_locations (racks), entities
   (pose + provenance + confidence + version), world_versions; RLS on every table; tenant isolation test.
4. Canonical ID mapping: `world.id_mappings` + `CanonicalIdService` (register idempotent, resolve) + IT.

## Verify
`npm run lint|typecheck|test -w @vo/twin-schema`; `scripts/mvnw.ps1 -B verify`.
