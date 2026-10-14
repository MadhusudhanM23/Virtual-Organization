# Plan 7.3 — Camera registry, observation ingestion, incident evidence, camera panel

Working plan (brief). Source: REQ-080, REQ-083, REQ-084, REQ-085, REQ-086; BLUEPRINT §7, §10, §11, §16, §18;
ADR-02/03/27/32. New: ADR-65 (privacy), ADR-66 (service credential, identity association), Proposed.

## API (`io.vorg.api.cameras`)

- Migration `V7_3_01__cameras.sql`, schema `cam`, forced RLS (`tenant_isolation`), like `V4_1_01`:
  `cam.cameras` (site, floor, linked CAMERA twin entity, code, pose x/y/z/heading, `status`
  `ACTIVE`/`PLACEHOLDER`, calibration jsonb, class_map jsonb, media name/kind, version) and `cam.observations`
  (one row per accepted observation, linked entity, position event id, retention-pruned).
- Seed (local/demo only, not a migration): CAM-01 (synthetic scene, Receiving) and CAM-03 (stock clip, Storage A)
  calibrated and ACTIVE; CAM-02/04 PLACEHOLDER without calibration.
- Queries: `GET /api/cameras?site_id=`, `GET /api/cameras/{id}`, `GET /api/cameras/{id}/observations?from&to`,
  `GET /api/cameras/{id}/media` (local file, audited; REQ-084).
- Service path (ADR-66): `Authorization: Service <client_id>:<secret>` only on `/api/vision/**`;
  `GET /api/vision/cameras/{id}` (calibration), `POST /api/vision/cameras/{id}/observations` (command,
  Idempotency-Key, gateway, audit actor type SERVICE).
- Ingestion: validate, store, associate STABLE mapped tracks of mapped classes with a derived twin entity
  (`V-<camera>-T<track>`, provisional), feed `ObservationIngestService` with `source=CAMERA`, the batch's data mode
  and `observation_status=DETECTED`, plus one `CAMERA_OBSERVATIONS_RECORDED` event per batch. The existing proximity
  rule then sees CAMERA worker/forklift poses; the incident gets a `CAMERA_REFERENCE` with the camera time range.
- Events in CATALOG.md first. Verify: `mvnw.ps1 -B -q -pl services/api -am install -DskipTests`.

## Web (`apps/web/src/features/cameras`)

Camera list, panel with the video (or the synthetic still) and canvas overlays of observations at the current media
time, truth label (data mode + source), privacy notice, link from incident evidence (`CAMERA_REFERENCE`) to the
camera at the incident time. i18n in `messages.en.ts`. Verify `npm run typecheck`, `npm run lint`.

## Docs

`docs/api/vision.md`, README catalog entry, `services/vision/README.md`.
