"""업무 백엔드 연동 기능 테스트.

1. 개인정보 단독 검사 (POST /privacy/mask)
2. 집계값 → 보고 문장 (POST /report/narrative)
3. 사진 버전과 변경 알림 (image_revision, GET /events)
4. 재판독 대기열이 끝나지 않고 남는 경우 막기
"""
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import activity  # noqa: E402
import jobs  # noqa: E402
import pipeline  # noqa: E402
import report  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

SAMPLE = Path(__file__).resolve().parent.parent / "eval" / "sample"
IMG = (SAMPLE / "s01.jpg").read_bytes()
IMG2 = (SAMPLE / "s02.jpg").read_bytes()
DARK = (SAMPLE / "dark.jpg").read_bytes()


def mock(scenario=None):
    p = MockProvider()
    p.next_scenario = scenario
    return p


class Failing(MockProvider):
    def analyze(self, *a, **k):
        raise TimeoutError("느림")


class TextProvider(MockProvider):
    """보고 문장 생성만 정해진 문장을 돌려주는 가짜 모델."""
    name = "fake_llm"

    def __init__(self, text):
        super().__init__()
        self.text = text

    def generate_text(self, prompt):
        return self.text


def client():
    from fastapi.testclient import TestClient
    import server
    return TestClient(server.app)


def events(types=None):
    evs = storage.get_events().since(0, 500)["events"]
    return [e for e in evs if types is None or e["type"] in types]


# ---------------------------------------------------------------------
# 1. 개인정보 단독 검사
# ---------------------------------------------------------------------
def test_mask_media_saves_masked_only_and_not_in_activity(temp_store):
    r = pipeline.mask_media(IMG, "med-1", "reference")
    assert r["status"] == "ok"
    assert r["masked_image_url"] == "/images/m_med-1_masked.jpg"
    assert r["image_revision"] == 1
    assert "input_sha256" not in r
    assert r["privacy"]["mode"] == "server_only"
    # 판독 결과 저장소에는 들어가지 않으므로 활동 실적·주간 보고·표본 점검에 섞이지 않는다
    assert list(storage.get_results().all()) == []
    assert activity.all_participants() == []
    assert report.weekly_from_store("2000-01-01", "2100-01-01")["stats"]["photos"] == 0


def test_mask_media_skips_quality_check(temp_store):
    # 참고·증거 사진은 사람이 보는 자료라 어두워도 재촬영을 요구하지 않는다
    assert pipeline.mask_media(DARK, "med-dark", "repair_evidence")["status"] == "ok"


def test_mask_media_idempotent_and_conflict(temp_store):
    a = pipeline.mask_media(IMG, "med-2", "reference")
    b = pipeline.mask_media(IMG, "med-2", "reference")
    assert b["meta"]["replayed"] is True and b["masked_image_url"] == a["masked_image_url"]
    assert storage.get_store().revision("m_med-2") == 1  # 다시 저장하지 않음
    with pytest.raises(pipeline.MediaConflict):
        pipeline.mask_media(IMG2, "med-2", "reference")


def test_mask_media_fail_closed(temp_store, monkeypatch):
    import privacy

    def boom(img):
        raise RuntimeError("검출기 오류")
    monkeypatch.setattr(privacy, "mask_image", boom)
    r = pipeline.mask_media(IMG, "med-3", "reference")
    assert r["status"] == "error" and r["masked_image_url"] is None
    assert list(temp_store.root.iterdir()) == []


def test_mask_media_rejects_bad_input(temp_store):
    with pytest.raises(ValueError):
        pipeline.mask_media(IMG, "med-4", "inspection")
    with pytest.raises(ValueError):
        pipeline.mask_media(IMG, "../x", "reference")


