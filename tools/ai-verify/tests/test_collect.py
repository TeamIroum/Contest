"""시험용 업무 API의 현장 수집 순회(patrol-collect): 업무 규칙 없이 계속 받고, 시험 코스에는 영향이 없다.

AI 서버 없이 돌리려고 판독(run_analyze)을 가짜로 바꿔 끼운다.
실행: python -m pytest -q tools/ai-verify/tests
"""
import json
import sys
import tempfile
import uuid
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import business  # noqa: E402
from store import Store  # noqa: E402

calls = []


def fake_analyze(data, filename, **kw):
    calls.append(kw)
    status = "retake" if b"BLUR" in data else "ok"
    return {"id": len(calls), "http_status": 200,
            "response": {"status": status, "masked_image_url": None if status == "retake" else "/images/x.jpg",
                         "quality": {"problems": ["blur"] if status == "retake" else []}}}


app = FastAPI()
app.include_router(business.create_router(fake_analyze, Store(tempfile.mkdtemp(prefix="collect-test-"))))
api = TestClient(app)
JPEG = b"\xff\xd8\xff\xe0 good"
BLUR = b"\xff\xd8\xff\xe0 BLUR"


def submit(stop, data, sid=None, attempt=None):
    sid = sid or str(uuid.uuid4())
    form = {"clientSubmissionId": sid, "captureSlotId": stop["captureSlots"][0]["id"], "attemptId": attempt or str(uuid.uuid4()),
            "capturedAt": "2026-10-13T10:00:00+09:00", "maskingVersion": "t", "deviceMasked": "true",
            "deviceMaskReport": json.dumps({"faces": 0, "plates": 0, "vehicles": 0, "detector": "t", "appVersion": "t"})}
    return api.post(f"/api/v1/patrols/patrol-collect/stops/{stop['id']}/inspections", data=form, files={"file": ("p.jpg", data, "image/jpeg")})


def test_수집_순회는_시설_5종이고_내_순회_목록에는_없음():
    p = api.get("/api/v1/patrols/patrol-collect").json()
    assert [s["facilityKey"] for s in p["stops"]] == ["sidewalk", "drain", "bench", "fence", "exercise"]
    assert p["collect"] is True
    assert [i["id"] for i in api.get("/api/v1/me/patrols").json()["items"]] == ["patrol-test"]


def test_흐린_사진이_계속_와도_재촬영_상한이나_끝난_곳이_없음():
    stop = api.get("/api/v1/patrols/patrol-collect").json()["stops"][1]
    for _ in range(5):
        r = submit(stop, BLUR)
        assert r.status_code == 200, r.text
        b = r.json()
        assert (b["captureStatus"], b["nextAction"], b["receiptStatus"]) == ("RETAKE_REQUIRED", "NEXT_CAPTURE", "NO_MEDIA")
    r = submit(stop, JPEG)
    assert r.json()["captureStatus"] == "ACCEPTED"
    assert submit(stop, JPEG).json()["captureStatus"] == "ACCEPTED"   # 채택 뒤에도 계속 받음 (duplicate 아님)


def test_고른_시설이_정답_라벨로_들어감():
    stop = api.get("/api/v1/patrols/patrol-collect").json()["stops"][2]
    submit(stop, JPEG)
    assert calls[-1]["expected"] == {"facility": "bench"}
    assert calls[-1]["photo_id"].startswith("collect-")


def test_같은_제출은_다시_판독하지_않음():
    stop = api.get("/api/v1/patrols/patrol-collect").json()["stops"][0]
    sid, attempt = str(uuid.uuid4()), str(uuid.uuid4())   # 같은 사진 재전송 = 같은 제출 ID·시도 ID·파일
    n = len(calls)
    first = submit(stop, JPEG, sid, attempt).json()
    again = submit(stop, JPEG, sid, attempt).json()
    assert len(calls) == n + 1 and again["replayed"] is True and again["inspectionId"] == first["inspectionId"]


def test_시험_코스_구도_상태는_그대로():
    before = api.get("/api/v1/patrols/patrol-test").json()
    assert all(c["captureStatus"] is None for s in before["stops"] for c in s["captureSlots"])
