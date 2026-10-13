"""시니어 앱(frontend/senior) 시험판을 GitHub Pages(TeamIroum/service의 test/senior/)에 올린다. (저장소 예전 이름 pwa-test)

휴대폰 카메라는 https에서만 열리므로, 시니어 앱을 휴대폰에서 시험하려면 공개 https 주소가 필요하다.
시니어 앱 코드는 고치지 않고, 빌드할 때 사진 보낼 곳(VITE_API_ORIGIN)과 하위 경로(--base)만 정한다.
사진은 PC의 검증 콘솔(터널 주소)로 가고, 검증 콘솔이 AI 서버를 부른다 (CORS는 teamiroum.github.io를 허용).

실행:  python tools/ai-verify/pwa/deploy_senior_pages.py                       # 빌드해서 올림
       python tools/ai-verify/pwa/deploy_senior_pages.py --out <폴더>          # 빌드만 (올리지 않음, 이 PC에서 먼저 시험)
       --api https://<서버>  사진 보낼 곳 (기본: deploy_pages.py와 같은 터널 주소, /api/v1 앞까지)
주소: https://teamiroum.github.io/service/test/senior/
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SENIOR = ROOT / "frontend" / "senior"
REPO = "https://github.com/TeamIroum/service.git"
SUBDIR = "test/senior"
BASE = f"/service/{SUBDIR}/"
PAGES_URL = f"https://teamiroum.github.io{BASE}"
NEEDED_MODELS = ["face_yunet_s_dynamic@1.onnx", "plate-t384@1.onnx", "efficientdet_lite0@1.tflite", "model-manifest.json"]

sys.path.insert(0, str(HERE))
from deploy_pages import default_api  # noqa: E402  (같은 터널 주소를 쓴다)


def run(cmd: list[str], cwd: Path, env: dict | None = None) -> None:
    # npm은 윈도우에서 npm.cmd라 셸로 실행한다
    subprocess.run(cmd, cwd=cwd, env=env, check=True, shell=(os.name == "nt"))


def build(out: Path, origin: str) -> None:
    models = SENIOR / "public" / "models"
    if not all((models / n).is_file() for n in NEEDED_MODELS):
        run(["npm", "run", "models"], SENIOR)   # 번호판·차량 모델 받기 + 해시 붙은 모델 목록
    if not (SENIOR / "node_modules").is_dir():
        run(["npm", "ci"], SENIOR)
    env = {**os.environ, "VITE_API_ORIGIN": origin}
    # frontend/senior/.env가 있어도 위 환경 변수가 우선한다 (Vite)
    run(["npx", "tsc", "--noEmit"], SENIOR)
    run(["npx", "vite", "build", "--base", BASE, "--outDir", str(out), "--emptyOutDir"], SENIOR, env)

    # 앱 정보 파일은 Vite가 고치지 않으므로 하위 경로에 맞춘다 (홈 화면에 추가할 때 시작 주소·아이콘)
    mf = out / "manifest.webmanifest"
    if mf.is_file():
        m = json.loads(mf.read_text(encoding="utf-8"))
        m["start_url"] = m["scope"] = BASE
        for icon in m.get("icons", []):
            if icon.get("src", "").startswith("/"):
                icon["src"] = BASE + icon["src"].lstrip("/")
        m["name"] = m.get("name", "동네 점검단") + " (시험판)"
        mf.write_text(json.dumps(m, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "README.md").write_text(
        "# 시니어 앱 시험판\n\n`frontend/senior`를 빌드한 것입니다. 직접 고치지 말고 "
        "[TeamIroum/Contest](https://github.com/TeamIroum/Contest)의 `tools/ai-verify/pwa/deploy_senior_pages.py`로 올립니다.\n\n"
        f"- 주소: {PAGES_URL}\n- 사진 보낼 곳: `{origin}/api/v1` (PC 검증 콘솔)\n", encoding="utf-8")


def git(*args, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    api = default_api()
    ap.add_argument("--api", default=api[: -len("/api/v1")] if api and api.endswith("/api/v1") else api,
                    help="사진 보낼 서버 (예: https://<서버>, /api/v1 앞까지)")
    ap.add_argument("--out", type=Path, help="빌드만 하고 올리지 않음")
    args = ap.parse_args()
    if not args.api or not args.api.startswith("https://"):
        sys.exit("--api https://<서버 주소> 를 주세요 (휴대폰에서는 https만 됨)")
    origin = args.api.rstrip("/")

    if args.out:
        build(args.out.resolve(), origin)
        print(f"빌드했습니다: {args.out} (사진 보낼 곳 {origin}/api/v1, 하위 경로 {BASE})")
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "site"
        git("clone", "--depth", "1", REPO, str(work), cwd=Path(tmp))
        target = work / SUBDIR
        build(target, origin)          # test/senior/만 바꾸고 나머지(입구 페이지·시험용 PWA)는 그대로 둔다
        git("add", "-A", SUBDIR, cwd=work)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=work).returncode == 0:
            print("바뀐 것이 없습니다.")
            return 0
        head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        git("commit", "-q", "-m", f"시니어 앱 시험판 배포 (Contest {head}, API {origin})", cwd=work)
        git("push", "-q", "origin", "HEAD:main", cwd=work)
    print(f"올렸습니다. 1~2분 뒤 {PAGES_URL} 에서 열립니다. (사진 보낼 곳: {origin}/api/v1)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
