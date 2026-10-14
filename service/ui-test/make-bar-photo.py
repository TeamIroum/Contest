"""영상 메인 장소 사진(shots/에셋/메인장소_원래사진.png)에서 오른쪽 맨 앞 운동기구의 오른쪽 손잡이 가로대를 지우고,
빠진 손잡이가 기구 왼쪽 앞 고무 바닥에 떨어진 모습으로 연출한다 (4배로 키워 작업한 뒤 원래 크기로).

  python make-bar-photo.py     # 결과: shots/에셋/메인장소_운동기구_가로대이탈.png (+ _4배.png)
"""
import os
import sys
import cv2
import numpy as np

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots", "에셋")
rgba = cv2.imdecode(np.fromfile(os.path.join(D, "메인장소_원래사진.png"), np.uint8), cv2.IMREAD_UNCHANGED)
img = rgba[..., :3].copy()
h, w = img.shape[:2]
S = 4  # 4배로 키워 작업 (가는 막대를 정확히 따내려고)
big = cv2.resize(img, (w * S, h * S), interpolation=cv2.INTER_LANCZOS4)
H, W = big.shape[:2]

# 오른쪽 손잡이 가로대: 기둥 옆 연결부에서 위로 휘어 오른쪽으로 뻗었다가 아래로 꺾이는 관 (4배 좌표로 직접 따라 그림)
PTS = np.array([(3375, 1338), (3402, 1306), (3432, 1276), (3470, 1270), (3610, 1270), (3626, 1282), (3612, 1310), (3585, 1342), (3562, 1360)], np.int32)
arm = np.zeros((H, W), np.uint8)
cv2.polylines(arm, [PTS], False, 255, 22, cv2.LINE_AA)
band = arm.copy()
# 관만: 띠 안에서 채도가 낮고 밝은 픽셀 (단풍은 채도가 높음)
hsv = cv2.cvtColor(big, cv2.COLOR_BGR2HSV)
tube = cv2.inRange(hsv, (0, 0, 105), (180, 95, 255))
arm = cv2.bitwise_and(band, tube)
arm = cv2.morphologyEx(arm, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
arm = cv2.morphologyEx(arm, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
arm = cv2.bitwise_and(cv2.morphologyEx(arm, cv2.MORPH_CLOSE, np.ones((13, 13), np.uint8)), band)   # 관 가운데(그늘진 부분)까지 채움
ys, xs = np.nonzero(arm)
print("손잡이", xs.min() // S, xs.max() // S, ys.min() // S, ys.max() // S, len(xs))

# 떼어 낸 조각
x0, x1, y0, y1 = xs.min() - 6, xs.max() + 6, ys.min() - 6, ys.max() + 6
piece = np.dstack([big[y0:y1, x0:x1], cv2.GaussianBlur(arm[y0:y1, x0:x1], (5, 5), 0)])

# 원래 자리 메우기 (주변 단풍 무늬)
hole = cv2.dilate(band, np.ones((9, 9), np.uint8))
# 바로 위쪽(4배 좌표로 110px 위) 단풍 무늬를 옮겨 와 메우고, 경계만 부드럽게
patch = np.roll(big, 110, axis=0)
m = cv2.GaussianBlur(hole, (21, 21), 0).astype(np.float32)[..., None] / 255
clean = (big.astype(np.float32) * (1 - m) + patch.astype(np.float32) * m).astype(np.uint8)

# 떨어진 손잡이: 빠진 손잡이와 같은 모양(꺾인 관)을 기구 왼쪽 앞 고무 바닥에 눕혀 그린다 (음영·그림자 포함)
P = PTS.astype(np.float32) - PTS.mean(axis=0)
ang = np.deg2rad(168)
R = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]], np.float32)
P = P @ R.T
P[:, 1] *= 0.55                     # 바닥에 누워 원근으로 세로가 줄어듦
P[:, 0] *= 1.05
P += np.array([790 * S, 447 * S], np.float32)
P = P.astype(np.int32)
out = clean.astype(np.float32)
# 그림자
sh = np.zeros((H, W), np.float32)
cv2.polylines(sh, [P + np.array([6, 10], np.int32)], False, 1.0, 22, cv2.LINE_AA)
sh = cv2.GaussianBlur(sh, (0, 0), 7) * 0.5
out *= (1 - sh[..., None])
# 관: 어두운 테두리 → 몸통 → 위쪽 반사광
tube = np.zeros((H, W, 3), np.float32)
alpha = np.zeros((H, W), np.float32)
for color, th, dy in (((98, 108, 122), 17, 0), ((160, 174, 192), 13, 0), ((200, 216, 232), 4, -3)):
    layer = np.zeros((H, W), np.uint8)
    cv2.polylines(layer, [P + np.array([0, dy], np.int32)], False, 255, th, cv2.LINE_AA)
    a_ = layer.astype(np.float32) / 255
    tube = tube * (1 - a_[..., None]) + np.array(color, np.float32) * a_[..., None]
    alpha = np.maximum(alpha, a_)
tube = cv2.GaussianBlur(tube, (3, 3), 0)
alpha = cv2.GaussianBlur(alpha, (3, 3), 0)[..., None]
out = out * (1 - alpha) + tube * alpha
out = np.clip(out, 0, 255).astype(np.uint8)
small = cv2.resize(out, (w, h), interpolation=cv2.INTER_AREA)
cv2.imencode(".png", small)[1].tofile(os.path.join(D, "메인장소_운동기구_가로대이탈.png"))
# 손잡이만 사라진 판 (떨어진 손잡이를 그려 넣지 않음)
cv2.imencode(".png", cv2.resize(clean, (w, h), interpolation=cv2.INTER_AREA))[1].tofile(os.path.join(D, "메인장소_운동기구_가로대없음.png"))
cv2.imencode(".png", cv2.resize(clean[250 * S:470 * S, 740 * S:930 * S], (760, 880)))[1].tofile(os.path.join(os.environ.get("TEMP", D), "bar_check2.png"))
crop = out[250 * S:470 * S, 740 * S:930 * S]
cv2.imencode(".png", cv2.resize(crop, (760, 880)))[1].tofile(os.path.join(os.environ.get("TEMP", D), "bar_check.png"))
print("ok")
