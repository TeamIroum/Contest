"""개인정보 마스킹: 얼굴과 차량 번호판을 찾아 가린다.

처리 순서 (pipeline.py가 호출)
  업로드 → images.decode_upload (방향 보정, 메타데이터 제거)
        → mask_image (원본 해상도에서 검출 → 가림)
        → 축소 → 저장·AI 전송
  원본 해상도에서 검출하는 이유: 축소한 뒤에 찾으면 멀리 있는 작은 얼굴과 번호판을 놓친다.

검출기
  얼굴  : YuNet (models/face_yunet_s.onnx, BSD-3, OpenCV 내장 FaceDetectorYN으로 실행)
          모델 파일이 없으면 OpenCV Haar(정면+측면)로 대신한다. 보고서에 어떤 검출기를 썼는지 남긴다.
  번호판: 아래를 모두 돌려서 하나라도 찾은 곳을 가린다.
          1) 사전 학습 모델: open-image-models의 번호판 검출기(YOLOv9, MIT). 여러 나라 번호판으로 이미 학습돼
             있어 따로 학습하지 않고 바로 쓴다 (PLATE_PRETRAINED)
          2) 팀이 한국 번호판으로 추가 학습한 모델이 있으면 함께 쓴다 (PLATE_MODEL)
          3) 규칙 기반 검출기 (밝은 직사각형 + 글자 모양 덩어리 4개 이상)
  차량  : 사전 학습 물체 검출기(RF-DETR, COCO)로 자동차·버스·트럭·오토바이를 찾고, 그 안에서 번호판을
          하나도 찾지 못했으면 차량 전체를 거칠게 뭉갠다 (VEHICLE_FALLBACK). 번호판 검출이 놓쳐도
          번호가 남지 않게 하는 안전망이다. 점검 사진에서 차량은 배경이라 가려도 판독에 지장이 적다.
          기획안·앱 마스킹 안내(contract/APP_MASKING_GUIDE.md)와 같은 규칙이며, 차의 크기·방향과 관계없이 적용한다.

가리는 방법
  얼굴  : 아주 거친 모자이크 + 흐림. 가로 6칸 안팎으로 뭉개서 복원할 정보가 남지 않게 한다.
  번호판: 단색으로 덮는다. 글자는 흐림·모자이크만으로는 복원 시도가 가능하므로 아예 지운다.

실패 원칙 (fail-closed)
  검출이나 가림 중 오류가 나면 예외를 그대로 올린다. pipeline.py는 이때 사진을 저장하지도,
  AI에 보내지도 않고 다시 찍어 달라고 안내한다. 가리지 못한 사진이 밖으로 나가는 경로는 없다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np

from config import BASE_DIR

# ---- 설정 (환경 변수로 바꿀 수 있음) ----
FACE_MODEL = os.environ.get("FACE_MODEL", str(BASE_DIR / "models" / "face_yunet_s.onnx"))
PLATE_MODEL = os.environ.get("PLATE_MODEL", "")                     # 한국 번호판 YOLO ONNX (선택)
PLATE_HEURISTIC = os.environ.get("PLATE_HEURISTIC", "1") == "1"
PLATE_PRETRAINED = os.environ.get("PLATE_PRETRAINED", "yolo-v9-s-608-license-plate-end2end")  # 비우면 끔
PLATE_PRETRAINED_CONF = float(os.environ.get("PLATE_PRETRAINED_CONF", "0.15"))  # 낮을수록 더 많이 가림
VEHICLE_FALLBACK = os.environ.get("VEHICLE_FALLBACK", "rf-detr-nano-384-coco")             # 비우면 끔
VEHICLE_CONF = float(os.environ.get("VEHICLE_CONF", "0.4"))
VEHICLE_LABELS = {"car", "bus", "truck", "motorcycle"}
DETECT_MAX_SIDE = int(os.environ.get("DETECT_MAX_SIDE", "2400"))    # 검출에 쓰는 최대 해상도
FACE_SCORE = float(os.environ.get("FACE_SCORE", "0.55"))            # 낮을수록 더 많이 가림
PLATE_SCORE = float(os.environ.get("PLATE_SCORE", "0.45"))
FACE_PAD = 0.25
PLATE_PAD = 0.2
PLATE_FILL = (60, 60, 60)


@dataclass
class Box:
    x: int
    y: int
    w: int
    h: int
    kind: str          # face | plate | vehicle
    score: float
    source: str        # 어떤 검출기가 찾았는지

    def padded(self, pad: float, W: int, H: int) -> "Box":
        dx, dy = int(self.w * pad), int(self.h * pad)
        x0, y0 = max(0, self.x - dx), max(0, self.y - dy)
        x1, y1 = min(W, self.x + self.w + dx), min(H, self.y + self.h + dy)
        return Box(x0, y0, x1 - x0, y1 - y0, self.kind, self.score, self.source)

    def scaled(self, k: float) -> "Box":
        return Box(int(self.x * k), int(self.y * k), int(round(self.w * k)), int(round(self.h * k)),
                   self.kind, self.score, self.source)


@dataclass
class MaskReport:
    faces: int = 0
    plates: int = 0
    vehicles: int = 0
    boxes: list = field(default_factory=list)      # 정규화 좌표 [x1, y1, x2, y2]
    detectors: dict = field(default_factory=dict)
    masked_area_ratio: float = 0.0

    def as_dict(self) -> dict:
        return {"faces": self.faces, "plates": self.plates, "vehicles": self.vehicles, "boxes": self.boxes,
                "detectors": self.detectors, "masked_area_ratio": self.masked_area_ratio}


# =====================================================================
# 얼굴 검출
# =====================================================================
class YuNetFaces:
    name = "yunet"

    def __init__(self, model_path: str, score: float = FACE_SCORE):
        self.det = cv2.FaceDetectorYN.create(model_path, "", (320, 320), score, 0.3, 5000)

    def detect(self, img: np.ndarray) -> list[Box]:
        h, w = img.shape[:2]
        self.det.setInputSize((w, h))
        _, faces = self.det.detect(img)
        out = []
        for f in (faces if faces is not None else []):
            x, y, bw, bh = (int(v) for v in f[:4])
            out.append(Box(x, y, bw, bh, "face", float(f[-1]), self.name))
        return out


class HaarFaces:
    """모델 파일이 없을 때 쓰는 대체 검출기. 정면과 좌우 측면을 본다."""
    name = "haar"

    def __init__(self):
        d = cv2.data.haarcascades
        self.front = cv2.CascadeClassifier(d + "haarcascade_frontalface_default.xml")
        self.profile = cv2.CascadeClassifier(d + "haarcascade_profileface.xml")

    def detect(self, img: np.ndarray) -> list[Box]:
        g = cv2.equalizeHist(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
        W = g.shape[1]
        out = []
        for (x, y, w, h) in self.front.detectMultiScale(g, 1.1, 4, minSize=(16, 16)):
            out.append(Box(x, y, w, h, "face", 0.6, self.name))
        for (x, y, w, h) in self.profile.detectMultiScale(g, 1.1, 4, minSize=(16, 16)):
            out.append(Box(x, y, w, h, "face", 0.5, self.name))
        for (x, y, w, h) in self.profile.detectMultiScale(cv2.flip(g, 1), 1.1, 4, minSize=(16, 16)):
            out.append(Box(W - x - w, y, w, h, "face", 0.5, self.name))
        return out


# =====================================================================
# 번호판 검출
# =====================================================================
class HeuristicPlates:
    """규칙 기반 번호판 검출.

    한국 번호판은 가로로 긴 직사각형(신형 약 4.7:1, 구형 약 2:1)이고 안에 글자 7~8개가 줄지어 있다.
    1) 세로 경계(글자 획)가 촘촘한 가로 띠를 찾고  2) 가로세로 비율로 거른 뒤
    3) 안에 글자 크기의 덩어리가 4개 이상 같은 줄에 있는지 확인한다.
    학습 모델이 준비되기 전까지 쓰는 임시 방식이며, 실제 사진으로 놓치는 비율을 재야 한다.
    """
    name = "heuristic"
    WORK_WIDTH = 1600

    def detect(self, img: np.ndarray) -> list[Box]:
        H0, W0 = img.shape[:2]
        k = min(1.0, self.WORK_WIDTH / W0)
        small = cv2.resize(img, (int(W0 * k), int(H0 * k)), interpolation=cv2.INTER_AREA) if k < 1 else img
        g = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        g = cv2.bilateralFilter(g, 5, 40, 40)
        H, W = g.shape
        cands = []
        for (x, y, w, h) in self._candidates(g):
            if h < 8 or w < 24 or h > H * 0.25 or w > W * 0.6 or not 1.6 <= w / h <= 7.5:
                continue
            score = self._verify(g, x, y, w, h)
            if score >= PLATE_SCORE:
                cands.append(Box(x, y, w, h, "plate", score, self.name))
        boxes = _nms(cands, 0.3)
        return [b.scaled(1 / k) for b in boxes] if k < 1 else boxes

    @staticmethod
    def _candidates(g) -> list[tuple]:
        """번호판 후보 상자를 세 가지 방식으로 모은다. 한 방식이 놓친 것을 다른 방식이 잡도록."""
        out = []
        # 1) 글자 획의 세로 경계가 촘촘한 가로 띠
        sx = cv2.convertScaleAbs(cv2.Sobel(g, cv2.CV_16S, 1, 0, ksize=3))
        _, bw = cv2.threshold(sx, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        for kw in (9, 17, 29):
            cl = cv2.morphologyEx(bw, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (kw, 3)))
            cl = cv2.morphologyEx(cl, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
            out += [cv2.boundingRect(c) for c in cv2.findContours(cl, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]]
        # 2) 테두리가 사각형인 윤곽
        for lo, hi in ((40, 120), (80, 200)):
            e = cv2.dilate(cv2.Canny(g, lo, hi), np.ones((2, 2), np.uint8))
            for c in cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)[0]:
                if cv2.contourArea(c) < 150:
                    continue
                a = cv2.approxPolyDP(c, 0.04 * cv2.arcLength(c, True), True)
                if 4 <= len(a) <= 6:
                    out.append(cv2.boundingRect(a))
        # 3) 주변보다 밝은 판
        for blk in (31, 61):
            b = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, blk, -8)
            b = cv2.morphologyEx(b, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
            out += [cv2.boundingRect(c) for c in cv2.findContours(b, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]]
        return out

    @staticmethod
    def _verify(g, x, y, w, h) -> float:
        """띠 안에 글자 모양 덩어리가 나란히 있는지 0~1 점수로 본다.
        밝은 바탕·어두운 글자와 그 반대, 테두리를 포함한 영역과 테두리를 잘라낸 안쪽 영역을 모두 확인한다
        (작은 번호판은 글자가 테두리에 붙어 한 덩어리로 보이기 때문)."""
        pad = max(2, h // 6)
        roi0 = g[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
        if roi0.size == 0:
            return 0.0
        rois = [roi0]
        ih, iw = roi0.shape
        inner = roi0[int(ih * 0.18):int(ih * 0.86), int(iw * 0.05):int(iw * 0.95)]
        if inner.size and min(inner.shape) >= 6:
            rois.append(inner)
        best = 0.0
        for roi in rois:
            if roi.shape[0] < 24:  # 작은 번호판은 키워서 글자를 분리한다
                f = 24 / roi.shape[0]
                roi = cv2.resize(roi, (int(roi.shape[1] * f), 24), interpolation=cv2.INTER_CUBIC)
            rh, rw = roi.shape
            for inv in (cv2.THRESH_BINARY_INV, cv2.THRESH_BINARY):
                _, b = cv2.threshold(roi, 0, 255, inv + cv2.THRESH_OTSU)
                n, _, stats, _ = cv2.connectedComponentsWithStats(b, 8)
                chars = []
                for i in range(1, n):
                    cx, cy, cw, ch, area = stats[i]
                    if 0.3 * rh <= ch <= 0.98 * rh and 2 <= cw <= 0.25 * rw and area >= 0.15 * cw * ch:
                        chars.append((cx, cy, cw, ch))
                if len(chars) < 4:
                    continue
                ys = np.array([c[1] + c[3] / 2 for c in chars])
                hs = np.array([c[3] for c in chars])
                aligned = np.mean(np.abs(ys - np.median(ys)) < 0.25 * np.median(hs))   # 같은 줄에 있는지
                similar = np.mean(np.abs(hs - np.median(hs)) < 0.3 * np.median(hs))    # 높이가 비슷한지
                count = min(len(chars), 8) / 8
                best = max(best, aligned * (0.5 * similar + 0.5 * count))
        return float(best)


class _OpenImageModel:
    """open-image-models(MIT) 사전 학습 모델 공통 부분. 처음 쓸 때 모델 파일을 내려받아 캐시에 둔다."""

    def __init__(self, model: str, conf: float):
        import warnings
        from open_image_models import create_detector
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            self.det = create_detector(model, conf_thresh=conf)
        self.model = model

    def _predict(self, img):
        return self.det.predict(img)


class PretrainedPlates(_OpenImageModel):
    """여러 나라 번호판으로 학습된 공개 모델. 한국 번호판도 직사각형 판이라 상당 부분 잡지만,
    실제 한국 사진으로 성능을 재고 부족하면 이 모델에서 출발해 소량 추가 학습한다."""
    name = "pretrained"

    def detect(self, img: np.ndarray) -> list[Box]:
        out = []
        for r in self._predict(img):
            b = r.bounding_box
            out.append(Box(int(b.x1), int(b.y1), int(b.x2 - b.x1), int(b.y2 - b.y1), "plate",
                           float(r.confidence), self.name))
        return out


class VehicleDetector(_OpenImageModel):
    name = "vehicle"

    def detect(self, img: np.ndarray) -> list[Box]:
        out = []
        for r in self._predict(img):
            if r.label in VEHICLE_LABELS:
                b = r.bounding_box
                out.append(Box(int(b.x1), int(b.y1), int(b.x2 - b.x1), int(b.y2 - b.y1), "vehicle",
                               float(r.confidence), self.name))
        return out


class OnnxPlates:
    """한국 번호판으로 학습한 YOLO(v8 형식, 클래스 1개) ONNX 모델. cv2.dnn으로 실행해 추가 라이브러리가 필요 없다.
    학습·내보내기 방법은 MASKING_AND_SERVER.md 참고."""
    name = "onnx"
    SIZE = 640

    def __init__(self, path: str, score: float = 0.25):
        self.net = cv2.dnn.readNetFromONNX(path)
        self.score = score

    def detect(self, img: np.ndarray) -> list[Box]:
        H, W = img.shape[:2]
        k = self.SIZE / max(H, W)
        canvas = np.full((self.SIZE, self.SIZE, 3), 114, np.uint8)
        rs = cv2.resize(img, (int(W * k), int(H * k)))
        canvas[:rs.shape[0], :rs.shape[1]] = rs
        blob = cv2.dnn.blobFromImage(canvas, 1 / 255.0, (self.SIZE, self.SIZE), swapRB=True)
        self.net.setInput(blob)
        return decode_yolo(self.net.forward(), k, self.score, W, H)


def decode_yolo(out: np.ndarray, k: float, score: float, W: int, H: int) -> list[Box]:
    """YOLOv8 출력 (1, 5, N) 또는 (1, N, 5)를 상자로 바꾼다. 값: cx, cy, w, h, score (640 입력 기준)."""
    a = np.squeeze(out)
    if a.ndim != 2:
        return []
    if a.shape[0] <= 6:  # YOLOv8 내보내기는 (4+클래스 수, 상자 수) 순서
        a = a.T
    boxes = []
    for row in a:
        cx, cy, w, h = row[:4]
        s = float(row[4:].max()) if len(row) > 4 else 0.0
        if s < score:
            continue
        x, y = (cx - w / 2) / k, (cy - h / 2) / k
        bx, by = int(max(0, x)), int(max(0, y))
        bw, bh = int(min(W - bx, w / k)), int(min(H - by, h / k))
        if bw > 0 and bh > 0:
            boxes.append(Box(bx, by, bw, bh, "plate", s, "onnx"))
    return _nms(boxes, 0.45)


# =====================================================================
# 공통
# =====================================================================
def _iou(a: Box, b: Box) -> float:
    x0, y0 = max(a.x, b.x), max(a.y, b.y)
    x1, y1 = min(a.x + a.w, b.x + b.w), min(a.y + a.h, b.y + b.h)
    inter = max(0, x1 - x0) * max(0, y1 - y0)
    union = a.w * a.h + b.w * b.h - inter
    return inter / union if union else 0.0


def _nms(boxes: list[Box], thr: float) -> list[Box]:
    keep = []
    for b in sorted(boxes, key=lambda b: -b.score):
        if all(_iou(b, k) < thr for k in keep):
            keep.append(b)
    return keep


@lru_cache
def face_detector():
    if Path(FACE_MODEL).is_file():
        return YuNetFaces(FACE_MODEL)
    return HaarFaces()


def _try(factory, what):
    try:
        return factory()
    except Exception as e:  # 라이브러리가 없거나 모델을 내려받지 못한 경우: 나머지 검출기로 계속
        import logging
        logging.getLogger("ai.privacy").warning("%s를 쓸 수 없어 건너뜀: %s", what, e)
        return None


@lru_cache
def plate_detectors() -> tuple:
    dets = []
    if PLATE_PRETRAINED:
        d = _try(lambda: PretrainedPlates(PLATE_PRETRAINED, PLATE_PRETRAINED_CONF), "사전 학습 번호판 모델")
        if d:
            dets.append(d)
    if PLATE_MODEL and Path(PLATE_MODEL).is_file():
        dets.append(OnnxPlates(PLATE_MODEL))
    if PLATE_HEURISTIC or not dets:
        dets.append(HeuristicPlates())
    return tuple(dets)


@lru_cache
def vehicle_detector():
    if not VEHICLE_FALLBACK:
        return None
    return _try(lambda: VehicleDetector(VEHICLE_FALLBACK, VEHICLE_CONF), "차량 검출 모델")


def _pixelate(roi: np.ndarray) -> np.ndarray:
    h, w = roi.shape[:2]
    cell = max(1, w // 6)
    small = cv2.resize(roi, (max(1, w // cell), max(1, h // cell)), interpolation=cv2.INTER_AREA)
    out = cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST)
    k = max(3, (min(w, h) // 4) | 1)
    return cv2.GaussianBlur(out, (k, k), 0)


def detect(img: np.ndarray) -> tuple[list[Box], dict]:
    """얼굴·번호판 상자를 원본 좌표로 돌려준다."""
    H, W = img.shape[:2]
    k = min(1.0, DETECT_MAX_SIDE / max(H, W))
    work = cv2.resize(img, (int(W * k), int(H * k)), interpolation=cv2.INTER_AREA) if k < 1 else img
    fd = face_detector()
    boxes = [b.scaled(1 / k) if k < 1 else b for b in fd.detect(work)]
    pds = plate_detectors()
    plates = []
    for pd in pds:
        plates += [b.scaled(1 / k) if k < 1 else b for b in pd.detect(work)]
    boxes += plates
    vd = vehicle_detector()
    if vd is not None:
        # 차량 안에서 번호판을 하나도 못 찾았으면 차량 전체를 가린다 (번호판 검출 누락 대비)
        for v in (b.scaled(1 / k) if k < 1 else b for b in vd.detect(work)):
            if not any(v.x <= p.x + p.w / 2 <= v.x + v.w and v.y <= p.y + p.h / 2 <= v.y + v.h for p in plates):
                boxes.append(v)
    detectors = {"face": fd.name if isinstance(fd, YuNetFaces) else "haar(대체)",
                 "plate": "+".join(p.name for p in pds),
                 "vehicle": vd.name if vd is not None else None}
    return boxes, detectors


def mask_image(img: np.ndarray) -> tuple[np.ndarray, MaskReport]:
    """얼굴·번호판을 가린 새 배열과 보고서를 돌려준다. 입력 배열은 바꾸지 않는다."""
    H, W = img.shape[:2]
    boxes, detectors = detect(img)
    out = img.copy()
    covered = np.zeros((H, W), np.uint8)
    rep = MaskReport(detectors=detectors)
    for b in boxes:
        p = b.padded({"face": FACE_PAD, "plate": PLATE_PAD}.get(b.kind, 0.05), W, H)
        if p.w <= 0 or p.h <= 0:
            continue
        if b.kind in ("face", "vehicle"):
            out[p.y:p.y + p.h, p.x:p.x + p.w] = _pixelate(out[p.y:p.y + p.h, p.x:p.x + p.w])
            if b.kind == "face":
                rep.faces += 1
            else:
                rep.vehicles += 1
        else:
            out[p.y:p.y + p.h, p.x:p.x + p.w] = PLATE_FILL
            rep.plates += 1
        covered[p.y:p.y + p.h, p.x:p.x + p.w] = 1
        rep.boxes.append({"kind": b.kind, "source": b.source, "score": round(b.score, 2),
                          "box": [round(p.x / W, 4), round(p.y / H, 4),
                                  round((p.x + p.w) / W, 4), round((p.y + p.h) / H, 4)]})
    rep.masked_area_ratio = round(float(covered.mean()), 4)
    return out, rep


def anonymize(image_jpeg: bytes) -> tuple[bytes, dict]:
    """JPEG 바이트를 받아 가린 JPEG과 보고서를 돌려준다 (예전 방식 호환용)."""
    import images
    masked, rep = mask_image(images.decode_jpeg(image_jpeg))
    return images.encode_jpeg(masked), rep.as_dict()
