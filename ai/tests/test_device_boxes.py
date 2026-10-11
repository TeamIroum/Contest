"""앱이 보낸 가린 위치(device_mask_report.boxes)로 server_added를 바르게 세는지.

모자이크한 얼굴도 서버 검출기가 다시 얼굴로 찾으므로, 앱이 이미 가린 곳과 겹치는 검출은
'앱이 놓친 것'(server_added)이 아니라 server_redetected로 센다. 가리기는 둘 다 그대로 한다."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import privacy  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

IMG = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()
REPORT = {"faces": 1, "plates": 0, "vehicles": 0, "detector": "yunet+plate-t384+effdet0", "app_version": "1.0.0"}


class FakeDet:
    def __init__(self, boxes):
        self.boxes, self.name = boxes, "fake"

    def detect(self, img):
        return list(self.boxes)


def _one_face(monkeypatch):
    # 서버가 사진 왼쪽 위(픽셀 50~110)에서 얼굴 하나를 찾는다고 가정
    monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet([privacy.Box(50, 50, 60, 60, "face", 0.9, "fake")]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: ())
    monkeypatch.setattr(privacy, "vehicle_detector", lambda: None)


def _analyze(pid, report):
    p = MockProvider()
    p.next_scenario = "drain_normal"
    return pipeline.analyze_photo(IMG, {"photo_id": pid, "device": {"masked": True, "report": report}}, p)


def _face_box():
    """서버가 찾은 얼굴 상자(사방 25% 넓힘)를 0~1 비율로."""
    import images
    H, W = images.decode_upload(IMG).shape[:2]
    b = privacy.Box(50, 50, 60, 60, "face", 0.9, "fake").padded(privacy.FACE_PAD, W, H)
    return [b.x / W, b.y / H, (b.x + b.w) / W, (b.y + b.h) / H]


def test_redetection_inside_device_box_is_not_counted(monkeypatch):
    _one_face(monkeypatch)
    x1, y1, x2, y2 = _face_box()
    report = dict(REPORT, boxes=[{"kind": "face", "box": [x1 - 0.01, y1 - 0.01, x2 + 0.01, y2 + 0.01]}])
    p = _analyze("p_box_hit", report)["meta"]["privacy"]
    assert p["faces"] == 1                                   # 서버는 그대로 가림 (안전망)
    assert p["server_added"] == 0 and p["server_redetected"] == 1
    assert p["device"]["boxes"] == 1                         # 위치 목록은 남기지 않고 개수만


def test_miss_outside_device_boxes_is_counted(monkeypatch):
    _one_face(monkeypatch)
    report = dict(REPORT, boxes=[{"kind": "face", "box": [0.8, 0.8, 0.95, 0.95]}])   # 다른 곳을 가림
    p = _analyze("p_box_miss", report)["meta"]["privacy"]
    assert p["server_added"] == 1 and p["server_redetected"] == 0


def test_mostly_uncovered_detection_is_a_miss(monkeypatch):
    _one_face(monkeypatch)
    x1, y1, x2, y2 = _face_box()
    # 서버 상자의 왼쪽 30%만 앱이 가림 → 놓친 것으로 셈
    report = dict(REPORT, boxes=[[x1, y1, x1 + (x2 - x1) * 0.3, y2]])
    p = _analyze("p_box_partial", report)["meta"]["privacy"]
    assert p["server_added"] == 1 and p["server_redetected"] == 0


def test_without_boxes_counts_everything_as_before(monkeypatch):
    _one_face(monkeypatch)
    p = _analyze("p_box_none", dict(REPORT))["meta"]["privacy"]
    assert p["server_added"] == 1
    assert "server_redetected" not in p and "boxes" not in p["device"]   # 예전 응답과 같은 모양


def test_invalid_boxes_are_ignored(monkeypatch):
    _one_face(monkeypatch)
    report = dict(REPORT, boxes=[{"box": [0.5, 0.5, 0.4, 0.6]}, {"box": "x"}, {"box": [1, 2]}, None, [0.1, 0.1, "a", 0.2]])
    p = _analyze("p_box_bad", report)["meta"]["privacy"]
    assert p["device"]["boxes"] == 0 and p["server_added"] == 1 and p["server_redetected"] == 0


def test_media_check_uses_device_boxes(monkeypatch):
    _one_face(monkeypatch)
    x1, y1, x2, y2 = _face_box()
    dev = {"masked": True, "report": dict(REPORT, boxes=[[x1, y1, x2, y2]])}
    r = pipeline.mask_media(IMG, "md_box_1", "reference", dev)
    assert r["privacy"]["server_added"] == 0 and r["privacy"]["server_redetected"] == 1
