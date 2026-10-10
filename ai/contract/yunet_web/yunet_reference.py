"""YuNet 얼굴 검출 기준 구현 (OpenCV 없이 ONNX Runtime만 사용).

서버(privacy.py)는 OpenCV FaceDetectorYN으로 YuNet을 돌린다. 브라우저에는 OpenCV가 없으므로
ONNX Runtime Web으로 같은 결과를 내야 한다. 이 파일은 그 과정을 단계별로 적은 기준 구현이며,
yunet.mjs(브라우저용)는 이 코드를 그대로 옮긴 것이다.

실행: python yunet_reference.py images/t01_lena.jpg
"""
import math
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
MODEL = HERE / "face_yunet_s_dynamic.onnx"   # 입력 크기를 가변으로 바꾼 모델 (가중치는 서버와 같음)
SCORE_THRESHOLD = 0.55                        # 서버 FACE_SCORE와 같음
NMS_THRESHOLD = 0.3
TOP_K = 5000
STRIDES = (8, 16, 32)


def preprocess(bgr: np.ndarray) -> tuple[np.ndarray, int, int]:
    """1) 가로·세로를 32의 배수로 올림해 오른쪽·아래를 0으로 채운다 (크기를 바꾸지 않음).
    2) BGR 순서, 0~255 실수 그대로 (정규화 없음), NCHW."""
    h, w = bgr.shape[:2]
    pad_h = ((h - 1) // 32 + 1) * 32
    pad_w = ((w - 1) // 32 + 1) * 32
    padded = np.zeros((pad_h, pad_w, 3), np.float32)
    padded[:h, :w] = bgr
    return padded.transpose(2, 0, 1)[None], pad_w, pad_h


def decode(outputs: dict, pad_w: int, pad_h: int, score_threshold: float = SCORE_THRESHOLD) -> list[list[float]]:
    """출력 12개(cls/obj/bbox/kps × 간격 8/16/32)를 상자로 바꾼다.
    돌려주는 값: [x, y, w, h, 눈·코·입 좌표 10개, score] (OpenCV FaceDetectorYN과 같은 순서)"""
    faces = []
    for s in STRIDES:
        cols, rows = pad_w // s, pad_h // s
        cls = outputs[f"cls_{s}"].reshape(-1)
        obj = outputs[f"obj_{s}"].reshape(-1)
        bbox = outputs[f"bbox_{s}"].reshape(-1, 4)
        kps = outputs[f"kps_{s}"].reshape(-1, 10)
        for r in range(rows):
            for c in range(cols):
                i = r * cols + c
                score = math.sqrt(min(max(float(cls[i]), 0.0), 1.0) * min(max(float(obj[i]), 0.0), 1.0))
                if score < score_threshold:
                    continue
                cx = (c + bbox[i, 0]) * s
                cy = (r + bbox[i, 1]) * s
                bw = math.exp(bbox[i, 2]) * s
                bh = math.exp(bbox[i, 3]) * s
                lm = []
                for n in range(5):
                    lm += [(kps[i, 2 * n] + c) * s, (kps[i, 2 * n + 1] + r) * s]
                faces.append([cx - bw / 2, cy - bh / 2, bw, bh, *lm, score])
    return nms(faces)


def iou(a, b) -> float:
    ix = max(0.0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = a[2] * a[3] + b[2] * b[3] - inter
    return inter / union if union > 0 else 0.0


def nms(faces: list, threshold: float = NMS_THRESHOLD, top_k: int = TOP_K) -> list:
    """점수 높은 순으로 고르고, 이미 고른 상자와 IoU가 threshold보다 크면 버린다."""
    faces = sorted(faces, key=lambda f: -f[-1])[:top_k]
    keep = []
    for f in faces:
        if all(iou(f, k) <= threshold for k in keep):
            keep.append(f)
    return keep


def detect(bgr: np.ndarray, session=None) -> list[list[float]]:
    import onnxruntime as ort
    session = session or ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    blob, pad_w, pad_h = preprocess(bgr)
    names = [o.name for o in session.get_outputs()]
    outs = dict(zip(names, session.run(None, {"input": blob})))
    return decode(outs, pad_w, pad_h)


if __name__ == "__main__":
    import cv2
    for p in sys.argv[1:]:
        for f in detect(cv2.imread(p)):
            print(p, [round(v, 1) for v in f[:4]], round(f[-1], 3))
