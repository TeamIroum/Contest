"""시연 영상 합치기: demo-film.mjs가 녹화한 화면으로 1920x1080 영상 두 개를 만든다.
- 이로움_시연_시니어앱.mp4: 어르신 휴대폰(둥근 모서리 본체 그림)을 크게, 오른쪽에 단계 자막·진행 막대. 휴대폰이 움직이는 단계만
- 이로움_시연_관리자웹.mp4: 관리자 웹(1600x900 녹화)을 크게, 아래에 단계 자막·진행 막대. 관리자 화면이 움직이는 단계만
- 둘 다 시작 표지(공모전·서비스 이름) → 본편 → 끝 화면(흐름 요약), 장면 사이는 부드럽게 넘김
- 화면이 멈춰 기다리는 구간(steps.json idles)은 4배속으로 넘기고 '빠르게 감기' 표시

  python make-film.py                # 두 개 모두
  python make-film.py senior         # 시니어 앱만 (admin: 관리자 웹만)
필요: pip install imageio-ffmpeg pillow (ffmpeg 실행 파일 포함)
"""
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
W, H = 1920, 1080
ACCENT = (79, 140, 255)
SPEED = 4                 # 기다리는 구간 배속
INTRO, OUTRO, XF = 4.0, 5.0, 0.7   # 표지·끝 화면 길이, 넘김 길이 (초)

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


# ---------- 공통 재료: 표지·끝 화면 ----------
CREDIT = [("이로움", 46, (255, 255, 255), True), ("   이소미 · 이승재 · 황상진", 34, (190, 208, 238), False)]


