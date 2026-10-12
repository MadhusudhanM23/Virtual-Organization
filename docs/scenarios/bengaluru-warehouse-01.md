# Bengaluru Warehouse 01 (SYNTHETIC world seed)

> **Provenance:** every row described here is **SYNTHETIC** generated test data (`data_mode = SYNTHETIC`, pose source
> `SYSTEM`). Nothing is read from a real warehouse, WMS, TMS, vision system or sensor. Workers are pseudonymous
> (`W-001` ... `W-030`); there is no personal data. Cameras are placeholders: no stream, calibration or field of view
> is invented.

Supports MCD steps 2-3 (blueprint section 22: navigate into a simulated Bengaluru warehouse; explore the 3D twin with
forklifts, workers, pallets and docks) and requirement REQ-024. The data is the starting world for Phase 3 (live
movement) and Phase 6 (dock-closure scenario).

## How it is produced

| Item | Value |
|---|---|
| Producer | `io.vorg.api.world.seed.WorldSeeder` (Spring `ApplicationRunner`, order 2, after `DemoSeeder`) |
| Plan | `BengaluruWarehouse01.build(tenantId, siteId)`: a pure function, no database, clock or environment |
| Profiles | `local`, `demo`, `test` only. Never a Flyway migration |
| Tenant / site | tenant `marutiflex-demo` (A), site "Bengaluru Warehouse 01" (`DemoIds.SITE_BLR`) |
| Seed version | namespace `vorg-seed/bengaluru-warehouse-01/v1`, RNG seed `20261009` |
| IDs | name-based, version-7-shaped UUIDs: SHA-256 of `namespace/site_id/kind/name` (`SeedIds`) |
| Randomness | `java.util.SplittableRandom` (algorithm fixed by the JDK specification), one independent stream per category |
| Timestamps | all seeded `occurred_at` / `created_at` = `2026-10-09T00:00:00Z` |
| Content hash | SHA-256 over every seeded value, stored in `world.world_versions` (version 1) and returned by the API |
| Idempotency | fixed ids + `on conflict do nothing`, one transaction; a site that already has world version 1 is never touched (a differing hash is logged, not overwritten) |

Determinism is tested in `WorldSeedPlanTest` (same plan twice, pinned content hash, independent of the default locale,
layout invariants such as no overlapping racks) and `WorldSeederIT` (database counts, idempotent re-run, tenant A only,
everything `SYNTHETIC`). The pinned hash must only change together with a new seed version (new namespace and label) and
an update of this page.

## Reproduce

```powershell
# Tests (needs Docker Desktop for Testcontainers)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify "-Dit.test=WorldSeederIT,TwinQueryIT"

# Local run: the seeder runs on startup in the local profile (the demo password is required by DemoSeeder)
$env:DEMO_USER_PASSWORD = '<choose a local password>'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/api spring-boot:run "-Dspring-boot.run.profiles=local"
```

Then `POST /api/auth/login` as `wm.blr@demo.invalid` (tenant `marutiflex-demo`) and
`GET /api/twin/world?site_id=018f0000-0000-7000-8000-00000000a021`. Resetting the demo database and restarting
reproduces byte-identical rows (same ids, coordinates and content hash).

## Layout

Site-local frame, meters (see [ARCHITECTURE](../../.gsd/ARCHITECTURE.md), "Coordinates"): origin = south-west corner of
the ground floor, +x east, +y north, +z up; heading in degrees, 0 = east, counter-clockwise positive. `z` is an absolute
elevation.

Building "Main Warehouse Building", two floors:

| Floor | Level | Slab elevation | Bounds (min_x, min_y, max_x, max_y) |
|---|---|---|---|
| Ground floor | 0 | 0 m | 0, 0, 120, 80 |
| Mezzanine | 1 | 5 m | 0, 0, 60, 40 |

Zones (7, covering all five required types):

| Zone | Type | Floor | Bounds |
|---|---|---|---|
| Receiving | RECEIVING | ground | 0, 0, 60, 16 |
| Packing | PACKING | ground | 60, 0, 120, 16 |
| Storage A | STORAGE | ground | 0, 16, 60, 64 |
| Storage B | STORAGE | ground | 60, 16, 120, 64 |
| Picking | PICKING | ground | 0, 64, 60, 80 |
| Dispatch | DISPATCH | ground | 60, 64, 120, 80 |
| Mezzanine Small Parts | STORAGE | mezzanine | 0, 0, 60, 40 |

Docks (6, width 3.5 m, z 0): inbound `IN-01..03` on the south wall (y = 0, x = 12, 27, 42, heading 90), outbound
`OUT-01..03` on the north wall (y = 80, x = 72, 90, 108, heading 270). `IN-01`, `IN-02`, `OUT-01` and `OUT-03` are
`OCCUPIED` (a truck is docked); `IN-03` and `OUT-02` are `AVAILABLE`.

Racks (78, storage locations of kind `RACK`, 9.0 m long x 1.2 m deep, running along x): Storage A `A-rr-cc` and
Storage B `B-rr-cc` are 7 rows x 5 columns each (6.0 m high, 4 levels); the mezzanine `M-rr-cc` is 2 rows x 4 columns
(3.0 m high, 2 levels). Forklift aisles run between the rack rows.

## Entities (170)

| Type | Count | Codes | Placement and status |
|---|---|---|---|
| FORKLIFT | 10 | `FL-01..10` | Aisles of Storage A/B (alternating), heading 0 or 180; status IDLE / MOVING / LOADING |
| WORKER | 30 | `W-001..030` | Picking 10, Packing 8, Receiving 5, Dispatch 4, Storage A aisles 3; status `ON_SHIFT`; pseudonymous |
| TRUCK | 6 | `TR-01..06` | 4 `DOCKED` at IN-01, IN-02, OUT-01, OUT-03 (just outside the wall), 2 `WAITING` in the yards |
| PALLET | 120 | `PAL-0001..0120` | 80 `STORED` in rack slots (level and slot in `attributes`), 20 `STAGED` in Receiving, 20 `STAGED` in Dispatch |
| CAMERA | 4 | `CAM-01..04` | Placeholders (`attributes.placeholder = true`, stream and calibration `null`); CAM-04 is on the mezzanine |

No ROBOT entities are seeded (the type exists in the schema). Every pose has `spatial_confidence = 1.0`: the position is
exact by construction because the platform itself placed it (it is not a measurement).

## Canonical ID mappings (132)

Seeded through `CanonicalIdService` (REQ-021) and labelled `SYNTHETIC`; they stand in for the Simulated WMS/TMS adapter
of Phase 3, which will label its own records `SIMULATED`:

| source_system | source_record_id | canonical |
|---|---|---|
| `SIM_WMS` | `LPN-100001 .. LPN-100120` | the 120 pallets |
| `SIM_TMS` | `TRIP-00001 .. TRIP-00006` | the 6 trucks |
| `SIM_TMS` | `DOOR-IN-01 .. DOOR-OUT-03` | the 6 docks |

Tenant B ("Pune Warehouse 01") deliberately has **no** world model, so isolation tests show a 404 rather than another
tenant's data.
