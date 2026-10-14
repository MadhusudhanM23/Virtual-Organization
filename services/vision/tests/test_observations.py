import math
from datetime import UTC, datetime

import pytest

from vorg_vision.detector import Detection
from vorg_vision.homography import Calibration, ControlPoint
from vorg_vision.observations import (
    ALLOWED_KEYS,
    ObservationMapper,
    assert_privacy_safe,
    heading_deg,
    speed_and_heading,
)
from vorg_vision.pipeline import FrameSource, run
from vorg_vision.synthetic import SyntheticScene
from vorg_vision.tracker import IouTracker, Track

START = datetime(2026, 10, 14, 10, 0, 0, tzinfo=UTC)
# 100 px = 1 m; image v grows southwards (site y decreases).
CAL = Calibration(
    1000,
    1000,
    [
        ControlPoint(0, 0, 0, 10),
        ControlPoint(1000, 0, 10, 10),
        ControlPoint(1000, 1000, 10, 0),
        ControlPoint(0, 1000, 0, 0),
    ],
)


def track(x1, y1, x2, y2, tid=7, label="person", score=0.876):
    return Track(tid, label, (x1, y1, x2, y2), score, hits=5)


def test_headings_follow_the_site_convention():
    assert heading_deg(1, 0) == 0.0
    assert heading_deg(0, 1) == 90.0
    assert heading_deg(-1, 0) == 180.0
    assert heading_deg(0, -1) == 270.0


def test_speed_over_the_window():
    hist = [(0.0, 0.0, 0.0), (0.5, 0.5, 0.0), (1.0, 1.0, 0.0)]
    speed, heading = speed_and_heading(hist)
    assert speed == pytest.approx(1.0)
    assert heading == pytest.approx(0.0)
    assert speed_and_heading([(0.0, 1.0, 1.0)]) == (None, None)
    slow, none_heading = speed_and_heading([(0.0, 0.0, 0.0), (1.0, 0.05, 0.0)])
    assert slow == pytest.approx(0.05) and none_heading is None


def test_mapped_observation_has_floor_position_and_only_whitelisted_fields():
    m = ObservationMapper("S1", CAL, START, 1000, 1000)
    t = track(480, 300, 520, 500)
    floor = m.update_floor(t, 2.0)
    obs = m.map(t, 50, 2.0, "STABLE", floor)
    assert set(obs) <= ALLOWED_KEYS
    assert obs["observation_id"] == "S1:7:50"
    assert obs["object_class"] == "person"
    assert obs["confidence"] == 0.876
    assert obs["observed_at"] == "2026-10-14T10:00:02.000Z"
    assert obs["spatial_status"] == "MAPPED"
    assert obs["floor_position"]["x"] == pytest.approx(5.0)  # foot point u = 500 -> x = 5 m
    assert obs["floor_position"]["y"] == pytest.approx(5.0)  # foot point v = 500 -> y = 5 m
    assert obs["bbox"] == {"x": 0.48, "y": 0.3, "w": 0.04, "h": 0.2}


def test_outside_the_zone_and_uncalibrated_have_no_floor_position():
    m = ObservationMapper("S1", CAL, START, 1000, 1000)
    t = track(480, 1200, 520, 1300)
    obs = m.map(t, 1, 0.04, "PROVISIONAL", m.update_floor(t, 0.04))
    assert obs["spatial_status"] == "OUTSIDE_CALIBRATED_ZONE"
    assert "floor_position" not in obs
    assert obs["bbox"]["y"] == 1.0 and obs["bbox"]["h"] == 0.0  # clamped to the frame
    un = ObservationMapper("S1", None, START, 1000, 1000)
    obs2 = un.map(t, 1, 0.04, "PROVISIONAL", un.update_floor(t, 0.04))
    assert obs2["spatial_status"] == "UNCALIBRATED"


