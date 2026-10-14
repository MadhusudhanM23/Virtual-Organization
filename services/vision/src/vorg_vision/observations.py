"""Maps confirmed tracks to privacy-safe observation records (REQ-083, REQ-084, ADR-65).

An observation carries only: the camera, the session, the track id, the detector class, the identity status, the
detection confidence, the bounding box normalised to the frame (0..1), the floor position in site meters when the foot
point lies inside the calibrated zone, a speed/heading derived from successive floor positions, the observation time and
the media time of the frame. Never pixels, crops, embeddings, faces or any other biometric attribute: the record is
built from a whitelist and :func:`assert_privacy_safe` rejects anything else.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from .homography import Calibration
from .tracker import Track

ALLOWED_KEYS = frozenset(
    {
        "observation_id",
        "track_id",
        "object_class",
        "identity_status",
        "confidence",
        "observed_at",
        "media_time_s",
        "frame_index",
        "bbox",
        "floor_position",
        "spatial_status",
        "speed_mps",
        "heading_deg",
    }
)
BBOX_KEYS = frozenset({"x", "y", "w", "h"})
FLOOR_KEYS = frozenset({"x", "y", "z"})

# Speeds are estimated over this many seconds of floor history (shorter windows amplify box jitter).
SPEED_WINDOW_S = 1.0
# Below this speed a heading is noise, so none is reported.
HEADING_MIN_SPEED_MPS = 0.2


def iso(ts: datetime) -> str:
    return ts.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def heading_deg(dx: float, dy: float) -> float:
    """Site-frame heading: 0 = +x (east), counter-clockwise positive, in [0, 360)."""
    h = math.degrees(math.atan2(dy, dx)) % 360.0
    return 0.0 if h >= 360.0 else h


def speed_and_heading(
    history: list[tuple[float, float, float]], window_s: float = SPEED_WINDOW_S
) -> tuple[float | None, float | None]:
    """(speed m/s, heading deg) from (t_s, x, y) samples over the last ``window_s`` seconds; None without data."""
    if len(history) < 2:
        return None, None
    t_end, x_end, y_end = history[-1]
    start = history[0]
    for sample in reversed(history[:-1]):
        start = sample
        if t_end - sample[0] >= window_s:
            break
    dt = t_end - start[0]
    if dt <= 0:
        return None, None
    dx, dy = x_end - start[1], y_end - start[2]
    speed = math.hypot(dx, dy) / dt
    heading = heading_deg(dx, dy) if speed >= HEADING_MIN_SPEED_MPS else None
    return speed, heading


@dataclass
class ObservationMapper:
    session_id: str
    calibration: Calibration | None
    started_at: datetime
    frame_width: int
    frame_height: int
    sample_every: int = 5

    def foot_point(self, track: Track) -> tuple[float, float]:
        x1, _, x2, y2 = track.box
        return (x1 + x2) / 2.0, y2

    def update_floor(self, track: Track, media_time_s: float) -> tuple[float, float] | None:
        """Projects the track's foot point and appends it to the track's floor history."""
        if self.calibration is None:
            return None
        u, v = self.foot_point(track)
        floor = self.calibration.to_floor(u, v)
        if floor is not None:
            track.floor_history.append((media_time_s, floor[0], floor[1]))
            # Keep a short window only.
            while len(track.floor_history) > 2 and media_time_s - track.floor_history[0][0] > 3 * SPEED_WINDOW_S:
                track.floor_history.pop(0)
        return floor

    def due(self, track: Track, frame_index: int) -> bool:
        return (frame_index - track.first_frame) % self.sample_every == 0

    def map(
        self,
        track: Track,
        frame_index: int,
        media_time_s: float,
        identity_status: str,
        floor: tuple[float, float] | None,
    ) -> dict:
        x1, y1, x2, y2 = track.box
        w, h = float(self.frame_width), float(self.frame_height)
        cx1, cy1 = min(max(x1, 0.0), w), min(max(y1, 0.0), h)
        cx2, cy2 = min(max(x2, 0.0), w), min(max(y2, 0.0), h)
        obs: dict = {
            "observation_id": f"{self.session_id}:{track.track_id}:{frame_index}",
            "track_id": track.track_id,
            "object_class": track.label,
            "identity_status": identity_status,
            "confidence": round(track.score, 3),
            "observed_at": iso(self.started_at + timedelta(seconds=media_time_s)),
            "media_time_s": round(media_time_s, 3),
            "frame_index": frame_index,
            "bbox": {
                "x": round(cx1 / w, 4),
                "y": round(cy1 / h, 4),
                "w": round((cx2 - cx1) / w, 4),
                "h": round((cy2 - cy1) / h, 4),
            },
        }
        if self.calibration is None:
            obs["spatial_status"] = "UNCALIBRATED"
        elif floor is None:
            obs["spatial_status"] = "OUTSIDE_CALIBRATED_ZONE"
        else:
            obs["spatial_status"] = "MAPPED"
            obs["floor_position"] = {
                "x": round(floor[0], 3),
                "y": round(floor[1], 3),
                "z": round(self.calibration.floor_z_m, 3),
            }
            speed, heading = speed_and_heading(track.floor_history)
            if speed is not None:
                obs["speed_mps"] = round(speed, 3)
            if heading is not None:
                obs["heading_deg"] = round(heading, 3)
        assert_privacy_safe(obs)
        return obs


def assert_privacy_safe(obs: dict) -> None:
    """Raises ValueError for any key outside the whitelist or any non-scalar value (no arrays, bytes or crops)."""
    extra = set(obs) - ALLOWED_KEYS
    if extra:
        raise ValueError(f"observation carries non-whitelisted fields: {sorted(extra)}")
    for key, value in obs.items():
        if key == "bbox":
            if set(value) != BBOX_KEYS:
                raise ValueError("bbox must have exactly x, y, w, h")
            if not all(isinstance(v, float | int) for v in value.values()):
                raise ValueError("bbox values must be numbers")
        elif key == "floor_position":
            if set(value) != FLOOR_KEYS or not all(isinstance(v, float | int) for v in value.values()):
                raise ValueError("floor_position must have numeric x, y, z")
        elif not isinstance(value, str | int | float) or isinstance(value, bool):
            raise ValueError(f"field {key} must be a scalar")
