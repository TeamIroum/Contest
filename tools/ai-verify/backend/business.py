"""시험용 업무 API (/api/v1). 실제 업무 백엔드(Spring Boot)가 생기기 전에 시니어 PWA를 붙여 보는 자리.

API 명세서 6절의 사진 제출 계약을 따른다. 인증·DB·조 공동 진행은 없다.
  - 앱은 camelCase multipart로 가린 사진을 올린다 (deviceMasked, deviceMaskReport, maskingVersion …)
  - 이 서버는 시설 대장 대신 아래 TEST_STOPS의 location_tags를 붙여 AI /analyze를 부른다 (lat/lon은 보내지 않음)
  - 같은 clientSubmissionId는 처음 결과를 그대로 돌려준다. AI photo_id도 제출 ID로 만들어 AI 쪽 재요청도 막는다
  - 어르신 응답에는 접수·진행 상태만 넣는다. AI 등급·문제·senior_message 원문은 넣지 않는다
    (판독 결과는 검증 콘솔 기록에서 본다. testConsoleRunId가 그 기록 번호)
"""
from __future__ import annotations

import hashlib
import json
import re
import threading
from datetime import datetime, timezone

from fastapi import APIRouter, Body, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

MAX_BYTES = 10 * 1024 * 1024        # 업무 제한 10 MiB (AI 15MB보다 작게)
MAX_RETAKES = 2                     # 구도별 최초 + 품질 재촬영 2회
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
REPORT_KEYS = {"faces", "plates", "vehicles", "detector", "appVersion"}

# 시설 대장·코스 대용. 구도 ID는 실제로는 UUID다.
TEST_PATROL_ID = "patrol-test"
TEST_STOPS = [
    {"id": "stop-01", "order": 1, "facilityId": "fac-01", "facilityType": "SIDEWALK", "name": "초등학교 앞 보도",
     "instruction": "보도블록 전체가 보이게 찍어 주세요.", "locationTags": ["school_route"],
     "captureSlots": [{"id": "00000000-0000-4000-8000-000000000101", "label": "전체 모습"}]},
    {"id": "stop-02", "order": 2, "facilityId": "fac-02", "facilityType": "DRAIN", "name": "마을회관 앞 빗물받이",
     "instruction": "덮개 전체와 주변 바닥을 찍어 주세요.", "locationTags": ["roadside"],
     "captureSlots": [{"id": "00000000-0000-4000-8000-000000000201", "label": "전체 모습"}]},
    {"id": "stop-03", "order": 3, "facilityId": "fac-03", "facilityType": "BENCH", "name": "어린이공원 벤치",
     "instruction": "벤치 앉는 곳과 다리가 함께 보이게 찍어 주세요.", "locationTags": ["child_facility"],
     "captureSlots": [{"id": "00000000-0000-4000-8000-000000000301", "label": "전체 모습"}]},
    {"id": "stop-04", "order": 4, "facilityId": "fac-04", "facilityType": "SAFETY_FENCE", "name": "하천변 안전 울타리",
     "instruction": "울타리 기둥과 연결 부분을 찍어 주세요.", "locationTags": ["roadside"],
     "captureSlots": [{"id": "00000000-0000-4000-8000-000000000401", "label": "전체 모습"}]},
    {"id": "stop-05", "order": 5, "facilityId": "fac-05", "facilityType": "EXERCISE_EQUIPMENT", "name": "근린공원 운동기구",
     "instruction": "기구 전체와 손잡이가 보이게 찍어 주세요.", "locationTags": [],
     "captureSlots": [{"id": "00000000-0000-4000-8000-000000000501", "label": "전체 모습"}]},
]

# AI quality.problems → 어르신 재촬영 안내 (위험도·이상 여부가 드러나지 않는 문구만)
RETAKE_GUIDE = {
    "blur": "사진이 흔들렸어요. 휴대폰을 잡고 잠깐 멈춘 뒤 다시 찍어 주세요.",
    "dark": "사진이 어두워요. 밝은 쪽에서 다시 찍어 주세요.",
    "out_of_frame": "시설이 화면 가운데 오도록 다시 찍어 주세요.",
}
RETRY_GUIDE = "사진을 처리하지 못했어요. 한 번 더 찍어 주세요."
CLIENT_LOG_KEEP = 2000              # 앱 실패 기록은 최근 것만 남긴다


