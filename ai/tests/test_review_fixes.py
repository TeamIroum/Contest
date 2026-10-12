"""코드 점검(2026-10-12)에서 찾은 문제가 다시 생기지 않는지 확인한다."""
import importlib
import shutil
import sys
import threading
import time
from pathlib import Path

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

import activity  # noqa: E402
import pipeline  # noqa: E402
import privacy  # noqa: E402
import quality  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import ANALYZE_SCENARIOS, MockProvider  # noqa: E402

IMG = (ROOT / "eval" / "sample" / "s01.jpg").read_bytes()
PRIOR = {"case_id": "C-1", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3,
         "bbox": [0.34, 0.65, 0.66, 0.80]}


def mock(scenario):
    p = MockProvider()
    p.next_scenario = scenario
    return p


class FakeDet:
    def __init__(self, boxes, name="fake"):
        self.boxes, self.name = boxes, name

    def detect(self, img):
        return list(self.boxes)


class Answer(MockProvider):
    """정해 둔 응답을 돌려주고 호출 수를 센다. delay를 주면 AI가 느린 상황을 흉내 낸다."""

    def __init__(self, answer, delay=0.0):
        super().__init__()
        self.answer, self.delay, self.calls = answer, delay, 0

    def analyze(self, *a, **k):
        self.calls += 1
        time.sleep(self.delay)
        return dict(self.answer)


def files(store):
    return sorted(f.name for f in store.root.iterdir())


# ---- 마스킹 ----
def test_heuristic_false_plate_does_not_turn_off_vehicle_mask(monkeypatch):
    img = np.full((600, 800, 3), 128, np.uint8)
    car = privacy.Box(100, 200, 400, 250, "vehicle", 0.9, "vehicle")
    sticker = privacy.Box(250, 300, 80, 20, "plate", 0.5, "heuristic")     # 규칙 기반 오탐이 차 안에 걸림
    monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet([]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: (FakeDet([sticker]),))
    monkeypatch.setattr(privacy, "vehicle_detector", lambda: FakeDet([car]))
    _, rep = privacy.mask_image(img)
    assert rep.vehicles == 1                                                 # 차 전체를 그대로 가림
    real = privacy.Box(250, 380, 90, 25, "plate", 0.8, "pretrained")         # 학습 모델이 찾은 번호판이면 번호판만
    monkeypatch.setattr(privacy, "plate_detectors", lambda: (FakeDet([real]),))
    _, rep = privacy.mask_image(img)
    assert (rep.plates, rep.vehicles) == (1, 0)


def test_detector_load_failure_rejects_photo(monkeypatch, temp_store):
    def broken():
        raise privacy.DetectorUnavailable("차량 검출 모델를 불러오지 못함: ConnectError")
    monkeypatch.setattr(privacy, "plate_detectors", lambda: ())
    monkeypatch.setattr(privacy, "vehicle_detector", broken)
    r = pipeline.analyze_photo(IMG, {"photo_id": "nodet"}, mock("drain_normal"))
    assert r["status"] == "error" and r["masked_image_url"] is None
    assert files(temp_store) == []                                           # 약하게 가린 사진도 남기지 않음
    st = privacy.detector_status()
    assert st["ok"] is False and st["vehicle"].startswith("오류")


def test_configured_model_failure_is_not_cached(monkeypatch):
    calls = []

    class Flaky:
        name = "pretrained"

        def __init__(self, *a):
            calls.append(1)
            if len(calls) == 1:
                raise ConnectionError("내려받기 실패")

    monkeypatch.setattr(privacy, "PLATE_PRETRAINED", "x")
    monkeypatch.setattr(privacy, "PretrainedPlates", Flaky)
    privacy.plate_detectors.cache_clear()
    try:
        with pytest.raises(privacy.DetectorUnavailable):
            privacy.plate_detectors()
        assert any(isinstance(d, Flaky) for d in privacy.plate_detectors())   # 다음 호출에서 다시 시도
    finally:
        privacy.plate_detectors.cache_clear()


def test_thin_image_is_retake_not_crash():
    q = quality.check_array(np.full((3, 4000, 3), 200, np.uint8))
    assert q["ok"] is False and q["problems"] == ["unreadable"]


