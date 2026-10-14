"""Command line: ``python -m vorg_vision <run|bench|synth|verify-assets> ...`` (see services/vision/README.md)."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path

from . import __version__
from .assets import verify_assets
from .detector import YoloxDetector
from .homography import Calibration
from .pipeline import FrameSource, run
from .synthetic import SyntheticScene

DEFAULT_CLASSES = frozenset({"person", "car", "truck", "bus", "motorcycle", "bicycle"})
DATA_MODES = ("SIMULATED", "SYNTHETIC", "REPLAY")


def _session_id(now: datetime) -> str:
    return "S" + now.strftime("%Y%m%dT%H%M%SZ")


def _batch_doc(
    args: argparse.Namespace,
    session_id: str,
    started_at: datetime,
    detector: str,
    source: FrameSource,
    observations: list[dict],
    seq: int,
) -> dict:
    return {
        "session_id": session_id,
        "batch_seq": seq,
        "data_mode": args.data_mode,
        "detector": detector,
        "pipeline_version": __version__,
        "media": {
            "name": args.media_name,
            "frame_width": source.width,
            "frame_height": source.height,
            "fps": source.fps,
        },
        "session_started_at": started_at.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "observations": observations,
    }


def cmd_run(args: argparse.Namespace) -> int:
    from .api_client import VisionApi

    now = datetime.now(UTC).replace(microsecond=0)
    session_id = args.session or _session_id(now)
    api = None
    camera_doc: dict | None = None
    if not args.dry_run:
        secret = os.environ.get(args.secret_env, "")
        if not secret:
            print(f"error: environment variable {args.secret_env} is not set", file=sys.stderr)
            return 2
        api = VisionApi(args.api, args.client_id, secret)
        camera_doc = api.camera(args.camera_id)
    calibration = None
    if args.calibration:
        calibration = Calibration.from_json(json.loads(Path(args.calibration).read_text(encoding="utf-8")))
    elif camera_doc and camera_doc.get("calibration"):
        calibration = Calibration.from_json(camera_doc["calibration"])

    if args.synthetic:
        scene = SyntheticScene()
        frames = ((i, None) for i in range(scene.frame_count))
        source = FrameSource(frames, scene.fps, 1280, 720, scene.frame_count)
        if calibration is None:
            calibration = scene.calibration
        detector_name = "synthetic-ground-truth"

        def detect(i: int, _frame: object) -> list:
            return scene.ground_truth(i)
    else:
        source = FrameSource.from_video(Path(args.clip), args.max_frames)
        model = YoloxDetector(Path(args.model), args.score, wanted=DEFAULT_CLASSES, threads=args.threads)
        detector_name = f"yolox:{Path(args.model).stem}"

        def detect(_i: int, frame: object) -> list:
            return model.detect(frame)

    # Observation time = session start + media time. Offline (faster or slower than real time) the session is placed so
    # its last frame is "now": no observation is ever stamped in the future. --realtime starts the session now.
    started_at = now if args.realtime else now - timedelta(seconds=source.frame_count / source.fps)
    out = open(args.out, "w", encoding="utf-8") if args.out else None  # noqa: SIM115
    seq = 0

    def emit(observations: list[dict]) -> None:
        nonlocal seq
        seq += 1
        doc = _batch_doc(args, session_id, started_at, detector_name, source, observations, seq)
        if out is not None:
            out.write(json.dumps(doc, separators=(",", ":")) + "\n")
        if args.realtime:
            due = datetime.fromisoformat(observations[-1]["observed_at"].replace("Z", "+00:00"))
            wait = (due - datetime.now(UTC)).total_seconds()
            if wait > 0:
                time.sleep(wait)
        if api is not None:
            key = f"vision:{args.camera_id}:{session_id}:{seq}"
            ack = api.post_batch(args.camera_id, key, doc)
            result = ack.get("result", {})
            print(
                f"batch {seq}: {len(observations)} sent, accepted={result.get('accepted')} "
                f"linked={result.get('linked')} duplicates={result.get('duplicates')}",
                file=sys.stderr,
            )

    try:
        stats = run(source, detect, calibration, session_id, started_at, args.sample_every, emit, args.batch_size)
    finally:
        if out is not None:
            out.close()
    print(
        json.dumps(
            {
                "session_id": session_id,
                "frames": stats.frames,
                "detections": stats.detections,
                "observations": stats.observations,
                "tracks": len(stats.tracks),
                "pipeline_fps": round(stats.fps, 2),
                "detector_fps": round(stats.detect_fps, 2),
                "detector": detector_name,
            }
        )
    )
    return 0


def cmd_bench(args: argparse.Namespace) -> int:
    """Measures decode + detect + track throughput on this machine; nothing is sent anywhere."""
    results = []
    for model_path in args.model:
        source = FrameSource.from_video(Path(args.clip), args.max_frames)
        model = YoloxDetector(Path(model_path), args.score, wanted=DEFAULT_CLASSES, threads=args.threads)
        detect: Callable = lambda _i, f, m=model: m.detect(f)  # noqa: E731
        stats = run(source, detect, None, "bench", datetime.now(UTC), 1, lambda _o: None, 10_000)
        results.append(
            {
                "model": Path(model_path).name,
                "input": list(model.input_size),
                "frames": stats.frames,
                "frame_size": [source.width, source.height],
                "pipeline_fps": round(stats.fps, 2),
                "detector_fps": round(stats.detect_fps, 2),
                "detections": stats.detections,
                "confirmed_tracks": len(stats.tracks),
                "threads": args.threads or "default",
            }
        )
    print(json.dumps({"bench": results, "python": sys.version.split()[0]}, indent=2))
    return 0


def cmd_synth(args: argparse.Namespace) -> int:
    import cv2

    scene = SyntheticScene()
    out = Path(args.out)
    if out.suffix.lower() in (".png", ".jpg", ".jpeg"):
        cv2.imwrite(str(out), scene.render(args.frame, actors=not args.background))
    else:
        scene.write(out)
    print(json.dumps({"written": str(out), "frames": scene.frame_count, "fps": scene.fps}))
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    problems = verify_assets(Path(args.assets), Path(args.manifest))
    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="vorg_vision", description="Virtual Org vision pipeline (Phase 7)")
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="process a clip (or the synthetic scene) and post observation batches")
    r.add_argument("--camera-id", required=True, help="camera registry id (GET /api/cameras)")
    src = r.add_mutually_exclusive_group(required=True)
    src.add_argument("--clip", help="recorded video file")
    src.add_argument("--synthetic", action="store_true", help="use the deterministic synthetic scene")
    r.add_argument("--model", default="assets/yolox_s.onnx")
    r.add_argument("--media-name", required=True, help="registry media name of the clip (served by the API)")
    r.add_argument("--data-mode", choices=DATA_MODES, default="SIMULATED")
    r.add_argument("--api", default="http://localhost:8080")
    r.add_argument("--client-id", default="svc-vision-local")
    r.add_argument("--secret-env", default="VO_VISION_INGEST_SECRET")
    r.add_argument("--calibration", help="local calibration JSON (overrides the registry's)")
    r.add_argument("--session", help="session id (default: S<UTC start time>)")
    r.add_argument("--sample-every", type=int, default=5, help="report each track every N frames")
    r.add_argument("--batch-size", type=int, default=100)
    r.add_argument("--score", type=float, default=0.35)
    r.add_argument("--threads", type=int)
    r.add_argument("--max-frames", type=int)
    r.add_argument("--realtime", action="store_true", help="post each batch when its observation time is reached")
    r.add_argument("--dry-run", action="store_true", help="do not call the API")
    r.add_argument("--out", help="also write the batches as NDJSON")
    r.set_defaults(func=cmd_run)

    b = sub.add_parser("bench", help="measure CPU frames per second")
    b.add_argument("--clip", required=True)
    b.add_argument("--model", action="append", required=True)
    b.add_argument("--max-frames", type=int)
    b.add_argument("--score", type=float, default=0.35)
    b.add_argument("--threads", type=int)
    b.set_defaults(func=cmd_bench)

    s = sub.add_parser("synth", help="write the synthetic scene as a video (.mp4/.avi) or one still (.png/.jpg)")
    s.add_argument("--out", required=True)
    s.add_argument("--frame", type=int, default=0)
    s.add_argument("--background", action="store_true", help="still without the actors (camera panel plate)")
    s.set_defaults(func=cmd_synth)

    v = sub.add_parser("verify-assets", help="check downloaded assets against the SHA-256 manifest")
    v.add_argument("--assets", default="assets")
    v.add_argument("--manifest", default="assets.sha256")
    v.set_defaults(func=cmd_verify)

    args = p.parse_args(argv)
    if args.cmd == "run" and args.data_mode == "SIMULATED" and args.synthetic:
        args.data_mode = "SYNTHETIC"
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