def test_privacy_endpoints(temp_store):
    c = client()
    r = c.post("/privacy/mask", files={"image": ("a.jpg", IMG, "image/jpeg")},
               data={"media_id": "med-5", "purpose": "repair_evidence", "device_masked": "true",
                     "device_mask_report": '{"faces":0,"plates":0,"detector":"mlkit","app_version":"1.0.0"}'}).json()
    assert r["status"] == "ok" and r["privacy"]["mode"] == "device+server"
    assert c.get(r["masked_image_url"]).status_code == 200
    assert c.get("/privacy/med-5").json()["purpose"] == "repair_evidence"
    assert c.get("/privacy/none").status_code == 404
    assert c.post("/privacy/mask", files={"image": ("a.jpg", IMG2, "image/jpeg")},
                  data={"media_id": "med-5", "purpose": "repair_evidence"}).status_code == 409
    assert c.post("/privacy/mask", files={"image": ("a.jpg", IMG, "image/jpeg")},
                  data={"media_id": "med-6", "purpose": "other"}).status_code == 400

    # 가림 점검은 media_id로 그대로 기록할 수 있고, 보류하면 사진을 내려주지 않으며 알림이 남는다
    assert c.post("/audit/masking/med-5", json={"ok": False, "note": "번호판 일부 보임"}).status_code == 200
    assert c.get(r["masked_image_url"]).status_code == 410
    hidden = events({"image_hidden"})
    assert len(hidden) == 1 and hidden[0]["photo_id"] == "m_med-5"


# ---------------------------------------------------------------------
# 2. 집계값 → 보고 문장
# ---------------------------------------------------------------------
ITEMS = [
    {"key": "photos", "label": "점검 사진", "value": 1240, "unit": "장"},
    {"key": "facilities", "label": "점검한 고유 시설", "value": 312, "unit": "곳"},
    {"key": "urgent", "label": "긴급 알림", "value": 3},
    {"key": "pairs", "label": "참여 조(2인 1조)", "value": 12, "unit": "조"},
]


def test_narrative_template_without_llm():
    r = report.write_narrative(ITEMS, None)
    assert r["source"] == "template"
    assert "점검 사진: 1,240장" in r["text"] and "긴급 알림: 3건" in r["text"]


def test_narrative_accepts_llm_when_numbers_match():
    text = ("이번 주 점검 사진은 1,240장이며 점검한 고유 시설은 312곳입니다. "
            "긴급 알림은 3건이었고, 참여 조(2인 1조)는 12조입니다.")
    r = report.write_narrative(ITEMS, TextProvider(text))
    assert r["source"] == "llm" and r["text"] == text


def test_narrative_rejects_new_number():
    text = "점검 사진 1,240장, 고유 시설 312곳, 긴급 3건, 12조가 참여해 전주보다 15% 늘었습니다."
    r = report.write_narrative(ITEMS, TextProvider(text))
    assert r["source"] == "template" and "note" in r


def test_narrative_rejects_missing_number():
    text = "점검 사진 1,240장, 고유 시설 312곳, 12조가 참여했습니다."  # 긴급 3건 누락
    assert report.write_narrative(ITEMS, TextProvider(text))["source"] == "template"


def test_narrative_falls_back_on_llm_error():
    class Broken(MockProvider):
        name = "broken"

        def generate_text(self, prompt):
            raise RuntimeError("down")
    assert report.write_narrative(ITEMS, Broken())["source"] == "template"


@pytest.mark.parametrize("items", [
    [], "x", [{"key": "a", "label": "x", "value": -1}], [{"key": "a", "label": "x", "value": 1.5}],
    [{"key": "a", "label": "x", "value": True}], [{"key": "a", "label": "x", "value": 1}] * 2,
    [{"key": "a b", "label": "x", "value": 1}], [{"key": "a", "label": "x", "value": 1, "unit": "1건"}],
])
def test_narrative_input_validation(items):
    with pytest.raises(report.NarrativeInputError):
        report.write_narrative(items, None)


def test_narrative_endpoint():
    c = client()
    r = c.post("/report/narrative", json={"items": ITEMS})
    assert r.status_code == 200 and r.json()["source"] == "template"
    assert c.post("/report/narrative", json={"items": []}).status_code == 400


# ---------------------------------------------------------------------
# 3. 사진 버전과 변경 알림
# ---------------------------------------------------------------------
def test_revision_starts_at_one_and_remask_bumps(temp_store):
    r = pipeline.analyze_photo(IMG, {"photo_id": "rv1"}, mock("plate_leak"))
    # AI가 가림 누락을 알려 응답 전에 다시 가렸으므로 응답에는 이미 2번째 버전
    assert r["image_revision"] == 2
    ev = events({"image_remasked"})
    assert ev and ev[-1]["photo_id"] == "rv1" and ev[-1]["image_revision"] == 2


