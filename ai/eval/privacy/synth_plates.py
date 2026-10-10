"""합성 번호판으로 규칙 기반 번호판 검출기를 빠르게 점검한다.

실제 사진 평가를 대신하지 않는다. 검출기 코드를 고칠 때 성능이 떨어지지 않았는지 보는 회귀 점검용이다.
사용: python eval/privacy/synth_plates.py [배경사진 폴더]
"""
import glob
import random
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import privacy  # noqa: E402

FONT_CANDIDATES = glob.glob("/usr/share/fonts/**/NotoSansCJK-Bold.ttc", recursive=True) + \
    glob.glob("/usr/share/fonts/**/*Gothic*.tt*", recursive=True) + glob.glob("C:/Windows/Fonts/malgunbd.ttf")
HANGUL = "가나다라마거너더러머버서어저고노도로모보소오조구누두루무부수우주하허호배"


def plate_image(rng: random.Random, kind: str) -> np.ndarray:
    font_path = FONT_CANDIDATES[0]
    if kind == "new":          # 신형 한 줄 번호판 520x110
        W, H = 520, 110
        text = f"{rng.randint(10, 399)}{rng.choice(HANGUL)} {rng.randint(1000, 9999)}"
    else:                      # 구형 335x155 비율(두 줄 대신 큰 한 줄로 단순화)
        W, H = 335, 155
        text = f"{rng.randint(10, 99)}{rng.choice(HANGUL)}{rng.randint(1000, 9999)}"
    im = Image.new("RGB", (W, H), (245, 245, 240))
    d = ImageDraw.Draw(im)
    d.rectangle([3, 3, W - 4, H - 4], outline=(20, 20, 20), width=4)
    size = int(H * (0.72 if kind == "new" else 0.5))
    font = ImageFont.truetype(font_path, size)
    tw = d.textlength(text, font=font)
    while tw > W * 0.92:
        size -= 2
        font = ImageFont.truetype(font_path, size)
        tw = d.textlength(text, font=font)
    d.text(((W - tw) / 2, (H - size) / 2 - size * 0.12), text, fill=(15, 15, 15), font=font)
    return cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2BGR)


def paste(bg: np.ndarray, plate: np.ndarray, rng: random.Random):
    H, W = bg.shape[:2]
    pw = rng.randint(max(50, W // 25), max(60, W // 5))
    ph = int(pw * plate.shape[0] / plate.shape[1])
    ang = rng.uniform(-6, 6)
    x, y = rng.randint(0, W - pw - 1), rng.randint(0, H - ph - 1)
    src = np.float32([[0, 0], [plate.shape[1], 0], [plate.shape[1], plate.shape[0]], [0, plate.shape[0]]])
    jit = lambda: rng.uniform(-0.04, 0.04) * pw  # 약한 원근
    dst = np.float32([[x + jit(), y + jit()], [x + pw + jit(), y + jit()], [x + pw + jit(), y + ph + jit()], [x + jit(), y + ph + jit()]])
    M = cv2.getPerspectiveTransform(src, dst)
    c, s = np.cos(np.radians(ang)), np.sin(np.radians(ang))
    cx, cy = x + pw / 2, y + ph / 2
    R = np.array([[c, -s, cx - c * cx + s * cy], [s, c, cy - s * cx - c * cy], [0, 0, 1]], np.float32)
    M = R @ M
    warped = cv2.warpPerspective(plate, M, (W, H))
    mask = cv2.warpPerspective(np.full(plate.shape[:2], 255, np.uint8), M, (W, H))
    out = bg.copy()
    out[mask > 0] = warped[mask > 0]
    if rng.random() < 0.5:
        out = cv2.GaussianBlur(out, (3, 3), 0)
    ys, xs = np.where(mask > 0)
    gt = (xs.min(), ys.min(), xs.max(), ys.max())
    ok, buf = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, rng.randint(70, 92)])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR), gt


def covered(gt, boxes, W, H) -> float:
    x0, y0, x1, y1 = gt
    m = np.zeros((H, W), np.uint8)
    for b in boxes:
        bx0, by0, bx1, by1 = (int(b["box"][0] * W), int(b["box"][1] * H), int(b["box"][2] * W), int(b["box"][3] * H))
        m[by0:by1, bx0:bx1] = 1
    return float(m[y0:y1 + 1, x0:x1 + 1].mean())


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).resolve().parents[1] / "sample")
    bgs = [cv2.imread(p) for p in sorted(glob.glob(folder + "/*.jpg")) + sorted(glob.glob(folder + "/*.png"))]
    bgs = [b for b in bgs if b is not None and min(b.shape[:2]) > 200]
    rng = random.Random(7)
    hit = total = 0
    fp_imgs = 0
    for bg in bgs:  # 번호판 없는 배경에서 잘못 가리는지
        _, rep = privacy.mask_image(bg)
        fp_imgs += rep.plates > 0
    for i in range(80):
        bg = bgs[i % len(bgs)]
        img, gt = paste(bg, plate_image(rng, "new" if i % 3 else "old"), rng)
        _, rep = privacy.mask_image(img)
        plates = [b for b in rep.boxes if b["kind"] == "plate"]
        total += 1
        hit += covered(gt, plates, img.shape[1], img.shape[0]) >= 0.9
    print(f"합성 번호판 {total}장 중 90% 이상 가림: {hit}장 ({100 * hit / total:.0f}%)")
    print(f"번호판 없는 배경 {len(bgs)}장 중 잘못 가린 사진: {fp_imgs}장")


if __name__ == "__main__":
    main()
