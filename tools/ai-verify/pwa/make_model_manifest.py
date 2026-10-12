"""가림 모델 폴더에 모델 목록(model-manifest.json)을 만든다. masking.loadModels({manifestUrl})가 읽는다.

시니어 앱처럼 모델을 정적 파일로 올릴 때 쓴다 (시험용 PWA는 검증 콘솔이 목록을 직접 내려줌).
주소마다 내용 해시(?v=)를 붙인다. 앱은 받은 뒤 해시를 확인하고, 모델이 바뀌면 캐시를 새로 받는다.
모델 파일을 바꾸면 이 스크립트를 다시 실행한다.

사용:  python tools/ai-verify/pwa/make_model_manifest.py frontend/senior/public/models
       → frontend/senior/public/models/model-manifest.json
앱:    masking.loadModels(onChange, { manifestUrl: "/models/model-manifest.json" })
"""
import hashlib
import json
import sys
from pathlib import Path

# 키 → 파일 이름 (masking.js가 쓰는 모델. 이름의 @숫자는 모델 버전)
MODELS = {
    "face": "face_yunet_s_dynamic@1.onnx",
    "plate": "plate-t384@1.onnx",
    "vehicle": "efficientdet_lite0@1.tflite",
}


def main(folder: str) -> None:
    d = Path(folder)
    files, missing = {}, []
    for key, name in MODELS.items():
        p = d / name
        if not p.is_file():
            missing.append(name)
            continue
        h = hashlib.sha256(p.read_bytes()).hexdigest()[:12]   # 검증 콘솔(app.py _file_hash)과 같은 길이
        files[key] = f"./{name}?v={h}"
    if missing:
        sys.exit(f"모델 파일 없음: {', '.join(missing)} ({d})")
    version = hashlib.sha256("|".join(files[k] for k in sorted(files)).encode()).hexdigest()[:8]
    out = d / "model-manifest.json"
    out.write_text(json.dumps({"version": version, "files": files}, indent=2) + "\n", encoding="utf-8")
    print(f"{out}  version {version}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
