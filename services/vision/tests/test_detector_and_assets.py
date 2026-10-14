import numpy as np

from vorg_vision.assets import read_manifest, sha256_of, verify_assets
from vorg_vision.detector import COCO_CLASSES, decode, nms, postprocess


def test_coco_has_80_classes_and_no_forklift():
    assert len(COCO_CLASSES) == 80
    assert "forklift" not in COCO_CLASSES


def test_decode_places_a_cell_prediction_on_its_grid():
    size = (64, 64)
    n = (8 * 8) + (4 * 4) + (2 * 2)
    raw = np.zeros((n, 5 + 80), dtype=np.float32)
    # cell (x=3, y=2) of the stride-8 grid -> index 2 * 8 + 3
    raw[19, :4] = [0.5, 0.5, np.log(2.0), np.log(4.0)]
    out = decode(raw, size)
    assert np.allclose(out[19, :4], [(3 + 0.5) * 8, (2 + 0.5) * 8, 16.0, 32.0])


def test_postprocess_scales_back_and_filters_by_score_and_class():
    size = (64, 64)
    n = 84
    raw = np.zeros((n, 85), dtype=np.float32)
    raw[19, :4] = [0.5, 0.5, np.log(2.0), np.log(4.0)]
    raw[19, 4] = 0.9
    raw[19, 5 + 0] = 0.9  # person
    raw[20, :4] = [0.5, 0.5, np.log(2.0), np.log(4.0)]
    raw[20, 4] = 0.9
    raw[20, 5 + 7] = 0.2  # truck, below threshold
    dets = postprocess(raw, size, ratio=0.5, score_threshold=0.5, nms_threshold=0.45)
    assert len(dets) == 1
    d = dets[0]
    assert d.label == "person" and abs(d.score - 0.81) < 1e-6
    assert np.allclose([d.x1, d.y1, d.x2, d.y2], [40.0, 8.0, 72.0, 72.0])
    assert postprocess(raw, size, 0.5, 0.5, 0.45, wanted=frozenset({"truck"})) == []


def test_nms_keeps_the_best_of_overlapping_boxes():
    boxes = np.array([[0, 0, 10, 10], [1, 1, 11, 11], [50, 50, 60, 60]], dtype=np.float32)
    scores = np.array([0.8, 0.9, 0.5])
    assert nms(boxes, scores, 0.5) == [1, 2]


def test_manifest_verification(tmp_path):
    f = tmp_path / "a.bin"
    f.write_bytes(b"hello")
    digest = sha256_of(f)
    manifest = tmp_path / "m.sha256"
    manifest.write_text(f"# comment\n{digest}  a.bin\n{'0' * 64}  missing.bin\n", encoding="utf-8")
    assert read_manifest(manifest)["a.bin"] == digest
    assert verify_assets(tmp_path, manifest) == ["missing: missing.bin"]
    f.write_bytes(b"tampered")
    assert "sha256 mismatch: a.bin" in verify_assets(tmp_path, manifest)