# ---- 같은 photo_id 동시 처리·보류 ----
def test_duplicate_request_while_first_running_calls_ai_once():
    p = Answer(ANALYZE_SCENARIOS["drain_normal"], delay=0.3)
    out = []
    ts = [threading.Thread(target=lambda: out.append(pipeline.analyze_photo(IMG, {"photo_id": "dup"}, p)))
          for _ in range(2)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert p.calls == 1
    assert sorted(bool((r.get("meta") or {}).get("replayed")) for r in out) == [False, True]
    assert storage.get_store().revision("dup") == 1


def test_audit_during_reanalyze_is_not_overwritten():
    fail = MockProvider()
    fail.analyze = lambda *a, **k: (_ for _ in ()).throw(TimeoutError("느림"))
    pipeline.analyze_photo(IMG, {"photo_id": "race"}, fail)                 # 재판독 대기 상태로 만든다
    slow = Answer(ANALYZE_SCENARIOS["drain_normal"], delay=0.3)
    t = threading.Thread(target=pipeline.reanalyze, args=("race", 1, slow))
    t.start()
    time.sleep(0.05)                                                         # 재판독이 AI를 기다리는 중
    activity.record_audit("race", False, "주무관", "얼굴 보임")
    t.join()
    assert activity.is_hidden("race")                                        # 재판독 저장이 보류를 지우지 않음


def test_hidden_photo_not_sent_to_ai_again():
    fail = MockProvider()
    fail.analyze = lambda *a, **k: (_ for _ in ()).throw(TimeoutError("느림"))
    pipeline.analyze_photo(IMG, {"photo_id": "held"}, fail)
    activity.record_audit("held", False, "주무관", "번호판 보임")
    p = Answer(ANALYZE_SCENARIOS["drain_normal"])
    r = pipeline.reanalyze("held", 1, p)
    assert p.calls == 0 and r["retry"]["gave_up"] and r["status"] == "needs_review"
    with pytest.raises(pipeline.PhotoHidden):                                # 비교에도 쓰지 않는다
        pipeline.verify_photo(None, IMG, PRIOR, "repair", {"photo_id": "v_held", "before_photo_id": "held"},
                              mock("fixed"))


# ---- AI 응답·요청 형식 ----
@pytest.mark.parametrize("bad", [
    {"issues": [{"type": "lifted", "severity": 4, "bbox": [0.1, 0.1, 0.5, 0.5], "evidence": "x"}]},
    {"issues": [{"type": "lifted", "severity": 2, "bbox": [0.1, 0.1, 0.5, 0.5]}]},
    {"privacy_leaks": [{"kind": "plate", "bbox": [0.1, 0.1, 0.5]}]},
    {"framing_ok": None},
])
def test_malformed_model_output_goes_to_retry(bad):
    answer = dict(ANALYZE_SCENARIOS["sidewalk_danger"], **bad)
    r = pipeline.analyze_photo(IMG, {"photo_id": "bad"}, Answer(answer))
    assert r["status"] == "needs_review" and r["retry"]["pending"]
    assert storage.get_results().load("bad") is not None                    # 결과가 저장돼 재판독할 수 있음


@pytest.mark.parametrize("ctx", [
    {"history": {"severity": None}}, {"history": [1]}, {"open_cases": [1]}, {"capture_hour": 30},
    {"location_tags": "school_route"},
])
def test_bad_context_rejected_before_storing(ctx, temp_store):
    with pytest.raises(pipeline.RequestError):
        pipeline.analyze_photo(IMG, dict(ctx, photo_id="ctx"), mock("sidewalk_danger"))
    assert files(temp_store) == []


def test_bad_prior_rejected_before_storing(temp_store):
    with pytest.raises(pipeline.RequestError):
        pipeline.verify_photo(IMG, IMG, {"facility_type": "sidewalk"}, "change", {"photo_id": "pr"}, mock("worse"))
    assert files(temp_store) == []


def test_norm1000_small_box_stays_small():
    from providers.ollama_provider import to_ratio_box
    assert to_ratio_box([0, 0, 1, 1], 1000, 1000, "norm1000") == [0.0, 0.0, 0.001, 0.001]
    assert to_ratio_box([0.2, 0.4, 0.7, 0.6], 1000, 1000, "norm1000") == [0.2, 0.4, 0.7, 0.6]


def test_overlap_counts_union_of_mask_boxes():
    priv = {"boxes": [{"box": [0.0, 0.0, 0.2, 1.0]}, {"box": [0.8, 0.0, 1.0, 1.0]}]}
    issue = [0.1, 0.4, 0.9, 0.6]                       # 상자마다 1/8씩, 합치면 1/4 겹침
    assert pipeline._hidden_by_mask(issue, priv)
    assert not pipeline._hidden_by_mask(issue, {"boxes": priv["boxes"][:1]})


# ---- 전후 비교 ----
def test_verify_before_upload_follows_device_masking_rule(monkeypatch, temp_store):
    monkeypatch.setattr(pipeline, "REQUIRE_DEVICE_MASKING", True)
    dev = {"masked": True, "report": {"faces": 0, "plates": 0}}
    r = pipeline.verify_photo(IMG, IMG, PRIOR, "repair", {"photo_id": "vb", "device": dev}, mock("fixed"))
    assert r["status"] == "ok"
    big = np.full((3000, 3000, 3), 120, np.uint8)
    ok, buf = cv2.imencode(".jpg", big)
    shutil.rmtree(temp_store.root)
    temp_store.root.mkdir()
    r = pipeline.verify_photo(buf.tobytes(), IMG, PRIOR, "repair", {"photo_id": "vb2", "device": dev}, mock("fixed"))
    before = next(n for n in files(temp_store) if n.startswith("m_vb_"))
    saved = cv2.imdecode(np.frombuffer((temp_store.root / before).read_bytes(), np.uint8), cv2.IMREAD_COLOR)
    assert max(saved.shape[:2]) <= 1568                                        # 판독 사진처럼 줄여서 저장
    r = pipeline.verify_photo(IMG, IMG, PRIOR, "repair", {"photo_id": "vb3"}, mock("fixed"))   # 기기 가림 없음
    assert r["status"] == "error" and not any(n.startswith("vb3") for n in files(temp_store))


def test_verify_long_photo_id_can_reverify():
    pid = "v" * 80
    r = pipeline.verify_photo(IMG, IMG, PRIOR, "repair", {"photo_id": pid}, mock("fixed"))
    assert r["status"] == "ok"
    assert pipeline.reverify(pid, 1, mock("fixed")) is not None               # 수리 전 사본을 다시 찾을 수 있음


def test_analyze_and_verify_ids_do_not_overwrite_each_other():
    pipeline.analyze_photo(IMG, {"photo_id": "shared"}, mock("drain_normal"))
    with pytest.raises(pipeline.PhotoIdConflict):
        pipeline.verify_photo(IMG, IMG, PRIOR, "repair", {"photo_id": "shared"}, mock("fixed"))
    assert storage.get_store().revision("shared") == 1


def test_verify_issue_under_mask_goes_to_human(monkeypatch):
    plate = privacy.Box(250, 380, 300, 150, "plate", 0.9, "pretrained")       # 이전 문제 위치 근처를 가림
    monkeypatch.setattr(privacy, "face_detector", lambda: FakeDet([]))
    monkeypatch.setattr(privacy, "plate_detectors", lambda: (FakeDet([plate]),))
    h, w = cv2.imdecode(np.frombuffer(IMG, np.uint8), cv2.IMREAD_COLOR).shape[:2]
    prior = dict(PRIOR, bbox=[250 / w, 380 / h, 550 / w, 530 / h])
    r = pipeline.verify_photo(IMG, IMG, prior, "repair", {"photo_id": "vm"}, mock("fixed"))
    assert r["status"] == "needs_review" and r["action"] == "human_review"


# ---- 서버 ----
def test_server_rejects_bad_requests(monkeypatch):
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)
    img = {"image": ("a.jpg", IMG, "image/jpeg")}
    assert c.post("/analyze", files=img, data={"photo_id": "../x"}).status_code == 400
    assert c.post("/analyze", files=img, data={"photo_id": "s9", "context_json": '{"history": [1]}'}).status_code == 400
    assert c.post("/analyze", files=img, data={"photo_id": "s9"}).status_code == 200
    v = c.post("/verify", files={"after": ("a.jpg", IMG, "image/jpeg"), "before": ("b.jpg", IMG, "image/jpeg")},
               data={"photo_id": "s9", "prior_json": '{"facility_type": "sidewalk", "issue_type": "lifted"}'})
    assert v.status_code == 409
    assert c.post("/audit/masking/s9", json={}).status_code == 400            # ok가 없으면 보류를 풀지 않음
    assert c.post("/audit/masking/s9", json={"ok": "false"}).status_code == 400
    assert c.post("/audit/masking/s9").status_code == 400                     # 본문 없음도 422가 아니라 400
    assert c.post("/audit/masking/s9", json={"ok": False}).status_code == 200
    v = c.post("/verify", files={"after": ("a.jpg", IMG, "image/jpeg")},
               data={"photo_id": "s10", "before_photo_id": "s9",
                     "prior_json": '{"facility_type": "sidewalk", "issue_type": "lifted"}'})
    assert v.status_code == 410                                                 # 보류된 사진은 비교에 쓰지 않음


