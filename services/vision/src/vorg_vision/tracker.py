"""A small ByteTrack-style IoU tracker (written for this project; no third-party tracker code).

Per frame:
1. every track predicts its box with a damped constant-velocity model;
2. high-score detections (score >= ``high``) are matched to all live tracks by IoU, same class only, greedily by
   descending IoU (ties: lower track id, then lower detection index), which keeps the result deterministic;
3. low-score detections (``low`` <= score < ``high``) are matched to the confirmed tracks still unmatched; this is the
   ByteTrack idea that recovers occluded objects without starting new tracks from weak boxes;
4. unmatched high-score detections start TENTATIVE tracks; a tentative track that misses a frame is dropped;
5. a track is CONFIRMED after ``min_hits`` matched frames, LOST while unmatched, and removed after ``max_age`` frames.

Identity is provisional (BLUEPRINT section 11): a confirmed track is reported with identity status PROVISIONAL until it
has ``stable_hits`` matched frames, then STABLE. Track ids are never reused within one tracker.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from .detector import Detection

Box = tuple[float, float, float, float]


class TrackState(Enum):
    TENTATIVE = "TENTATIVE"
    CONFIRMED = "CONFIRMED"
    LOST = "LOST"


def iou(a: Box, b: Box) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0.0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)


@dataclass
class Track:
    track_id: int
    label: str
    box: Box
    score: float
    hits: int = 1
    misses: int = 0
    state: TrackState = TrackState.TENTATIVE
    velocity: tuple[float, float] = (0.0, 0.0)
    last_frame: int = 0
    first_frame: int = 0
    updated: bool = True
    floor_history: list[tuple[float, float, float]] = field(default_factory=list)

    def predicted(self, frame: int) -> Box:
        """The box extrapolated to ``frame`` with the track's velocity (pixels per frame)."""
        k = float(max(0, frame - self.last_frame))
        dx, dy = self.velocity
        return (self.box[0] + dx * k, self.box[1] + dy * k, self.box[2] + dx * k, self.box[3] + dy * k)


class IouTracker:
    """Multi-object tracker over :class:`Detection` lists, one call per frame."""

    def __init__(
        self,
        high: float = 0.5,
        low: float = 0.15,
        match_iou: float = 0.3,
        min_hits: int = 3,
        stable_hits: int = 10,
        max_age: int = 15,
        velocity_damping: float = 0.5,
    ) -> None:
        if not 0.0 <= low <= high <= 1.0:
            raise ValueError("thresholds must satisfy 0 <= low <= high <= 1")
        self.high = high
        self.low = low
        self.match_iou = match_iou
        self.min_hits = min_hits
        self.stable_hits = stable_hits
        self.max_age = max_age
        self.velocity_damping = velocity_damping
        self._tracks: list[Track] = []
        self._next_id = 1

    @property
    def tracks(self) -> list[Track]:
        return list(self._tracks)

    def identity_status(self, track: Track) -> str:
        return "STABLE" if track.hits >= self.stable_hits else "PROVISIONAL"

    def _match(self, tracks: list[Track], dets: list[tuple[int, Detection]], frame: int) -> list[tuple[Track, int]]:
        pairs = []
        for t in tracks:
            pbox = t.predicted(frame)
            for di, d in dets:
                if d.label != t.label:
                    continue
                score = iou(pbox, (d.x1, d.y1, d.x2, d.y2))
                if score >= self.match_iou:
                    pairs.append((-score, t.track_id, di, t))
        pairs.sort(key=lambda p: (p[0], p[1], p[2]))
        used_t: set[int] = set()
        used_d: set[int] = set()
        out = []
        for _, tid, di, t in pairs:
            if tid in used_t or di in used_d:
                continue
            used_t.add(tid)
            used_d.add(di)
            out.append((t, di))
        return out

    def _apply(self, t: Track, d: Detection, frame: int) -> None:
        new_box = (d.x1, d.y1, d.x2, d.y2)
        gap = max(1, frame - t.last_frame)
        cx_old, cy_old = (t.box[0] + t.box[2]) / 2.0, (t.box[1] + t.box[3]) / 2.0
        cx_new, cy_new = (new_box[0] + new_box[2]) / 2.0, (new_box[1] + new_box[3]) / 2.0
        vx, vy = (cx_new - cx_old) / gap, (cy_new - cy_old) / gap
        a = self.velocity_damping
        t.velocity = (a * t.velocity[0] + (1 - a) * vx, a * t.velocity[1] + (1 - a) * vy)
        t.box = new_box
        t.score = d.score
        t.hits += 1
        t.misses = 0
        t.last_frame = frame
        t.updated = True
        if t.hits >= self.min_hits:
            t.state = TrackState.CONFIRMED

    def update(self, detections: list[Detection], frame: int) -> list[Track]:
        """Feeds one frame; returns the confirmed tracks matched in this frame (the ones worth reporting)."""
        for t in self._tracks:
            t.updated = False
        indexed = list(enumerate(detections))
        high = [(i, d) for i, d in indexed if d.score >= self.high]
        low = [(i, d) for i, d in indexed if self.low <= d.score < self.high]

        matched = self._match(self._tracks, high, frame)
        matched_ids = {t.track_id for t, _ in matched}
        used_high = {di for _, di in matched}
        for t, di in matched:
            self._apply(t, detections[di], frame)

        leftover = [t for t in self._tracks if t.track_id not in matched_ids and t.state != TrackState.TENTATIVE]
        for t, di in self._match(leftover, low, frame):
            self._apply(t, detections[di], frame)
            matched_ids.add(t.track_id)

        survivors: list[Track] = []
        for t in self._tracks:
            if t.track_id in matched_ids:
                survivors.append(t)
                continue
            if t.state == TrackState.TENTATIVE:
                continue
            t.misses += 1
            t.state = TrackState.LOST
            if t.misses <= self.max_age:
                survivors.append(t)
        for i, d in high:
            if i in used_high:
                continue
            t = Track(self._next_id, d.label, (d.x1, d.y1, d.x2, d.y2), d.score, last_frame=frame, first_frame=frame)
            if t.hits >= self.min_hits:
                t.state = TrackState.CONFIRMED
            self._next_id += 1
            survivors.append(t)
        self._tracks = survivors
        return [t for t in self._tracks if t.updated and t.state == TrackState.CONFIRMED]
