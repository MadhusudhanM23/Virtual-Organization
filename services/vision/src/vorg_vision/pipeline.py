"""Frame source -> detection -> tracking -> floor mapping -> observation batches (BLUEPRINT section 10 camera chain)."""

from __future__ import annotations

import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import numpy as np

from .detector import Detection
from .homography import Calibration
from .observations import ObservationMapper
from .tracker import IouTracker


@dataclass
class FrameSource:
    """Frames of a video file (decoded with OpenCV) or of the synthetic scene."""

    frames: Iterator[tuple[int, np.ndarray | None]]
    fps: float
    width: int
    height: int
    frame_count: int

    @staticmethod
    def from_video(path: Path, max_frames: int | None = None) -> FrameSource:
        import cv2

        cap = cv2.VideoCapture(str(path))
        if not cap.isOpened():
            raise FileNotFoundError(f"cannot open video {path}")
        fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        limit = count if max_frames is None else min(count, max_frames)

        def gen() -> Iterator[tuple[int, np.ndarray | None]]:
            try:
                i = 0
                while i < limit:
                    ok, frame = cap.read()
                    if not ok:
                        return
                    yield i, frame
                    i += 1
            finally:
                cap.release()

        return FrameSource(gen(), fps, width, height, limit)


@dataclass
class RunStats:
    frames: int = 0
    detections: int = 0
    observations: int = 0
    detect_s: float = 0.0
    total_s: float = 0.0
    tracks: set[int] = field(default_factory=set)

    @property
    def fps(self) -> float:
        return self.frames / self.total_s if self.total_s > 0 else 0.0

    @property
    def detect_fps(self) -> float:
        return self.frames / self.detect_s if self.detect_s > 0 else 0.0


def run(
    source: FrameSource,
    detect: Callable[[int, np.ndarray | None], list[Detection]],
    calibration: Calibration | None,
    session_id: str,
    started_at: datetime,
    sample_every: int,
    emit: Callable[[list[dict]], None],
    batch_size: int = 100,
    tracker: IouTracker | None = None,
) -> RunStats:
    """Processes every frame; calls ``emit`` with observation batches of at most ``batch_size``."""
    tracker = tracker or IouTracker()
    cal = None
    if calibration is not None:
        same = (calibration.image_width, calibration.image_height) == (source.width, source.height)
        cal = calibration if same else calibration.scaled(source.width, source.height)
    mapper = ObservationMapper(session_id, cal, started_at, source.width, source.height, sample_every)
    stats = RunStats()
    pending: list[dict] = []
    t0 = time.perf_counter()
    for index, frame in source.frames:
        media_t = index / source.fps
        d0 = time.perf_counter()
        dets = detect(index, frame)
        stats.detect_s += time.perf_counter() - d0
        stats.frames += 1
        stats.detections += len(dets)
        for track in tracker.update(dets, index):
            floor = mapper.update_floor(track, media_t)
            if not mapper.due(track, index):
                continue
            pending.append(mapper.map(track, index, media_t, tracker.identity_status(track), floor))
            stats.tracks.add(track.track_id)
            if len(pending) >= batch_size:
                emit(pending)
                stats.observations += len(pending)
                pending = []
    if pending:
        emit(pending)
        stats.observations += len(pending)
    stats.total_s = time.perf_counter() - t0
    return stats