def retakes_left(quality_failures: int) -> int:
    """남은 품질 재촬영 기회. 최초 촬영 + 재촬영 2회: 실패 0·1번 → 2, 2번 → 1, 3번 → 0(담당자 확인)."""
    return max(0, min(MAX_RETAKES, MAX_RETAKES + 1 - quality_failures))


def _err(status: int, code: str, detail: str, **extra):
    return JSONResponse({"code": code, "detail": detail, **extra}, status_code=status)


def _slot(stop_id: str, slot_id: str):
    for i, s in enumerate(TEST_STOPS):
        if s["id"] == stop_id:
            for c in s["captureSlots"]:
                if c["id"] == slot_id:
                    return i, s, c
            return i, s, None
    return None, None, None


def create_router(run_analyze, store) -> APIRouter:
    r = APIRouter(prefix="/api/v1")
    lock = threading.Lock()
    inflight: set[str] = set()                      # 처리 중인 제출 ID (같은 ID가 또 오면 409)
    slot_locks: dict[str, threading.Lock] = {}      # 구도별 잠금: 상태를 읽고 판독하고 쓰는 동안 같은 구도의 다른 제출을 막음

    def _slot_lock(slot_id: str) -> threading.Lock:
        with lock:
            return slot_locks.setdefault(slot_id, threading.Lock())

    def _kv(key: str):
        row = store.db.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        return json.loads(row["value"]) if row else None

    def _kv_set(key: str, value):
        with store._lock:
            store.db.execute("INSERT OR REPLACE INTO kv(key, value) VALUES (?,?)", (key, json.dumps(value)))
            store.db.commit()

    def _slot_state(slot_id: str) -> dict:
        return _kv(f"slot:{slot_id}") or {"qualityFailures": 0, "status": None, "inspectionId": None}

    def _patrol():
        stops = []
        for s in TEST_STOPS:
            slots = []
            for c in s["captureSlots"]:
                st = _slot_state(c["id"])
                slots.append({**c, "required": True, "purpose": "REGULAR",
                              "receiptStatus": "RECEIVED" if st["inspectionId"] else "NONE",
                              "captureStatus": st["status"], "retakesRemaining": retakes_left(st["qualityFailures"]),
                              "referenceMediaId": None})
            stops.append({k: v for k, v in s.items() if k not in ("captureSlots", "locationTags")} |
                         {"captureSlots": slots})
        return {"id": TEST_PATROL_ID, "date": datetime.now().date().isoformat(), "activityStatus": "IN_PROGRESS",
                "routeName": "시험 코스", "stops": stops,
                "contact": {"name": "활동 담당자", "phoneNumber": "시험용"}}

    @r.get("/me/patrols")
    def my_patrols():
        p = _patrol()
        return {"items": [{"id": p["id"], "date": p["date"], "routeName": p["routeName"],
                           "activityStatus": p["activityStatus"]}]}

    @r.get("/patrols/{patrol_id}")
    def patrol(patrol_id: str):
        if patrol_id != TEST_PATROL_ID:
            return _err(404, "PATROL_NOT_FOUND", "순회를 찾을 수 없습니다")
        return _patrol()

    # ---- 시험용: 앱 쪽 실패 기록 (사진이 서버에 오기 전에 멈춘 경우를 서버에서 보기 위함. 사진은 받지 않음) ----
    with store._lock:
        store.db.execute("CREATE TABLE IF NOT EXISTS client_logs (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                         " at TEXT NOT NULL, stage TEXT, name TEXT, message TEXT, user_agent TEXT, detail TEXT)")
        store.db.commit()

    @r.post("/test-client-logs")
    def add_client_log(body: dict = Body(...)):
        cut = lambda v, n=500: (str(v)[:n] if v is not None else None)
        with store._lock:
            store.db.execute("INSERT INTO client_logs(at, stage, name, message, user_agent, detail) VALUES (?,?,?,?,?,?)",
                             (datetime.now().astimezone().isoformat(timespec="seconds"), cut(body.get("stage"), 40),
                              cut(body.get("name"), 80), cut(body.get("message")), cut(body.get("userAgent"), 300),
                              cut(json.dumps(body.get("detail"), ensure_ascii=False) if body.get("detail") else None, 1000)))
            # 인증 없는 시험 API라 무한히 쌓이지 않게 최근 것만 남김
            store.db.execute("DELETE FROM client_logs WHERE id <= (SELECT MAX(id) FROM client_logs) - ?", (CLIENT_LOG_KEEP,))
            store.db.commit()
        return {"ok": True}

    @r.get("/test-client-logs")
    def list_client_logs(request: Request, limit: int = 50):
        # 기기 정보·파일 경로가 담겨 있으므로 이 PC에서만 본다. 터널(cloudflared)을 거친 요청은 Cf-Connecting-Ip가 붙음
        if request.headers.get("cf-connecting-ip") or request.client.host not in ("127.0.0.1", "::1", "localhost"):
            raise HTTPException(403, "이 PC에서만 볼 수 있습니다")
        limit = max(1, min(limit, 500))   # 음수 LIMIT은 SQLite에서 제한 없음이 되므로 막음
        rows = store.db.execute("SELECT * FROM client_logs ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return {"items": [dict(x) for x in rows]}

    @r.post("/patrols/{patrol_id}/test-reset")
    def reset(patrol_id: str):
        """시험용: 구도별 재촬영 횟수·채택 상태를 처음으로 되돌린다 (실제 API에는 없음)."""
        with store._lock:
            store.db.execute("DELETE FROM kv WHERE key LIKE 'slot:%' OR key LIKE 'sub:%'")
            store.db.commit()
        return {"ok": True}

    @r.post("/patrols/{patrol_id}/stops/{stop_id}/inspections")
    def submit(
        patrol_id: str,
        stop_id: str,
        file: UploadFile = File(...),
        clientSubmissionId: str = Form(...),
        captureSlotId: str = Form(...),
        attemptId: str = Form(...),
        capturedAt: str = Form(...),
        maskingVersion: str = Form(...),
        deviceMasked: str = Form(...),
        deviceMaskReport: str = Form(...),
        latitude: float | None = Form(None),
        longitude: float | None = Form(None),
        locationAccuracyMeters: float | None = Form(None),
    ):
        # ---- 형식 검사 (저장 전) ----
        if patrol_id != TEST_PATROL_ID:
            return _err(404, "PATROL_NOT_FOUND", "순회를 찾을 수 없습니다")
        idx, stop, slot = _slot(stop_id, captureSlotId)
        if stop is None or slot is None:
            return _err(404, "CAPTURE_SLOT_NOT_FOUND", "순회 장소나 구도를 찾을 수 없습니다")
        for name, v in (("clientSubmissionId", clientSubmissionId), ("attemptId", attemptId)):
            if not UUID_RE.match(v):
                return _err(400, "INVALID_REQUEST", f"{name}는 UUID여야 합니다")
        if deviceMasked != "true":
            return _err(400, "DEVICE_MASKING_REQUIRED", "기기에서 가린 사진만 받습니다 (deviceMasked=true)")
        try:
            report = json.loads(deviceMaskReport)
            assert isinstance(report, dict) and REPORT_KEYS <= report.keys()
        except (ValueError, AssertionError):
            return _err(400, "INVALID_MASK_REPORT", f"deviceMaskReport는 {sorted(REPORT_KEYS)} 키를 가진 JSON 객체여야 합니다")
        try:
            datetime.fromisoformat(capturedAt.replace("Z", "+00:00"))
        except ValueError:
            return _err(400, "INVALID_REQUEST", "capturedAt은 ISO 8601이어야 합니다")
        data = file.file.read()
        if len(data) > MAX_BYTES:
            return _err(413, "PHOTO_TOO_LARGE", "사진은 10 MiB 이하여야 합니다")
        if not data.startswith(b"\xff\xd8"):
            return _err(415, "UNSUPPORTED_MEDIA_TYPE", "JPEG만 받습니다")

        req_key = [patrol_id, stop_id, captureSlotId, attemptId, hashlib.sha256(data).hexdigest()]

        # ---- 같은 제출 ID: 처음 결과 재현 / 처리 중이면 409 ----
        key = f"sub:{clientSubmissionId}"
        with lock:
            prev = _kv(key)
            if prev is None and clientSubmissionId in inflight:
                return _err(409, "SUBMISSION_PROCESSING", "같은 사진을 처리하고 있습니다")
            if prev is None:
                inflight.add(clientSubmissionId)
        if prev is not None:
            if prev["request"] != req_key:
                return _err(409, "SUBMISSION_CONFLICT", "같은 제출 ID로 다른 사진이 왔습니다")
            return JSONResponse(prev["body"] | {"replayed": True}, status_code=prev["http"])

        try:
            # 같은 구도의 제출은 한 번에 하나씩: 재촬영 횟수·채택 상태를 덮어쓰지 않게 (실제 백엔드의 구도 행 잠금에 해당)
            with _slot_lock(captureSlotId):
                st = _slot_state(captureSlotId)
                if st["status"] in ("ACCEPTED", "REVIEW_REQUESTED"):
                    return _err(409, "SLOT_RESOLVED", "이미 끝난 구도입니다", existingInspectionId=st["inspectionId"])
                run = run_analyze(
                    data, f"{clientSubmissionId}.jpg", scenario=None,
                    photo_id=f"pwa-{clientSubmissionId}", participant_id="P-PWA", captured_at=capturedAt,
                    tags=stop["locationTags"], device_masked=True, device_report=report, context=None,
                    label=f"PWA {stop['name']} (masking {maskingVersion})")
                http, body = _to_business(run, st, idx)
                if http >= 500:  # 접수 실패: 저장하지 않고 앱이 같은 ID로 다시 보내게 함
                    return JSONResponse(body, status_code=http)
                body |= {"clientSubmissionId": clientSubmissionId, "testConsoleRunId": run["id"]}
                _kv_set(f"slot:{captureSlotId}", st)
                _kv_set(key, {"http": http, "body": body, "request": req_key})
            return JSONResponse(body, status_code=http)
        finally:
            with lock:
                inflight.discard(clientSubmissionId)

    return r


def _to_business(run: dict, st: dict, stop_idx: int) -> tuple[int, dict]:
    """AI /analyze 결과를 어르신용 접수·진행 상태로 바꾼다. st(구도 상태)를 고친다."""
    if run["http_status"] != 200:
        # AI 연결 실패·4xx/5xx: 접수가 아니다. 같은 제출 ID로 다시 보내면 됨
        return 503, {"code": "ANALYSIS_UNAVAILABLE", "detail": "잠시 후 다시 보내 주세요", "receiptStatus": "RETRY_REQUIRED"}
    ai = run["response"] or {}
    status = ai.get("status")
    saved = bool(ai.get("masked_image_url"))
    nxt = TEST_STOPS[stop_idx + 1]["id"] if stop_idx + 1 < len(TEST_STOPS) else None
    move_on = "NEXT_STOP" if nxt else "RETURN_TO_CENTER"
    inspection_id = f"insp-{run['id']}"
    base = {"inspectionId": inspection_id, "receiptStatus": "RECEIVED" if saved else "NO_MEDIA",
            "duplicate": False, "nextStopId": None, "message": None}

    if status == "error":
        # 기술·마스킹 실패는 품질 재촬영 횟수에 넣지 않는다
        return 200, base | {"receiptStatus": "NO_MEDIA", "captureStatus": "TECHNICAL_FAILED", "nextAction": "RETAKE",
                            "retakesRemaining": retakes_left(st["qualityFailures"]), "message": RETRY_GUIDE}
    if status == "retake":
        st["qualityFailures"] += 1
        left = retakes_left(st["qualityFailures"])  # 이번 실패까지 센 뒤 남은 재촬영 기회
        if left <= 0:   # 3번째 품질 실패: 검토 요청으로 넘기고 다음으로
            st.update(status="REVIEW_REQUESTED", inspectionId=inspection_id)
            return 200, base | {"captureStatus": "REVIEW_REQUESTED", "nextAction": move_on, "nextStopId": nxt,
                                "retakesRemaining": 0, "message": "담당자가 확인할게요. 다음 장소로 가 주세요."}
        problems = (ai.get("quality") or {}).get("problems") or []
        msg = next((RETAKE_GUIDE[p] for p in problems if p in RETAKE_GUIDE), "한 번 더 찍어 주세요.")
        st["status"] = "RETAKE_REQUIRED"
        return 200, base | {"captureStatus": "RETAKE_REQUIRED", "nextAction": "RETAKE",
                            "retakesRemaining": left, "message": msg}
    # ok / needs_review: 사진 채택 또는 검토 인계. 판독 결과는 어르신에게 보내지 않는다
    capture = "ACCEPTED" if status == "ok" else "REVIEW_REQUESTED"
    st.update(status=capture, inspectionId=inspection_id)
    http = 202 if (ai.get("retry") or {}).get("pending") else 200
    return http, base | {"captureStatus": capture, "nextAction": move_on, "nextStopId": nxt,
                         "retakesRemaining": retakes_left(st["qualityFailures"]),
                         "message": "잘 받았어요. 다음 장소로 가 주세요." if nxt else "모두 찍었어요. 센터로 돌아가 주세요."}
