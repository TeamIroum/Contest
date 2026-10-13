"""AI 판독 모듈 검증용 백엔드.

실제 업무 백엔드(Spring Boot)가 만들어지기 전에 AI 모듈을 "앱 → 백엔드 → AI" 흐름 그대로 시험한다.
  - AI API 키는 이 서버에만 둔다. 화면은 AI를 직접 부르지 않는다.
  - 응답마다 계약서 v2.0 검사(checks.py)를 돌리고, 정답 라벨을 붙이면 정확도를 집계한다.
  - 가린 사진은 받아서 복사본으로 보여 준다 (AI 서버의 사진 주소를 화면에서 직접 쓰지 않음).

실행:  python -m uvicorn app:app --port 8100     (backend 폴더에서)
설정:  AI_BASE_URL(기본 http://127.0.0.1:8000. localhost는 윈도우에서 IPv6를 먼저 시도해 요청마다 2초 늦어짐), AI_API_KEY, VERIFY_DATA_DIR
"""
from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import statistics
import uuid
from pathlib import Path

from fastapi import Body, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles

import business
import checks
import suite
from ai_client import AIClient
from store import Store, now

mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("text/javascript", ".mjs")

HERE = Path(__file__).resolve().parent
FRONTEND = HERE.parent / "frontend"
PWA = HERE.parent / "pwa"
YUNET_WEB = HERE.parents[2] / "ai" / "contract" / "yunet_web"
AI_BASE_URL = os.environ.get("AI_BASE_URL", "http://127.0.0.1:8000")
AI_API_KEY = os.environ.get("AI_API_KEY", "")
DATA_DIR = Path(os.environ.get("VERIFY_DATA_DIR", HERE.parent / "data"))

# 로컬 모델(Ollama)은 큰 사진 한 장에 1분 넘게 걸릴 수 있어 넉넉히 기다린다
AI_WAIT_S = float(os.environ.get("VERIFY_AI_TIMEOUT_S", "300"))
# 판독·비교·개인정보 검사 입구는 일반 함수(def)라 작업 스레드에서 돈다. AI를 기다리는 동안에도
# 화면의 다른 요청(기록, 알림, 상태)은 계속 응답한다.
ai = AIClient(AI_BASE_URL, AI_API_KEY, timeout_s=AI_WAIT_S)
store = Store(DATA_DIR)
app = FastAPI(title="AI 판독 모듈 검증 콘솔", version="1.0")
# 다른 주소에서 여는 앱이 이 서버의 /api/v1을 부를 수 있게 그 주소만 허용한다 (PWA_ORIGINS로 바꿈, 쉼표로 구분)
#   GitHub Pages의 시험용 PWA(pwa/deploy_pages.py), 시니어 앱 개발·미리보기(Vite 5173·4173), 시니어 앱 시연 주소(infra/demo-https)
# 시니어 앱은 로그인 정보(쿠키·Authorization)를 함께 보내므로 allow_credentials가 필요하다. 이때는 주소를 '*'로 둘 수 없다
PWA_ORIGINS = ("https://teamiroum.github.io,http://localhost:5173,http://localhost:4173,"
               "https://xn----f55ey6jz3bl8ah95e.xn--h32bi4v.xn--3e0b707e,https://www.xn----f55ey6jz3bl8ah95e.xn--h32bi4v.xn--3e0b707e")