def cards(kind_name):
    save(card([
        ("2026 양주시 미래인재 AI 공모전 · 함께하는 양주", 38, (200, 220, 255), True, 0),
        ("시니어 동네 점검단", 128, (255, 255, 255), True, 36),
        (f"시연 · {kind_name}", 46, (255, 255, 255), True, 80),
        (CREDIT, 46, None, True, 90),
    ]), "_intro.png")
    outro = card([
        ("시니어 동네 점검단", 100, (255, 255, 255), True, -40),
        ("어르신이 찍고 AI가 판독해 현장과 행정을 잇는 생활 시설 점검 서비스", 46, (255, 255, 255), True, 70),
        (" ", 62, (0, 0, 0), False, 60),
        (CREDIT, 46, None, True, 170),
    ], rule_after=0)
    chips(outro, ["어르신 촬영", "얼굴·번호판 마스킹", "AI 판독", "담당자 판정", "보수", "재점검"], H // 2 + 50)
    save(outro, "_outro.png")


def run(cmd, what):
    print(what)
    r = subprocess.run(cmd, cwd=FILM, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print(os.linesep.join(l for l in r.stderr.splitlines() if not l.startswith(("[libx264", "frame=", "  ")))[-4000:])
        sys.exit(1)


def during(ranges):
    return "+".join(f"between(t,{a:.2f},{b:.2f})" for a, b in ranges) or "0"


def bar(img, x0, y, width, n, cur):
    """진행 막대: 지난 단계·지금 단계·남은 단계"""
    d = ImageDraw.Draw(img)
    gap = 8
    seg = (width - gap * (n - 1)) / n
    for j in range(n):
        x = x0 + round(j * (seg + gap))
        color = (140, 185, 255, 255) if j < cur else ACCENT + (255,) if j == cur else (255, 255, 255, 40)
        d.rounded_rectangle([x, y, round(x + seg), y + 7], radius=4, fill=color)


# ---------- 영상 두 개: 시니어 앱 / 관리자 웹 ----------
# 시니어 앱: 휴대폰(본체 그림)을 왼쪽에 크게, 오른쪽에 단계 자막
P_SCREEN_W, P_SCREEN_H = 444, 960          # 390:844 비율
P_BEZEL, P_TOP = 16, 24
P_BODY_W, P_BODY_H = P_SCREEN_W + P_BEZEL * 2, P_SCREEN_H + P_BEZEL * 2 + P_TOP
P_BODY_X, P_BODY_Y = 300, (H - P_BODY_H) // 4 * 2    # 짝수 좌표 (yuv420)
P_SCREEN_X, P_SCREEN_Y = P_BODY_X + P_BEZEL, P_BODY_Y + P_BEZEL + P_TOP
P_TEXT_X, P_TEXT_W = 900, 900
# 관리자 웹: 1600x900 녹화를 위쪽에 그대로, 아래에 단계 자막
A_X, A_Y, A_W, A_H = 160, 20, 1600, 900

KINDS = {
    "senior": {"name": "어르신 휴대폰 (시니어 앱)", "out": "이로움_시연_시니어앱.mp4", "video": "phone.webm", "focus": "p1"},
    "admin": {"name": "담당 공무원 화면 (관리자 웹)", "out": "이로움_시연_관리자웹.mp4", "video": "admin.webm", "focus": "admin"},
}


def make(kind):
    k = KINDS[kind]
    cards(k["name"])
    picked = [i for i in range(len(steps)) if k["focus"] in FOCUS[i]]
    ends = [steps[i + 1]["t"] if i + 1 < len(steps) else total for i in range(len(steps))]

    # 바탕과 단계별 덮개 (덮개를 시간 순서로 잇는다: ffmpeg concat)
    base = gradient(W, H, (12, 22, 40), (18, 36, 70)).convert("RGBA")
    if kind == "senior":
        body = Image.new("RGBA", (P_BODY_W, P_BODY_H), (0, 0, 0, 0))
        d = ImageDraw.Draw(body)
        d.rounded_rectangle([0, 0, P_BODY_W - 1, P_BODY_H - 1], radius=62, fill=(20, 22, 26, 255), outline=(70, 76, 88, 255), width=3)
        d.rounded_rectangle([P_BODY_W // 2 - 46, 14, P_BODY_W // 2 + 46, 22], radius=4, fill=(55, 58, 66, 255))  # 스피커
        base.alpha_composite(ring(P_BODY_W, P_BODY_H, 62), (P_BODY_X - 10, P_BODY_Y - 10))
        base.alpha_composite(body, (P_BODY_X, P_BODY_Y))
        ImageDraw.Draw(base).text((P_TEXT_X, 250), "시니어 동네 점검단 · 시연 · 어르신 휴대폰", font=font(28), fill=(143, 163, 188))
        corners = Image.new("RGBA", (P_SCREEN_W, P_SCREEN_H), (20, 22, 26, 255))
        ImageDraw.Draw(corners).rounded_rectangle([0, 0, P_SCREEN_W - 1, P_SCREEN_H - 1], radius=44, fill=(0, 0, 0, 0), outline=(20, 22, 26, 255), width=3)
    else:
        frame = Image.new("RGBA", (A_W + 12, A_H + 12), (0, 0, 0, 0))
        ImageDraw.Draw(frame).rounded_rectangle([0, 0, A_W + 11, A_H + 11], radius=14, outline=(70, 76, 88, 255), width=6)
    save(base.convert("RGB"), "_base.png")

    def decor(cur):
        img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        if kind == "senior":
            img.alpha_composite(corners, (P_SCREEN_X, P_SCREEN_Y))
            if cur >= 0:
                bar(img, P_TEXT_X, 820, P_TEXT_W, len(picked), cur)
        else:
            img.alpha_composite(frame, (A_X - 6, A_Y - 6))
            if cur >= 0:
                bar(img, A_X, H - 22, A_W, len(picked), cur)
        return save(img, f"_decor_{cur + 1:02d}.png")

    seq = [(decor(-1), steps[0]["t"])]
    cur = -1
    for i, s in enumerate(steps):
        if i in picked:
            cur = picked.index(i)
        seq.append((decor(cur) if i in picked else seq[-1][0], ends[i] - s["t"]))
    with open(os.path.join(FILM, "_decor.txt"), "w", encoding="utf-8", newline="") as f:
        for name, dur in seq:
            f.write(f"file '{name}'\nduration {max(dur, 0.04):.3f}\n")
        f.write(f"file '{seq[-1][0]}'\n")

    # 자막 (고른 단계만): 서서히 나타나며 살짝 올라온다. 줄마다 파일 하나 (여러 줄을 한 번에 그리면 줄 간격이 커짐)
    if kind == "senior":
        sizes, width, x0, y0, maxl, gap = (56, 50, 44), P_TEXT_W, P_TEXT_X, 300, 5, 18
    else:
        sizes, width, x0, y0, maxl, gap = (38, 34, 30), A_W, A_X, 936, 2, 10
    filters = [
        "[1:v]format=rgba[bg]",
        (f"[0:v]scale={P_SCREEN_W}:{P_SCREEN_H}:flags=lanczos,setsar=1[v]" if kind == "senior"
         else f"[0:v]scale={A_W}:{A_H}:flags=lanczos,setsar=1[v]"),
        (f"[bg][v]overlay={P_SCREEN_X}:{P_SCREEN_Y}:shortest=1[s1]" if kind == "senior"
         else f"[bg][v]overlay={A_X}:{A_Y}:shortest=1[s1]"),
        "[2:v]format=rgba[deco]",
        "[s1][deco]overlay=0:0:eof_action=repeat[s2]",
    ]
    last = "s2"
    for n_, i in enumerate(picked):
        start, end = steps[i]["t"], ends[i]
        for size in sizes:
            lines = wrap(steps[i]["text"], size, width)
            if len(lines) <= maxl:
                break
        for j, line in enumerate(lines):
            name = f"cap{i:02d}_{j}.txt"
            with open(os.path.join(FILM, name), "w", encoding="utf-8", newline="") as f:
                f.write(line)
            nxt = f"c{i}_{j}"
            y = y0 + j * (size + gap)
            filters.append(
                f"[{last}]drawtext=fontfile='{FONT}':textfile='{name}':fontcolor=white:fontsize={size}:x={x0}:"
                f"y='{y}+max(0,10*(1-(t-{start:.2f})/0.4))':alpha='min(1,max(0,(t-{start:.2f})/0.4))':"
                f"enable='between(t,{start:.2f},{end:.2f})'[{nxt}]")
            last = nxt
    idles = [(a, b) for a, b in data.get("idles", []) if b - a > 1]
    if idles:
        bx, by = (P_TEXT_X, 160) if kind == "senior" else (A_X + A_W - 330, A_Y + 22)
        filters.append(f"[{last}]drawtext=fontfile='{FONT}':text='▶▶ 빠르게 감기 ×{SPEED}':fontcolor=white:fontsize=30:box=1:boxcolor=0x1E3A6E@0.85:"
                       f"boxborderw=14:x={bx}:y={by}:enable='{during(idles)}'[ff]")
        last = "ff"
    run([FFMPEG, "-y", "-i", k["video"], "-loop", "1", "-framerate", "30", "-i", "_base.png", "-f", "concat", "-safe", "0", "-i", "_decor.txt",
         "-filter_complex", ";".join(filters), "-map", f"[{last}]", "-t", f"{total:.2f}",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "18", "-r", "30", "_main.mp4"],
        f"[{k['name']}] 1/2 본편 합치는 중…")

    # 고른 단계 구간만 잇고, 그 안의 기다리는 구간은 빨리 감는다
    pieces = []
    for i in picked:
        t, end = steps[i]["t"], ends[i]
        for a, b in sorted(idles):
            if b <= t or a >= end:
                continue
            a, b = max(a, t), min(b, end)
            if a > t:
                pieces.append((t, a, 1))
            pieces.append((a, b, SPEED))
            t = b
        pieces.append((t, end, 1))
    pieces = [p for p in pieces if p[1] - p[0] > 0.05]
    main_len = sum((b - a) / sp for a, b, sp in pieces)
    fl = [f"[0:v]trim={a:.3f}:{b:.3f},setpts=(PTS-STARTPTS)/{sp}[p{j}]" for j, (a, b, sp) in enumerate(pieces)]
    fl.append("".join(f"[p{j}]" for j in range(len(pieces))) + f"concat=n={len(pieces)}:v=1:a=0,fps=30,format=yuv420p,settb=1/30[main]")
    fl.append("[1:v]fps=30,format=yuv420p,settb=1/30,fade=in:st=0:d=0.8[intro]")
    fl.append(f"[2:v]fps=30,format=yuv420p,settb=1/30,fade=out:st={OUTRO - 0.8:.2f}:d=0.8[outro]")
    fl.append(f"[intro][main]xfade=transition=fade:duration={XF}:offset={INTRO - XF:.3f}[im]")
    fl.append(f"[im][outro]xfade=transition=fade:duration={XF}:offset={INTRO - XF + main_len - XF:.3f}[out]")
    run([FFMPEG, "-y", "-i", "_main.mp4", "-loop", "1", "-t", f"{INTRO}", "-i", "_intro.png", "-loop", "1", "-t", f"{OUTRO}", "-i", "_outro.png",
         "-filter_complex", ";".join(fl), "-map", "[out]", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20",
         "-movflags", "+faststart", k["out"]], f"[{k['name']}] 2/2 빨리 감기·표지 붙이는 중…")
    os.remove(os.path.join(FILM, "_main.mp4"))
    length = INTRO + main_len + OUTRO - 2 * XF
    size = os.path.getsize(os.path.join(FILM, k["out"])) / 1e6
    print(f"완료: {os.path.join(FILM, k['out'])} ({length:.0f}초, {size:.1f}MB, 단계 {len(picked)}개)")


for kind in (sys.argv[1:] or ["senior", "admin"]):
    make(kind)
