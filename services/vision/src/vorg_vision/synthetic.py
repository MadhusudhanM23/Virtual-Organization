"""Deterministic SYNTHETIC camera scene (fallback feed and the proximity demo, ADR-64).

COCO-pretrained YOLOX has no forklift class, so a recorded clip cannot honestly produce forklift observations. This
module draws a scene with OpenCV: a floor grid, one forklift (yellow box) driving east and one worker (blue box) walking
south, both moving in site-floor meters and projected into the image through the inverse of the synthetic camera's
homography. The detections are the drawn boxes themselves (ground truth, confidence 1.0 by construction), so the
observations are labelled ``data_mode=SYNTHETIC`` and ``detector=synthetic-ground-truth``. No randomness, no clock: the
same frame index always gives the same image and the same boxes.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .detector import Detection
from .homography import Calibration, ControlPoint, apply, fit_homography

WIDTH, HEIGHT, FPS = 1280, 720, 25
DURATION_S = 12.0

# Synthetic CAM-01 (Receiving, ground floor of Bengaluru Warehouse 01): four corners of the calibrated floor patch.
BASE_POINTS = (
    ControlPoint(100.0, 700.0, 34.0, 4.0),
    ControlPoint(1180.0, 700.0, 46.0, 4.0),
    ControlPoint(900.0, 240.0, 44.0, 14.0),
    ControlPoint(380.0, 240.0, 36.0, 14.0),
)


def calibration_points() -> list[ControlPoint]:
    """The four base points plus two interior points computed from the same homography (exact by construction)."""
    h = fit_homography(BASE_POINTS)
    inv = np.linalg.inv(h)
    extra = []
    for x, y in ((40.0, 6.0), (40.0, 11.0)):
        u, v = apply(inv, x, y)
        extra.append(ControlPoint(round(u, 2), round(v, 2), x, y))
    return list(BASE_POINTS) + extra


@dataclass(frozen=True)
class Actor:
    label: str
    color: tuple[int, int, int]
    width_m: float
    aspect: float  # drawn box height / width
    start: tuple[float, float]
    velocity: tuple[float, float]  # m/s
    t_start: float
    t_end: float

    def position(self, t: float) -> tuple[float, float] | None:
        if t < self.t_start or t > self.t_end:
            return None
        dt = t - self.t_start
        return self.start[0] + self.velocity[0] * dt, self.start[1] + self.velocity[1] * dt


ACTORS = (
    Actor("forklift", (0, 200, 255), 1.2, 1.4, (35.0, 6.0), (1.5, 0.0), 0.0, 7.0),
    Actor("person", (255, 120, 0), 0.6, 2.6, (42.0, 13.0), (0.0, -1.0), 0.0, 8.5),
)


class SyntheticScene:
    def __init__(self) -> None:
        self.calibration = Calibration(WIDTH, HEIGHT, calibration_points())
        self._to_image = np.linalg.inv(self.calibration.h)
        self.frame_count = int(DURATION_S * FPS)
        self.fps = float(FPS)

    def _box(self, actor: Actor, pos: tuple[float, float]) -> tuple[float, float, float, float]:
        left = apply(self._to_image, pos[0] - actor.width_m / 2.0, pos[1])
        right = apply(self._to_image, pos[0] + actor.width_m / 2.0, pos[1])
        w = abs(right[0] - left[0])
        cx = (left[0] + right[0]) / 2.0
        foot_v = (left[1] + right[1]) / 2.0
        return cx - w / 2.0, foot_v - w * actor.aspect, cx + w / 2.0, foot_v

    def ground_truth(self, frame_index: int) -> list[Detection]:
        t = frame_index / self.fps
        out = []
        for actor in ACTORS:
            pos = actor.position(t)
            if pos is None:
                continue
            x1, y1, x2, y2 = self._box(actor, pos)
            out.append(Detection(x1, y1, x2, y2, 1.0, actor.label))
        return out

    def render(self, frame_index: int, actors: bool = True) -> np.ndarray:
        """The frame; with ``actors=False`` the empty background plate (the camera panel's still image)."""
        import cv2

        img = np.full((HEIGHT, WIDTH, 3), 70, dtype=np.uint8)
        for x in range(34, 47):
            a = apply(self._to_image, float(x), 4.0)
            b = apply(self._to_image, float(x), 14.0)
            cv2.line(img, (int(a[0]), int(a[1])), (int(b[0]), int(b[1])), (110, 110, 110), 1)
        for y in range(4, 15):
            a = apply(self._to_image, 34.0, float(y))
            b = apply(self._to_image, 46.0, float(y))
            cv2.line(img, (int(a[0]), int(a[1])), (int(b[0]), int(b[1])), (110, 110, 110), 1)
        cv2.putText(img, "SYNTHETIC", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (230, 230, 230), 2)
        if not actors:
            return img
        for actor, det in zip(
            [a for a in ACTORS if a.position(frame_index / self.fps) is not None],
            self.ground_truth(frame_index),
            strict=True,
        ):
            cv2.rectangle(img, (int(det.x1), int(det.y1)), (int(det.x2), int(det.y2)), actor.color, -1)
        return img

    def write(self, path: Path) -> None:
        import cv2

        path.parent.mkdir(parents=True, exist_ok=True)
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), self.fps, (WIDTH, HEIGHT))
        try:
            for i in range(self.frame_count):
                writer.write(self.render(i))
        finally:
            writer.release()
