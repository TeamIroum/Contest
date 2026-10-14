"""회색 테두리만 두른 투명 배경 영상: make-film.py가 만든 녹화 원본에 휴대폰 본체(회색)·관리자 화면 테두리(회색)를 입힌다.
배경·자막·표지는 없고, 테두리 바깥은 투명이다 (영상 편집에서 원하는 배경 위에 올려 쓰기).
- 이로움_테두리_시니어앱_1920x1080.mov: 1920x1080 가운데에 휴대폰 본체(회색 테두리) + 시니어 앱 화면
- 이로움_테두리_관리자웹_1920x1080.mov: 1920x1080 가운데에 회색 테두리 + 관리자 웹 화면
형식: QuickTime Animation(qtrle, RGBA) — 손실 없이 투명도를 담고 프리미어·애프터이펙트·파이널컷·다빈치에서 열린다.

  python make-framed.py [초]      # 초를 주면 앞부분만 (시험용)
필요: pip install imageio-ffmpeg pillow
"""
import os
import subprocess
import sys

import imageio_ffmpeg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FILM = os.path.join(HERE, "shots", "film")
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
W, H = 1920, 1080
GRAY, BODY = (70, 76, 88, 255), (20, 22, 26, 255)   # 테두리 선, 휴대폰 본체
LIMIT = ["-t", sys.argv[1]] if len(sys.argv) > 1 else []

# 시니어 앱: 화면 540x960(9:16) + 본체(테두리 16, 위 스피커 자리 24) 가운데
SW, SH, BEZ, TOP = 540, 960, 16, 24
BW, BH = SW + BEZ * 2, SH + BEZ * 2 + TOP * 2          # 위·아래 베젤을 같게
BX, BY = (W - BW) // 2 // 2 * 2, (H - BH) // 2 // 2 * 2          # 짝수 좌표
SX, SY = BX + BEZ, BY + BEZ + TOP
# 관리자 웹: 1600x900 + 회색 테두리 6px 가운데
AW, AH = 1600, 900
AX, AY = (W - AW) // 2, (H - AH) // 2


def png(name, img):
    img.save(os.path.join(FILM, name))
    return name


# 휴대폰 본체(아래 깔림)와 화면 모서리·가장자리 가리개(위에 덮음)
body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(body)
d.rounded_rectangle([BX, BY, BX + BW - 1, BY + BH - 1], radius=68, fill=BODY, outline=GRAY, width=3)
d.rounded_rectangle([BX + BW // 2 - 50, BY + 14, BX + BW // 2 + 50, BY + 22], radius=4, fill=(55, 58, 66, 255))  # 스피커
png("_frame_body.png", body)
cover = Image.new("RGBA", (W, H), (0, 0, 0, 0))
corner = Image.new("RGBA", (SW, SH), BODY)
ImageDraw.Draw(corner).rounded_rectangle([0, 0, SW - 1, SH - 1], radius=48, fill=(0, 0, 0, 0), outline=BODY, width=3)
cover.alpha_composite(corner, (SX, SY))
# 가리개가 본체 둥근 모서리 밖으로 삐져나오지 않게 본체 모양으로 자른다
shape = Image.new("L", (W, H), 0)
ImageDraw.Draw(shape).rounded_rectangle([BX, BY, BX + BW - 1, BY + BH - 1], radius=68, fill=255)
cover.putalpha(Image.composite(cover.getchannel("A"), Image.new("L", (W, H), 0), shape))
png("_frame_cover.png", cover)
# 관리자 화면 테두리 (안쪽은 투명)
frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(frame).rounded_rectangle([AX - 6, AY - 6, AX + AW + 5, AY + AH + 5], radius=14, outline=GRAY, width=6)
png("_frame_admin.png", frame)

JOBS = [
    ("이로움_원본_시니어앱_1080x1920.mp4", "이로움_테두리_시니어앱_1920x1080.mov",
     f"[1:v]format=rgba[b];[0:v]scale={SW}:{SH}:flags=lanczos,format=rgba[v];[b][v]overlay={SX}:{SY}[s];[2:v]format=rgba[c];[s][c]overlay=0:0[out]",
     ["-loop", "1", "-i", "_frame_body.png", "-loop", "1", "-i", "_frame_cover.png"]),
    ("이로움_원본_관리자웹_1920x1080.mp4", "이로움_테두리_관리자웹_1920x1080.mov",
     f"color=c=black@0.0:s={W}x{H},format=rgba[bg];[0:v]scale={AW}:{AH}:flags=lanczos,format=rgba[v];[bg][v]overlay={AX}:{AY}:shortest=1[s];"
     f"[1:v]format=rgba[f];[s][f]overlay=0:0[out]",
     ["-loop", "1", "-i", "_frame_admin.png"]),
]
def duration(path):
    """원본 영상 길이(초). 테두리 그림은 반복 입력이라, 길이를 정하지 않으면 영상이 끝나지 않는다"""
    import re
    r = subprocess.run([FFMPEG, "-i", path], cwd=FILM, capture_output=True, text=True, encoding="utf-8", errors="replace")
    h, m, sec = re.search(r"Duration: (\d+):(\d+):([\d.]+)", r.stderr).groups()
    return int(h) * 3600 + int(m) * 60 + float(sec)


for src, out, fc, extra in JOBS:
    print(f"{out} 만드는 중…")
    limit = LIMIT or ["-t", f"{duration(src):.2f}"]
    cmd = [FFMPEG, "-y", "-i", src, *extra, "-filter_complex", fc, "-map", "[out]", *limit,
           "-r", "30", "-c:v", "qtrle", "-pix_fmt", "argb", out]
    r = subprocess.run(cmd, cwd=FILM, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print(r.stderr[-2500:])
        sys.exit(1)
    print(f"완료: {out} ({os.path.getsize(os.path.join(FILM, out)) / 1e6:.0f}MB)")
for f in ("_frame_body.png", "_frame_cover.png", "_frame_admin.png"):
    os.remove(os.path.join(FILM, f))
