"""기기 1차 마스킹, 참여자 활동 기록, 시설 이력, 주간 우선 처리 목록, 마스킹 표본 점검."""
import json
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import activity  # noqa: E402
import pipeline  # noqa: E402
import privacy  # noqa: E402
import report  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

IMG = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()
DEVICE = {"masked": True, "report": {"faces": 1, "plates": 0, "detector": "mlkit+ocr", "app_version": "1.0.0"}}


def mock(scenario):
    p = MockProvider()
    p.next_scenario = scenario
    return p


def shoot(pid, scenario, when, participant="P-001", **ctx):
    return pipeline.analyze_photo(IMG, dict(photo_id=pid, participant_id=participant, captured_at=when,
                                            device=DEVICE, **ctx), mock(scenario))


class FakeDet:
    def __init__(self, boxes):
        self.boxes, self.name = boxes, "fake"

    def detect(self, img):
        return list(self.boxes)


# ---- 기기 1차 마스킹 ----
def test_device_masked_upload_recorded():
    r = shoot("p_dev", "drain_normal", "2026-10-08T09:10:00+09:00")
    p = r["meta"]["privacy"]
    assert p["mode"] == "device+server" and p["device"]["detector"] == "mlkit+ocr" and p["server_added"] == 0


def test_server_second_check_counts_device_miss(monkeypatch):
    monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet([privacy.Box(50, 50, 60, 60, "face", 0.9, "fake")]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: ())
    r = shoot("p_miss", "drain_normal", "2026-10-08T09:11:00+09:00")
    assert r["meta"]["privacy"]["server_added"] == 1   # 기기가 놓친 얼굴을 서버가 가림


def test_undeclared_upload_is_server_only():
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_srv"}, mock("drain_normal"))
    assert r["meta"]["privacy"]["mode"] == "server_only"


def test_require_device_masking_rejects(monkeypatch, temp_store):
    monkeypatch.setattr(pipeline, "REQUIRE_DEVICE_MASKING", True)
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_raw"}, mock("drain_normal"))
    assert r["status"] == "error" and list(temp_store.root.iterdir()) == []
    ok = pipeline.analyze_photo(IMG, {"photo_id": "p_ok", "device": DEVICE}, mock("drain_normal"))
    assert ok["status"] == "ok"


def test_device_report_is_sanitized():
    rep = pipeline._device_report({"masked": True, "report": {"faces": "-3", "plates": "x", "detector": "a" * 100}})
    assert rep["faces"] == 0 and rep["plates"] is None and len(rep["detector"]) == 40


# ---- 참여자 활동 ----
def test_participant_activity():
    shoot("a1", "drain_normal", "2026-10-06T09:02:00+09:00", lat=37.82430, lon=127.08960)
    shoot("a2", "sidewalk_danger", "2026-10-06T10:40:00+09:00", lat=37.82410, lon=127.08920)
    shoot("a3", "fence_tilted", "2026-10-07T09:30:00+09:00", lat=37.82380, lon=127.08850)
    shoot("b1", "drain_normal", "2026-10-07T09:31:00+09:00", participant="P-002")
    a = activity.participant_activity("P-001", "2026-10-05", "2026-10-11")
    assert (a["days_active"], a["photos"], a["facilities_checked"]) == (2, 3, 3)
    assert a["days"][0] == {"date": "2026-10-06", "photos": 2, "facilities": 2, "first_capture": "09:02",
                            "last_capture": "10:40", "repair_checks": 0}
    assert a["found"]["긴급"] == 1 and a["found"]["주의"] == 1
    assert [p["participant_id"] for p in activity.all_participants()] == ["P-001", "P-002"]


