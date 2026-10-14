# Vision: camera registry and observation ingestion (Phase 7, plan 7.3)

Recorded or synthetic camera feeds become **observations** (BLUEPRINT sections 10–11, REQ-080..086). Computer vision is
a perception layer, not an authority: an observation is a detection with a confidence, never business truth, and never
a safety-rated signal. Decisions: ADR-64 (model, licensing, class coverage), ADR-65 (privacy), ADR-66 (service
credential, derived identity). Pipeline: [`services/vision/README.md`](../../services/vision/README.md). Assets and
provenance: [`docs/scenarios/vision-demo-clip.md`](../scenarios/vision-demo-clip.md).

## Camera registry (people; bearer token)

All endpoints need `VIEW_OPERATIONAL_STATE` on the camera's site (unknown or other-tenant camera `404`, out of scope
`403`; denials audited).

| Endpoint | Parameters | Response |
|---|---|---|
| `GET /api/cameras` | `site_id` (required) | `{site_id, items: [camera]}` |
| `GET /api/cameras/{id}` | | `camera` |
| `GET /api/cameras/{id}/observations` | `session_id` (default: latest), `from_s`, `to_s` (media seconds), `limit` (1–5000, default 2000) | `{camera_id, camera_code, session_id, sessions[], items[], truncated, data_mode, source: "CAMERA"}` |
| `GET /api/cameras/{id}/media` | | the camera's registered local media (`video/mp4` or `image/png`), byte ranges supported; **every access writes an audit row** (`VIEW_CAMERA_MEDIA`); `404 media_not_available` when the file is not present |

`camera`:

```json
{
  "camera_id": "…", "site_id": "…", "entity_id": "…(CAMERA twin entity)", "code": "CAM-03", "name": "Camera CAM-03",
  "floor_id": "…", "zone_id": "…", "status": "ACTIVE | PLACEHOLDER | DISABLED",
  "pose": {"x": 30.0, "y": 40.0, "z": 8.0, "heading_deg": 0.0},
  "calibrated": true, "control_points": 6,
  "calibration": {"image_width": 1920, "image_height": 1080, "floor_z_m": 0.0, "method": "MANUAL_DEMO", "note": "…",
                  "points": [{"u": 650, "v": 250, "x": 31.0, "y": 39.2}]},
  "class_map": {"person": "WORKER"},
  "feed": {"kind": "RECORDED | SYNTHETIC | NONE", "media_name": "pexels-4280746-1920x1080.mp4",
           "media_kind": "VIDEO | STILL", "media_href": "/api/cameras/{id}/media"},
  "data_mode": "SIMULATED", "source": "CAMERA",
  "privacy": {"frames_stored": false, "faces_stored": false, "biometrics_stored": false, "retention_days": 7,
              "notice": "…"},
  "last_observation_at": "2026-10-14T10:55:47.120Z", "version": 1, "updated_at": "…"
}
```

Observation item: `{observation_id, track_id, object_class, identity_status, confidence, observed_at, media_time_s,
frame_index, bbox {x, y, w, h} (normalised 0..1, origin top-left), spatial_status, floor_position? {x, y, z},
speed_mps?, heading_deg?, entity_id?, entity_code?}`. `entity_*` is set when the track was linked to a derived twin
entity (ADR-66). `sessions[]`: `{session_id, first_observed_at, last_observed_at, count, detector}` (latest 20).

Seeded demo registry (local/demo/test profiles, `CameraSeeder`, SYNTHETIC): CAM-01 (Receiving, synthetic scene,
`SYNTHETIC`, maps `person -> WORKER`, `forklift -> FORKLIFT`), CAM-03 (Storage A, Pexels clip 4280746, `SIMULATED`,
maps `person -> WORKER`), CAM-02 and CAM-04 `PLACEHOLDER` (no calibration, no feed).

## Vision service (machine; `Authorization: Service <client_id>:<secret>`)

Only these paths accept the service credential, and they accept nothing else (a user token is `401`). The secret is
configured on both sides from `VO_VISION_INGEST_SECRET` (local profile client `svc-vision-local`, scope
`vision:ingest`).

