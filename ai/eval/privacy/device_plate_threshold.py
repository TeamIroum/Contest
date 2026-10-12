"""앱(PWA) 번호판 검출 기준(masking.js PLATE_CONF)을 정하는 측정.

앱의 작은 모델(t-384)을 masking.js findPlates와 같은 전처리로 돌려 두 점수 분포를 비교한다.
  - 진짜 번호판: 한국 차 사진(eval/photos/plates_kr)에서 서버 큰 모델(s-608)이 0.5점 이상으로 찾은 번호판 중
    사진의 20%보다 작은 것(실제 순회 사진처럼 장면 속 번호판). 서버 검출은 눈으로 확인해 모두 진짜 번호판이었다
  - 거짓 검출: 번호판 없는 사진(eval/photos/internet 시설 사진 + 검증 콘솔에 저장된 휴대폰 시험 사진)

사용: python eval/privacy/device_plate_threshold.py
사진 받기: eval/photos/plates_kr/SOURCES.md (Wikimedia Commons, 저장소에는 올리지 않음)
"""
import glob
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

AI = Path(__file__).resolve().parents[2]
ROOT = AI.parent
sys.path.insert(0, str(AI))

S = 384
AREA_MAX, ASPECT_MIN = 0.25, 1.3   # masking.js plausiblePlate
DEVICE_MODEL = ROOT / "tools" / "ai-verify" / "pwa" / "models" / "plate-t384@1.onnx"
# 기기가 번호판으로 가린 시험 사진은 가린 자리가 남아 있어 거짓 검출 측정에서 뺀다
MASKED_RUNS = (19, 20, 21, 32, 55, 60)


def load(p):
    return cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)


def device_detect(sess, bgr):
    """masking.js findPlates와 같은 전처리·걸러내기. [(점수, x1, y1, x2, y2 비율)]"""
    H, W = bgr.shape[:2]
    r = min(S / W, S / H)
    nw, nh = round(W * r), round(H * r)
    dw, dh = (S - nw) / 2, (S - nh) / 2
    canvas = np.full((S, S, 3), 114, np.uint8)
    x0, y0 = round(dw - 0.1), round(dh - 0.1)
    canvas[y0:y0 + nh, x0:x0 + nw] = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
    x = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32).transpose(2, 0, 1)[None] / 255
    out = []
    for row in sess.run(None, {sess.get_inputs()[0].name: x})[0]:
        x1, y1, x2, y2 = (row[1] - dw) / r, (row[2] - dh) / r, (row[3] - dw) / r, (row[4] - dh) / r
        w, h = min(W, x2) - max(0, x1), min(H, y2) - max(0, y1)
        if w <= 0 or h <= 0 or w * h / (W * H) >= AREA_MAX or w / h < ASPECT_MIN:
            continue
        out.append((float(row[6]), x1 / W, y1 / H, x2 / W, y2 / H))
    return out


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter + 1e-9)


def main():
    from dotenv import load_dotenv
    load_dotenv(AI / ".env")
    import privacy
    sess = ort.InferenceSession(str(DEVICE_MODEL))
    server = privacy.PretrainedPlates(privacy.PLATE_PRETRAINED, 0.15)

    real, fp_kr = [], []
    for p in sorted((AI / "eval" / "photos" / "plates_kr").glob("*.jpg")):
        img = load(p)
        H, W = img.shape[:2]
        gts = [(b.score, b.x / W, b.y / H, (b.x + b.w) / W, (b.y + b.h) / H) for b in server.detect(img) if b.score >= 0.5]
        dev = device_detect(sess, img)
        for g in gts:
            if (g[3] - g[1]) * (g[4] - g[2]) < 0.2:
                real.append(max([d[0] for d in dev if iou(d[1:], g[1:]) > 0.3], default=0.0))
        fp_kr += [d[0] for d in dev if not any(iou(d[1:], g[1:]) > 0.1 for g in gts)]

    neg_paths = sorted((AI / "eval" / "photos" / "internet").glob("*.jpg"))
    neg_paths += [Path(p) for p in sorted(glob.glob(str(ROOT / "tools" / "ai-verify" / "data" / "images" / "run*_r1.jpg")))
                  if not any(Path(p).name.startswith(f"run{n}_") for n in MASKED_RUNS)]
    neg = [max([d[0] for d in device_detect(sess, img)], default=0.0) for img in map(load, neg_paths) if img is not None]

    real, neg = np.array(real), np.array(neg)
    print(f"진짜 번호판 {len(real)}개: 기기 점수 낮은 순 {np.round(np.sort(real)[:5], 2).tolist()}")
    print(f"번호판 없는 사진 {len(neg)}장: 가장 높은 거짓 점수 {np.round(np.sort(neg)[-5:], 2).tolist()}")
    print(f"한국 차 사진 속 번호판 아닌 곳 점수: {np.round(sorted(fp_kr, reverse=True)[:5], 2).tolist()}")
    for t in (0.15, 0.3, 0.5, 0.6, 0.7):
        print(f"기준 {t:.2f}: 진짜 번호판 가림 {(real >= t).sum()}/{len(real)} · 번호판 없는 사진 잘못 가림 {(neg >= t).sum()}/{len(neg)}")


if __name__ == "__main__":
    main()
