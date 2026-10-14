"""스토리보드 이미지(shots/에셋/스토리보드_원본.png, 카카오톡으로 받은 1024x572)의 칸 자막·슬로건·로고를 고친다.
2배(2048x1144)로 키워 글자를 다시 쓴다. 결과: shots/에셋/스토리보드_수정.png(2048x1144), 스토리보드_수정_원본크기.png(1024x572)

  python make-storyboard.py
"""
import os
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots", "에셋")
ASSET = r"C:\Contest\service\ui-test\shots\에셋"
S = 2  # 2배로 키워 글자를 선명하게
src = Image.open(os.path.join(D, "스토리보드_원본.png")).convert("RGB")
W0, H0 = src.size
im = src.resize((W0 * S, H0 * S), Image.LANCZOS)
BOLD = r"C:\Windows\Fonts\malgunbd.ttf"
REG = r"C:\Windows\Fonts\malgun.ttf"
font = lambda size, bold=True: ImageFont.truetype(BOLD if bold else REG, size)

COLS = [(0, 204), (205, 408), (409, 614), (615, 819), (820, 1024)]
BANDS = [(120, 141), (264, 285), (408, 430), (552, 572)]   # 칸 아래 자막 띠 (원본 좌표)
# (행, 열) → 새 자막. None이면 그대로
CAPS = {
    (0, 0): "양주시 옥정호수공원",
    (1, 3): "센터 도착, 출석 요청",   # 04
    (0, 4): "선생님이 출석을 확인해요",  # 05
}
CAPS = {
    (0, 0): "양주시 옥정호수공원",
    (0, 3): "센터 도착, 출석 요청",
    (0, 4): "선생님이 출석을 확인해요",
    (1, 0): "오늘 찍을 장소를 안내해요",
    (1, 2): "앱이 안내하는 대로 찍으면 돼요",
    (1, 3): "사진을 보내면 바로 알려 줘요",
    (1, 4): "두 분이 함께 동네를 살펴요",
    (2, 0): "얼굴 가림 · AI 판독",
    (2, 4): "AI 근거를 보고 이상을 확정해요",
    (3, 0): "수리할 곳으로 등록해요",
    (3, 2): "주간 현황 자동 집계 · AI 보고 초안",
}

AF = os.path.join(os.environ["TEMP"], "af")
REAL_OFF = {  # (행, 열): (프레임 파일, 원본 1920x1080에서 자를 곳)
    (2, 2): ("full141.png", (1000, 520, 1880, 1050)),    # 15 검토 목록 + 판독 근거
    (2, 4): ("full145.png", (1000, 300, 1850, 812)),   # 17 AI 근거를 보고 이상 확정
    (3, 1): ("full153.png", (1020, 150, 1910, 686)),    # 20 보수 요청
    (3, 2): ("full197.png", (360, 140, 1880, 1056)),   # 22 주간 현황·보고 문장
}
REAL = {}
IMG_ROWS = [(19, 120), (163, 264), (307, 408), (451, 552)]   # 칸의 그림 영역 (원본 좌표)
for (r, c), (f, box) in REAL.items():
    x0, x1 = COLS[c][0] * S + 2, COLS[c][1] * S - 1
    y0, y1 = IMG_ROWS[r][0] * S, (BANDS[r][1]) * S
    shot = Image.open(os.path.join(AF, f)).convert("RGB").crop(box)
    tw, th = x1 - x0, y1 - y0
    im.paste(shot.resize((tw, th), Image.LANCZOS), (x0, y0))
# 시니어 앱 칸(04·05·06·09): 원래 그림을 흐리게 깐 바탕 가운데에 실제 앱 화면(휴대폰 테두리)을 세운다
from PIL import ImageFilter
PHONE_OFF = {(0, 3): "s12.png", (0, 4): "s16.png", (1, 0): "s28.png", (1, 3): "s50.png"}
PHONE = {}
for (r, c), f in PHONE.items():
    x0, x1 = COLS[c][0] * S + 2, COLS[c][1] * S - 1
    y0, y1 = IMG_ROWS[r][0] * S, BANDS[r][0] * S
    bg = im.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(14))
    from PIL import ImageEnhance
    bg = ImageEnhance.Color(bg).enhance(0.25)   # 원래 그림의 초록 화면이 번져 보이지 않게 색을 뺀다
    bg = Image.blend(bg, Image.new("RGB", bg.size, (20, 24, 32)), 0.35)
    im.paste(bg, (x0, y0))
    shot = Image.open(os.path.join(AF, f)).convert("RGB")      # 화면 전체 (9:16 그대로)
    ph = (y1 - y0) - 22
    pw = int(ph * 9 / 16)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    bez = 5
    px, py = (x0 + x1 - pw) // 2, y0 + 11
    frame = Image.new("RGBA", (pw + bez * 2, ph + bez * 2), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, pw + bez * 2 - 1, ph + bez * 2 - 1], radius=16, fill=(20, 22, 26, 255), outline=(70, 76, 88, 255), width=2)
    im.paste(frame, (px - bez, py - bez), frame)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, pw - 1, ph - 1], radius=11, fill=255)
    im.paste(shot, (px, py), mask)
