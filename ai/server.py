"""AI 판독 서버.

실행:  AI_PROVIDER=mock uvicorn server:app --port 8000
       (실제 판독: AI_PROVIDER=ollama, 자체 서버의 Ollama 모델)
문서:  http://localhost:8000/docs
설정 값 전체는 .env.example 참고.

보안
  - API_KEYS(쉼표 구분)를 설정하면 모든 요청에 X-API-Key 헤더가 필요하다. 비워 두면 개발 모드(인증 없음).
  - 업로드는 JPEG/PNG, 15MB 이하만 받는다.
개인정보
  - 운영: 앱이 휴대폰에서 먼저 가린 사진만 보낸다(device_masked=true). 서버는 저장 전에 가림 상태를 확인하고 남은 부분을 가린다.
  - 받은 사진은 메모리에서만 처리한다. 업로드 임시 버퍼는 읽자마자 닫아서 지운다.
  - 저장소에는 가린 사진만 있고 GET /images/... 로만 내려준다.
"""
import copy
import json
import logging
import os
import secrets
import threading
import time
from contextlib import asynccontextmanager

from typing import Any

from fastapi import Body, Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

import activity
import images
import jobs
import pipeline
import privacy
import report
import retention
import storage
from config import taxonomy
from providers import get_provider
from providers.base import Provider

logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ai.server")

API_KEYS = {k.strip() for k in os.environ.get("API_KEYS", "").split(",") if k.strip()}
CORS_ORIGINS = [o.strip() for o in os.environ.get("CORS_ORIGINS", "*").split(",") if o.strip()]

provider = get_provider()
pipeline.set_default_provider(provider)
# 모델 미리 올리기 상태: skipped(필요 없는 모델) | loading | ready | failed
warmup = {"state": "skipped", "seconds": None, "error": None}


def _warm_up():
    """활동 시간 전에 서버를 켜 두면 첫 판독을 기다리지 않도록 모델을 미리 올린다."""
    if type(provider).warm_up is Provider.warm_up:
        return
    warmup["state"] = "loading"
    t0 = time.time()
    try:
        provider.warm_up()
        warmup.update(state="ready", seconds=round(time.time() - t0, 1))
        log.info("모델 준비 완료: %s (%.0f초)", provider.model, time.time() - t0)
    except Exception as e:  # 실패해도 서버는 계속 동작한다. 첫 판독 때 모델이 올라간다
        warmup.update(state="failed", error=f"{type(e).__name__}: {e}"[:200])
        log.warning("모델 미리 올리기 실패 (첫 판독 때 다시 시도): %s: %s", type(e).__name__, e)


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not API_KEYS:
        log.warning("API_KEYS가 비어 있어 인증 없이 동작합니다 (개발 모드)")
    # 검출기를 미리 불러온다. 실패해도 서버는 켜지지만, 그동안 들어온 사진은 가리지 않고 거절한다(fail-closed)
    det = privacy.detector_status()
    (log.info if det["ok"] else log.error)("검출기: 얼굴=%s 번호판=%s 차량=%s / AI=%s(%s)",
                                         det["face"], det["plate"], det["vehicle"], provider.name, provider.model)
    n = jobs.retry_queue.resume_from_store()
    if n:
        log.info("다시 판독할 대기 건 %d개를 이어서 처리합니다", n)
    jobs.retry_queue.start()
    retention.retention_job.start()
    threading.Thread(target=_warm_up, name="ai-warmup", daemon=True).start()
    yield
    jobs.retry_queue.stop()
    retention.retention_job.stop()


app = FastAPI(title="시니어 동네 점검단 판독 API", version=pipeline.CONTRACT_VERSION, lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=CORS_ORIGINS, allow_methods=["*"], allow_headers=["*"])


# ---------------------------------------------------------------------
# 오류 응답: 모든 오류는 {"detail": "사람이 읽는 설명", "code": "기계가 읽는 코드"}
# 백엔드는 code로 분기하고 detail은 기록·표시에만 쓴다. 코드 표는 contract/API_CONTRACT.md
# ---------------------------------------------------------------------
class ApiError(HTTPException):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(status, detail)
        self.code = code


