"""시험용 PWA가 쓸 번호판·차량 모델을 pwa/models/에 받아 둔다 (용량이 커서 Git에 넣지 않음).

얼굴 모델(YuNet)은 ai/contract/yunet_web/의 파일을 검증 콘솔이 그대로 내려주므로 받지 않는다.
번호판 모델은 AI 서버가 쓰는 open-image-models 캐시(~/.cache/open-image-models)에 있으면 복사한다.

실행:  python tools/ai-verify/pwa/fetch_models.py
"""
from __future__ import annotations

import shutil
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "models"

# (저장 이름, 원본 주소, open-image-models 캐시 위치)
MODELS = [
    ("plate-t384@1.onnx",
     "https://github.com/ankandrew/open-image-models/releases/download/assets/yolo-v9-t-384-license-plates-end2end.onnx",
     Path.home() / ".cache/open-image-models/yolo-v9-t-384-license-plate-end2end/yolo-v9-t-384-license-plates-end2end.onnx"),
    ("efficientdet_lite0@1.tflite",
     "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite",
     None),
]


def main() -> int:
    OUT.mkdir(exist_ok=True)
    for name, url, cache in MODELS:
        dst = OUT / name
        if dst.is_file() and dst.stat().st_size > 0:
            print(f"있음   {name} ({dst.stat().st_size / 1e6:.1f}MB)")
            continue
        if cache and cache.is_file():
            shutil.copyfile(cache, dst)
            print(f"복사   {name} ← {cache}")
            continue
        print(f"받는 중 {name} ← {url}")
        tmp = dst.with_suffix(".part")
        try:
            urllib.request.urlretrieve(url, tmp)
        except OSError as e:
            tmp.unlink(missing_ok=True)
            print(f"실패   {name}: {e}", file=sys.stderr)
            return 1
        tmp.replace(dst)
        print(f"완료   {name} ({dst.stat().st_size / 1e6:.1f}MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
