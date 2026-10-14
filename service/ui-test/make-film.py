"""시연 화면 녹화 원본 만들기: demo-film.mjs가 받아 둔 실제 해상도 화면 프레임을 MP4로 잇는다 (배경·자막·표지 없음).
- 이로움_원본_시니어앱_1080x1920.mp4: 시니어 앱, 세로 9:16 원본 해상도
- 이로움_원본_관리자웹_1920x1080.mp4: 관리자 웹, 1920x1080 (브라우저 배율 125%로 본 화면)
- 컷_시니어앱/, 컷_관리자웹/: 단계마다 앞뒤 2.5초 여유를 둔 컷 (그 화면이 움직이는 단계만)
화면 프레임은 화면이 바뀔 때만 오므로, 프레임마다 다음 프레임까지 그대로 보여 주게 이어 30fps로 만든다.

  python make-film.py            # 결과: shots/film/ 아래
필요: pip install imageio-ffmpeg
"""
import json
import os
import shutil
import subprocess
import sys

import imageio_ffmpeg

HERE = os.path.dirname(os.path.abspath(__file__))
FILM = os.path.join(HERE, "shots", "film")
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
PAD = 2.5   # 컷 앞뒤 여유 (초)

data = json.load(open(os.path.join(FILM, "steps.json"), encoding="utf-8"))
steps = [s for s in data["steps"] if s["text"] != "끝"]
total, t0 = data["total"], data["t0Epoch"]
ends = [steps[i + 1]["t"] if i + 1 < len(steps) else total for i in range(len(steps))]
# 단계마다 움직이는 화면 (①~⑮): p1 = 김○○ 휴대폰(시니어 앱), admin = 관리자 웹
FOCUS = [{"p1"}, {"p1"}, {"admin"}, {"p1"}, {"p1"}, {"p1"}, {"p1"}, {"p1"},
         {"admin", "p1"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}, {"admin"}]

KINDS = [
    ("raw_phone", "p1", "이로움_원본_시니어앱_1080x1920.mp4", "컷_시니어앱", 1080, 1920),
    ("raw_admin", "admin", "이로움_원본_관리자웹_1920x1080.mp4", "컷_관리자웹", 1920, 1080),
]


def run(cmd, what):
    print(what)
    r = subprocess.run(cmd, cwd=FILM, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print(os.linesep.join(l for l in r.stderr.splitlines() if not l.startswith(("[libx264", "frame=", "  ")))[-3000:])
        sys.exit(1)


for folder, focus, out, cut_dir, w, h in KINDS:
    frames = json.load(open(os.path.join(FILM, folder, "frames.json"), encoding="utf-8"))
    if not frames:
        print(f"{folder}: 프레임이 없습니다")
        continue
    # 프레임 시각(epoch) → 촬영 시작 기준 초. 첫 프레임 전은 첫 프레임으로 채운다
    times = [max(0.0, ts - t0) for _, ts in frames]
    with open(os.path.join(FILM, folder, "list.txt"), "w", encoding="utf-8", newline="") as f:
        for k, (name, _) in enumerate(frames):
            start = 0.0 if k == 0 else times[k]
            end = times[k + 1] if k + 1 < len(frames) else total
            f.write(f"file '{name}'\nduration {max(end - start, 0.001):.4f}\n")
        f.write(f"file '{frames[-1][0]}'\n")
    # 프레임 크기가 조금 다를 수 있어 정확한 크기로 맞춘다 (비율 유지, 남는 곳은 흰색)
    vf = (f"scale={w}:{h}:force_original_aspect_ratio=decrease:flags=lanczos,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=white,"
          "setsar=1,fps=30,format=yuv420p")
    run([FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", os.path.join(folder, "list.txt"), "-vf", vf,
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "16", "-movflags", "+faststart", out], f"{out} 만드는 중…")

    # 단계별 컷 (앞뒤 여유 포함)
    cd = os.path.join(FILM, cut_dir)
    shutil.rmtree(cd, ignore_errors=True)
    os.makedirs(cd)
    lines, n = [f"앞뒤 {PAD}초 여유를 둔 컷입니다. 시각은 {out} 기준(초)", ""], 0
    for i, s in enumerate(steps):
        if focus not in FOCUS[i]:
            continue
        n += 1
        a, b = max(0.0, s["t"] - PAD), min(total, ends[i] + PAD)
        name = f"{n:02d}_{s['text'][0]}.mp4"   # 예: 01_①.mp4
        run([FFMPEG, "-y", "-ss", f"{a:.2f}", "-to", f"{b:.2f}", "-i", out, "-c:v", "libx264", "-preset", "medium", "-crf", "16",
             "-movflags", "+faststart", os.path.join(cut_dir, name)], f"  컷 {name} ({a:.1f}~{b:.1f}초)")
        lines.append(f"{name}  {a:6.1f} ~ {b:6.1f}  {s['text']}")
    with open(os.path.join(cd, "컷_목록.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    shutil.rmtree(os.path.join(FILM, folder))   # 프레임 원본은 MP4로 옮겼으니 지운다
    size = os.path.getsize(os.path.join(FILM, out)) / 1e6
    print(f"완료: {out} ({total:.0f}초, {size:.1f}MB), 컷 {n}개 → {cut_dir}/")