| Endpoint | Response |
|---|---|
| `GET /api/vision/cameras/{id}` | `{camera_id, site_id, code, status, calibration, class_map, data_mode, feed_kind, media_name}` |
| `POST /api/vision/cameras/{id}/observations` | command ack (`200`): `{command_id, status: "SUCCEEDED", correlation_id, result}` |

**Command** `VISION_OBSERVATIONS_INGEST`: `Idempotency-Key` required (the CLI sends
`vision:<camera_id>:<session_id>:<batch_seq>`); a replay with the same key returns the same ack, a different body with
the same key is `409`. Request (values illustrative):

```json
{
  "session_id": "S20261014T105535Z", "batch_seq": 1, "data_mode": "SIMULATED",
  "detector": "yolox:yolox_s", "pipeline_version": "0.7.0",
  "media": {"name": "pexels-4280746-1920x1080.mp4", "frame_width": 1920, "frame_height": 1080, "fps": 25.0},
  "session_started_at": "2026-10-14T10:55:35.000Z",
  "observations": [{
    "observation_id": "S20261014T105535Z:4:61", "track_id": 4, "object_class": "person",
    "identity_status": "STABLE", "confidence": 0.861, "observed_at": "2026-10-14T10:55:37.440Z",
    "media_time_s": 2.44, "frame_index": 61, "bbox": {"x": 0.52, "y": 0.31, "w": 0.06, "h": 0.17},
    "spatial_status": "MAPPED", "floor_position": {"x": 34.82, "y": 37.11, "z": 0.0},
    "speed_mps": 1.12, "heading_deg": 272.4
  }]
}
```

`result`: `{camera_id, camera_code, session_id, batch_seq, received, accepted, duplicates, linked, position_events,
linked_entities[], event_id?}`.

Rules (ADR-65/66):
- **Whitelist**: any other field anywhere in the body is `400` (privacy guard: no crops, faces, embeddings).
- `data_mode` must be `SIMULATED`, `SYNTHETIC` or `REPLAY` (`LIVE` is `400`) and equal the camera's registered data
  mode (`422 data_mode_mismatch`); `media.name` must be the camera's media (`422 media_mismatch`); the camera must be
  `ACTIVE` (`409 camera_not_active`); at most `vo.vision.max-batch` (500) observations; `observation_id` must start
  with `session_id:`; `floor_position` only and always with `spatial_status=MAPPED`.
- `(camera, observation_id)` is stored once; re-sent observations count as `duplicates`.
- Linking: a `STABLE`, `MAPPED` observation of a class in the camera's `class_map` moves the derived entity
  `V-<camera>-T<track>` (status `PROVISIONAL`) through the shared observation ingestion: one
  `<TYPE>_POSITION_UPDATED` event per frame with `source=CAMERA`, the batch data mode, `observation_status=DETECTED`,
  `confidence` = detection score and `payload.camera {…}` provenance. The worker/forklift proximity rule and the
  incident path consume these events unchanged (REQ-086); incidents then carry a `CAMERA_REFERENCE` evidence item with
  `role: OBSERVING_CAMERA`, `camera_id`, `session_id`, `track_id`, `media_time_s`, `window {from_s, to_s}` (±5 s) and
  `clip.href` = the camera's media.
- Every batch with new observations records one `CAMERA_OBSERVATIONS_RECORDED` event (catalog).

## Retention and audit

Observations are deleted after the camera's `retention_days` (`CameraRetentionJob`, `vo.vision.prune-interval`).
Command records and audit rows of the service carry actor type `SERVICE`, actor `svc:<client_id>`.

## Run it locally

1. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-vision-assets.ps1`, then
   `services/vision/.venv/Scripts/python.exe -m vorg_vision synth --background --out services/vision/assets/synthetic-cam-01.png`.
2. Start the API with the `local` profile and `VO_VISION_INGEST_SECRET` set (24+ characters). Media is served from
   `../vision/assets` relative to `services/api` (override with `VO_VISION_MEDIA_DIR`).
3. Look up camera ids with `GET /api/cameras?site_id=<Bengaluru site>` and run the CLI
   ([`services/vision/README.md`](../../services/vision/README.md)).
4. Open the web app, **Cameras** view: the clip with overlays; the synthetic run raises a proximity incident whose
   evidence links back to CAM-01 at the incident time.
