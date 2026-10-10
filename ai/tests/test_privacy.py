"""마스킹 테스트: 방향 보정, 메타데이터 제거, 검출기, 가리는 방식, 실패 시 차단, AI 2차 확인."""
import io
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import images  # noqa: E402
import pipeline  # noqa: E402
import privacy  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

SAMPLE = Path(__file__).resolve().parent.parent / "eval" / "sample"
IMG = (SAMPLE / "s01.jpg").read_bytes()


def mock(scenario):
    p = MockProvider()
    p.next_scenario = scenario
    return p


def textured(h=600, w=800):
    rng = np.random.default_rng(1)
    img = (rng.random((h, w, 3)) * 255).astype(np.uint8)
    return cv2.GaussianBlur(img, (3, 3), 0)


class FakeDet:
    def __init__(self, boxes, name="fake"):
        self.boxes, self.name = boxes, name

    def detect(self, img):
        return list(self.boxes)


@pytest.fixture
def fake_detectors(monkeypatch):
    def install(faces=(), plates=()):
        monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet(faces))
        monkeypatch.setattr(privacy, "plate_detectors", lambda: (FakeDet(plates),))
    return install


# ---- 업로드 처리 ----
def test_exif_orientation_applied_and_gps_removed():
    im = Image.new("RGB", (400, 200), (200, 200, 200))
    im.paste((0, 0, 0), (0, 0, 100, 200))           # 왼쪽 검은 띠
    exif = Image.Exif()
    exif[0x0112] = 6                                  # 90도 회전 표시
    exif[0x8825] = {2: (37.0, 49.0, 26.0), 1: "N"}   # GPS
    buf = io.BytesIO()
    im.save(buf, format="JPEG", exif=exif)
    arr = images.decode_upload(buf.getvalue())
    assert arr.shape[:2] == (400, 200)                # 세로 사진으로 보정됨
    out = images.encode_jpeg(arr)
    assert Image.open(io.BytesIO(out)).getexif() == {}  # 메타데이터 없음


def test_reject_non_image_and_too_large():
    with pytest.raises(images.ImageRejected) as e:
        images.decode_upload(b"%PDF-1.4 not an image")
    assert e.value.code == "unsupported_type"
    with pytest.raises(images.ImageRejected) as e:
        images.decode_upload(b"\xff\xd8\xff" + b"0" * (images.MAX_UPLOAD_BYTES + 1))
    assert e.value.code == "too_large"


def test_rejected_upload_is_error_and_saves_nothing(temp_store):
    r = pipeline.analyze_photo(b"not an image", {}, MockProvider())
    assert r["status"] == "error" and r["senior_message"]["key"] == "retry_later"
    assert list(temp_store.root.iterdir()) == []


# ---- 검출기 ----
def test_yunet_model_is_used():
    assert isinstance(privacy.face_detector(), privacy.YuNetFaces)


def test_face_pixelated_and_plate_filled(fake_detectors):
    img = textured()
    fake_detectors(faces=[privacy.Box(100, 100, 80, 80, "face", 0.9, "fake")],
                   plates=[privacy.Box(500, 400, 120, 30, "plate", 0.9, "fake")])
    out, rep = privacy.mask_image(img)
    assert (rep.faces, rep.plates) == (1, 1)
    face = out[110:170, 110:170].astype(float)
    assert face.std() < img[110:170, 110:170].astype(float).std() * 0.3   # 얼굴 무늬가 뭉개짐
    plate = out[405:425, 510:610]
    assert (plate == privacy.PLATE_FILL).all()                              # 번호판은 단색으로 지움
    assert not np.shares_memory(out, img)                                   # 원본 배열은 그대로


