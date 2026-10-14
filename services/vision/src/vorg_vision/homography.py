"""Image-to-floor homography for one calibrated camera zone (BLUEPRINT section 11, REQ-082).

The floor is a plane, so a 3x3 homography maps a pixel (u, v) on the floor to site-local floor coordinates (x, y) in
meters (site frame of packages/twin-schema: origin south-west ground-floor corner, +x east, +y north). The homography is
fitted with the normalised direct linear transform from at least four control points; with more than four points the
root-mean-square residual on the control points is a measured quality figure (with exactly four the fit is exact and
the residual says nothing, so it is reported as None).

The calibrated zone is the convex hull of the control points in the image. A foot point outside it is not mapped:
extrapolating a homography beyond its control points is not trustworthy, and nothing is invented.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class ControlPoint:
    """A pixel (u, v) in the calibration image and the site-local floor point (x, y) in meters it shows."""

    u: float
    v: float
    x: float
    y: float


def _normaliser(points: np.ndarray) -> np.ndarray:
    """Similarity transform moving the centroid to the origin with mean distance sqrt(2) (Hartley normalisation)."""
    centroid = points.mean(axis=0)
    mean_dist = np.sqrt(((points - centroid) ** 2).sum(axis=1)).mean()
    if mean_dist <= 0:
        raise ValueError("control points are degenerate (all identical)")
    s = math.sqrt(2.0) / mean_dist
    return np.array([[s, 0.0, -s * centroid[0]], [0.0, s, -s * centroid[1]], [0.0, 0.0, 1.0]])


def fit_homography(points: Sequence[ControlPoint]) -> np.ndarray:
    """Fits H with (x, y, 1)^T ~ H (u, v, 1)^T; needs at least four points, no three of them collinear."""
    if len(points) < 4:
        raise ValueError("a homography needs at least four control points")
    src = np.array([[p.u, p.v] for p in points], dtype=np.float64)
    dst = np.array([[p.x, p.y] for p in points], dtype=np.float64)
    t_src = _normaliser(src)
    t_dst = _normaliser(dst)
    src_n = (t_src @ np.c_[src, np.ones(len(src))].T).T
    dst_n = (t_dst @ np.c_[dst, np.ones(len(dst))].T).T
    rows = []
    for (u, v, _), (x, y, _) in zip(src_n, dst_n, strict=True):
        rows.append([-u, -v, -1.0, 0.0, 0.0, 0.0, x * u, x * v, x])
        rows.append([0.0, 0.0, 0.0, -u, -v, -1.0, y * u, y * v, y])
    a = np.array(rows)
    _, singular, vt = np.linalg.svd(a)
    if singular[-2] < 1e-10:
        raise ValueError("control points are degenerate (collinear)")
    h_n = vt[-1].reshape(3, 3)
    h = np.linalg.inv(t_dst) @ h_n @ t_src
    if abs(h[2, 2]) < 1e-12:
        raise ValueError("degenerate homography")
    return h / h[2, 2]


def apply(h: np.ndarray, u: float, v: float) -> tuple[float, float]:
    """Maps one pixel to the floor plane."""
    x, y, w = h @ np.array([u, v, 1.0])
    if abs(w) < 1e-12:
        raise ValueError("point maps to infinity")
    return float(x / w), float(y / w)


def rms_error_m(h: np.ndarray, points: Sequence[ControlPoint]) -> float | None:
    """Root-mean-square floor residual of the control points in meters; None for an exact (four-point) fit."""
    if len(points) <= 4:
        return None
    sq = []
    for p in points:
        x, y = apply(h, p.u, p.v)
        sq.append((x - p.x) ** 2 + (y - p.y) ** 2)
    return math.sqrt(sum(sq) / len(sq))


def convex_hull(points: Sequence[tuple[float, float]]) -> list[tuple[float, float]]:
    """Andrew's monotone chain; counter-clockwise hull without repeated end point."""
    pts = sorted(set(points))
    if len(pts) <= 2:
        return pts

    def cross(o: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list[tuple[float, float]] = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list[tuple[float, float]] = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def inside_polygon(polygon: Sequence[tuple[float, float]], u: float, v: float) -> bool:
    """Even-odd rule; points on an edge count as inside."""
    n = len(polygon)
    if n < 3:
        return False
    inside = False
    for i in range(n):
        x1, y1 = polygon[i]
        x2, y2 = polygon[(i + 1) % n]
        # On-edge test.
        within = min(x1, x2) - 1e-9 <= u <= max(x1, x2) + 1e-9 and min(y1, y2) - 1e-9 <= v <= max(y1, y2) + 1e-9
        if within and abs((x2 - x1) * (v - y1) - (y2 - y1) * (u - x1)) < 1e-9:
            return True
        if (y1 > v) != (y2 > v):
            x_cross = x1 + (v - y1) * (x2 - x1) / (y2 - y1)
            if u < x_cross:
                inside = not inside
    return inside


@dataclass
class Calibration:
    """A camera's floor calibration: control points in an image of a given size, on one floor of the site."""

    image_width: int
    image_height: int
    points: list[ControlPoint]
    floor_id: str | None = None
    floor_z_m: float = 0.0

    def __post_init__(self) -> None:
        self.h = fit_homography(self.points)
        self.rms_m = rms_error_m(self.h, self.points)
        self.zone = convex_hull([(p.u, p.v) for p in self.points])

    def scaled(self, width: int, height: int) -> Calibration:
        """The same calibration for frames of another size (the control points scale with the image)."""
        sx, sy = width / self.image_width, height / self.image_height
        pts = [ControlPoint(p.u * sx, p.v * sy, p.x, p.y) for p in self.points]
        return Calibration(width, height, pts, self.floor_id, self.floor_z_m)

    def to_floor(self, u: float, v: float) -> tuple[float, float] | None:
        """Floor point of pixel (u, v), or None outside the calibrated zone."""
        if not inside_polygon(self.zone, u, v):
            return None
        return apply(self.h, u, v)

    @staticmethod
    def from_json(doc: dict) -> Calibration:
        """Reads the camera-registry calibration shape (docs/api/vision.md)."""
        pts = [ControlPoint(float(p["u"]), float(p["v"]), float(p["x"]), float(p["y"])) for p in doc["points"]]
        return Calibration(
            int(doc["image_width"]),
            int(doc["image_height"]),
            pts,
            doc.get("floor_id"),
            float(doc.get("floor_z_m", 0.0)),
        )
