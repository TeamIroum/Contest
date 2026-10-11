"""가린 사진 저장 원칙 테스트: 서버에는 가린 사진만 남는다."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402

from providers.mock_provider import MockProvider  # noqa: E402

SAMPLE = Path(__file__).resolve().parent.parent / "eval" / "sample"
IMG = (SAMPLE / "s01.jpg").read_bytes()


def mock(scenario):
    p = MockProvider()
    p.next_scenario = scenario
    return p


def files(store):
    return sorted(f.name for f in store.root.iterdir())


def test_analyze_saves_only_masked(temp_store):
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_test_1"}, mock("sidewalk_danger"))
    assert r["masked_image_url"] == "/images/p_test_1_masked.jpg"
    assert files(temp_store) == ["p_test_1_masked.jpg"]  # 원본·임시 파일 없음


def test_saved_image_is_the_one_sent_to_ai(temp_store):
    sent = {}

    class Spy(MockProvider):
        def analyze(self, image_jpeg, prompt, schema):
            sent["img"] = image_jpeg
            return super().analyze(image_jpeg, prompt, schema)

    p = Spy()
    p.next_scenario = "drain_normal"
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_spy"}, p)
    assert temp_store.load_masked(r["masked_image_url"]) == sent["img"]


def test_retake_saves_nothing(temp_store):
    r = pipeline.analyze_photo((SAMPLE / "dark.jpg").read_bytes(), {"photo_id": "p_dark"}, MockProvider())
    assert r["status"] == "retake" and r["masked_image_url"] is None
    assert files(temp_store) == []


def test_api_error_still_keeps_masked_for_officer(temp_store):
    class Fail(MockProvider):
        def analyze(self, *a, **k):
            raise TimeoutError("시간 초과")
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_err"}, Fail())
    assert r["status"] == "needs_review" and r["masked_image_url"] == "/images/p_err_masked.jpg"


def test_unsafe_photo_id_rejected(temp_store):
    # 조용히 새 ID로 바꾸면 앱의 재요청을 알아볼 수 없으므로 거절한다. 사진은 저장되지 않는다.
    for bad in ("../../etc/passwd", "m_media", "x" * 81):
        with pytest.raises(pipeline.RequestError):
            pipeline.analyze_photo(IMG, {"photo_id": bad}, mock("drain_normal"))
    assert files(temp_store) == []
    r = pipeline.analyze_photo(IMG, {}, mock("drain_normal"))          # 없으면 서버가 만든다
    assert r["photo_id"].startswith("p_")


def test_verify_saves_masked_after(temp_store):
    prior = {"case_id": "C-1", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3,
             "bbox": [0.34, 0.65, 0.66, 0.80]}
    r = pipeline.verify_photo(IMG, IMG, prior, "repair", {"photo_id": "p_after"}, mock("fixed"))
    assert r["masked_image_url"] == "/images/p_after_masked.jpg"
    # 처리 전 사진을 직접 받은 경우 재비교용으로 가린 사본도 남긴다 (서버 내부 이름 m_vb_...)
    names = files(temp_store)
    assert len(names) == 2 and "p_after_masked.jpg" in names
    before = next(n for n in names if n != "p_after_masked.jpg")
    assert before.startswith("m_vb_")
    # 그 사본은 가림 기록이 있어 가림 점검으로 보류할 수 있다
    import activity
    before_id = before.removesuffix("_masked.jpg")
    assert activity.record_audit(before_id, False, "주무관", "얼굴 보임") is not None
    assert activity.is_hidden(before_id)


def test_path_traversal_blocked(temp_store):
    assert temp_store.path_for("../config.py") is None
    assert temp_store.path_for("p_x.jpg") is None  # 가린 사진 이름 형식이 아니면 거부


# ---- 서버 ----
def test_server_flow(temp_store):
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)

    r = c.post("/analyze", files={"image": ("a.jpg", IMG, "image/jpeg")},
               data={"photo_id": "p_srv_1"}, headers={"X-Mock-Scenario": "sidewalk_danger"}).json()
    url = r["masked_image_url"]
    g = c.get(url)
    assert g.status_code == 200 and g.headers["content-type"] == "image/jpeg"
    assert c.get("/images/..%2Fconfig.py").status_code == 404

    prior = {"case_id": "C-1", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3}
    v = c.post("/verify", files={"after": ("b.jpg", IMG, "image/jpeg")},
               data={"before_photo_id": url, "prior_json": json.dumps(prior), "photo_id": "p_srv_2"},
               headers={"X-Mock-Scenario": "fixed"}).json()
    assert v["action"] == "close_case" and v["masked_image_url"] == "/images/p_srv_2_masked.jpg"
    assert files(temp_store) == ["p_srv_1_masked.jpg", "p_srv_2_masked.jpg"]

    miss = c.post("/verify", files={"after": ("b.jpg", IMG, "image/jpeg")},
                  data={"before_photo_id": "p_none", "prior_json": json.dumps(prior)})
    assert miss.status_code == 404