def test_speed_is_derived_from_successive_floor_positions():
    m = ObservationMapper("S1", CAL, START, 1000, 1000)
    t = track(480, 300, 520, 500)
    m.update_floor(t, 0.0)
    t.box = (580, 300, 620, 500)  # moved 1 m east in 1 s
    floor = m.update_floor(t, 1.0)
    obs = m.map(t, 25, 1.0, "STABLE", floor)
    assert obs["speed_mps"] == pytest.approx(1.0)
    assert obs["heading_deg"] == pytest.approx(0.0)


@pytest.mark.parametrize(
    "bad",
    [
        {"face_embedding": [0.1, 0.2]},
        {"crop": b"\x00\x01"},
        {"thumbnail": "data:image/png;base64,AAAA"},
    ],
)
def test_privacy_guard_rejects_anything_outside_the_whitelist(bad):
    obs = {"observation_id": "a", "track_id": 1, **bad}
    with pytest.raises(ValueError):
        assert_privacy_safe(obs)


def test_privacy_guard_rejects_non_scalar_values():
    with pytest.raises(ValueError):
        assert_privacy_safe({"observation_id": ["x"]})
    with pytest.raises(ValueError):
        assert_privacy_safe({"bbox": {"x": 0, "y": 0, "w": 1, "h": 1, "pixels": 3}})


def test_synthetic_scene_is_deterministic_and_projects_consistently():
    a, b = SyntheticScene(), SyntheticScene()
    assert a.ground_truth(100) == b.ground_truth(100)
    assert a.calibration.rms_m is not None and a.calibration.rms_m < 1e-3  # control points are rounded to 0.01 px
    forklift = next(d for d in a.ground_truth(0) if d.label == "forklift")
    x, y = a.calibration.to_floor((forklift.x1 + forklift.x2) / 2.0, forklift.y2)
    assert math.isclose(x, 35.0, abs_tol=1e-6) and math.isclose(y, 6.0, abs_tol=1e-6)


def test_pipeline_on_the_synthetic_scene_yields_two_tracks_close_enough_for_the_proximity_rule():
    scene = SyntheticScene()
    batches: list[list[dict]] = []
    source = FrameSource(((i, None) for i in range(scene.frame_count)), scene.fps, 1280, 720, scene.frame_count)
    stats = run(
        source, lambda i, _f: scene.ground_truth(i), scene.calibration, "S1", START, 5, batches.append, 50, IouTracker()
    )
    obs = [o for b in batches for o in b]
    assert stats.observations == len(obs)
    assert {o["object_class"] for o in obs} == {"forklift", "person"}
    assert len({o["track_id"] for o in obs}) == 2
    by_time: dict[str, dict[str, dict]] = {}
    for o in obs:
        by_time.setdefault(o["observed_at"], {})[o["object_class"]] = o
    distances = [
        math.hypot(
            p["forklift"]["floor_position"]["x"] - p["person"]["floor_position"]["x"],
            p["forklift"]["floor_position"]["y"] - p["person"]["floor_position"]["y"],
        )
        for p in by_time.values()
        if len(p) == 2
    ]
    assert min(distances) < 3.0
    moving = [o["speed_mps"] for o in obs if o["object_class"] == "forklift" and "speed_mps" in o]
    assert moving and max(moving) == pytest.approx(1.5, abs=0.05)


def test_detection_dataclass_is_frozen():
    d = Detection(0, 0, 1, 1, 0.5, "person")
    with pytest.raises(AttributeError):
        d.score = 1.0  # type: ignore[misc]


def test_an_implausible_speed_from_a_box_jump_is_not_reported():
    m = ObservationMapper("S1", CAL, START, 1000, 1000)
    t = track(80, 300, 120, 500)
    m.update_floor(t, 0.0)
    t.box = (880, 300, 920, 500)  # 8 m in 0.2 s = 40 m/s
    floor = m.update_floor(t, 0.2)
    obs = m.map(t, 5, 0.2, "STABLE", floor)
    assert "speed_mps" not in obs
    assert obs["heading_deg"] == pytest.approx(0.0)
