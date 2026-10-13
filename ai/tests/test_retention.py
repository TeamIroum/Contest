"""가린 사진 보관 기간 테스트: 기간이 지난 사진만 지우고, 판독 결과는 남긴다."""
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import retention  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

SAMPLE = Path(__file__).resolve().parent.parent / "eval" / "sample"
IMG = (SAMPLE / "s01.jpg").read_bytes()
NOW = datetime(2027, 6, 1, 9, 0, tzinfo=retention.KST)


def mock(scenario):
    p = MockProvider()
    p.next_scenario = scenario
    return p


def shot(photo_id, scenario, days_ago):
    taken = (NOW - timedelta(days=days_ago)).isoformat(timespec="seconds")
    return pipeline.analyze_photo(IMG, {"photo_id": photo_id, "captured_at": taken}, mock(scenario))


def test_normal_photo_deleted_after_six_months(temp_store):
    r = shot("p_old_normal", "drain_normal", 181)
    assert r["status"] == "ok" and not r["issues"]
    shot("p_new_normal", "drain_normal", 179)

    assert retention.sweep(NOW) == {"normal": 1, "case": 0}
    assert not temp_store.exists("p_old_normal") and temp_store.exists("p_new_normal")
    kept = storage.get_results().load("p_old_normal")  # 결과는 남고 사진 주소만 비운다
    assert kept["masked_image_url"] is None and kept["image_deleted"]["policy"] == "normal"
    ev = storage.get_events().since(0)["events"]
    assert [(e["type"], e["photo_id"]) for e in ev] == [("image_deleted", "p_old_normal")]


def test_case_photo_kept_until_three_years(temp_store):
    r = shot("p_case", "sidewalk_danger", 400)
    assert r["issues"]
    assert retention.sweep(NOW) == {"normal": 0, "case": 0}
    assert temp_store.exists("p_case")
    assert retention.sweep(NOW + timedelta(days=1095)) == {"normal": 0, "case": 1}
    assert not temp_store.exists("p_case")


def test_needs_review_counts_as_case(temp_store):
    class Fail(MockProvider):
        def analyze(self, *a, **k):
            raise TimeoutError("시간 초과")
    taken = (NOW - timedelta(days=200)).isoformat(timespec="seconds")
    pipeline.analyze_photo(IMG, {"photo_id": "p_review", "captured_at": taken}, Fail())
    storage.get_results().update("p_review", retry=None)  # 재판독 대기가 끝난 상태
    assert retention.sweep(NOW) == {"normal": 0, "case": 0}
    assert temp_store.exists("p_review")


def test_pending_retry_and_reference_photos_are_kept(temp_store):
    class Fail(MockProvider):
        def analyze(self, *a, **k):
            raise TimeoutError("시간 초과")
    pipeline.analyze_photo(IMG, {"photo_id": "p_wait", "captured_at": "2020-01-01T09:00:00+09:00"}, Fail())
    pipeline.mask_media(IMG, "ref1", "reference")
    pipeline.mask_media(IMG, "fix1", "repair_evidence")
    later = datetime.now(retention.KST) + timedelta(days=4000)
    assert retention.sweep(later) == {"normal": 0, "case": 1}
    assert temp_store.exists("p_wait") and temp_store.exists("m_ref1")
    assert not temp_store.exists("m_fix1")


def test_days_from_env_and_zero_disables(temp_store, monkeypatch):
    shot("p_n", "drain_normal", 40)
    monkeypatch.setenv("RETENTION_NORMAL_DAYS", "0")
    assert retention.sweep(NOW) == {"normal": 0, "case": 0}
    monkeypatch.setenv("RETENTION_NORMAL_DAYS", "30")
    assert retention.sweep(NOW) == {"normal": 1, "case": 0}
    assert retention.sweep(NOW) == {"normal": 0, "case": 0}  # 두 번 지우지 않는다
    assert temp_store.revision("p_n") == 0


def test_deleted_photo_is_not_served(temp_store):
    from fastapi.testclient import TestClient
    import server
    shot("p_gone", "drain_normal", 365)
    retention.sweep(NOW)
    c = TestClient(server.app)
    assert c.get("/images/p_gone_masked.jpg").status_code == 404