app.add_middleware(CORSMiddleware, allow_origins=[o.strip() for o in os.environ.get("PWA_ORIGINS", PWA_ORIGINS).split(",") if o.strip()],
                   allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

# 바깥(Tailscale Funnel 등 터널)에는 휴대폰 앱이 쓰는 곳만 연다: 시험용 업무 API와 PWA 화면·모델.
# 검증 콘솔 화면·콘솔 API(가린 사진 복사본, 기록 지우기, AI 서버 대신 부르기)·자동 문서(/docs)는 이 PC에서만 열린다
PUBLIC_PREFIXES = ("/api/v1/", "/pwa/")


@app.middleware("http")
async def local_only_console(request, call_next):
    path = request.url.path
    if not (path.startswith(PUBLIC_PREFIXES) or path == "/pwa") and business.from_outside(request):
        return Response("검증 콘솔은 이 PC에서만 열 수 있습니다 (http://127.0.0.1:8100)", status_code=403,
                        media_type="text/plain; charset=utf-8")
    return await call_next(request)


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def _json_field(value: str | None, name: str, want=dict):
    if not value or not value.strip():
        return None
    try:
        obj = json.loads(value)
    except json.JSONDecodeError as e:
        raise HTTPException(400, f"{name}가 올바른 JSON이 아닙니다: {e}")
    if not isinstance(obj, want):
        raise HTTPException(400, f"{name}는 {'객체 {...}' if want is dict else '배열 [...]'}여야 합니다")
    return obj


def _tags(value: str | None) -> list[str]:
    return [t.strip() for t in (value or "").split(",") if t.strip()]


def _copy_image(run_id: int, resp: dict | None) -> None:
    """AI에 저장된 가린 사진을 받아 복사본을 만든다 (업무 백엔드의 S3 복사에 해당)."""
    url = (resp or {}).get("masked_image_url")
    if not url:
        return
    r = ai.image(url)
    if r.status == 410:
        store.update_run(run_id, hidden=1)
        return
    if r.status != 200 or not r.content:
        return
    rev = int(r.headers.get("x-image-revision") or (resp or {}).get("image_revision") or 1)
    path = store.image_dir / f"run{run_id}_r{rev}.jpg"
    path.write_bytes(r.content)
    store.update_run(run_id, image_path=path.name, image_revision=rev, hidden=0)


def _labels(**kw) -> dict | None:
    out = {k: v for k, v in kw.items() if v not in (None, "")}
    return out or None


# =====================================================================
# 상태
# =====================================================================
@app.get("/api/health")
def health():
    r = ai.health()
    body = r.body if isinstance(r.body, dict) else {}
    auth = ai.get("/taxonomy") if r.status == 200 else None
    return {
        "console": {"ai_base_url": AI_BASE_URL, "has_api_key": bool(AI_API_KEY), "data_dir": str(DATA_DIR)},
        "reachable": r.status == 200,
        "http_status": r.status,
        "latency_ms": r.latency_ms,
        "ai": body,
        "auth_ok": auth is not None and auth.status == 200,
        "auth_status": auth.status if auth else None,
        "contract_match": body.get("contract_version") == checks.CONTRACT_VERSION,
        "expected_contract": checks.CONTRACT_VERSION,
    }


@app.get("/api/taxonomy")
def taxonomy():
    t = ai.taxonomy(refresh=True)
    if t is None:
        raise HTTPException(502, "AI 서버에서 taxonomy를 받지 못했습니다")
    return t


# =====================================================================
# 판독
# =====================================================================
@app.post("/api/analyze")
def analyze(
    image: UploadFile = File(...),
    scenario: str | None = Form(None),
    photo_id: str | None = Form(None),
    participant_id: str | None = Form("P-TEST"),
    captured_at: str | None = Form(None),
    location_tags: str | None = Form(""),
    device_masked: bool = Form(True),
    device_report: str | None = Form(None),
    open_cases: str | None = Form(None),
    history: str | None = Form(None),
    lat: float | None = Form(None),
    lon: float | None = Form(None),
    expected_facility: str | None = Form(None),
    expected_issue: str | None = Form(None),
    expected_grade: str | None = Form(None),
    label: str | None = Form(None),
):
    data = image.file.read()
    report = _json_field(device_report, "device_report")
    cases = _json_field(open_cases, "open_cases", list)
    hist = _json_field(history, "history")
    context = {k: v for k, v in {"open_cases": cases, "history": hist}.items() if v} or None
    return run_analyze(data, image.filename or "photo.jpg", scenario=scenario or None,
                       photo_id=(photo_id or "").strip() or new_id("v"), participant_id=participant_id,
                       captured_at=captured_at or now(), tags=_tags(location_tags), device_masked=device_masked,
                       device_report=report, context=context,
                       latlon=(lat, lon) if lat is not None and lon is not None else None,
                       expected=_labels(facility=expected_facility, issue=expected_issue, grade=expected_grade),
                       label=label or image.filename)


def run_analyze(data: bytes, filename: str, *, scenario, photo_id, participant_id, captured_at, tags,
                device_masked, device_report, context, latlon=None, expected=None, label=None, client=None) -> dict:
    sent = {"photo_id": photo_id, "participant_id": participant_id, "captured_at": captured_at,
            "location_tags": tags, "device_masked": device_masked,
            "device_report": _snake(device_report), "has_latlon": latlon is not None,
            "open_case_ids": [c.get("case_id") for c in (context or {}).get("open_cases") or []],
            "context": context, "latlon": latlon, "filename": filename, "size": len(data)}
    if client:
        sent["test_client"] = client   # 시험 앱 정보 (휴대폰 가림 처리 시간 등). AI에는 보내지 않음
    r = ai.analyze(data, filename, photo_id=photo_id, participant_id=participant_id, captured_at=captured_at,
                   location_tags=tags, device_masked=device_masked, device_report=device_report,
                   context=context, latlon=latlon, scenario=scenario)
    body = r.body if isinstance(r.body, dict) else {"detail": r.body}
    items = checks.check_analyze(body, sent, ai.taxonomy(), r.status, r.latency_ms) if r.status == 200 else \
        [{"id": "http", "title": "HTTP 200", "level": "fail", "detail": f"HTTP {r.status}: {body.get('detail')}"}]
    run_id = store.add_run("analyze", photo_id=photo_id, scenario=scenario, label=label, request=sent,
                           expected=expected, http_status=r.status, latency_ms=r.latency_ms, checks=items,
                           response=body)
    if r.status == 200:
        _copy_image(run_id, body)
    return store.get_run(run_id)


def _snake(report):
    from ai_client import to_snake
    return to_snake(report) if report else None


# =====================================================================
# 전후 비교
# =====================================================================
def prior_from_run(run: dict) -> dict:
    """처음 판독 결과에서 prior_json을 꺼낸다 (계약서: 처음 판독 결과에서 그대로 꺼내 씀)."""
    resp = run.get("response") or {}
    fac = resp.get("facility") or {}
    issues = sorted(resp.get("issues") or [], key=lambda i: i.get("severity") or 0, reverse=True)
    if not fac.get("type") or fac.get("type") == "unknown" or not issues:
        raise HTTPException(400, f"run #{run['id']}에는 비교할 문제(시설·문제 유형)가 없습니다. 문제가 판독된 사진을 고르거나 prior_json을 직접 넣으세요")
    top = issues[0]
    return {"case_id": f"C-{run['id']:04d}", "facility_id": fac.get("facility_id"), "facility_type": fac["type"],
            "issue_type": top.get("type"), "severity": top.get("severity"), "bbox": top.get("bbox"),
            "evidence": top.get("evidence"), "location_tags": fac.get("location_tags") or []}


@app.post("/api/verify")
def verify(
    after: UploadFile = File(...),
    before_run_id: int | None = Form(None),
    before: UploadFile | None = File(None),
    prior_json: str | None = Form(None),
    mode: str = Form("repair"),
    scenario: str | None = Form(None),
    participant_id: str | None = Form("P-TEST"),
    device_masked: bool = Form(True),
    device_report: str | None = Form(None),
    expected_result: str | None = Form(None),
    label: str | None = Form(None),
):
    if mode not in ("repair", "change"):
        raise HTTPException(400, "mode는 repair 또는 change입니다")
    base = store.get_run(before_run_id, with_snapshots=False) if before_run_id else None
    if before_run_id and not base:
        raise HTTPException(404, f"run #{before_run_id}이 없습니다")
    prior = _json_field(prior_json, "prior_json") or (prior_from_run(base) if base else None)
    if not prior:
        raise HTTPException(400, "before_run_id 또는 prior_json이 필요합니다")
    before_bytes = before.file.read() if before is not None and before.filename else None
    if not base and before_bytes is None:
        raise HTTPException(400, "처음 사진(before_run_id) 또는 before 사진 중 하나가 필요합니다")
    after_bytes = after.file.read()
    photo_id = new_id("vr")
    report = _json_field(device_report, "device_report")
    sent = {"photo_id": photo_id, "mode": mode, "case_id": prior.get("case_id"), "prior": prior,
            "before_photo_id": base["photo_id"] if base and before_bytes is None else None,
            "device_masked": device_masked, "device_report": _snake(report), "filename": after.filename}
    r = ai.verify(after_bytes, after.filename or "after.jpg", prior=prior, mode=mode, photo_id=photo_id,
                  before_photo_id=sent["before_photo_id"], before=before_bytes, participant_id=participant_id,
                  captured_at=now(), device_masked=device_masked, device_report=report, scenario=scenario or None)
    body = r.body if isinstance(r.body, dict) else {"detail": r.body}
    items = checks.check_verify(body, sent, r.status, r.latency_ms) if r.status == 200 else \
        [{"id": "http", "title": "HTTP 200", "level": "fail", "detail": f"HTTP {r.status}: {body.get('detail')}"}]
    run_id = store.add_run("verify", photo_id=photo_id, parent_run_id=before_run_id, scenario=scenario or None,
                           label=label or after.filename, request=sent,
                           expected=_labels(result=expected_result), http_status=r.status,
                           latency_ms=r.latency_ms, checks=items, response=body)
    if r.status == 200:
        _copy_image(run_id, body)
    return store.get_run(run_id)


# =====================================================================
# 개인정보 단독 검사, 보고 문장
# =====================================================================
@app.post("/api/privacy")
def privacy_mask(
    image: UploadFile = File(...),
    media_id: str | None = Form(None),
    purpose: str = Form("reference"),
    device_masked: bool = Form(True),
    device_report: str | None = Form(None),
):
    data = image.file.read()
    media_id = (media_id or "").strip() or new_id("med")
    report = _json_field(device_report, "device_report")
    r = ai.privacy_mask(data, image.filename or "photo.jpg", media_id=media_id, purpose=purpose,
                        device_masked=device_masked, device_report=report)
    body = r.body if isinstance(r.body, dict) else {"detail": r.body}
    sent = {"media_id": media_id, "purpose": purpose, "device_masked": device_masked, "filename": image.filename}
    items = checks.check_privacy_mask(body, sent, r.status)
    run_id = store.add_run("privacy", photo_id=body.get("photo_id") or f"m_{media_id}", label=image.filename,
                           request=sent, http_status=r.status, latency_ms=r.latency_ms, checks=items, response=body)
    if r.status == 200:
        _copy_image(run_id, body)
    return store.get_run(run_id)


@app.post("/api/narrative")
def narrative(body: dict = Body(...)):
    items = body.get("items") or []
    r = ai.narrative(items, body.get("audience"))
    resp = r.body if isinstance(r.body, dict) else {"detail": r.body}
    items_checks = checks.check_narrative(resp, items, r.status) if r.status == 200 else \
        [{"id": "http", "title": "HTTP 200", "level": "fail", "detail": f"HTTP {r.status}: {resp.get('detail')}"}]
    run_id = store.add_run("narrative", label=body.get("audience") or "보고 문장", request=body,
                           http_status=r.status, latency_ms=r.latency_ms, checks=items_checks, response=resp)
    return store.get_run(run_id)


# =====================================================================
# 기록
# =====================================================================
@app.get("/api/runs")
def list_runs(kind: str | None = None, limit: int = 200):
    out = store.list_runs(kind, limit)
    for run in out:
        run["summary"] = checks.summarize(run["checks"] or [])
    return out


@app.get("/api/runs/{run_id}")
def get_run(run_id: int):
    run = store.get_run(run_id)
    if not run:
        raise HTTPException(404, "기록이 없습니다")
    run["summary"] = checks.summarize(run["checks"] or [])
    return run


@app.delete("/api/runs")
def clear_runs():
    dest = store.clear()   # 지우기 전에 data/backups/<시각>/에 기록과 사진 복사본을 남긴다
    return {"ok": True, "backup": str(dest)}


@app.put("/api/runs/{run_id}/expected")
def set_expected(run_id: int, body: dict = Body(...)):
    if not store.get_run(run_id, with_snapshots=False):
        raise HTTPException(404, "기록이 없습니다")
    store.update_run(run_id, expected=_labels(**{k: body.get(k) for k in ("facility", "issue", "grade", "result")}))
    return get_run(run_id)


@app.post("/api/runs/{run_id}/refresh")
def refresh_run(run_id: int):
    """GET /results/{photo_id}로 최신 결과를 받아, 바뀌었으면 스냅샷을 추가한다."""
    run = store.get_run(run_id, with_snapshots=False)
    if not run:
        raise HTTPException(404, "기록이 없습니다")
    changed = _refresh(run, "refresh")
    out = get_run(run_id)
    out["refresh_changed"] = changed
    return out


def _refresh(run: dict, source: str) -> bool | None:
    if run["kind"] not in ("analyze", "verify") or not run.get("photo_id"):
        return None
    r = ai.result(run["photo_id"])
    if r.status != 200:
        return None
    # 계약 검사는 결과가 그대로여도 지금 규칙으로 다시 한다 (검사 규칙을 고친 뒤 예전 기록을 다시 볼 수 있게)
    req = run.get("request") or {}
    items = checks.check_analyze(r.body, req, ai.taxonomy()) if run["kind"] == "analyze" else \
        checks.check_verify(r.body, req)
    store.update_run(run["id"], checks=items)
    if _strip_volatile(r.body) == _strip_volatile(run.get("response")):
        return False
    store.add_snapshot(run["id"], source, r.body)
    if run.get("http_status") != 200:   # 콘솔이 먼저 기다리기를 포기했던 건이 나중에 결과를 받은 경우
        store.update_run(run["id"], http_status=200)
    if (r.body.get("image_revision") or 0) > (run.get("image_revision") or 0):
        _copy_image(run["id"], r.body)
    return True


def _strip_volatile(resp):
    if not isinstance(resp, dict):
        return resp
    d = dict(resp)
    meta = dict(d.get("meta") or {})
    meta.pop("replayed", None)
    d["meta"] = meta
    return d


@app.get("/api/runs/{run_id}/image")
def run_image(run_id: int):
    run = store.get_run(run_id, with_snapshots=False)
    if not run:
        raise HTTPException(404, "기록이 없습니다")
    if run["hidden"]:
        raise HTTPException(410, "가림 점검에서 보류된 사진입니다")
    if not run.get("image_path"):
        raise HTTPException(404, "저장된 사진이 없습니다")
    return FileResponse(store.image_dir / run["image_path"], media_type="image/jpeg",
                        headers={"Cache-Control": "no-store"})


@app.get("/api/ai-image/{photo_id}")
def ai_image(photo_id: str):
    """가림 점검 표본처럼 기록에 없는 사진은 AI에서 받아 그대로 전달한다 (API 키는 서버에만)."""
    r = ai.image(photo_id)
    if r.status != 200:
        raise HTTPException(r.status or 502, (r.body or {}).get("detail", "사진을 받지 못했습니다"))
    return Response(r.content, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


# =====================================================================
# 변경 알림
# =====================================================================
@app.post("/api/events/poll")
def poll_events():
    """GET /events를 마지막 순번 이후로 받아 업무 백엔드처럼 처리한다."""
    after = store.cursor()
    handled = []
    while True:
        r = ai.events(after)
        if r.status != 200:
            raise HTTPException(502, f"GET /events 실패: HTTP {r.status} {r.body}")
        evs = r.body.get("events") or []
        for ev in evs:
            applied = _apply_event(ev)
            store.add_event(ev, applied)
            handled.append({"seq": ev.get("seq"), "type": ev.get("type"), "photo_id": ev.get("photo_id"), "applied": applied})
        nxt = r.body.get("next_after", after)
        if not evs or nxt <= after:
            break
        after = nxt
        store.set_cursor(after)
    store.set_cursor(after)
    return {"handled": handled, "cursor": after, "last_seq": r.body.get("last_seq")}


def _apply_event(ev: dict) -> str:
    run_ids = store.runs_by_photo(ev.get("photo_id") or "")
    if not run_ids:
        return "이 콘솔 기록에 없는 사진 (무시)"
    t = ev.get("type")
    out = []
    for rid in run_ids:
        run = store.get_run(rid, with_snapshots=False)
        if t == "image_remasked":
            if (ev.get("image_revision") or 0) > (run.get("image_revision") or 0):
                _copy_image(rid, {"masked_image_url": f"/images/{run['photo_id']}_masked.jpg",
                                  "image_revision": ev.get("image_revision")})
                out.append(f"#{rid} 사진을 r{ev.get('image_revision')}로 교체")
            else:
                out.append(f"#{rid} 이미 r{run.get('image_revision')} 보유 (무시)")
        elif t == "image_hidden":
            store.update_run(rid, hidden=1)
            out.append(f"#{rid} 사진 조회 차단")
        elif t == "image_unhidden":
            store.update_run(rid, hidden=0)
            out.append(f"#{rid} 다시 표시 (실제 백엔드는 재승인 정책 적용)")
        elif t == "image_deleted":
            out.append(f"#{rid} AI 서버 보관 기간이 지나 원본 쪽 사진 삭제 (콘솔 사본은 검증 기록용으로 유지)")
        elif t == "result_updated":
            changed = _refresh(run, "event")
            out.append(f"#{rid} " + ("새 결과 스냅샷 추가" if changed else "결과 변화 없음"))
        else:
            out.append(f"#{rid} 알 수 없는 알림 종류 {t!r}")
    return "; ".join(out)


@app.get("/api/events")
def list_events():
    return {"cursor": store.cursor(), "events": store.list_events()}


@app.post("/api/events/reset")
def reset_cursor():
    store.set_cursor(0)
    return {"cursor": 0}


# =====================================================================
# 가림 점검, AI 쪽 집계 (프록시)
# =====================================================================
@app.get("/api/audit/masking")
def audit_sample(start: str | None = None, end: str | None = None, n: int = 20):
    r = ai.audit_sample(start=start, end=end, n=n)
    if r.status != 200:
        raise HTTPException(r.status or 502, r.body)
    return r.body


@app.post("/api/audit/masking/{photo_id}")
def audit_record(photo_id: str, body: dict = Body(...)):
    r = ai.audit_record(photo_id, body)
    if r.status != 200:
        raise HTTPException(r.status or 502, r.body)
    return r.body


@app.get("/api/ai/{path:path}")
def ai_get(path: str, start: str | None = None, end: str | None = None):
    """AI 쪽 조회 기능(시연용 집계)을 그대로 보여 준다: participants, facilities/{id}/history, report/weekly"""
    if not path.startswith(("participants", "facilities/", "report/weekly", "results/")):
        raise HTTPException(404, "허용하지 않은 경로")
    r = ai.get("/" + path, start=start, end=end)
    return {"http_status": r.status, "latency_ms": r.latency_ms, "body": r.body}


# =====================================================================
# 정확도 집계
# =====================================================================
@app.get("/api/stats")
def stats():
    analyze_runs = [r for r in store.list_runs("analyze", 10_000) if r["http_status"] == 200]
    verify_runs = [r for r in store.list_runs("verify", 10_000) if r["http_status"] == 200]
    return {"analyze": _analyze_stats(analyze_runs), "verify": _verify_stats(verify_runs),
            "contract": _contract_stats(store.list_runs(None, 10_000))}


def _pct(a, b):
    return None if not b else round(100 * a / b, 1)


def _latency(runs):
    vals = sorted(r["latency_ms"] for r in runs if r.get("latency_ms") is not None)
    if not vals:
        return None
    return {"mean": int(statistics.mean(vals)), "p95": vals[min(len(vals) - 1, int(len(vals) * 0.95))], "max": vals[-1]}


def _analyze_stats(runs):
    status = {}
    for r in runs:
        s = (r["response"] or {}).get("status")
        status[s] = status.get(s, 0) + 1
    labeled = [r for r in runs if r.get("expected")]
    grades = checks.GRADES
    fac_n = fac_ok = iss_n = iss_ok = g_n = g_ok = g_near = urgent_n = urgent_hit = 0
    confusion = {e: {p: 0 for p in grades + ["없음"]} for e in grades}
    missed = []
    for r in labeled:
        exp, resp = r["expected"], r["response"] or {}
        fac = (resp.get("facility") or {}).get("type")
        if exp.get("facility"):
            fac_n += 1
            fac_ok += fac == exp["facility"]
        if exp.get("issue"):
            iss_n += 1
            got = [i.get("type") for i in resp.get("issues") or []]
            iss_ok += (exp["issue"] == "none" and not got) or (exp["issue"] in got)
        if exp.get("grade") in grades:
            pred = resp.get("grade")
            g_n += 1
            confusion[exp["grade"]][pred if pred in grades else "없음"] += 1
            if pred in grades:
                g_ok += pred == exp["grade"]
                g_near += abs(grades.index(pred) - grades.index(exp["grade"])) <= 1
            if exp["grade"] == "긴급":
                urgent_n += 1
                if pred == "긴급":
                    urgent_hit += 1
                else:
                    missed.append({"run_id": r["id"], "label": r["label"], "grade": pred, "status": resp.get("status")})
    return {
        "total": len(runs), "status": status,
        "needs_review_rate": _pct(status.get("needs_review", 0), len(runs)),
        "retake_rate": _pct(status.get("retake", 0), len(runs)),
        "latency": _latency(runs),
        "labeled": len(labeled),
        "facility_accuracy": _pct(fac_ok, fac_n), "facility_n": fac_n,
        "issue_accuracy": _pct(iss_ok, iss_n), "issue_n": iss_n,
        "grade_accuracy": _pct(g_ok, g_n), "grade_within1": _pct(g_near, g_n), "grade_n": g_n,
        "urgent_recall": _pct(urgent_hit, urgent_n), "urgent_n": urgent_n, "urgent_missed": missed,
        "confusion": confusion,
    }


def _verify_stats(runs):
    labeled = [r for r in runs if (r.get("expected") or {}).get("result")]
    ok = wrong_rerequest = wrong_close = 0
    for r in labeled:
        exp, resp = r["expected"]["result"], r["response"] or {}
        got = "retake" if resp.get("status") == "retake" else resp.get("result")
        ok += got == exp
        if resp.get("action") == "re_request" and exp != "not_fixed":
            wrong_rerequest += 1
        if resp.get("action") == "close_case" and exp == "not_fixed":
            wrong_close += 1
    return {"total": len(runs), "labeled": len(labeled), "result_accuracy": _pct(ok, len(labeled)),
            "wrong_rerequest": wrong_rerequest, "wrong_close": wrong_close, "latency": _latency(runs)}


def _contract_stats(runs):
    fails = {}
    for r in runs:
        for c in r.get("checks") or []:
            if c["level"] == "fail":
                fails[c["title"]] = fails.get(c["title"], 0) + 1
    clean = sum(1 for r in runs if checks.summarize(r.get("checks") or [])["ok"])
    return {"runs": len(runs), "clean": clean, "fail_by_check": sorted(fails.items(), key=lambda x: -x[1])}


# =====================================================================
# 시나리오 자동 점검
# =====================================================================
@app.post("/api/suite")
def run_suite():
    return suite.run(ai, run_analyze)


# =====================================================================
# 시험용 업무 API와 시니어 PWA
# =====================================================================
app.include_router(business.create_router(run_analyze, store))


# 얼굴 검출 코드·모델·시험 사진은 AI 계약 폴더의 것을 그대로 내려준다 (복사본을 두지 않음)
@app.get("/pwa/yunet.mjs")
@app.get("/pwa/lib/yunet.mjs")   # 예전 주소 (설치된 앱의 캐시가 바뀔 때까지)
def pwa_yunet_js():
    return FileResponse(YUNET_WEB / "yunet.mjs", media_type="text/javascript")


# 마스킹 모델 목록. 주소에 내용 해시(?v=)를 붙여, 모델 파일이 바뀌면 앱(서비스 워커·WebView 캐시)이 새로 받게 한다
PWA_MODELS = {
    "face": ("models/face_yunet_s_dynamic@1.onnx", YUNET_WEB / "face_yunet_s_dynamic.onnx"),
    "plate": ("models/plate-t384@1.onnx", PWA / "models" / "plate-t384@1.onnx"),
    "vehicle": ("models/efficientdet_lite0@1.tflite", PWA / "models" / "efficientdet_lite0@1.tflite"),
}
# 휴대폰이 원본 서버에서 바로 받는 모델. 터널(Tailscale Funnel)은 느려 15MB를 다 받으면 처음 여는 데 오래 걸린다.
# 원본이 브라우저 직접 다운로드(CORS)를 허용해야 한다: Google은 허용, 번호판 모델의 GitHub 릴리스는 막혀 있어 서버에서 준다.
# 내용 해시는 같은 파일(fetch_models.py가 받은 것)로 계산한다.
PWA_MODEL_ORIGINS = {
    "vehicle": "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite",
}
_hash_cache: dict[Path, tuple[float, str]] = {}


def _file_hash(path: Path) -> str:
    mtime = path.stat().st_mtime
    hit = _hash_cache.get(path)
    if not hit or hit[0] != mtime:
        hit = (mtime, hashlib.sha256(path.read_bytes()).hexdigest()[:12])
        _hash_cache[path] = hit
    return hit[1]


@app.get("/pwa/model-manifest.json")
def pwa_model_manifest():
    files, local, missing = {}, {}, []
    for key, (url, path) in PWA_MODELS.items():
        if not path.is_file():
            missing.append(key)
            continue
        h = _file_hash(path)
        local[key] = f"./{url}?v={h}"
        files[key] = f"{PWA_MODEL_ORIGINS[key]}?v={h}" if key in PWA_MODEL_ORIGINS else local[key]
    if missing:
        raise HTTPException(503, f"모델 파일 없음: {', '.join(missing)} (pwa/fetch_models.py 실행)")
    # 받는 곳이 바뀌어도 내용이 같으면 버전(앱이 보고하는 maskingVersion)은 그대로
    version = hashlib.sha256("|".join(local[k] for k in sorted(local)).encode()).hexdigest()[:8]
    return Response(json.dumps({"version": version, "files": files}), media_type="application/json",
                    headers={"Cache-Control": "no-cache"})


@app.get("/pwa/models/face_yunet_s_dynamic@1.onnx")
def pwa_yunet_model():
    return FileResponse(YUNET_WEB / "face_yunet_s_dynamic.onnx", media_type="application/octet-stream")


@app.get("/pwa/selftest/{name}")
def pwa_selftest_file(name: str):
    path = YUNET_WEB / ("expected.json" if name == "expected.json" else f"images/{name}")
    if "/" in name or "\\" in name or not path.is_file():
        raise HTTPException(404, "없는 파일")
    return FileResponse(path)


@app.get("/pwa/sw.js")
def pwa_sw():
    # 서비스 워커는 /pwa/ 범위로 등록한다. 매번 새 버전을 확인하게 캐시하지 않는다
    return FileResponse(PWA / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})


app.mount("/pwa", StaticFiles(directory=PWA, html=True), name="pwa")
app.mount("/", StaticFiles(directory=FRONTEND, html=True), name="frontend")
