"""시나리오 자동 점검.

1) 계약 예시 재현: mock 모드에서 ai/contract/fixtures/ 의 예시 응답과 같은 결과가 나오는지 비교한다.
   (make_fixtures.py와 같은 입력을 HTTP로 보냄)
2) 프로토콜 점검: 400·401·404·409, 같은 photo_id 재요청, 다시 가림 알림, 사진 버전 헤더 등
   공급자(mock/ollama)와 관계없이 계약서가 약속한 동작을 확인한다.
"""
from __future__ import annotations

import json
import time
import uuid
from pathlib import Path

import checks

AI_DIR = Path(__file__).resolve().parents[3] / "ai"
FIXTURES = AI_DIR / "contract" / "fixtures"
SAMPLE = AI_DIR / "eval" / "sample"

DEVICE_REPORT = {"faces": 0, "plates": 1, "detector": "yunet+plate-t384+effdet0", "appVersion": "1.0.0"}

# (예시 파일 이름, mock 시나리오, 입력) — contract/make_fixtures.py의 ANALYZE_CASES와 같게 유지
ANALYZE_CASES = [
    ("01_normal", "drain_normal", {"latlon": (37.82430, 127.08960)}, "s01.jpg"),
    ("02_observe", "drain_minor", {"tags": []}, "s01.jpg"),
    ("03_caution", "fence_tilted", {"latlon": (37.82380, 127.08850)}, "s01.jpg"),
    ("04_urgent", "sidewalk_danger", {"latlon": (37.82410, 127.08920)}, "s01.jpg"),
    ("05_urgent_duplicate", "exercise_part",
     {"latlon": (37.82600, 127.09200),
      "open_cases": [{"case_id": "C-0412", "facility_id": "EX-0001", "issue_type": "part_detached"}]}, "s01.jpg"),
    ("05b_urgent_bench", "bench_broken", {"latlon": (37.82580, 127.09180)}, "s01.jpg"),
    ("06_needs_review_urgent", "sidewalk_uncertain", {"tags": ["school_route"]}, "s01.jpg"),
    ("07_needs_review_unknown", "unknown_facility", {}, "s01.jpg"),
    ("08_retake_frame", "out_of_frame", {}, "s01.jpg"),
    ("09_retake_dark", None, {}, "dark.jpg"),
    ("10_error_unreadable", None, {}, None),
    ("16_privacy_leak_fixed", "plate_leak", {}, "s01.jpg"),
]
VERIFY_CASES = [
    ("11_verify_fixed", "fixed", "repair"),
    ("12_verify_not_fixed", "not_fixed", "repair"),
    ("13_verify_different_place", "different_place", "repair"),
    ("14_change_worse", "worse", "change"),
]
PRIOR = {"case_id": "C-0388", "facility_id": "SW-0001", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3,
         "bbox": [0.31, 0.42, 0.55, 0.70], "evidence": "보행 동선 가운데 블록이 들려 단차가 보임",
         "location_tags": ["school_route"]}
# 시나리오와 관계없이 provider에 따라 달라지는 값
SCENARIO_FREE = {"09_retake_dark", "10_error_unreadable"}


def _get(d, path):
    for k in path.split("."):
        if not isinstance(d, dict):
            return None
        d = d.get(k)
    return d


COMPARE_ANALYZE = ["status", "action", "grade", "facility.type", "senior_message.key", "duplicate_of", "image_revision",
                   "quality.problems"]
COMPARE_VERIFY = ["status", "action", "result", "mode", "same_facility", "new_grade", "senior_message.key", "case_id"]


def _diff(got: dict, want: dict, fields: list[str]) -> list[dict]:
    out = []
    for f in fields:
        a, b = _get(got, f), _get(want, f)
        if a != b:
            out.append({"field": f, "got": a, "want": b})
    a = [i.get("type") for i in got.get("issues") or []]
    b = [i.get("type") for i in want.get("issues") or []]
    if a != b:
        out.append({"field": "issues[].type", "got": a, "want": b})
    if (got.get("masked_image_url") is None) != (want.get("masked_image_url") is None):
        out.append({"field": "masked_image_url 유무", "got": got.get("masked_image_url") is not None,
                    "want": want.get("masked_image_url") is not None})
    return out