# ---- 시설 이력 ----
def test_facility_history_with_repair():
    first = shoot("h1", "sidewalk_danger", "2026-10-02T09:12:00+09:00", lat=37.82410, lon=127.08920)
    prior = {"case_id": "C-1", "facility_id": "SW-0001", "facility_type": "sidewalk", "issue_type": "lifted",
             "severity": 3, "bbox": [0.3, 0.6, 0.7, 0.8]}
    pipeline.verify_photo(storage.get_store().load_masked(first["photo_id"]), IMG, prior, "repair",
                          {"photo_id": "h2", "before_photo_id": "h1", "captured_at": "2026-10-08T09:14:00+09:00",
                           "device": DEVICE}, mock("fixed"))
    h = activity.facility_history("SW-0001")
    assert [e["kind"] for e in h["events"]] == ["inspect", "verify"]
    assert h["problems_found"] == 1 and h["repairs_confirmed"] == 1


# ---- 주간 우선 처리 목록 ----
def test_priority_list_order_and_closed_excluded():
    shoot("w1", "fence_tilted", "2026-10-05T09:00:00+09:00", lat=37.82380, lon=127.08850)        # 주의
    shoot("w2", "exercise_part", "2026-10-06T09:00:00+09:00", lat=37.82600, lon=127.09200)       # 긴급, 어린이 시설
    shoot("w3", "bench_broken", "2026-10-05T10:00:00+09:00", lat=37.82580, lon=127.09180)        # 긴급
    shoot("w4", "sidewalk_danger", "2026-10-05T11:00:00+09:00", lat=37.82410, lon=127.08920)     # 긴급, 통학로 → 수리 확인됨
    prior = {"case_id": "C-9", "facility_id": "SW-0001", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3}
    pipeline.verify_photo(IMG, IMG, prior, "repair", {"photo_id": "w5", "captured_at": "2026-10-09T09:00:00+09:00",
                                                     "device": DEVICE}, mock("fixed"))
    out = report.weekly_from_store("2026-10-05", "2026-10-11")
    ranks = [(p["photo_id"], p["grade"]) for p in out["priority"]]
    assert ranks == [("w2", "긴급"), ("w3", "긴급"), ("w1", "주의")]   # 어린이 시설 긴급 먼저, 수리된 w4 제외
    assert out["stats"]["seniors"] == 1 and out["stats"]["repair_fixed"] == 1


# ---- 마스킹 표본 점검 ----
def test_masking_audit_flow(monkeypatch):
    for i in range(6):
        shoot(f"m{i}", "drain_normal", f"2026-10-07T09:0{i}:00+09:00")
    pipeline.analyze_photo(IMG, {"photo_id": "m_srv", "captured_at": "2026-10-07T10:00:00+09:00"}, mock("drain_normal"))
    sample = activity.masking_audit_sample("2026-10-07", "2026-10-07", n=3)
    assert sample[0]["photo_id"] == "m_srv" and len(sample) == 3          # 서버 단독 마스킹은 반드시 포함
    activity.record_audit("m0", ok=False, reviewer="주무관", note="번호판 일부 보임")
    assert activity.is_hidden("m0")
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)
    assert c.get("/images/m0_masked.jpg").status_code == 410               # 보류된 사진은 내려주지 않음
    assert c.get("/images/m1_masked.jpg").status_code == 200
    assert "m0" not in [s["photo_id"] for s in activity.masking_audit_sample("2026-10-07", "2026-10-07", n=10)]


def test_server_endpoints():
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)
    r = c.post("/analyze", files={"image": ("a.jpg", IMG, "image/jpeg")},
               data={"photo_id": "s1", "participant_id": "P-007", "captured_at": "2026-10-08T09:00:00+09:00",
                     "device_masked": "true", "device_mask_report": json.dumps(DEVICE["report"])},
               headers={"X-Mock-Scenario": "bench_broken"}).json()
    assert r["meta"]["privacy"]["mode"] == "device+server" and r["participant_id"] == "P-007"
    assert c.get("/participants/P-007/activity").json()["photos"] == 1
    assert c.get("/report/weekly", params={"start": "2026-10-05", "end": "2026-10-11"}).json()["priority"][0]["photo_id"] == "s1"
    assert c.post("/analyze", files={"image": ("a.jpg", IMG, "image/jpeg")},
                  data={"device_masked": "true", "device_mask_report": "{bad"}).status_code == 400