def test_error_responses_carry_code():
    """모든 오류는 {"detail", "code"}. 백엔드는 code로 분기한다."""
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)
    img = {"image": ("a.jpg", IMG, "image/jpeg")}
    pr = '{"facility_type": "sidewalk", "issue_type": "lifted"}'

    def code(r, status):
        assert r.status_code == status, r.text
        return r.json()["code"]
    assert code(c.post("/analyze", files=img, data={"photo_id": "m_x"}), 400) == "INVALID_PHOTO_ID"
    assert code(c.post("/analyze", files=img, data={"context_json": "{"}), 400) == "INVALID_JSON"
    assert code(c.post("/analyze", files=img, data={"context_json": '{"history": {"severity": 9}}'}), 400) == "INVALID_CONTEXT"
    assert code(c.post("/analyze", files=img, data={"lat": "abc"}), 400) == "INVALID_REQUEST"
    after = {"after": ("a.jpg", IMG, "image/jpeg")}
    assert code(c.post("/verify", files=after, data={"prior_json": pr}), 400) == "BEFORE_REQUIRED"
    assert code(c.post("/verify", files=after, data={"prior_json": '{"facility_type": "x"}', "before_photo_id": "nope"}),
                404) == "PHOTO_NOT_FOUND"
    assert code(c.post("/audit/masking/s1", json={"ok": True, "note": 3}), 400) == "INVALID_AUDIT"
    assert code(c.post("/audit/masking/none", json={"ok": True}), 404) == "AUDIT_TARGET_NOT_FOUND"
    assert code(c.get("/results/none"), 404) == "RESULT_NOT_FOUND"
    assert code(c.get("/no-such-path"), 404) == "NOT_FOUND"


