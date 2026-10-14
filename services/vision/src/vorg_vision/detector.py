"""YOLOX object detection with onnxruntime on the CPU (ADR-25, ADR-64).

The pre- and post-processing follow the official YOLOX ONNXRuntime demo (Apache-2.0,
https://github.com/Megvii-BaseDetection/YOLOX/tree/main/demo/ONNXRuntime): letterbox to the model input with pad value
114 at the top-left, BGR, float32 in 0..255 (no mean/std for the 0.1.1rc0 weights), decode the grid outputs with
strides 8/16/32, then class-wise NMS. Only boxes are returned; the frame itself never leaves this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

# COCO-80 class names in YOLOX output order.
COCO_CLASSES: tuple[str, ...] = (
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "airplane",
    "bus",
    "train",
    "truck",
    "boat",
    "traffic light",
    "fire hydrant",
    "stop sign",
    "parking meter",
    "bench",
    "bird",
    "cat",
    "dog",
    "horse",
    "sheep",
    "cow",
    "elephant",
    "bear",
    "zebra",
    "giraffe",
    "backpack",
    "umbrella",
    "handbag",
    "tie",
    "suitcase",
    "frisbee",
    "skis",
    "snowboard",
    "sports ball",
    "kite",
    "baseball bat",
    "baseball glove",
    "skateboard",
    "surfboard",
    "tennis racket",
    "bottle",
    "wine glass",
    "cup",
    "fork",
    "knife",
    "spoon",
    "bowl",
    "banana",
    "apple",
    "sandwich",
    "orange",
    "broccoli",
    "carrot",
    "hot dog",
    "pizza",
    "donut",
    "cake",
    "chair",
    "couch",
    "potted plant",
    "bed",
    "dining table",
    "toilet",
    "tv",
    "laptop",
    "mouse",
    "remote",
    "keyboard",
    "cell phone",
    "microwave",
    "oven",
    "toaster",
    "sink",
    "refrigerator",
    "book",
    "clock",
    "vase",
    "scissors",
    "teddy bear",
    "hair drier",
    "toothbrush",
)


@dataclass(frozen=True)
class Detection:
    """One detected box in source-image pixels (x1, y1) top-left to (x2, y2) bottom-right."""

    x1: float
    y1: float
    x2: float
    y2: float
    score: float
    label: str

    @property
    def width(self) -> float:
        return self.x2 - self.x1

    @property
    def height(self) -> float:
        return self.y2 - self.y1


def letterbox(image: np.ndarray, size: tuple[int, int]) -> tuple[np.ndarray, float]:
    """Resizes keeping the aspect ratio into a (h, w) canvas padded with 114; returns CHW float32 and the ratio."""
    import cv2

    h_in, w_in = size
    ratio = min(h_in / image.shape[0], w_in / image.shape[1])
    new_w, new_h = int(image.shape[1] * ratio), int(image.shape[0] * ratio)
    resized = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((h_in, w_in, 3), 114, dtype=np.uint8)
    canvas[:new_h, :new_w] = resized
    return np.ascontiguousarray(canvas.transpose(2, 0, 1), dtype=np.float32), ratio


def decode(outputs: np.ndarray, size: tuple[int, int], strides: tuple[int, ...] = (8, 16, 32)) -> np.ndarray:
    """Turns raw YOLOX grid outputs (N, 5 + C) into (cx, cy, w, h, obj, cls...) in letterboxed input pixels."""
    grids = []
    expanded = []
    for stride in strides:
        hs, ws = size[0] // stride, size[1] // stride
        xv, yv = np.meshgrid(np.arange(ws), np.arange(hs))
        grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
        expanded.append(np.full((hs * ws, 1), stride))
    grid = np.concatenate(grids, 0)
    stride_col = np.concatenate(expanded, 0)
    out = outputs.copy()
    out[:, :2] = (out[:, :2] + grid) * stride_col
    out[:, 2:4] = np.exp(out[:, 2:4]) * stride_col
    return out


def nms(boxes: np.ndarray, scores: np.ndarray, iou_threshold: float) -> list[int]:
    """Greedy non-maximum suppression over xyxy boxes; returns kept indices, best score first."""
    order = scores.argsort()[::-1]
    keep: list[int] = []
    areas = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        xx1 = np.maximum(boxes[i, 0], boxes[order[1:], 0])
        yy1 = np.maximum(boxes[i, 1], boxes[order[1:], 1])
        xx2 = np.minimum(boxes[i, 2], boxes[order[1:], 2])
        yy2 = np.minimum(boxes[i, 3], boxes[order[1:], 3])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        iou = inter / (areas[i] + areas[order[1:]] - inter + 1e-9)
        order = order[1:][iou <= iou_threshold]
    return keep


def postprocess(
    raw: np.ndarray,
    size: tuple[int, int],
    ratio: float,
    score_threshold: float,
    nms_threshold: float,
    classes: tuple[str, ...] = COCO_CLASSES,
    wanted: frozenset[str] | None = None,
) -> list[Detection]:
    """Decodes one image's output (N, 5 + C) to detections in source pixels, class-wise NMS."""
    pred = decode(raw, size)
    cxcywh = pred[:, :4]
    scores_all = pred[:, 4:5] * pred[:, 5:]
    boxes = np.empty_like(cxcywh)
    boxes[:, 0] = cxcywh[:, 0] - cxcywh[:, 2] / 2.0
    boxes[:, 1] = cxcywh[:, 1] - cxcywh[:, 3] / 2.0
    boxes[:, 2] = cxcywh[:, 0] + cxcywh[:, 2] / 2.0
    boxes[:, 3] = cxcywh[:, 1] + cxcywh[:, 3] / 2.0
    boxes /= ratio
    cls_ids = scores_all.argmax(1)
    cls_scores = scores_all[np.arange(len(cls_ids)), cls_ids]
    mask = cls_scores >= score_threshold
    out: list[Detection] = []
    for cls in np.unique(cls_ids[mask]):
        label = classes[int(cls)]
        if wanted is not None and label not in wanted:
            continue
        idx = np.where(mask & (cls_ids == cls))[0]
        for k in nms(boxes[idx], cls_scores[idx], nms_threshold):
            b = boxes[idx[k]]
            out.append(Detection(float(b[0]), float(b[1]), float(b[2]), float(b[3]), float(cls_scores[idx[k]]), label))
    out.sort(key=lambda d: (-d.score, d.x1, d.y1))
    return out


class YoloxDetector:
    """Runs a YOLOX ONNX model on BGR frames. CPU execution provider only (the host GPU is integrated)."""

    def __init__(
        self,
        model_path: Path,
        score_threshold: float = 0.35,
        nms_threshold: float = 0.45,
        wanted: frozenset[str] | None = None,
        threads: int | None = None,
    ) -> None:
        import onnxruntime as ort

        opts = ort.SessionOptions()
        if threads:
            opts.intra_op_num_threads = threads
        self._session = ort.InferenceSession(str(model_path), sess_options=opts, providers=["CPUExecutionProvider"])
        inp = self._session.get_inputs()[0]
        self._input_name = inp.name
        self.input_size: tuple[int, int] = (int(inp.shape[2]), int(inp.shape[3]))
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self.wanted = wanted

    def detect(self, frame: np.ndarray) -> list[Detection]:
        blob, ratio = letterbox(frame, self.input_size)
        raw = self._session.run(None, {self._input_name: blob[None, :, :, :]})[0][0]
        return postprocess(raw, self.input_size, ratio, self.score_threshold, self.nms_threshold, wanted=self.wanted)