DEFAULT_CODES = {400: "INVALID_REQUEST", 401: "UNAUTHORIZED", 404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED",
                 409: "CONFLICT", 410: "GONE", 413: "PHOTO_TOO_LARGE"}


@app.exception_handler(StarletteHTTPException)
async def _http_error(request, exc):
    code = getattr(exc, "code", None) or DEFAULT_CODES.get(exc.status_code, f"HTTP_{exc.status_code}")
    return JSONResponse({"detail": exc.detail, "code": code}, status_code=exc.status_code,
                        headers=getattr(exc, "headers", None))


@app.exception_handler(RequestValidationError)
async def _validation_error(request, exc):
    """FastAPI 기본은 422지만, 계약에 맞춰 형식 오류는 모두 400으로 돌려준다."""
    errs = exc.errors()
    where = ".".join(str(p) for p in errs[0].get("loc", ()) if p != "body") if errs else ""
    msg = errs[0].get("msg", "") if errs else ""
    return JSONResponse({"detail": f"요청 형식 오류: {where} {msg}".strip(), "code": "INVALID_REQUEST"},
                        status_code=400)


def require_key(x_api_key: str | None = Header(None)):
    if not API_KEYS:
        return
    # 바이트로 비교한다: 문자열 compare_digest는 한글 등 ASCII 밖 글자가 오면 401 대신 오류(500)를 낸다
    given = (x_api_key or "").encode("utf-8")
    if not given or not any(secrets.compare_digest(given, k.encode("utf-8")) for k in API_KEYS):
        raise ApiError(401, "UNAUTHORIZED", "API 키가 없거나 올바르지 않습니다")


async def _read_and_close(f: UploadFile) -> bytes:
    """업로드를 메모리로 읽고 바로 닫는다. 크기 제한을 넘으면 읽기 전에 거절한다."""
    try:
        if f.size is not None and f.size > images.MAX_UPLOAD_BYTES:
            raise ApiError(413, "PHOTO_TOO_LARGE", "사진 파일이 너무 큽니다")
        data = await f.read(images.MAX_UPLOAD_BYTES + 1)
        if len(data) > images.MAX_UPLOAD_BYTES:
            raise ApiError(413, "PHOTO_TOO_LARGE", "사진 파일이 너무 큽니다")
        return data
    finally:
        await f.close()


def _device(device_masked: bool, device_mask_report: str | None) -> dict:
    try:
        rep = json.loads(device_mask_report) if device_mask_report else None
    except json.JSONDecodeError:
        raise ApiError(400, "INVALID_JSON", "device_mask_report가 올바른 JSON이 아닙니다")
    return {"masked": bool(device_masked), "report": rep}


def _json_form(value: str | None, name: str, default=None):
    """폼으로 받은 JSON 문자열을 dict로 바꾼다. 형식이 틀리면 500 대신 400으로 알려준다."""
    if not value:
        return default
    try:
        obj = json.loads(value)
    except json.JSONDecodeError:
        raise ApiError(400, "INVALID_JSON", f"{name}가 올바른 JSON이 아닙니다")
    if not isinstance(obj, dict):
        raise ApiError(400, "INVALID_JSON", f"{name}는 JSON 객체({{...}})여야 합니다")
    return obj


def _provider_for(scenario: str | None) -> Provider:
    """시연용 mock 시나리오 지정. 요청마다 사본을 써서 동시에 온 다른 요청의 시나리오와 섞이지 않게 한다."""
    if scenario and provider.name == "mock":
        p = copy.copy(provider)
        p.next_scenario = scenario
        return p
    return provider


def _run_errors(fn, *args):
    """pipeline 예외를 계약의 HTTP 상태로 바꾼다. 모두 사진을 저장하기 전에 난다."""
    try:
        return fn(*args)
    except pipeline.RequestError as e:
        raise ApiError(400, e.code, str(e))
    except pipeline.PhotoIdConflict:
        raise ApiError(409, "PHOTO_ID_CONFLICT", "이 photo_id는 이미 다른 종류(판독·전후 비교)의 요청에 쓰였습니다")
    except pipeline.PhotoHidden:
        raise ApiError(410, "PHOTO_HIDDEN", "가림 점검에서 보류된 사진은 비교에 쓸 수 없습니다")


