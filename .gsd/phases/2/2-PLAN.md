---
phase: 2
plan: 2
wave: 2
depends_on: [2.1]
requirements: [REQ-024, REQ-025]
---

# Plan 2.2: Deterministic SYNTHETIC seed + world/entity query APIs (working doc)

## Tasks
5. Pure deterministic seed plan "Bengaluru Warehouse 01" (fixed namespace UUIDs, SplittableRandom seed, content hash) + determinism test.
6. `WorldSeeder` (local/demo/test profiles, idempotent, tenant-scoped, runs after DemoSeeder) + IT.
7. Query APIs (tenant+scope authorized via PolicyService VIEW_OPERATIONAL_STATE): world, version, floors, zones, docks,
   racks, entities (type/floor/zone/bbox, keyset pagination), entity detail + IT.
8. Docs: `docs/scenarios/bengaluru-warehouse-01.md`, `docs/api/README.md` endpoint summary, Proposed ADRs.

## Verify
`scripts/mvnw.ps1 -B verify` (Testcontainers), `npm test`.
