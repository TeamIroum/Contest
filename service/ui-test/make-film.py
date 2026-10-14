"""시연 영상 합치기: demo-film.mjs가 녹화한 휴대폰(phone.webm)·관리자(admin.webm) 화면을 1920x1080 한 화면에 놓는다.
- 시작 표지(공모전·서비스 이름) → 본편 → 끝 화면(흐름 요약), 장면 사이는 부드럽게 넘김
- 왼쪽: 김○○ 휴대폰(둥근 모서리 본체 안에 시니어 앱 화면. 박○○ 휴대폰은 화면 밖) / 오른쪽 위: 관리자 웹
- 단계마다 움직이는 쪽(휴대폰·관리자)에 빛나는 테두리, 다른 쪽은 살짝 어둡게
- 오른쪽 아래: 단계 자막(서서히 나타남, 폭을 재서 줄바꿈) + 단계 진행 막대
- 화면이 멈춰 기다리는 구간(steps.json idles)은 4배속으로 넘기고 '빠르게 감기' 표시
단계 캡처를 모아 보는 index.html도 만든다.

  python make-film.py            # 결과: shots/film/이로움_시연.mp4, shots/film/index.html
필요: pip install imageio-ffmpeg pillow (ffmpeg 실행 파일 포함)
"""
import html
import json
import os
import subprocess
import sys

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FILM = os.path.join(HERE, "shots", "film")
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
FONTS = r"C:\Windows\Fonts"
BOLD = os.path.join(FONTS, "malgunbd.ttf") if os.path.exists(os.path.join(FONTS, "malgunbd.ttf")) else os.path.join(FONTS, "malgun.ttf")
REGULAR = os.path.join(FONTS, "malgun.ttf")
FONT = BOLD.replace("\\", "/").replace(":", "\\:")
OUT = "이로움_시연.mp4"
W, H = 1920, 1080
ACCENT = (79, 140, 255)
SPEED = 4                 # 기다리는 구간 배속
INTRO, OUTRO, XF = 4.0, 5.0, 0.7   # 표지·끝 화면 길이, 넘김 길이 (초)

# 배치
SCREEN_W, SCREEN_H = 420, 909            # 휴대폰 화면 (390:844 비율)
BEZEL = 16                               # 본체 테두리 두께
BODY_W, BODY_H = SCREEN_W + BEZEL * 2, SCREEN_H + BEZEL * 2 + 24
BODY_R, SCREEN_R = 58, 40                # 본체·화면 모서리 둥글기
BODY_Y = (H - BODY_H) // 4 * 2          # 짝수로: 영상(yuv420)은 짝수 좌표에 놓여서, 홀수면 화면 맨 윗줄이 가리개 위로 1px 삐져나와 회색 줄이 보였다
PHONES = [(70, "")]                      # 본체 x, 이름표 (김○○ 휴대폰 한 대)
SCREEN_DX, SCREEN_Y = BEZEL, BODY_Y + BEZEL + 24
ADMIN_X, ADMIN_Y, ADMIN_W, ADMIN_H = 580, 40, 1280, 800   # 1440x900 비율
CAP_X, CAP_Y, CAP_W = ADMIN_X, ADMIN_Y + ADMIN_H + 26, ADMIN_W
BAR_Y = H - 34

data = json.load(open(os.path.join(FILM, "steps.json"), encoding="utf-8"))
steps = [s for s in data["steps"] if s["text"] != "끝"]
total = data["total"]
# 단계마다 움직이는 쪽 (①~⑮): p1 = 김○○ 휴대폰, admin = 관리자 웹
FOCUS = [{"p1"}, {"p1"}, {"admin"}, {"p1"}, {"p1"}, {"p1"}, {"p1"}, {"p1"},
         {"admin", "p1"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}]


def font(size, bold=True):
    return ImageFont.truetype(BOLD if bold else REGULAR, size)


def save(img, name):
    img.save(os.path.join(FILM, name))
    return name