def test_normal_analyze_has_revision_one_and_no_event(temp_store):
    r = pipeline.analyze_photo(IMG, {"photo_id": "rv2"}, mock("drain_normal"))
    assert r["image_revision"] == 1 and events() == []


def test_blur_retake_has_no_revision(temp_store):
    r = pipeline.analyze_photo(DARK, {"photo_id": "rv3"}, mock("drain_normal"))
    assert r["status"] == "retake" and r["image_revision"] is None


def test_images_header_and_events_endpoint(temp_store):
    c = client()
    r = c.post("/analyze", files={"image": ("a.jpg", IMG, "image/jpeg")},
               data={"photo_id": "rv4"}, headers={"X-Mock-Scenario": "plate_leak"}).json()
    g = c.get(r["masked_image_url"])
    assert g.headers["X-Image-Revision"] == str(r["image_revision"])
    assert g.headers["ETag"] == f'"rv4-r{r["image_revision"]}"'

    c.post("/audit/masking/rv4", json={"ok": False})
    c.post("/audit/masking/rv4", json={"ok": False})  # 이미 보류 상태면 알림을 다시 내지 않음
    c.post("/audit/masking/rv4", json={"ok": True})
    all_ev = c.get("/events").json()
    types = [e["type"] for e in all_ev["events"]]
    assert types == ["image_remasked", "image_hidden", "image_unhidden"]
    seqs = [e["seq"] for e in all_ev["events"]]
    assert seqs == sorted(seqs) and all_ev["next_after"] == seqs[-1]
    page = c.get("/events", params={"after": seqs[0], "limit": 1}).json()
    assert [e["type"] for e in page["events"]] == ["image_hidden"]


def test_event_seq_survives_restart(tmp_path):
    log = storage.EventLog(tmp_path / "ev.jsonl")
    log.add("result_updated", "a")
    log.add("result_updated", "b")
    again = storage.EventLog(tmp_path / "ev.jsonl")
    assert again.add("result_updated", "c")["seq"] == 3


# ---------------------------------------------------------------------
# 4. 재판독 대기열
# ---------------------------------------------------------------------
def test_reanalyze_emits_result_updated(temp_store):
    r = pipeline.analyze_photo(IMG, {"photo_id": "rq1"}, Failing())
    assert r["retry"]["pending"]
    pipeline.set_default_provider(mock("drain_normal"))
    jobs.retry_queue.process_due(force=True)
    assert storage.get_results().load("rq1")["status"] == "ok"
    ev = events({"result_updated"})
    assert ev[-1]["photo_id"] == "rq1" and ev[-1]["status"] == "ok"


def test_missing_image_gives_up_instead_of_hanging(temp_store):
    pipeline.analyze_photo(IMG, {"photo_id": "rq2"}, Failing())
    os.remove(temp_store.root / "rq2_masked.jpg")
    jobs.retry_queue.process_due(force=True)
    r = storage.get_results().load("rq2")
    assert r["retry"]["pending"] is False and r["retry"]["gave_up"] is True
    assert r["status"] == "needs_review"
    assert jobs.RetryQueue().resume_from_store() == 0  # 다시 켜도 집어 들지 않음


def test_crash_during_retry_counts_as_attempt_then_gives_up(temp_store, monkeypatch):
    pipeline.analyze_photo(IMG, {"photo_id": "rq3"}, Failing())

    def crash(*a, **k):
        raise RuntimeError("버그")
    monkeypatch.setattr(pipeline, "reanalyze", crash)
    for _ in range(5):
        jobs.retry_queue.process_due(force=True)
    r = storage.get_results().load("rq3")
    assert r["retry"]["gave_up"] is True and len(jobs.retry_queue) == 0


def test_resume_keeps_scheduled_time_and_no_duplicates(temp_store):
    import time
    jobs.retry_queue = jobs.RetryQueue(delays=[600, 600, 600])
    r = pipeline.analyze_photo(IMG, {"photo_id": "rq4"}, Failing())
    due = r["retry"]["next_at"]
    q = jobs.RetryQueue()
    assert q.resume_from_store() == 1
    assert q.resume_from_store() == 0 and len(q) == 1  # 두 번 불러도 한 번만 들어감
    assert q.pending()[0]["due_at"] >= due - 1 and q.pending()[0]["due_at"] > time.time() + 500
