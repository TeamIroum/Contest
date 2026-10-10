"""시니어 PWA용 얼굴 검출 기준 구현(contract/yunet_web)이 서버와 같은 결과를 내는지 확인한다.

서버의 얼굴 검출 설정(FACE_SCORE)이나 모델이 바뀌었는데 웹용 자료를 다시 만들지 않으면 여기서 실패한다.
"""
import json
import sys
from pathlib import Path

import cv2
import pytest

AI = Path(__file__).resolve().parent.parent
KIT = AI / "contract" / "yunet_web"
sys.path.insert(0, str(AI))
sys.path.insert(0, str(KIT))

import privacy  # noqa: E402
import yunet_reference as ref  # noqa: E402

IMAGES = sorted((KIT / "images").glob("*.jpg"))


def test_kit_uses_server_settings():
    assert ref.SCORE_THRESHOLD == privacy.FACE_SCORE
    expected = json.loads((KIT / "expected.json").read_text(encoding="utf-8"))
    assert expected["score_threshold"] == privacy.FACE_SCORE
    assert {p.name for p in IMAGES} == set(expected["images"])


@pytest.mark.parametrize("path", IMAGES, ids=lambda p: p.name)
def test_reference_matches_server_detector(path):
    img = cv2.imread(str(path))
    # 서버 검출기와 같은 설정의 OpenCV 객체에서, 정수로 자르기 전 값을 비교한다
    server = privacy.YuNetFaces(str(AI / "models" / "face_yunet_s.onnx")).det
    server.setInputSize((img.shape[1], img.shape[0]))
    _, faces = server.detect(img)
    faces = [] if faces is None else faces
    mine = ref.detect(img)
    assert len(mine) == len(faces)
    for f in faces:
        assert max(ref.iou(list(f[:4]), m) for m in mine) > 0.999
        best = max(mine, key=lambda m: ref.iou(list(f[:4]), m))
        assert abs(best[-1] - float(f[-1])) < 1e-4


@pytest.mark.parametrize("path", IMAGES, ids=lambda p: p.name)
def test_expected_json_is_current(path):
    expected = json.loads((KIT / "expected.json").read_text(encoding="utf-8"))["images"][path.name]
    mine = ref.detect(cv2.imread(str(path)))
    assert len(mine) == len(expected["faces"])
    for e in expected["faces"]:
        assert max(ref.iou([e["x"], e["y"], e["w"], e["h"]], m) for m in mine) > 0.99
