import math

import numpy as np
import pytest

from vorg_vision.homography import (
    Calibration,
    ControlPoint,
    apply,
    convex_hull,
    fit_homography,
    inside_polygon,
    rms_error_m,
)

# A known projective map: floor = H_true * pixel.
H_TRUE = np.array([[0.01, 0.002, 30.0], [0.0005, -0.012, 45.0], [0.00001, 0.00002, 1.0]])


def project(u, v):
    return apply(H_TRUE, u, v)


def pts(pixels):
    return [ControlPoint(u, v, *project(u, v)) for u, v in pixels]


def test_four_point_fit_reproduces_the_known_map():
    h = fit_homography(pts([(0, 0), (1000, 0), (1000, 800), (0, 800)]))
    for u, v in [(500, 400), (123, 456), (999, 1), (10, 790)]:
        x, y = apply(h, u, v)
        ex, ey = project(u, v)
        assert math.isclose(x, ex, abs_tol=1e-6)
        assert math.isclose(y, ey, abs_tol=1e-6)


def test_four_points_report_no_residual_and_more_points_report_it():
    four = pts([(0, 0), (1000, 0), (1000, 800), (0, 800)])
    assert rms_error_m(fit_homography(four), four) is None
    six = [*four, ControlPoint(500, 400, *project(500, 400)), ControlPoint(200, 600, *project(200, 600))]
    assert rms_error_m(fit_homography(six), six) < 1e-6


def test_noisy_points_give_a_measured_positive_residual():
    six = pts([(0, 0), (1000, 0), (1000, 800), (0, 800), (500, 400), (200, 600)])
    noisy = [ControlPoint(p.u, p.v, p.x + (0.1 if i % 2 else -0.1), p.y) for i, p in enumerate(six)]
    rms = rms_error_m(fit_homography(noisy), noisy)
    assert 0.0 < rms < 0.2


def test_collinear_points_are_rejected():
    with pytest.raises(ValueError):
        fit_homography([ControlPoint(i, i, i, i) for i in range(5)])


def test_fewer_than_four_points_are_rejected():
    with pytest.raises(ValueError):
        fit_homography(pts([(0, 0), (1, 0), (0, 1)]))


def test_hull_and_point_in_polygon():
    hull = convex_hull([(0, 0), (10, 0), (10, 10), (0, 10), (5, 5)])
    assert len(hull) == 4
    assert inside_polygon(hull, 5, 5)
    assert inside_polygon(hull, 0, 5)  # on an edge
    assert not inside_polygon(hull, 11, 5)
    assert not inside_polygon(hull, -0.1, -0.1)


def test_calibration_maps_inside_and_refuses_outside_the_zone():
    cal = Calibration(1000, 800, pts([(100, 100), (900, 100), (900, 700), (100, 700)]))
    inside = cal.to_floor(500, 400)
    assert inside is not None
    assert math.isclose(inside[0], project(500, 400)[0], abs_tol=1e-6)
    assert cal.to_floor(50, 50) is None


def test_scaled_calibration_maps_the_same_floor_point():
    cal = Calibration(1000, 800, pts([(100, 100), (900, 100), (900, 700), (100, 700)]))
    half = cal.scaled(500, 400)
    a = cal.to_floor(400, 300)
    b = half.to_floor(200, 150)
    assert a is not None and b is not None
    assert math.isclose(a[0], b[0], abs_tol=1e-6) and math.isclose(a[1], b[1], abs_tol=1e-6)


def test_registry_json_round_trip():
    doc = {
        "image_width": 1920,
        "image_height": 1080,
        "floor_z_m": 0.0,
        "floor_id": "f",
        "points": [
            {"u": 0, "v": 0, "x": 1, "y": 1},
            {"u": 100, "v": 0, "x": 2, "y": 1},
            {"u": 100, "v": 100, "x": 2, "y": 0},
            {"u": 0, "v": 100, "x": 1, "y": 0},
        ],
    }
    cal = Calibration.from_json(doc)
    x, y = cal.to_floor(50, 50)
    assert math.isclose(x, 1.5, abs_tol=1e-9) and math.isclose(y, 0.5, abs_tol=1e-9)
