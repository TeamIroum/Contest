"""업무 API 확인 시험 공통 준비. 설정은 환경 변수로 받는다 (README 참고).

  API_BASE    업무 API 주소. 기본 http://127.0.0.1:8100/api/v1 (검증 콘솔)
  API_TOKEN   있으면 Authorization: Bearer <값>
  PATROL_ID   시험할 순회. 없으면 GET /me/patrols의 첫 순회
  RESET_URL   있으면 시험 전에 POST (구도 상태를 처음으로). 검증 콘솔: <API_BASE>/patrols/<id>/test-reset
"""
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pytest

HERE = Path(__file__).resolve().parent
SAMPLE = HERE.parents[1] / "ai" / "eval" / "sample"
API_BASE = os.environ.get("API_BASE", "http://127.0.0.1:8100/api/v1").rstrip("/")
TIMEOUT_S = float(os.environ.get("API_TIMEOUT_S", "120"))   # AI 판독까지 기다리는 제출이 있어서 넉넉히

# 명세서 6장: AI grade/action/notes/원문 senior_message는 시니어 응답에서 뺀다. 위험도·판독 내용도 넣지 않는다
FORBIDDEN_KEYS = {"grade", "action", "notes", "senior_message", "seniorMessage", "issues", "severity",
                  "ai_severity", "aiSeverity", "dept", "risk", "riskLevel", "quality", "facility", "grade_reasons"}
RECEIPT = {"RECEIVING", "RECEIVED", "NO_MEDIA", "RETRY_REQUIRED"}
CAPTURE = {"PROCESSING", "ACCEPTED", "RETAKE_REQUIRED", "TECHNICAL_FAILED", "REVIEW_REQUESTED"}
NEXT = {"WAIT", "RETAKE", "RETRY", "NEXT_CAPTURE", "NEXT_STOP", "RETURN_TO_CENTER"}
MOVE_ON = {"NEXT_CAPTURE", "NEXT_STOP", "RETURN_TO_CENTER"}

PHOTOS = {
    "blur": SAMPLE / "blur.jpg",          # 서버 품질 검사에서 늘 흐림 (선명도 1.6)
    "dark": SAMPLE / "dark.jpg",          # 늘 어두움 (밝기 8.9)
    "blur_png": HERE / "photos" / "blur.png",
    "good": HERE / "photos" / "sidewalk_cc0.jpg",   # AI가 정상 접수하는 보도 사진 (CC0, photos/README)
    "other": SAMPLE / "s02.jpg",
}


def pytest_configure(config):
    config.addinivalue_line("markers", "ai: AI 판독 결과(시설 판독)에 기대는 시험. 품질 실패 시험보다 느림")


@pytest.fixture(scope="session")
def api():
    headers = {"Authorization": f"Bearer {os.environ['API_TOKEN']}"} if os.environ.get("API_TOKEN") else {}
    with httpx.Client(base_url=API_BASE, headers=headers, timeout=TIMEOUT_S) as c:
        yield c


@pytest.fixture(scope="session")
def patrol(api):
    pid = os.environ.get("PATROL_ID")
    if not pid:
        r = api.get("/me/patrols")
        assert r.status_code == 200, r.text
        items = r.json().get("items") or []
        if not items:
            pytest.skip("오늘 배정된 순회가 없음 (GET /me/patrols items=[])")
        pid = items[0]["id"]
    if os.environ.get("RESET_URL"):
        r = api.post(os.environ["RESET_URL"])
        assert r.status_code < 300, f"RESET_URL 실패: {r.status_code} {r.text}"
    r = api.get(f"/patrols/{pid}")
    assert r.status_code == 200, r.text
    return r.json()


@pytest.fixture(scope="session")
def stops(patrol):
    """시험마다 쓸 장소 (A~E). 첫 구도가 아직 시도 없는 장소 5곳이 필요하다"""
    fresh = [s for s in patrol["stops"] if s.get("captureSlots")
             and s["captureSlots"][0].get("receiptStatus", "NONE") == "NONE"
             and not s["captureSlots"][0].get("captureStatus")]
    if len(fresh) < 5:
        pytest.fail(f"시도 없는 장소가 5곳 필요한데 {len(fresh)}곳뿐 (RESET_URL로 초기화하거나 새 순회를 배정)")
    return dict(zip("ABCDE", fresh))


def photo(name: str) -> bytes:
    return PHOTOS[name].read_bytes()


def report(**over):
    r = {"faces": 0, "plates": 0, "vehicles": 0, "detector": "conformance-test", "appVersion": "conformance-0.1", "boxes": []}
    r.update(over)
    return r


class Submission:
    """같은 제출을 다시 보낼 수 있게 ID·파일을 묶어 둔다"""

    def __init__(self, api, patrol_id, stop, data: bytes, filename="photo.jpg", mime="image/jpeg", **fields):
        self.api, self.url = api, f"/patrols/{patrol_id}/stops/{stop['id']}/inspections"
        self.data, self.filename, self.mime = data, filename, mime
        self.fields = {
            "clientSubmissionId": str(uuid.uuid4()), "captureSlotId": stop["captureSlots"][0]["id"],
            "attemptId": str(uuid.uuid4()), "capturedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "maskingVersion": "conformance-1", "deviceMasked": "true", "deviceMaskReport": json.dumps(report()),
        }
        self.fields.update(fields)

    def send(self, data: bytes | None = None):
        return self.api.post(self.url, data=self.fields,
                             files={"file": (self.filename, self.data if data is None else data, self.mime)})


@pytest.fixture(scope="session")
def submit(api, patrol):
    def make(stop, name="blur", **kw):
        if name == "blur_png":
            kw.setdefault("filename", "photo.png")
            kw.setdefault("mime", "image/png")
        return Submission(api, patrol["id"], stop, photo(name), **kw)
    return make


def keys_deep(v) -> set:
    """JSON 안의 모든 키 (중첩 포함)"""
    out = set()
    if isinstance(v, dict):
        out |= set(v)
        v = list(v.values())
    if isinstance(v, list):
        for x in v:
            out |= keys_deep(x)
    return out


def senior_body(r: httpx.Response) -> dict:
    """시니어에게 가는 응답: JSON 객체이고 판독 내용이 섞여 있지 않아야 한다"""
    body = r.json()
    assert isinstance(body, dict), body
    leaked = keys_deep(body) & FORBIDDEN_KEYS
    assert not leaked, f"시니어 응답에 판독 내용이 들어 있음: {sorted(leaked)} (명세서 6장)"
    return body


def error_body(r: httpx.Response, status: int | tuple, code: str | None = None) -> dict:
    """명세서 2장 오류 형식: code, message"""
    want = (status,) if isinstance(status, int) else status
    assert r.status_code in want, f"HTTP {r.status_code} (기대 {want}): {r.text[:300]}"
    body = r.json()
    assert isinstance(body.get("code"), str) and body["code"], f"오류에 code 없음: {body}"
    assert isinstance(body.get("message"), str) and body["message"], f"오류에 message 없음 (명세서 2장): {body}"
    if code:
        assert body["code"] == code, f"code {body['code']} (기대 {code})"
    return body
