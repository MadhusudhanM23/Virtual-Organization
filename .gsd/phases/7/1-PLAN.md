# Plan 7.1 — Research spike: model and licensing, demo clip, CPU budget

Working plan (brief), recording the findings. Source: REQ-080, REQ-081, REQ-087; BLUEPRINT §11, §19; ADR-25.
Owner decision OD-6: Apache-2.0 / MIT components only; a royalty-free stock clip is approved. New: ADR-64 (Proposed).

## Findings

**Detector: YOLOX (Megvii, Apache-2.0)**, the official COCO-80 ONNX weights from the YOLOX GitHub release
`0.1.1rc0`, run with **onnxruntime (MIT)** on the `CPUExecutionProvider`. OpenCV (Apache-2.0) decodes the video,
NumPy (BSD-3) does the rest. No Ultralytics, no AGPL code, no third-party tracker code: the IoU/ByteTrack-style
tracker is written in `services/vision`.

| Asset | Source | License | SHA-256 |
|---|---|---|---|
| `yolox_s.onnx` (35.9 MB, input 640x640) | github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx | Apache-2.0 | `c5c2d13e…998063` |
| `yolox_nano.onnx` (3.7 MB, input 416x416) | …/0.1.1rc0/yolox_nano.onnx | Apache-2.0 | `c789161e…a0b7d` |
| `pexels-4280746-1920x1080.mp4` (12.2 s, 25 fps, 305 frames) | pexels.com/video/4280746 by Tiger Lily | Pexels License | `cfaa552f…3062e9a` |

Full hashes and provenance: `services/vision/assets.sha256`, `docs/scenarios/vision-demo-clip.md`. Assets are
downloaded by `scripts/fetch-vision-assets.ps1` / `.sh` into `services/vision/assets/` (gitignored), never committed.

**Clip choice.** Of the three Pexels clips examined, two were edited, moving-camera footage (cuts and pans), which
breaks a fixed floor homography. Clip 4280746 is a high, roughly overhead shot of a warehouse aisle with workers
walking, so the floor plane is visible and a homography is meaningful. The camera drifts slightly; that is a known
limitation of the demo calibration.

**Class coverage.** COCO has `person`, `car`, `truck`, `bus`, but **no forklift, pallet or box class**. On a
forklift clip the model detected the driver as `person` and the forklift not at all (it was sometimes labelled
`suitcase`, `truck` or `motorcycle`). So the recorded clip honestly yields **worker observations only**. The
forklift/worker proximity path (REQ-086) is demonstrated with the deterministic **SYNTHETIC** scene
(`vorg_vision.synthetic`: OpenCV-drawn forklift and worker, ground-truth boxes, `data_mode=SYNTHETIC`). A permissive
forklift checkpoint, or fine-tuning, is an open item (ADR-64), not faked.

**Measured CPU throughput** (Intel Core i3-12100, 4 cores / 8 threads, Windows 11, Python 3.14.6, onnxruntime
1.31 CPU, default threads; `python -m vorg_vision bench` / `run`, all on 2026-10-14, all 305 frames of clip 4280746
at 1920x1080; "pipeline" = decode + detect + track):

| Model | Machine otherwise idle: pipeline / detector | Machine under load from other processes (another session's Testcontainers tests): pipeline / detector |
|---|---|---|
| yolox_nano (416) | 60.9 / 88.0 fps | 13.1 / 18.0 fps |
| yolox_s (640) | 10.6 / 11.3 fps (an end-to-end `run` posting to the API: 10.1 fps) | 3.5 / 3.6 fps (a dry `run`: 4.2 fps) |

Load on the machine changes the figures by a factor of 3 to 5, so they are a range, not a guarantee.
**Budget:** the demo processes a recorded clip offline, so real time is not required; observations are sampled
every 5th frame (5 Hz at 25 fps). yolox_s is the default for quality; on an idle machine yolox_nano keeps up with a
25 fps feed, yolox_s does not (about 10 fps).

**REQ-087 (evaluation).** Precision/recall need labelled ground truth; the stock clip has none. The synthetic scene
has exact ground truth and the tracker unit tests cover ID stability. Labelling the stock clip is an open item.

## Tasks

1. This findings plan + `docs/scenarios/vision-demo-clip.md` + fetch scripts + `assets.sha256`.
2. ADR-64 (licensing, model, class coverage), Proposed.
