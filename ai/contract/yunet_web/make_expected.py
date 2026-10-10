"""서버와 같은 방식(OpenCV FaceDetectorYN)으로 기대 결과 expected.json을 만들고,
yunet_reference.py(ONNX Runtime만 사용)가 그 결과와 똑같은지 확인합니다.

실행: python make_expected.py
모델이나 서버 설정(FACE_SCORE 등)이 바뀌면 다시 실행해 expected.json을 갱신하고 앱 담당에게 알립니다.
"""
import json
import sys
from pathlib import Path

import cv2
import onnx

HERE = Path(__file__).resolve().parent
AI = HERE.parents[1]
sys.path.insert(0, str(HERE))
import yunet_reference as ref  # noqa: E402

SERVER_MODEL = AI / "models" / "face_yunet_s.onnx"


def make_dynamic_model():
    """서버 모델의 입력 크기(640×640 고정)를 가변으로 바꾼다. 가중치는 그대로다.
    OpenCV는 크기를 바꿔 돌릴 수 있지만 ONNX Runtime은 선언된 크기를 지키므로 필요하다."""
    m = onnx.load(str(SERVER_MODEL))
    dims = m.graph.input[0].type.tensor_type.shape.dim
    for i, name in ((2, "H"), (3, "W")):
        dims[i].ClearField("dim_value")
        dims[i].dim_param = name
    for o in m.graph.output:
        for d in o.type.tensor_type.shape.dim:
            if d.HasField("dim_value") and d.dim_value > 1:
                d.ClearField("dim_value")
                d.dim_param = "N"
    del m.graph.value_info[:]
    onnx.checker.check_model(m)
    onnx.save(m, str(ref.MODEL))


def opencv_faces(img):
    det = cv2.FaceDetectorYN.create(str(SERVER_MODEL), "", (320, 320), ref.SCORE_THRESHOLD, ref.NMS_THRESHOLD, ref.TOP_K)
    det.setInputSize((img.shape[1], img.shape[0]))
    _, f = det.detect(img)
    return [] if f is None else [list(map(float, x)) for x in f]


def main():
    make_dynamic_model()
    out = {"generated_by": "서버와 같은 방식: OpenCV FaceDetectorYN, face_yunet_s.onnx, 원본 해상도",
           "opencv_version": cv2.__version__, "score_threshold": ref.SCORE_THRESHOLD,
           "nms_threshold": ref.NMS_THRESHOLD, "images": {}}
    worst = 1.0
    for p in sorted((HERE / "images").glob("*.jpg")):
        img = cv2.imread(str(p))
        cv_faces, ref_faces = opencv_faces(img), ref.detect(img)
        assert len(cv_faces) == len(ref_faces), (p.name, len(cv_faces), len(ref_faces))
        for a in cv_faces:
            worst = min(worst, max(ref.iou(a, b) for b in ref_faces))
        out["images"][p.name] = {"width": img.shape[1], "height": img.shape[0], "faces": [
            {"x": round(f[0], 2), "y": round(f[1], 2), "w": round(f[2], 2), "h": round(f[3], 2),
             "landmarks": [round(v, 2) for v in f[4:14]], "score": round(f[14], 4)} for f in cv_faces]}
    assert worst > 0.999, f"기준 구현이 서버와 다름 (최저 IoU {worst})"
    (HERE / "expected.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print({k: len(v["faces"]) for k, v in out["images"].items()}, "기준 구현 일치")


if __name__ == "__main__":
    main()