for (r, c) in REAL:   # 원래 자막을 실제 화면 위에 다시 그릴 수 있게 목록에 넣는다
    CAPS.setdefault((r, c), {(2, 2): "AI가 판독 근거를 정리해요", (3, 1): "담당 부서로 보수 요청"}.get((r, c), CAPS.get((r, c))))
arr = np.array(im)
for (r, c), text in CAPS.items():
    x0, x1 = COLS[c][0] * S + 2, COLS[c][1] * S - 2
    y0, y1 = BANDS[r][0] * S + 1, BANDS[r][1] * S
    band = arr[y0:y1, x0:x1].copy()
    # 원래 글자(밝은 픽셀)를 지우고 띠 무늬로 메운다
    gray = cv2.cvtColor(band, cv2.COLOR_RGB2GRAY)
    m = cv2.dilate((gray > 90).astype(np.uint8) * 255, np.ones((5, 5), np.uint8))
    band = cv2.inpaint(band, m, 5, cv2.INPAINT_TELEA)
    if (r, c) in PHONE:   # 원래 그림의 초록 화면 색이 띠에 남지 않게
        g = cv2.cvtColor(band, cv2.COLOR_RGB2GRAY); band = np.dstack([g, g, g])
    # 띠를 조금 더 어둡게 맞춰 새 글자가 또렷하게
    band = (band.astype(np.float32) * (0.18 if (r, c) in REAL or (r, c) in PHONE else 0.75)).astype(np.uint8)
    arr[y0:y1, x0:x1] = band
im = Image.fromarray(arr)
d = ImageDraw.Draw(im)
for (r, c), text in CAPS.items():
    x0, x1 = COLS[c][0] * S, COLS[c][1] * S
    y0, y1 = BANDS[r][0] * S, BANDS[r][1] * S
    size = 26
    while font(size).getlength(text) > (x1 - x0) - 16:
        size -= 1
    tw = font(size).getlength(text)
    bbox = font(size).getbbox(text)
    ty = (y0 + y1) // 2 - (bbox[1] + bbox[3]) // 2
    d.text(((x0 + x1 - tw) / 2, ty), text, font=font(size), fill=(255, 255, 255))

NAVY = (11, 39, 78)
# 23번 슬로건: 큰 글자를 다시 쓴다
x0, x1, y0, y1 = 615 * S, 819 * S, 451 * S, 551 * S
d.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], fill=NAVY)
lines = ["어르신이 찍고,", "AI가 판독하고,", "담당자가 확정합니다"]
fs = 28
while max(font(fs).getlength(l) for l in lines) > (x1 - x0) - 40:
    fs -= 1
lh = int(fs * 1.25)
top = y0 + (y1 - y0 - lh * 3) // 2
for i, l in enumerate(lines):
    d.text(((x0 + x1 - font(fs).getlength(l)) / 2, top + i * lh), l, font=font(fs), fill=(255, 255, 255))

# 24번: 양주시 로고 → 시니어 동네 점검단 로고 + 팀 이로움 로고
x0, x1, y0, y1 = 820 * S, 1024 * S, 451 * S, 551 * S
d.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], fill=NAVY)
icon = Image.open(os.path.join(ASSET, "시니어동네점검단_아이콘.png")).convert("RGBA").resize((64, 64), Image.LANCZOS)
cy = (y0 + y1) // 2
label1 = "시니어 동네 점검단"
f1 = font(22)
total = 64 + 12 + f1.getlength(label1)
x = (x0 + x1 - total) / 2
im.paste(icon, (int(x), cy - 32), icon)
bb = f1.getbbox(label1)
d.text((x + 76, cy - (bb[1] + bb[3]) / 2), label1, font=f1, fill=(255, 255, 255))

# 20번 그림 속 알림 문구: '자동 이첩' → 담당자가 요청한 결과로
bx0, by0, bx1, by1 = 256 * S, 483 * S, 357 * S, 518 * S
d.rectangle([bx0, by0, bx1, by1], fill=(255, 255, 255))
for i, l in enumerate(["공원사업과에", "보수를 요청했어요"]):
    f = font(13)
    d.text(((bx0 + bx1 - f.getlength(l)) / 2, by0 + 8 + i * 30), l, font=f, fill=(40, 44, 52))

out = os.path.join(D, "스토리보드_수정.png")
im.save(out)
im.resize((W0, H0), Image.LANCZOS).save(os.path.join(D, "스토리보드_수정_원본크기.png"))
print("ok", im.size)