def gradient(w, h, top, bottom):
    """위→아래 그라데이션 + 오른쪽 위 은은한 빛"""
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        k = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * k) for i in range(3)))
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse([w * 0.55, -h * 0.45, w * 1.25, h * 0.55], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img.paste(Image.new("RGB", (w, h), (60, 110, 220)), (0, 0), glow)
    return img


def wrap(text, size, width):
    """글자 폭을 재서 width 안에 들어가게 줄을 나눈다 (띄어쓰기 기준)"""
    f = font(size)
    lines, cur = [], ""
    for word in text.split(" "):
        cand = f"{cur} {word}".strip()
        if f.getlength(cand) <= width:
            cur = cand
        else:
            lines.append(cur)
            cur = word
    lines.append(cur)
    return lines


def ring(w, h, radius, pad=10):
    """빛나는 테두리 (번진 빛 + 선)"""
    img = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle([pad - 2, pad - 2, w + pad + 1, h + pad + 1], radius=radius + 2, outline=ACCENT + (255,), width=10)
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(9)))
    ImageDraw.Draw(img).rounded_rectangle([pad - 3, pad - 3, w + pad + 2, h + pad + 2], radius=radius + 3, outline=(140, 185, 255, 255), width=3)
    return img


def dim(w, h, radius):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=(8, 14, 26, 120))
    return img


def card(lines_spec, rule_after=1):
    """표지·끝 화면: [(글자, 크기, 색, 굵게, 위 여백)] 를 가운데 정렬. 글자 대신 [(글자, 크기, 색, 굵게), ...]를 주면 한 줄에 이어 그린다(팀 이름 강조).
    글자 뒤에 번진 그림자를 깔아 배경과 떨어져 보이게 하고, rule_after번째 줄 아래에 강조 막대를 긋는다"""
    img = gradient(W, H, (10, 20, 38), (18, 40, 84)).convert("RGBA")
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    text_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ds, dt = ImageDraw.Draw(shadow), ImageDraw.Draw(text_layer)
    y = sum(sp[4] for sp in lines_spec)
    y = (H - y - sum(sp[1] for sp in lines_spec)) // 2
    for k, (text, size, color, bold, gap) in enumerate(lines_spec):
        y += gap
        parts = text if isinstance(text, list) else [(text, size, color, bold)]
        x = (W - sum(font(sz, b).getlength(t) for t, sz, _, b in parts)) // 2
        for t, sz, c, b in parts:
            f = font(sz, b)
            ty = y + (size - sz) * 3 // 4   # 글자 크기가 달라도 아래선을 맞춘다
            ds.text((x, ty + 4), t, font=f, fill=(0, 0, 0, 230))
            dt.text((x, ty), t, font=f, fill=c)
            x += f.getlength(t)
        y += size
        if k == rule_after:
            dt.rounded_rectangle([W // 2 - 60, y + 46, W // 2 + 60, y + 52], radius=3, fill=ACCENT + (255,))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(8)))
    img.alpha_composite(text_layer)
    return img


def chips(img, labels, y):
    """흐름 요약 칩 (가운데 정렬, 화살표로 잇기)"""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))  # 반투명 칩은 따로 그려 겹친다 (바로 그리면 투명도가 섞이지 않음)
    d = ImageDraw.Draw(layer)
    f = font(32)
    widths = [f.getlength(t) + 48 for t in labels]
    arrow = 46
    x = (W - sum(widths) - arrow * (len(labels) - 1)) // 2
    for i, (t, w) in enumerate(zip(labels, widths)):
        d.rounded_rectangle([x, y, x + w, y + 62], radius=31, fill=(79, 140, 255, 70), outline=(160, 200, 255, 255), width=3)
        d.text((x + 24, y + 10), t, font=f, fill=(255, 255, 255))
        x += w
        if i < len(labels) - 1:
            d.text((x + 10, y + 9), "→", font=f, fill=(140, 185, 255))
            x += arrow
    img.alpha_composite(layer)
    return img