def _fixture(name: str) -> dict:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def _case(group, id_, title, level, detail="", **extra):
    return dict({"group": group, "id": id_, "title": title, "level": level, "detail": detail}, **extra)


def _expect(group, id_, title, ok, detail="", bad="fail", **extra):
    return _case(group, id_, title, "pass" if ok else bad, detail, **extra)


def run(ai, run_analyze) -> dict:
    t0 = time.perf_counter()
    h = ai.health()
    if h.status != 200:
        return {"ok": False, "error": f"AI 서버 /health 실패: HTTP {h.status} {h.body}", "cases": []}
    health = h.body
    is_mock = health.get("provider") == "mock"
    tag = time.strftime("%H%M%S") + uuid.uuid4().hex[:4]
    img = (SAMPLE / "s01.jpg").read_bytes()
    cases: list[dict] = []
    runs: dict[str, dict] = {}

    # ---- 1. 계약 예시 재현 (판독) ----
    for name, scenario, ctx, file in ANALYZE_CASES:
        group = "계약 예시 재현 · 판독"
        if scenario and not is_mock:
            cases.append(_case(group, name, f"analyze_{name}", "skip", "mock 모드에서만 시나리오를 고를 수 있음"))
            continue
        data = (SAMPLE / file).read_bytes() if file else b"not an image"
        context = {"open_cases": ctx["open_cases"]} if ctx.get("open_cases") else None
        run = run_analyze(data, file or "broken.jpg", scenario=scenario, photo_id=f"suite_{tag}_{name}",
                          participant_id="P-001", captured_at="2026-10-08T09:14:00+09:00",
                          tags=ctx.get("tags"), device_masked=True, device_report=DEVICE_REPORT,
                          context=context, latlon=ctx.get("latlon"), label=f"[자동] {name}")
        runs[name] = run
        resp = run["response"] or {}
        summary = checks.summarize(run["checks"] or [])
        if is_mock or name in SCENARIO_FREE:
            diffs = _diff(resp, _fixture(f"analyze_{name}"), COMPARE_ANALYZE)
        else:
            diffs = []
        ok = run["http_status"] == 200 and summary["ok"] and not diffs
        detail = "; ".join(f"{d['field']}: 받음 {d['got']!r} / 예시 {d['want']!r}" for d in diffs)
        if not summary["ok"]:
            detail = (detail + "; " if detail else "") + "계약 검사 실패 " + ", ".join(
                c["title"] for c in run["checks"] if c["level"] == "fail")
        cases.append(_expect(group, name, f"analyze_{name} ({scenario or '시나리오 없음'})", ok, detail,
                             run_id=run["id"], diffs=diffs))
    if is_mock:
        cases.append(_case("계약 예시 재현 · 판독", "15_needs_review_retry", "analyze_15_needs_review_retry", "skip",
                           "AI 호출 실패를 HTTP로 일으킬 수 없어 건너뜀 (ai/tests에서 확인)"))

    # ---- 2. 계약 예시 재현 (전후 비교) ----
    base = runs.get("04_urgent")
    for name, scenario, mode in VERIFY_CASES:
        group = "계약 예시 재현 · 전후 비교"
        if not is_mock:
            cases.append(_case(group, name, f"verify_{name}", "skip", "mock 모드에서만 시나리오를 고를 수 있음"))
            continue
        pid = f"suite_{tag}_{name}"
        r = ai.verify(img, "after.jpg", prior=PRIOR, mode=mode, photo_id=pid, before_photo_id=base["photo_id"],
                      before=None, participant_id="P-001", captured_at="2026-10-08T09:14:00+09:00",
                      device_masked=True, device_report=DEVICE_REPORT, scenario=scenario)
        body = r.body if isinstance(r.body, dict) else {}
        items = checks.check_verify(body, {"photo_id": pid, "mode": mode, "case_id": PRIOR["case_id"], "device_masked": True,
                                           "device_report": {"faces": 0, "plates": 1, "detector": DEVICE_REPORT["detector"],
                                                             "app_version": "1.0.0"}}, r.status, r.latency_ms)
        diffs = _diff(body, _fixture(f"verify_{name}"), COMPARE_VERIFY)
        summary = checks.summarize(items)
        detail = "; ".join(f"{d['field']}: 받음 {d['got']!r} / 예시 {d['want']!r}" for d in diffs)
        if not summary["ok"]:
            detail = (detail + "; " if detail else "") + "계약 검사 실패 " + ", ".join(c["title"] for c in items if c["level"] == "fail")
        cases.append(_expect(group, name, f"verify_{name} ({scenario}, {mode})", r.status == 200 and summary["ok"] and not diffs,
                             detail, diffs=diffs))

    # ---- 3. 프로토콜 점검 ----
    g = "프로토콜"
    if base:
        before = base["response"] or {}
        r = ai.analyze(img, "s01.jpg", photo_id=base["photo_id"], participant_id="P-001", captured_at=None,
                       location_tags=None, device_masked=True, device_report=DEVICE_REPORT,
                       scenario="drain_normal" if is_mock else None)
        b = r.body if isinstance(r.body, dict) else {}
        cases.append(_expect(g, "replay", "같은 photo_id 재요청 → 저장된 결과 + meta.replayed",
                             r.status == 200 and _get(b, "meta.replayed") is True and b.get("grade") == before.get("grade"),
                             f"replayed={_get(b, 'meta.replayed')!r}, grade {b.get('grade')!r} (처음 {before.get('grade')!r})"))

    r = ai.analyze(img, "s01.jpg", photo_id=f"suite_{tag}_badjson", participant_id=None, captured_at=None,
                   location_tags=[], device_masked=True, device_report=None, extra_form={"context_json": "{bad"})
    cases.append(_expect(g, "bad_json", "context_json이 JSON이 아니면 400", r.status == 400, f"HTTP {r.status}"))
    r = ai.analyze(img, "s01.jpg", photo_id=f"suite_{tag}_arrjson", participant_id=None, captured_at=None,
                   location_tags=[], device_masked=True, device_report=None, extra_form={"context_json": "[1,2]"})
    cases.append(_expect(g, "array_json", "context_json이 객체가 아니면 400", r.status == 400, f"HTTP {r.status}"))

    r = ai.result(f"nope_{tag}")
    cases.append(_expect(g, "result_404", "없는 결과 조회 → 404", r.status == 404, f"HTTP {r.status}"))
    r = ai.image(f"nope_{tag}")
    cases.append(_expect(g, "image_404", "없는 사진 조회 → 404", r.status == 404, f"HTTP {r.status}"))
    r = ai.verify(img, "after.jpg", prior=PRIOR, mode="repair", photo_id=f"suite_{tag}_v404", before_photo_id=f"nope_{tag}",
                  before=None, participant_id=None, captured_at=None, device_masked=True, device_report=None)
    cases.append(_expect(g, "verify_404", "verify에 없는 before_photo_id → 404", r.status == 404, f"HTTP {r.status}"))

    r = ai._call("GET", "/taxonomy", api_key="wrong-key-for-test")
    if r.status == 401:
        cases.append(_case(g, "auth", "틀린 API 키 → 401", "pass", "인증 켜짐"))
    else:
        cases.append(_case(g, "auth", "틀린 API 키 → 401", "warn",
                           f"HTTP {r.status}: AI 서버의 API_KEYS가 비어 있어 인증 없이 동작 중 (개발 모드). 운영에서는 켜야 함"))
    cases.append(_expect(g, "health_contract", f"/health contract_version = {checks.CONTRACT_VERSION}",
                         health.get("contract_version") == checks.CONTRACT_VERSION, f"{health.get('contract_version')!r}"))
    cases.append(_case(g, "require_device_masking", "운영 설정 REQUIRE_DEVICE_MASKING",
                       "pass" if health.get("require_device_masking") else "warn",
                       "켜짐" if health.get("require_device_masking") else "꺼짐 (시험 단계면 괜찮음, 운영에서는 1)"))

    leak = runs.get("16_privacy_leak_fixed")
    if leak and leak["http_status"] == 200:
        pid = leak["photo_id"]
        r = ai.image(pid)
        rev = r.headers.get("x-image-revision")
        cases.append(_expect(g, "revision_header", "다시 가린 사진의 X-Image-Revision = 2", r.status == 200 and rev == "2",
                             f"HTTP {r.status}, X-Image-Revision={rev!r}, ETag={r.headers.get('etag')!r}"))
        found = _find_event(ai, pid, "image_remasked")
        cases.append(_expect(g, "event_remasked", "다시 가리면 GET /events에 image_remasked 알림",
                             bool(found and found.get("image_revision") == 2), f"{found}"))

    media_id = f"suite{tag}"
    r1 = ai.privacy_mask(img, "s01.jpg", media_id=media_id, purpose="reference", device_masked=True, device_report=DEVICE_REPORT)
    items = checks.check_privacy_mask(r1.body if isinstance(r1.body, dict) else {}, {"media_id": media_id, "purpose": "reference"}, r1.status)
    cases.append(_expect(g, "privacy_mask", "POST /privacy/mask 계약 검사", checks.summarize(items)["ok"],
                         ", ".join(c["title"] for c in items if c["level"] == "fail")))
    r2 = ai.privacy_mask(img, "s01.jpg", media_id=media_id, purpose="reference", device_masked=True, device_report=DEVICE_REPORT)
    cases.append(_expect(g, "privacy_replay", "같은 media_id·같은 사진 → meta.replayed",
                         r2.status == 200 and _get(r2.body, "meta.replayed") is True, f"HTTP {r2.status}"))
    r3 = ai.privacy_mask((SAMPLE / "s02.jpg").read_bytes(), "s02.jpg", media_id=media_id, purpose="reference",
                         device_masked=True, device_report=DEVICE_REPORT)
    cases.append(_expect(g, "privacy_conflict", "같은 media_id·다른 사진 → 409", r3.status == 409, f"HTTP {r3.status}"))
    r = ai.privacy_mask(img, "s01.jpg", media_id=f"bad{tag}", purpose="nope", device_masked=True, device_report=None)
    cases.append(_expect(g, "privacy_purpose", "purpose가 reference·repair_evidence가 아니면 400", r.status == 400, f"HTTP {r.status}"))

    items = [{"key": "photos", "label": "점검 사진", "value": 1240, "unit": "장"},
             {"key": "urgent_alerts", "label": "긴급 알림", "value": 2},
             {"key": "repair_close_suggested", "label": "수리 확인 제안(담당자 확정 전)", "value": 5}]
    r = ai.narrative(items, "시청 시설 담당 부서")
    nc = checks.check_narrative(r.body if isinstance(r.body, dict) else {}, items, r.status)
    cases.append(_expect(g, "narrative", "보고 문장: 새 숫자·빠진 숫자 없음", checks.summarize(nc)["ok"],
                         f"source={_get(r.body, 'source')!r}; " + ", ".join(c["title"] for c in nc if c["level"] == "fail")))
    r = ai.narrative([{"key": "x", "label": "소수", "value": 1.5}], None)
    cases.append(_expect(g, "narrative_400", "보고 문장 value가 정수가 아니면 400", r.status == 400, f"HTTP {r.status}"))

    counts = {"pass": 0, "fail": 0, "warn": 0, "skip": 0}
    for c in cases:
        counts[c["level"]] += 1
    return {"ok": counts["fail"] == 0, "provider": health.get("provider"), "model": health.get("model"),
            "is_mock": is_mock, "counts": counts, "elapsed_ms": int((time.perf_counter() - t0) * 1000), "cases": cases}


def _find_event(ai, photo_id: str, type_: str) -> dict | None:
    h = ai.health()
    last = (h.body or {}).get("last_event_seq") or 0
    r = ai.events(max(0, last - 200), 500)
    for ev in (r.body or {}).get("events") or []:
        if ev.get("photo_id") == photo_id and ev.get("type") == type_:
            return ev
    return None
