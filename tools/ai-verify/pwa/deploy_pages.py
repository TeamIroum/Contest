"""시험용 PWA를 GitHub Pages(TeamIroum/pwa-test)에 올린다.

PC 터널(Tailscale Funnel)로 화면·모델을 받으면 느려서, 화면·모델은 GitHub Pages(CDN)에서 받고
사진 전송·업무 API만 터널로 PC의 검증 콘솔에 보낸다. 검증 콘솔은 이 주소의 요청을 허용한다(CORS, PWA_ORIGINS).

실행:  python tools/ai-verify/pwa/deploy_pages.py
       python tools/ai-verify/pwa/deploy_pages.py --api https://<서버 주소>/api/v1
--api를 빼면 tools/ai-test-app/.env.local의 EXPO_PUBLIC_SERVER_URL(start-test가 채움)에 /api/v1을 붙인다.
주소: https://teamiroum.github.io/pwa-test/
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
YUNET_WEB = ROOT / "ai" / "contract" / "yunet_web"
REPO = "https://github.com/TeamIroum/pwa-test.git"
PAGES_URL = "https://teamiroum.github.io/pwa-test/"

SHELL = ["index.html", "app.js", "masking.js", "download.js", "quality.js", "camera.js", "outbox.js", "style.css", "manifest.webmanifest", "icon.svg",
         "sw.js", "mask-host.html", "mask-host.js"]
# backend/app.py의 PWA_MODELS·PWA_MODEL_ORIGINS와 같은 목록·규칙 (버전이 검증 콘솔 배포본과 같아야 함)
MODELS = {
    "face": ("models/face_yunet_s_dynamic@1.onnx", YUNET_WEB / "face_yunet_s_dynamic.onnx"),
    "plate": ("models/plate-t384@1.onnx", HERE / "models" / "plate-t384@1.onnx"),
    "vehicle": ("models/efficientdet_lite0@1.tflite", HERE / "models" / "efficientdet_lite0@1.tflite"),
}
ORIGINS = {
    "vehicle": "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite",
}


def default_api() -> str | None:
    env = ROOT / "tools" / "ai-test-app" / ".env.local"
    if env.is_file():
        for line in env.read_text(encoding="utf-8-sig").splitlines():
            if line.startswith("EXPO_PUBLIC_SERVER_URL="):
                return line.split("=", 1)[1].strip().rstrip("/") + "/api/v1"
    return None


def build(out: Path, api: str) -> str:
    for name in SHELL:
        shutil.copyfile(HERE / name, out / name)
    (out / "lib").mkdir()
    shutil.copyfile(YUNET_WEB / "yunet.mjs", out / "lib" / "yunet.mjs")
    (out / "selftest").mkdir()
    shutil.copyfile(YUNET_WEB / "expected.json", out / "selftest" / "expected.json")
    for img in (YUNET_WEB / "images").iterdir():
        shutil.copyfile(img, out / "selftest" / img.name)

    (out / "models").mkdir()
    files, local = {}, {}
    for key, (url, path) in MODELS.items():
        if not path.is_file():
            sys.exit(f"모델 파일 없음: {path} (python tools/ai-verify/pwa/fetch_models.py)")
        h = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
        local[key] = f"./{url}?v={h}"
        if key in ORIGINS:
            files[key] = f"{ORIGINS[key]}?v={h}"
        else:
            files[key] = local[key]
            shutil.copyfile(path, out / url)
    version = hashlib.sha256("|".join(local[k] for k in sorted(local)).encode()).hexdigest()[:8]
    (out / "model-manifest.json").write_text(json.dumps({"version": version, "files": files}), encoding="utf-8")

    (out / "config.js").write_text(
        "// deploy_pages.py가 만든 파일. 업무 API는 PC 검증 콘솔(터널 주소)로 보낸다.\n"
        f"self.PWA_CONFIG = {json.dumps({'api': api})};\n", encoding="utf-8")
    (out / ".nojekyll").write_text("", encoding="utf-8")   # Jekyll 처리 없이 파일 그대로
    (out / "README.md").write_text(
        "# pwa-test\n\n시니어 동네 점검단 시험용 PWA 배포본입니다. 직접 고치지 말고 "
        "[TeamIroum/Contest](https://github.com/TeamIroum/Contest)의 `tools/ai-verify/pwa/deploy_pages.py`로 올립니다.\n\n"
        f"- 주소: {PAGES_URL}\n- 업무 API: `{api}`\n", encoding="utf-8")
    return version


def git(*args, cwd: Path):
    subprocess.run(["git", *args], cwd=cwd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=default_api(), help="업무 API 주소 (예: https://<서버>/api/v1)")
    args = ap.parse_args()
    if not args.api or not args.api.startswith("https://"):
        sys.exit("--api https://<서버 주소>/api/v1 를 주세요 (휴대폰 카메라는 https에서만 열림)")

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "site"
        git("clone", "--depth", "1", REPO, str(work), cwd=Path(tmp))
        for p in work.iterdir():
            if p.name != ".git":
                shutil.rmtree(p) if p.is_dir() else p.unlink()
        version = build(work, args.api)
        git("add", "-A", cwd=work)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=work).returncode == 0:
            print("바뀐 것이 없습니다.")
            return 0
        git("commit", "-q", "-m", f"PWA 배포 (모델 {version}, API {args.api})", cwd=work)
        git("push", "-q", "origin", "HEAD:main", cwd=work)
    print(f"올렸습니다. 1~2분 뒤 {PAGES_URL} 에서 열립니다. (업무 API: {args.api})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
