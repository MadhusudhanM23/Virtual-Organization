import pytest

from vorg_vision.detector import Detection
from vorg_vision.tracker import IouTracker, TrackState, iou


def box(x, y, w=40, h=80, score=0.9, label="person"):
    return Detection(x, y, x + w, y + h, score, label)


def test_iou_basics():
    assert iou((0, 0, 10, 10), (0, 0, 10, 10)) == 1.0
    assert iou((0, 0, 10, 10), (20, 20, 30, 30)) == 0.0
    assert iou((0, 0, 10, 10), (5, 0, 15, 10)) == pytest.approx(1 / 3)


def test_track_is_confirmed_after_min_hits_and_keeps_its_id():
    t = IouTracker(min_hits=3)
    assert t.update([box(100, 100)], 0) == []
    assert t.update([box(103, 100)], 1) == []
    out = t.update([box(106, 100)], 2)
    assert [x.track_id for x in out] == [1]
    for f in range(3, 20):
        out = t.update([box(100 + 3 * f, 100)], f)
        assert [x.track_id for x in out] == [1]


def test_identity_is_provisional_until_stable():
    t = IouTracker(min_hits=2, stable_hits=5)
    statuses = []
    for f in range(6):
        for tr in t.update([box(100 + f, 100)], f):
            statuses.append(t.identity_status(tr))
    assert statuses == ["PROVISIONAL", "PROVISIONAL", "PROVISIONAL", "STABLE", "STABLE"]


def test_two_objects_get_distinct_ids_and_classes_never_mix():
    t = IouTracker(min_hits=1)
    out = t.update([box(100, 100), box(102, 100, label="truck")], 0)
    assert sorted((x.track_id, x.label) for x in out) == [(1, "person"), (2, "truck")]
    out = t.update([box(104, 100, label="truck"), box(101, 100)], 1)
    assert sorted((x.track_id, x.label) for x in out) == [(1, "person"), (2, "truck")]


def test_low_score_detection_keeps_a_confirmed_track_alive_but_never_starts_one():
    t = IouTracker(high=0.5, low=0.1, min_hits=2)
    t.update([box(100, 100)], 0)
    t.update([box(102, 100)], 1)
    out = t.update([box(104, 100, score=0.2)], 2)
    assert [x.track_id for x in out] == [1]
    fresh = IouTracker(high=0.5, low=0.1, min_hits=1)
    assert fresh.update([box(100, 100, score=0.2)], 0) == []
    assert fresh.tracks == []


def test_lost_track_is_recovered_within_max_age_and_dropped_after():
    t = IouTracker(min_hits=1, max_age=3)
    t.update([box(100, 100)], 0)
    t.update([], 1)
    assert t.tracks[0].state == TrackState.LOST
    out = t.update([box(100, 100)], 2)
    assert [x.track_id for x in out] == [1]
    for f in range(3, 8):
        t.update([], f)
    assert t.tracks == []
    out = t.update([box(100, 100)], 8)
    assert [x.track_id for x in out] == [2]  # ids are never reused


def test_velocity_prediction_bridges_a_gap_of_a_fast_mover():
    t = IouTracker(min_hits=1, max_age=5, velocity_damping=0.0)
    t.update([box(100, 100)], 0)
    t.update([box(115, 100)], 1)
    t.update([], 2)  # missed: the box would be at 130
    out = t.update([box(145, 100)], 3)  # IoU with the last box (115) is 0.14, below match_iou
    assert [x.track_id for x in out] == [1]


def test_tracking_is_deterministic():
    frames = [[box(100 + 5 * f, 100), box(400 - 5 * f, 100), box(250, 300 + f, score=0.3)] for f in range(30)]

    def ids():
        t = IouTracker(min_hits=2)
        return [[(x.track_id, x.box) for x in t.update(d, f)] for f, d in enumerate(frames)]

    assert ids() == ids()


def test_invalid_thresholds_are_rejected():
    with pytest.raises(ValueError):
        IouTracker(high=0.2, low=0.5)