def test_non_ascii_api_key_is_401(monkeypatch):
    from fastapi.testclient import TestClient
    monkeypatch.setenv("API_KEYS", "k1")
    import server
    importlib.reload(server)
    try:
        c = TestClient(server.app)
        r = c.get("/taxonomy", headers={"X-API-Key": "é".encode("utf-8")})
        assert r.status_code == 401 and r.json()["code"] == "UNAUTHORIZED"
    finally:
        monkeypatch.delenv("API_KEYS")
        importlib.reload(server)


# ---- 평가 ----
def test_eval_counts_failed_photos_as_misses(monkeypatch, tmp_path):
    import run_eval
    labels = tmp_path / "labels.csv"
    shutil.copy(ROOT / "eval" / "sample" / "s01.jpg", tmp_path / "s01.jpg")
    labels.write_text("photo_id,image_path,facility_type,issue_type,severity,quality_ok,location_tags,capture_hour,note\n"
                      "u1,s01.jpg,sidewalk,lifted,3,1,,,\n", encoding="utf-8")
    fail = MockProvider()
    fail.analyze = lambda *a, **k: (_ for _ in ()).throw(TimeoutError("느림"))
    for name, st in (("set_store", storage.LocalImageStore(tmp_path / "i")),
                     ("set_results", storage.LocalResultStore(tmp_path / "r"))):
        getattr(storage, name)(st)
    s = run_eval.run_analyze(labels, fail, tmp_path)
    assert s["n_scored"] == 1 and s["n_not_graded"] == 1
    assert s["urgent_recall_pct"] == 0.0 and s["urgent_not_graded"] == ["u1"]    # 빠지지 않고 놓친 것으로 셈
