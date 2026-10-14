# Plan 7.2 — `services/vision` pipeline (detect, track, calibrate, observe) + tests

Working plan (brief). Source: REQ-080, REQ-081, REQ-082, REQ-083, REQ-084; BLUEPRINT §10, §11, §18; ADR-64, ADR-65.
Depends on 7.1 (assets). Python 3.12 in containers, 3.14 on the host; pytest + ruff (ADR-15).

## Package `vorg_vision` (`services/vision/src`)

| Module | Responsibility |
|---|---|
| `detector` | YOLOX ONNX on onnxruntime CPU: letterbox (pad 114), grid decode (strides 8/16/32), class-wise NMS |
| `tracker` | IoU tracker, ByteTrack-style two-stage association (high, then low scores), damped constant velocity, TENTATIVE / CONFIRMED / LOST, identity `PROVISIONAL` until `stable_hits`, ids never reused, deterministic |
| `homography` | normalised DLT from at least 4 control points (pixel to site floor meters), RMS residual (None for an exact 4-point fit), calibrated zone = convex hull; no extrapolation outside it |
| `observations` | whitelisted, privacy-safe record per sampled confirmed track; speed/heading from floor history (site heading convention) |
| `synthetic` | deterministic OpenCV scene (forklift + worker) with ground truth; the SYNTHETIC fallback and proximity demo |
| `pipeline` | frames -> detect -> track -> floor -> batches; measured fps |
| `api_client` | service-credential HTTP client, deterministic Idempotency-Key per batch, retry on 5xx |
| `cli` | `run`, `bench`, `synth`, `verify-assets` |

Observation (wire, snake_case): `observation_id` (`<session>:<track>:<frame>`), `track_id`, `object_class`,
`identity_status`, `confidence`, `observed_at` (session start + media time), `media_time_s`, `frame_index`, `bbox`
(normalised x, y, w, h), `spatial_status` (`MAPPED` / `OUTSIDE_CALIBRATED_ZONE` / `UNCALIBRATED`),
`floor_position {x, y, z}`, `speed_mps`, `heading_deg`. Nothing else, enforced by `assert_privacy_safe`.

## Tasks

1. Package, `pyproject.toml` (ruff config), unit tests (fast, no network, no model): homography with a known map,
   residuals, degenerate input, hull; tracker confirmation, provisional to stable, class separation, low-score
   recovery, max age, velocity bridging, determinism; observation mapping, clamping, speed, privacy guard; YOLOX
   decode/NMS on synthetic tensors; asset manifest; synthetic scene end to end through the pipeline.
2. `services/vision/README.md` (how to run), calibration files under `services/vision/config/`.

Verify: `python -m pytest` and `python -m ruff check .` in `services/vision` (venv).
