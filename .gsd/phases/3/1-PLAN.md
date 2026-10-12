---
phase: 3
plan: 1
wave: 1
requirements: [REQ-040, REQ-041]
---

# Plan 3.1: SourceAdapter SPI, Simulated WMS/TMS adapter, mirrored integration data (working doc)

Scope: `io.vorg.api.integration.core` (SPI, mirror service, reconciliation) + `integration.simulated` (adapters) + Flyway `V3_1_*`.

## Tasks
1. Migration `V3_1_01__integration_mirrors.sql` (integ.mirror_records, mirror_revisions, findings; RLS) + `SourceAdapter` SPI,
   `MirrorService` (versioned, never-silently-overwritten ingest; stale/duplicate/data-quality findings + events) and the
   deterministic `SimulatedWmsAdapter` (SIM-WMS) / `SimulatedTmsAdapter` (SIM-TMS) with unit + IT tests.
2. Sync runner (`IntegrationSyncService`, optional scheduler) and read APIs `GET /api/integration/records|findings` + IT.

## Verify
`scripts/mvnw.ps1 -B verify`.