# ---------- 그림 재료 ----------
# 입력 그림을 많이 넣으면 ffmpeg가 프레임을 쌓아 메모리가 모자란다. 그래서 두 장으로 미리 합친다:
#   바탕(_base.png: 배경·휴대폰 본체·이름표)  ← 그 위에 휴대폰·관리자 영상 ←  단계별 덮개(_decor_NN.png: 화면 모서리·관리자 테두리·강조·어둡게·진행 막대)
base = gradient(W, H, (12, 22, 40), (18, 36, 70)).convert("RGBA")
body = Image.new("RGBA", (BODY_W, BODY_H), (0, 0, 0, 0))
d = ImageDraw.Draw(body)
d.rounded_rectangle([0, 0, BODY_W - 1, BODY_H - 1], radius=BODY_R, fill=(20, 22, 26, 255), outline=(70, 76, 88, 255), width=3)
d.rounded_rectangle([BODY_W // 2 - 44, 14, BODY_W // 2 + 44, 22], radius=4, fill=(55, 58, 66, 255))  # 스피커
label_font = font(26)
d = ImageDraw.Draw(base)
for x, label in PHONES:
    base.alpha_composite(body, (x, BODY_Y))
    d.text((x + (BODY_W - label_font.getlength(label)) // 2, BODY_Y - 46), label, font=label_font, fill=(190, 208, 235))
save(base.convert("RGB"), "_base.png")
corners = Image.new("RGBA", (SCREEN_W, SCREEN_H), (20, 22, 26, 255))
# 화면 가장자리 3px도 본체 색으로 덮는다 (녹화 영상 맨 윗줄이 밝게 남아 화면 위에 가로줄처럼 보였음)
ImageDraw.Draw(corners).rounded_rectangle([0, 0, SCREEN_W - 1, SCREEN_H - 1], radius=SCREEN_R, fill=(0, 0, 0, 0), outline=(20, 22, 26, 255), width=3)
frame = Image.new("RGBA", (ADMIN_W + 12, ADMIN_H + 12), (0, 0, 0, 0))
ImageDraw.Draw(frame).rounded_rectangle([0, 0, ADMIN_W + 11, ADMIN_H + 11], radius=14, outline=(70, 76, 88, 255), width=6)
ring_phone, ring_admin = ring(BODY_W, BODY_H, BODY_R), ring(ADMIN_W + 12, ADMIN_H + 12, 14)
dim_phone, dim_admin = dim(BODY_W, BODY_H, BODY_R), dim(ADMIN_W + 12, ADMIN_H + 12, 14)
n = len(steps)
gap = 8
seg = (CAP_W - gap * (n - 1)) / n


def decor(i):
    """단계 i의 덮개 (i = -1: 첫 단계 전)"""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.alpha_composite(frame, (ADMIN_X - 6, ADMIN_Y - 6))
    focus = FOCUS[i] if i >= 0 else None   # 첫 단계 전에는 강조·어둡게 없음
    for k, (x, _) in enumerate(PHONES):
        img.alpha_composite(corners, (x + SCREEN_DX, SCREEN_Y))
        if focus is not None and f"p{k + 1}" in focus:
            img.alpha_composite(ring_phone, (x - 10, BODY_Y - 10))
        elif focus is not None:
            img.alpha_composite(dim_phone, (x, BODY_Y))
    if focus is not None and "admin" in focus:
        img.alpha_composite(ring_admin, (ADMIN_X - 16, ADMIN_Y - 16))
    elif focus is not None:
        img.alpha_composite(dim_admin, (ADMIN_X - 6, ADMIN_Y - 6))
    d = ImageDraw.Draw(img)
    for j in range(n if i >= 0 else 0):  # 진행 막대: 지난 단계·지금 단계·남은 단계
        x = CAP_X + round(j * (seg + gap))
        color = (140, 185, 255, 255) if j < i else ACCENT + (255,) if j == i else (255, 255, 255, 40)
        d.rounded_rectangle([x, BAR_Y, round(x + seg), BAR_Y + 7], radius=4, fill=color)
    return save(img, f"_decor_{i + 1:02d}.png")


# 덮개를 시간 순서로 잇는 목록 (ffmpeg concat)
decors = [(decor(-1), steps[0]["t"])] + [(decor(i), (steps[i + 1]["t"] if i + 1 < n else total) - s["t"]) for i, s in enumerate(steps)]
with open(os.path.join(FILM, "_decor.txt"), "w", encoding="utf-8", newline="") as f:
    for name, dur in decors:
        f.write(f"file '{name}'\nduration {max(dur, 0.04):.3f}\n")
    f.write(f"file '{decors[-1][0]}'\n")

# 표지·끝 화면
save(card([
    ("2026 양주시 미래인재 AI 공모전 · 함께하는 양주", 38, (200, 220, 255), True, 0),
    ("시니어 동네 점검단", 128, (255, 255, 255), True, 36),
    ("어르신이 찍고 AI가 판독해 현장과 행정을 잇는 생활 시설 점검 서비스", 46, (255, 255, 255), True, 80),
    ([("이로움", 46, (255, 255, 255), True), ("   이소미 · 이승재 · 황상진", 34, (190, 208, 238), False)], 46, None, True, 90),
]), "_intro.png")
outro = card([
    ("시니어 동네 점검단", 100, (255, 255, 255), True, -40),
    ("어르신이 찍고 AI가 판독해 현장과 행정을 잇는 생활 시설 점검 서비스", 46, (255, 255, 255), True, 70),
    (" ", 62, (0, 0, 0), False, 60),
    ([("이로움", 46, (255, 255, 255), True), ("   이소미 · 이승재 · 황상진", 34, (190, 208, 238), False)], 46, None, True, 170),
], rule_after=0)
chips(outro, ["어르신 촬영", "얼굴·번호판 마스킹", "AI 판독", "담당자 판정", "보수", "재점검"], H // 2 + 50)
save(outro, "_outro.png")

# ---------- 1단계: 본편 (원래 시간 그대로) ----------
caps = []
for i, s in enumerate(steps):
    end = steps[i + 1]["t"] if i + 1 < len(steps) else total
    for size in (40, 36, 32, 28):
        lines = wrap(s["text"], size, CAP_W - 20)
        if len(lines) <= 2:
            break
    names = []
    for j, line in enumerate(lines):  # 줄마다 파일 하나 (여러 줄을 한 번에 그리면 ffmpeg가 줄 간격을 크게 잡는다)
        name = f"cap{i:02d}_{j}.txt"
        with open(os.path.join(FILM, name), "w", encoding="utf-8", newline="") as f:
            f.write(line)
        names.append(name)
    caps.append((names, s["t"], end, size))


def during(ranges):
    return "+".join(f"between(t,{a:.2f},{b:.2f})" for a, b in ranges) or "0"


idles = [(a, b) for a, b in data.get("idles", []) if b - a > 1]
filters = [
    "[2:v]format=rgba[bg]",
    f"[0:v]scale={SCREEN_W}:{SCREEN_H}:flags=lanczos,setsar=1[ph]",
    f"[bg][ph]overlay={PHONES[0][0] + SCREEN_DX}:{SCREEN_Y}:shortest=1[s0]",
    f"[1:v]scale={ADMIN_W}:{ADMIN_H}:flags=lanczos,setsar=1[ad]",
    f"[s0][ad]overlay={ADMIN_X}:{ADMIN_Y}:eof_action=repeat[s2]",
    "[3:v]format=rgba[deco]",
    "[s2][deco]overlay=0:0:eof_action=repeat[s3]",
    f"[s3]drawtext=fontfile='{FONT}':text='시니어 동네 점검단 · 시연':fontcolor=0x8FA3BC:fontsize=24:x={CAP_X}:y={CAP_Y}[s4]",
]
last = "s4"


def add(f):
    global last
    nxt = f"v{len(filters)}"
    filters.append(f"[{last}]{f}[{nxt}]")
    last = nxt


for i, (names, start, end, size) in enumerate(caps):
    for j, name in enumerate(names):
        # 자막: 0.4초 동안 서서히 나타나며 살짝 올라온다
        y0 = CAP_Y + 44 + j * (size + 14)
        add(f"drawtext=fontfile='{FONT}':textfile='{name}':fontcolor=white:fontsize={size}:x={CAP_X}:"
            f"y='{y0}+max(0,10*(1-(t-{start:.2f})/0.4))':alpha='min(1,max(0,(t-{start:.2f})/0.4))':"
            f"enable='between(t,{start:.2f},{end:.2f})'")
if idles:
    add(f"drawtext=fontfile='{FONT}':text='▶▶ 빠르게 감기 ×{SPEED}':fontcolor=white:fontsize=30:box=1:boxcolor=0x1E3A6E@0.85:boxborderw=14:"
        f"x={ADMIN_X + ADMIN_W - 330}:y={ADMIN_Y + 22}:enable='{during(idles)}'")

cmd = [FFMPEG, "-y", "-i", "phone.webm", "-i", "admin.webm", "-loop", "1", "-framerate", "30", "-i", "_base.png",
       "-f", "concat", "-safe", "0", "-i", "_decor.txt",
       "-filter_complex", ";".join(filters), "-map", f"[{last}]", "-t", f"{total:.2f}",
       "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "18", "-r", "30", "_main.mp4"]


def run(cmd, what):
    print(what)
    r = subprocess.run(cmd, cwd=FILM, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print(os.linesep.join(l for l in r.stderr.splitlines() if not l.startswith(("[libx264", "frame=", "  ")))[-4000:])
        sys.exit(1)


run(cmd, "1/2 본편 합치는 중…")

# ---------- 2단계: 기다리는 구간 빨리 감기 + 표지·끝 화면 ----------
pieces, t = [], 0.0
for a, b in sorted(idles):
    if a > t:
        pieces.append((t, a, 1))
    pieces.append((max(a, t), b, SPEED))
    t = b
pieces.append((t, total, 1))
pieces = [p for p in pieces if p[1] - p[0] > 0.05]


def final_time(x):
    """본편 시각 → 완성 영상 시각"""
    out = INTRO - XF
    for a, b, sp in pieces:
        if x <= a:
            break
        out += (min(x, b) - a) / sp
    return out


main_len = sum((b - a) / sp for a, b, sp in pieces)
fl = []
for k, (a, b, sp) in enumerate(pieces):
    fl.append(f"[0:v]trim={a:.3f}:{b:.3f},setpts=(PTS-STARTPTS)/{sp}[p{k}]")
fl.append("".join(f"[p{k}]" for k in range(len(pieces))) + f"concat=n={len(pieces)}:v=1:a=0,fps=30,format=yuv420p,settb=1/30[main]")
fl.append(f"[1:v]fps=30,format=yuv420p,settb=1/30,fade=in:st=0:d=0.8[intro]")
fl.append(f"[2:v]fps=30,format=yuv420p,settb=1/30,fade=out:st={OUTRO - 0.8:.2f}:d=0.8[outro]")
fl.append(f"[intro][main]xfade=transition=fade:duration={XF}:offset={INTRO - XF:.3f}[im]")
fl.append(f"[im][outro]xfade=transition=fade:duration={XF}:offset={INTRO - XF + main_len - XF:.3f}[out]")
run([FFMPEG, "-y", "-i", "_main.mp4", "-loop", "1", "-t", f"{INTRO}", "-i", "_intro.png", "-loop", "1", "-t", f"{OUTRO}", "-i", "_outro.png",
     "-filter_complex", ";".join(fl), "-map", "[out]", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20",
     "-movflags", "+faststart", OUT], "2/2 빨리 감기·표지 붙이는 중…")
os.remove(os.path.join(FILM, "_main.mp4"))
length = INTRO + main_len + OUTRO - 2 * XF

# 캡처 모아 보기
pngs = sorted(f for f in os.listdir(FILM) if f.endswith(".png") and not f.startswith("_"))
rows = [f'<figure><img src="{html.escape(f)}" loading="lazy"><figcaption>{html.escape(f[:-4])}</figcaption></figure>' for f in pngs]
steps_html = "".join(f"<li><b>{int(final_time(s['t']) // 60)}:{int(final_time(s['t']) % 60):02d}</b> {html.escape(s['text'])}</li>" for s in steps)
page = f"""<!doctype html><meta charset="utf-8"><title>시연 캡처</title>
<style>body{{font-family:'Malgun Gothic',sans-serif;margin:24px;background:#f5f7fb}}figure{{display:inline-block;margin:8px;vertical-align:top;background:#fff;padding:8px;border-radius:10px}}
img{{max-height:420px;max-width:640px;display:block}}figcaption{{font-size:13px;color:#556}}video{{max-width:100%;border-radius:12px}}</style>
<h1>시니어 동네 점검단 · 시연</h1><p>가상 인물·가상 번호</p><video src="{OUT}" controls></video><ol>{steps_html}</ol>{''.join(rows)}"""
open(os.path.join(FILM, "index.html"), "w", encoding="utf-8").write(page)
size = os.path.getsize(os.path.join(FILM, OUT)) / 1e6
print(f"완료: {os.path.join(FILM, OUT)} ({length:.0f}초, {size:.1f}MB, 빨리 감은 구간 {len(idles)}개), 캡처 {len(pngs)}장 → index.html")