@app.get("/health")
def health():
    det = privacy.detector_status()
    return {"ok": det["ok"], "contract_version": pipeline.CONTRACT_VERSION, "provider": provider.name,
            "model": provider.model, "face_detector": type(privacy.face_detector()).__name__, "detectors": det,
            "require_device_masking": pipeline.REQUIRE_DEVICE_MASKING,
            "retry_queue": len(jobs.retry_queue), "last_event_seq": storage.get_events().since(10**12)["last_seq"],
            "warmup": warmup}


@app.get("/taxonomy", dependencies=[Depends(require_key)])
def get_taxonomy():
    """앱이 시설 이름, 등급, 어르신 안내 문구를 이 값으로 띄우게 한다."""
    return taxonomy()


@app.post("/analyze", dependencies=[Depends(require_key)])
async def analyze(
    image: UploadFile = File(...),
    photo_id: str | None = Form(None, description="앱이 사진마다 새로 만든 ID. 같은 ID 재요청에는 저장된 결과를 돌려줌"),
    lat: float | None = Form(None),
    lon: float | None = Form(None),
    capture_hour: int | None = Form(None),
    location_tags: str | None = Form(None, description="쉼표 구분. 비우면 시설 대장 값 사용"),
    context_json: str | None = Form(None, description='{"history": {...}, "open_cases": [...]}'),
    participant_id: str | None = Form(None, description="촬영한 참여자의 가명 ID"),
    captured_at: str | None = Form(None, description="촬영 시각 ISO 8601 (예: 2026-10-08T09:14:00+09:00)"),
    device_masked: bool = Form(False, description="앱이 휴대폰에서 얼굴·번호판을 이미 가렸으면 true"),
    device_mask_report: str | None = Form(None, description='{"faces":1,"plates":0,"detector":"yunet+plate-t384+effdet0","app_version":"1.0.0"}'),
    x_mock_scenario: str | None = Header(None),
):
    ctx = _json_form(context_json, "context_json", {})
    ctx.update(photo_id=photo_id, lat=lat, lon=lon, capture_hour=capture_hour, participant_id=participant_id,
               captured_at=captured_at, device=_device(device_masked, device_mask_report))
    if location_tags is not None:
        ctx["location_tags"] = [t for t in location_tags.split(",") if t]
    # 판독은 AI 응답을 기다리는 긴 작업이라 작업 스레드에서 돌린다. 이벤트 루프에서 돌리면 그동안
    # /health·/results·/events 등 다른 요청이 모두 멈춘다.
    return await run_in_threadpool(_run_errors, pipeline.analyze_photo, await _read_and_close(image), ctx,
                                   _provider_for(x_mock_scenario))


@app.post("/verify", dependencies=[Depends(require_key)])
async def verify(
    after: UploadFile = File(..., description="이번 재촬영 사진"),
    prior_json: str = Form(..., description="{case_id, facility_type, issue_type, severity, bbox, evidence, location_tags}"),
    before_photo_id: str | None = Form(None, description="처음 판독한 사진의 photo_id 또는 masked_image_url (권장)"),
    before: UploadFile | None = File(None, description="before_photo_id를 쓸 수 없을 때만"),
    mode: str = Form("repair", description="repair | change"),
    photo_id: str | None = Form(None),
    participant_id: str | None = Form(None),
    captured_at: str | None = Form(None),
    device_masked: bool = Form(False),
    device_mask_report: str | None = Form(None),
    x_mock_scenario: str | None = Header(None),
):
    if mode not in ("repair", "change"):
        raise ApiError(400, "INVALID_MODE", "mode는 repair 또는 change")
    ctx = {"photo_id": photo_id, "participant_id": participant_id, "captured_at": captured_at,
           "device": _device(device_masked, device_mask_report)}
    before_bytes = None
    if before_photo_id:
        if storage.get_store().load_masked(before_photo_id) is None:
            raise ApiError(404, "PHOTO_NOT_FOUND", f"저장된 가린 사진이 없습니다: {before_photo_id}")
        ctx["before_photo_id"] = before_photo_id.rsplit("/", 1)[-1].removesuffix(storage.SUFFIX)
    elif before is not None:
        before_bytes = await _read_and_close(before)  # 앱이 올린 사진: pipeline이 판독 사진과 같은 검사를 한다
    else:
        raise ApiError(400, "BEFORE_REQUIRED", "before_photo_id 또는 before 중 하나가 필요합니다")
    return await run_in_threadpool(_run_errors, pipeline.verify_photo, before_bytes, await _read_and_close(after),
                                   _json_form(prior_json, "prior_json", {}), mode, ctx, _provider_for(x_mock_scenario))