def test_detection_runs_at_high_resolution(fake_detectors, monkeypatch):
    seen = {}

    class Spy(FakeDet):
        def detect(self, img):
            seen["shape"] = img.shape
            return []
    monkeypatch.setattr(privacy, "face_detector", lambda: Spy([]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: ())
    privacy.mask_image(textured(3024, 4032))
    assert max(seen["shape"][:2]) == privacy.DETECT_MAX_SIDE  # 축소 전 큰 해상도에서 찾음


def test_decode_yolo():
    out = np.zeros((1, 5, 3), np.float32)
    out[0, :, 0] = [320, 320, 100, 20, 0.9]   # 잡을 것
    out[0, :, 1] = [100, 100, 50, 10, 0.1]    # 점수 낮음
    out[0, :, 2] = [322, 321, 98, 20, 0.8]    # 겹침
    boxes = privacy.decode_yolo(out, k=0.5, score=0.25, W=1280, H=1280)
    assert len(boxes) == 1 and boxes[0].w == 200 and boxes[0].kind == "plate"


# ---- 실패 시 차단 ----
def test_masking_failure_blocks_storage_and_ai(temp_store, monkeypatch):
    def boom(img):
        raise RuntimeError("검출기 고장")
    monkeypatch.setattr(privacy, "mask_image", boom)

    class NoCall(MockProvider):
        def analyze(self, *a, **k):
            raise AssertionError("가리지 못한 사진을 AI에 보내면 안 됨")
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_fail"}, NoCall())
    assert r["status"] == "error" and r["masked_image_url"] is None
    assert list(temp_store.root.iterdir()) == []


# ---- AI 2차 확인 ----
def test_ai_reported_leak_is_remasked(temp_store):
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_leak"}, mock("plate_leak"))
    assert r["meta"]["privacy"]["leaks_fixed"] == 1
    saved = cv2.imdecode(np.frombuffer(temp_store.load_masked("p_leak"), np.uint8), cv2.IMREAD_COLOR)
    H, W = saved.shape[:2]
    region = saved[int(0.07 * H):int(0.13 * H), int(0.08 * W):int(0.27 * W)].astype(int)
    assert np.abs(region - np.array(privacy.PLATE_FILL)).mean() < 8   # 그 자리가 단색으로 덮임
    assert any("다시 가렸" in n for n in r["notes"])


def test_issue_under_mask_goes_to_human(fake_detectors):
    fake_detectors(plates=[privacy.Box(300, 220, 200, 160, "plate", 0.9, "fake")])  # 사진 가운데를 가림
    r = pipeline.analyze_photo(IMG, {"location_tags": []}, mock("issue_under_mask"))
    assert r["status"] == "needs_review"
    assert any("가림 영역과 겹침" in n for n in r["notes"])


# ---- 사전 학습 모델과 차량 안전망 ----
def test_vehicle_masked_only_when_no_plate_inside(monkeypatch):
    img = textured()
    car_with_plate = privacy.Box(50, 300, 300, 200, "vehicle", 0.9, "fake")
    car_without = privacy.Box(450, 300, 300, 200, "vehicle", 0.9, "fake")
    plate = privacy.Box(150, 430, 90, 25, "plate", 0.8, "fake")
    monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet([]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: (FakeDet([plate]),))
    monkeypatch.setattr(privacy, "vehicle_detector", lambda: FakeDet([car_with_plate, car_without]))
    out, rep = privacy.mask_image(img)
    assert (rep.plates, rep.vehicles) == (1, 1)            # 번호판을 찾은 차는 번호판만, 못 찾은 차는 차 전체
    assert out[350:450, 500:700].std() < img[350:450, 500:700].std() * 0.3
    assert abs(out[320:400, 80:140].astype(int) - img[320:400, 80:140].astype(int)).mean() < 1   # 번호판 찾은 차 몸체는 그대로


def test_pretrained_models_load_if_installed():
    pytest.importorskip("open_image_models")
    try:
        plates = privacy.PretrainedPlates("yolo-v9-t-256-license-plate-end2end", 0.15)
    except Exception as e:  # 모델을 내려받을 수 없는 환경
        pytest.skip(f"모델 내려받기 불가: {e}")
    assert isinstance(plates.detect(textured()), list)
