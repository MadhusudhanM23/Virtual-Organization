# services/vision — Phase 7 vision pipeline

Recorded video (or a deterministic synthetic scene) → **YOLOX** detection (Apache-2.0, ONNX on onnxruntime CPU) →
IoU/ByteTrack-style **tracking** (own code) → **homography** to site floor meters → **privacy-safe observations**
posted to the API (`docs/api/vision.md`). Decisions: ADR-67 (licensing, classes), ADR-68 (privacy), ADR-69
(service credential, identity association). Plans: `.gsd/phases/7/`.

Computer vision here is a perception layer for monitoring and advisory analysis only, **not a safety-rated
sensor** (BLUEPRINT section 11).

## Setup (host: Python 3.14; containers: 3.12)

```powershell
cd services/vision
python -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
powershell -NoProfile -ExecutionPolicy Bypass -File ..\..\scripts\fetch-vision-assets.ps1   # weights + clip, SHA-256 checked
.venv\Scripts\python.exe -m vorg_vision synth --background --out assets/synthetic-cam-01.png   # CAM-01 still
```

Nothing in `assets/` is committed. Provenance: `docs/scenarios/vision-demo-clip.md`.

## Tests and lint (fast, no network, no model needed)

```powershell
.venv\Scripts\python.exe -m pytest
.venv\Scripts\python.exe -m ruff check .
```

## Run against the API

The API must run with the `local` (or `demo`) profile, which seeds the camera registry, and with the ingestion
secret configured (`VO_VISION_INGEST_SECRET`, same value on both sides; never committed). Camera ids come from
`GET /api/cameras?site_id=<site>` (as a signed-in user).

```powershell
$env:VO_VISION_INGEST_SECRET = "<the same secret the API was started with>"
# CAM-03: stock clip, worker observations, data_mode SIMULATED (recorded clip as a simulated feed)
.venv\Scripts\python.exe -m vorg_vision run --camera-id <CAM-03 id> --clip assets/pexels-4280746-1920x1080.mp4 `
    --model assets/yolox_s.onnx --media-name pexels-4280746-1920x1080.mp4
# CAM-01: synthetic forklift + worker, data_mode SYNTHETIC; raises a proximity alert and an incident
.venv\Scripts\python.exe -m vorg_vision run --camera-id <CAM-01 id> --synthetic --media-name synthetic-cam-01.png --realtime
```

Options: `--dry-run --out batches.ndjson` (no API call; with `--calibration config/<file>.json` for the floor
mapping), `--sample-every N` (default 5: 5 Hz at 25 fps), `--model assets/yolox_nano.onnx` (faster),
`--realtime` (post each batch when its observation time is reached, so the live twin moves at clip speed).

The calibration comes from the camera registry (`GET /api/vision/cameras/{id}`); `config/` holds the same control
points for offline runs.

## Measure throughput

```powershell
.venv\Scripts\python.exe -m vorg_vision bench --clip assets/pexels-4280746-1920x1080.mp4 `
    --model assets/yolox_nano.onnx --model assets/yolox_s.onnx
```

Measured figures (and the machine) are recorded in `.gsd/phases/7/1-PLAN.md`; they vary with machine load.

## What an observation contains

`observation_id`, `track_id`, `object_class`, `identity_status` (PROVISIONAL until the track is stable),
`confidence`, `observed_at`, `media_time_s`, `frame_index`, `bbox` (normalised 0..1), `spatial_status`,
`floor_position {x, y, z}` (site meters, only inside the calibrated zone), `speed_mps`, `heading_deg`.
Never frames, crops, faces, embeddings or other biometric data (`assert_privacy_safe` enforces the whitelist).

## Layout

| Path | |
|---|---|
| `src/vorg_vision/detector.py` | YOLOX ONNX pre/post-processing |
| `src/vorg_vision/tracker.py` | IoU tracker, two-stage (high/low score) association |
| `src/vorg_vision/homography.py` | DLT homography, residual, calibrated zone |
| `src/vorg_vision/observations.py` | observation records, speed/heading, privacy guard |
| `src/vorg_vision/synthetic.py` | deterministic synthetic scene with ground truth |
| `src/vorg_vision/pipeline.py`, `cli.py`, `api_client.py` | runner, CLI, HTTP client |
| `config/` | calibration control points (also seeded into the registry) |
| `assets.sha256` | expected hashes of the downloaded assets |