@app.get("/results/{photo_id}", dependencies=[Depends(require_key)])
def get_result(photo_id: str):
    """최신 판독 결과. AI 지연으로 담당자 확인이 된 건은 다시 판독되면 여기 결과가 바뀐다."""
    r = storage.get_results().load(photo_id)
    if r is None:
        raise ApiError(404, "RESULT_NOT_FOUND", "판독 결과가 없습니다")
    return r


@app.get("/images/{file_name}", dependencies=[Depends(require_key)])
def get_masked_image(file_name: str):
    """가린 사진만 내려준다. 저장소에는 원본이 없으므로 원본을 내려줄 방법 자체가 없다."""
    path = storage.get_store().path_for(file_name)
    if path is None:
        raise ApiError(404, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다")
    pid = file_name.removesuffix(storage.SUFFIX)
    if activity.is_hidden(pid):
        raise ApiError(410, "PHOTO_HIDDEN", "가림 점검에서 보류된 사진입니다")
    rev = storage.get_store().revision(pid)
    # 다시 가리면 버전이 바뀌므로 오래 캐시하지 않는다. 받은 쪽은 X-Image-Revision으로 버전을 기록한다.
    return FileResponse(path, media_type="image/jpeg",
                        headers={"Cache-Control": "private, no-cache", "ETag": f'"{pid}-r{rev}"',
                                 "X-Image-Revision": str(rev)})


@app.post("/report", dependencies=[Depends(require_key)])
def weekly_report(body: dict):
    """body: {"analyze_results": [...], "verify_results": [...], "corrections": 7, "seniors": 24}"""
    stats = report.weekly_stats(body.get("analyze_results", []), body.get("verify_results"),
                                body.get("corrections", 0), body.get("seniors"))
    return {"stats": stats, **report.write_report(stats, provider)}


# ---------------------------------------------------------------------
# 활동 기록·시설 이력·주간 보고·마스킹 점검
# ---------------------------------------------------------------------
@app.get("/participants/{participant_id}/activity", dependencies=[Depends(require_key)])
def participant_activity(participant_id: str, start: str | None = None, end: str | None = None):
    """참여자 활동 실적 (날짜별 촬영 수, 점검 시설 수, 첫·마지막 촬영 시각). 이동 경로는 없음."""
    return activity.participant_activity(participant_id, start, end)


@app.get("/participants", dependencies=[Depends(require_key)])
def participants(start: str | None = None, end: str | None = None):
    return activity.all_participants(start, end)


@app.get("/facilities/{facility_id}/history", dependencies=[Depends(require_key)])
def facility_history(facility_id: str):
    return activity.facility_history(facility_id)


@app.get("/report/weekly", dependencies=[Depends(require_key)])
def weekly_from_store(start: str, end: str, corrections: int = 0):
    """저장된 기록으로 기간 보고서를 만든다. 우선 처리 목록 포함."""
    return report.weekly_from_store(start, end, corrections, provider)


@app.get("/audit/masking", dependencies=[Depends(require_key)])
def audit_sample(start: str | None = None, end: str | None = None, n: int = 20):
    """담당자 가림 점검용 표본. 누락을 보정한 사진은 모두, 나머지는 무작위."""
    return activity.masking_audit_sample(start, end, n)


@app.post("/audit/masking/{photo_id}", dependencies=[Depends(require_key)])
def audit_record(photo_id: str, body: Any = Body(None)):
    """body: {"ok": true|false, "reviewer": "담당자", "note": "..."} ok=false면 그 사진은 내려주지 않는다.
    photo_id 자리에 참고·보수 사진의 media_id를 넣어도 된다. 보류·해제는 GET /events로 전달된다."""
    # 본문이 없거나 객체가 아니거나, ok가 빠지거나 "false" 같은 문자열이면 보류가 잘못 풀리지 않게 거절한다
    if not isinstance(body, dict) or not isinstance(body.get("ok"), bool):
        raise ApiError(400, "INVALID_AUDIT", "본문은 JSON 객체이고 ok는 true 또는 false여야 합니다")
    for k in ("reviewer", "note"):
        if body.get(k) is not None and not isinstance(body[k], str):
            raise ApiError(400, "INVALID_AUDIT", f"{k}는 문자열이어야 합니다")
    args = (body["ok"], body.get("reviewer"), body.get("note"))
    r = activity.record_audit(photo_id, *args)
    if r is None and storage.media_photo_id(photo_id):  # 참고·보수 사진은 media_id로도 받는다
        r = activity.record_audit(storage.media_photo_id(photo_id), *args)
    if r is None:
        raise ApiError(404, "AUDIT_TARGET_NOT_FOUND", "판독 결과나 검사 기록이 없습니다")
    return r["privacy_audit"]


# ---------------------------------------------------------------------
# 개인정보 단독 검사·보고 문장·변경 알림 (업무 백엔드 연동용)
# ---------------------------------------------------------------------
@app.post("/privacy/mask", dependencies=[Depends(require_key)])
async def privacy_mask(
    image: UploadFile = File(..., description="참고 사진 또는 보수 증거 사진 (JPEG/PNG, 15MB 이하)"),
    media_id: str = Form(..., description="백엔드의 media ID (영문·숫자·_·-, 78자 이하). 같은 ID·같은 사진은 저장된 결과"),
    purpose: str = Form(..., description="reference | repair_evidence"),
    device_masked: bool = Form(False),
    device_mask_report: str | None = Form(None),
):
    """판독 없이 얼굴·번호판만 검사하고 가린다. 판독 결과·활동 실적·주간 보고에 들어가지 않는다."""
    try:
        return await run_in_threadpool(pipeline.mask_media, await _read_and_close(image), media_id, purpose,
                                       _device(device_masked, device_mask_report))
    except pipeline.MediaConflict:
        raise ApiError(409, "MEDIA_ID_CONFLICT", "같은 media_id로 다른 사진이 이미 들어왔습니다")
    except ValueError as e:
        raise ApiError(400, "INVALID_MEDIA", str(e))


@app.get("/privacy/{media_id}", dependencies=[Depends(require_key)])
def privacy_result(media_id: str):
    key = storage.media_photo_id(media_id)
    r = storage.get_media().load(key) if key else None
    if r is None:
        raise ApiError(404, "MEDIA_NOT_FOUND", "검사 기록이 없습니다")
    r.pop("input_sha256", None)
    r["image_revision"] = storage.get_store().revision(key)
    return r


@app.post("/report/narrative", dependencies=[Depends(require_key)])
def report_narrative(body: dict):
    """백엔드가 DB로 확정한 집계값을 받아 보고 문장만 만든다. 숫자를 새로 만들거나 빠뜨리면 고정 문장을 쓴다.
    body: {"items": [{"key": "photos", "label": "점검 사진", "value": 120, "unit": "장"}, ...], "audience": "..."}"""
    audience = body.get("audience") or "시청 시설 담당 부서"
    if not isinstance(audience, str) or len(audience) > 40:
        raise ApiError(400, "INVALID_NARRATIVE", "audience는 40자 이하 문자열")
    try:
        return report.write_narrative(body.get("items"), provider, audience)
    except report.NarrativeInputError as e:
        raise ApiError(400, "INVALID_NARRATIVE", str(e))


@app.get("/events", dependencies=[Depends(require_key)])
def events(after: int = 0, limit: int = 100):
    """응답을 돌려준 뒤에 바뀐 것: 다시 가림(image_remasked), 보류(image_hidden), 보류 해제(image_unhidden),
    재판독 결과(result_updated). 백엔드는 마지막으로 받은 seq를 저장하고 after로 넘긴다."""
    return storage.get_events().since(max(0, after), max(1, min(limit, 500)))
