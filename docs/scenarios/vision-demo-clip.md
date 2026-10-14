# Vision demo assets: provenance

> Phase 7 (REQ-080, ADR-64). None of these files is committed. `scripts/fetch-vision-assets.ps1` (or `.sh`)
> downloads them into `services/vision/assets/` (gitignored) and checks each against
> `services/vision/assets.sha256`; a file with the wrong hash is deleted. Downloads are treated as untrusted data:
> they are hashed and decoded as media/model data only, never executed.

## Demo clip (recorded feed for CAM-03, `data_mode=SIMULATED`)

| Field | Value |
|---|---|
| Title | Men talking while walking in the warehouse |
| Page | https://www.pexels.com/video/men-talking-while-walking-in-the-warehouse-4280746/ |
| File URL | https://videos.pexels.com/video-files/4280746/4280746-hd_1920_1080_25fps.mp4 |
| Author | Tiger Lily (https://www.pexels.com/@tiger-lily/) |
| License | Pexels License (https://www.pexels.com/license/): free to use and modify, attribution not required; may not be sold unaltered or used to imply endorsement |
| Downloaded | 2026-10-14 (UTC) |
| Local name | `pexels-4280746-1920x1080.mp4` |
| Size | 6,679,246 bytes; 1920x1080, 25 fps, 305 frames (12.2 s) |
| SHA-256 | `cfaa552f02eb032464ca3736130bbaf60b023534764d79700d5f8b06a3062e9a` |

The clip is a high, roughly overhead view of a warehouse aisle with three workers walking. It was **not** filmed in
the demo site. CAM-03's calibration (`services/vision/config/cam-03-pexels-4280746.calibration.json`, also seeded into
the camera registry) **asserts** that a floor patch of the clip lies in the Storage A aisle of the synthetic
Bengaluru Warehouse 01 (x 31 to 37.5 m, y 35 to 39.2 m: the aisle between rack rows 34 and 40). The six control points
were picked by hand on the first frame; the mapping is illustrative, documented and unit-tested, not a survey
(BLUEPRINT section 11, "demo spatial mapping"). The camera drifts slightly during the clip, which the fixed homography
does not model.

COCO-80 has no forklift class, so this clip yields **worker observations only**; persons in it are never identified,
and no frame, crop or face is stored (ADR-65).

Clips examined and not used: Pexels 31352808 ("Efficient warehouse operations with forklift drivers", EqualStock
IN), 37651089 (forklifts moving boxes, portrait) and 6170059 ("Warehouse employee loading delivery van"). All three
are edited or panning footage, unsuitable for a fixed homography. On 31352808, YOLOX found the forklift drivers as
`person` and the forklifts not at all. None of these files is used or kept.

## Synthetic feed (CAM-01, `data_mode=SYNTHETIC`)

`python -m vorg_vision run --synthetic` generates a deterministic OpenCV scene (a yellow forklift driving east at
1.5 m/s and a blue worker walking south at 1.0 m/s in the Receiving zone, passing within 3 m of each other) with exact
ground-truth boxes. It exists because the stock clip cannot show a forklift, and it drives the vision proximity →
incident path (REQ-086). The camera panel shows its empty background plate
(`python -m vorg_vision synth --background --out assets/synthetic-cam-01.png`) with the observations drawn on top.
This environment's OpenCV build cannot write a browser-playable video codec (H.264 or VP8), so there is no synthetic
video file.

## Model weights

| File | URL | License | Size (bytes) | SHA-256 |
|---|---|---|---|---|
| `yolox_s.onnx` | https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx | Apache-2.0 (Megvii YOLOX) | 35,858,002 | `c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063` |
| `yolox_nano.onnx` | https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx | Apache-2.0 | 3,659,407 | `c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d` |

Both downloaded on 2026-10-14. The weights are COCO-pretrained (80 classes).
