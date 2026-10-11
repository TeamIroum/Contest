"""AI 판독 응답이 계약서 v2.0(ai/contract/API_CONTRACT.md)을 지키는지 검사한다.

각 검사 결과는 {"id", "title", "level", "detail"} 이다.
level: pass(통과) | fail(계약 위반) | warn(위반은 아니지만 확인 필요) | info(참고)
"""
from __future__ import annotations

CONTRACT_VERSION = "2.0"

ANALYZE_STATUS = {"ok", "retake", "needs_review", "error"}
ANALYZE_ACTIONS = {"alert_now", "weekly_list", "recheck_next", "record_only",
                   "human_review_urgent", "human_review", "retake"}
VERIFY_ACTIONS = {"close_case", "re_request", "regrade", "keep", "human_review", "retake"}
VERIFY_RESULTS = {"repair": {"fixed", "not_fixed", "unclear"}, "change": {"worse", "same", "better", "unclear"}}
GRADES = ["정상", "관찰", "주의", "긴급"]
DEFAULT_GRADE_ACTIONS = {"정상": "record_only", "관찰": "recheck_next", "주의": "weekly_list", "긴급": "alert_now"}
DEVICE_REPORT_KEYS = {"faces", "plates", "vehicles", "detector", "app_version"}

# 응답 시간 목표 (계약서 엔드포인트 표)
ANALYZE_TARGET_MS, ANALYZE_LIMIT_MS = 10_000, 17_000
VERIFY_LIMIT_MS = 15_000


class _Checks:
    def __init__(self):
        self.items: list[dict] = []

    def add(self, id_: str, title: str, ok: bool, detail: str = "", level_if_bad: str = "fail"):
        self.items.append({"id": id_, "title": title, "level": "pass" if ok else level_if_bad, "detail": detail})

    def info(self, id_: str, title: str, detail: str):
        self.items.append({"id": id_, "title": title, "level": "info", "detail": detail})


def summarize(items: list[dict]) -> dict:
    out = {"pass": 0, "fail": 0, "warn": 0, "info": 0}
    for c in items:
        out[c["level"]] += 1
    out["ok"] = out["fail"] == 0
    return out


def _bbox_ok(b) -> bool:
    if b is None:
        return True
    if not (isinstance(b, list) and len(b) == 4 and all(isinstance(v, (int, float)) for v in b)):
        return False
    l, t, r, btm = b
    return 0 <= l < r <= 1 and 0 <= t < btm <= 1


def _common(c: _Checks, resp: dict, sent: dict, http_status: int):
    c.add("http", "HTTP 200", http_status == 200, f"HTTP {http_status}")
    meta = resp.get("meta") or {}
    cv = meta.get("contract_version")
    c.add("contract_version", f"meta.contract_version = {CONTRACT_VERSION}", cv == CONTRACT_VERSION, f"받은 값: {cv!r}")
    if sent.get("photo_id"):
        c.add("photo_id_echo", "보낸 photo_id를 그대로 돌려줌", resp.get("photo_id") == sent["photo_id"],
              f"보냄 {sent['photo_id']!r} / 받음 {resp.get('photo_id')!r}")

    url, rev = resp.get("masked_image_url"), resp.get("image_revision")
    c.add("revision_pair", "masked_image_url과 image_revision이 함께 있거나 함께 없음",
          (url is None) == (rev is None), f"url={url!r}, revision={rev!r}")
    if rev is not None:
        c.add("revision_min", "image_revision ≥ 1", isinstance(rev, int) and rev >= 1, f"{rev!r}")
    if resp.get("status") == "error":
        c.add("error_no_image", "status=error면 사진을 저장하지 않음 (masked_image_url = null)", url is None, f"{url!r}")

    sm = resp.get("senior_message")
    if resp.get("status") is not None:
        c.add("senior_message", "senior_message {key, text} 있음",
              isinstance(sm, dict) and bool(sm.get("key")) and bool(sm.get("text")), f"{sm!r}"[:120])

    priv = meta.get("privacy")
    if url is not None:
        c.add("privacy_present", "사진을 저장했으면 meta.privacy 기록 있음", isinstance(priv, dict))
    if isinstance(priv, dict):
        want_mode = "device+server" if sent.get("device_masked") else "server_only"
        c.add("privacy_mode", f"meta.privacy.mode = {want_mode}", priv.get("mode") == want_mode, f"받은 값: {priv.get('mode')!r}")
        sa = priv.get("server_added")
        if sent.get("device_masked"):
            c.add("server_added", "server_added = 0 (앱 마스킹이 놓친 것 없음)", sa == 0,
                  f"server_added={sa!r}. 0이 아니면 앱 마스킹 누락 또는 서버 검출 오탐", level_if_bad="warn")
        dev = priv.get("device")
        if sent.get("device_report") and isinstance(dev, dict):
            missing = [k for k, v in sent["device_report"].items() if v is not None and dev.get(k) is None]
            c.add("device_report_keys", "앱 마스킹 보고(snake_case)가 빠짐없이 기록됨", not missing,
                  "빠진 키: " + ", ".join(missing) if missing else "")
        for i, box in enumerate(priv.get("boxes") or []):
            b = box.get("box") if isinstance(box, dict) else box
            if not _bbox_ok(b):
                c.add(f"privacy_box_{i}", "가린 위치 좌표가 0~1 비율", False, f"{box!r}")
        if priv.get("leaks_fixed"):
            c.info("leaks_fixed", "AI가 가림 누락을 찾아 다시 가림",
                   f"leaks_fixed={priv['leaks_fixed']}, image_revision={rev}. GET /events에 image_remasked가 있어야 함")
            c.add("leak_revision", "다시 가렸으면 image_revision ≥ 2", isinstance(rev, int) and rev >= 2, f"{rev!r}")

    retry = resp.get("retry")
    if isinstance(retry, dict):
        if retry.get("pending"):
            c.add("retry_has_image", "재판독 대기(retry.pending)면 저장된 사진이 있음", url is not None)
            c.add("retry_needs_review", "재판독 대기면 status = needs_review", resp.get("status") == "needs_review",
                  f"status={resp.get('status')!r}")
        c.info("retry", "재판독 상태", f"{retry}")


def check_analyze(resp: dict, sent: dict, taxonomy: dict | None, http_status: int = 200,
                  latency_ms: int | None = None) -> list[dict]:
    c = _Checks()
    if not isinstance(resp, dict):
        c.add("json", "응답이 JSON 객체", False, f"{type(resp).__name__}")
        return c.items
    _common(c, resp, sent, http_status)
    status, action, grade = resp.get("status"), resp.get("action"), resp.get("grade")
    c.add("status_enum", "status ∈ ok | retake | needs_review | error", status in ANALYZE_STATUS, f"{status!r}")
    c.add("action_enum", "action이 계약서 값 중 하나", action in ANALYZE_ACTIONS, f"{action!r}")
    if grade is not None:
        c.add("grade_enum", "grade ∈ 정상 | 관찰 | 주의 | 긴급", grade in GRADES, f"{grade!r}")

    grade_actions = (taxonomy or {}).get("grade_actions") or DEFAULT_GRADE_ACTIONS
    if status == "ok":
        c.add("ok_image", "status=ok면 가린 사진이 저장됨", resp.get("masked_image_url") is not None)
        c.add("ok_grade", "status=ok면 grade 있음", grade in GRADES, f"{grade!r}")
        if grade in grade_actions:
            c.add("grade_action", "grade에 맞는 action (긴급→alert_now, 주의→weekly_list, 관찰→recheck_next, 정상→record_only)",
                  action == grade_actions[grade], f"grade={grade}, action={action}")
        c.add("ok_reasons", "status=ok면 grade_reasons(판정 근거) 있음", bool(resp.get("grade_reasons")),
              level_if_bad="warn")
    elif status == "retake":
        c.add("retake_action", "status=retake면 action = retake", action == "retake", f"{action!r}")
        probs = set(((resp.get("quality") or {}).get("problems")) or [])
        if probs & {"blur", "dark"}:
            c.add("retake_quality_no_image", "흐림·어두움 재촬영은 사진을 저장하지 않음",
                  resp.get("masked_image_url") is None, f"problems={sorted(probs)}")
        c.info("retake_saved", "재촬영 사진 저장 여부",
               "저장됨 (틀 밖·시설 안 보임)" if resp.get("masked_image_url") else "저장 안 됨 (흐림·어두움 등)")
    elif status == "needs_review":
        c.add("review_action", "status=needs_review면 action ∈ human_review | human_review_urgent",
              action in {"human_review", "human_review_urgent"}, f"{action!r}")
        if action == "human_review_urgent":
            c.add("urgent_grade", "human_review_urgent면 잠정 grade = 긴급", grade == "긴급", f"{grade!r}")
    elif status == "error":
        c.add("error_action", "status=error면 action = retake", action == "retake", f"{action!r}")

    fac = resp.get("facility")
    facs = (taxonomy or {}).get("facilities") or {}
    if isinstance(fac, dict):
        ftype = fac.get("type")
        if facs:
            c.add("facility_type", "facility.type이 taxonomy 5종 또는 unknown", ftype in facs or ftype == "unknown", f"{ftype!r}")
        if ftype == "unknown":
            c.add("unknown_review", "목록 외 시설(unknown)은 담당자 확인 (재촬영 아님)", status == "needs_review", f"status={status!r}")
        if not sent.get("has_latlon"):
            c.add("no_latlon_ambiguous", "lat/lon을 안 보냈으면 match_ambiguous = false",
                  fac.get("match_ambiguous") in (False, None), f"{fac.get('match_ambiguous')!r}")
            c.add("no_latlon_facility_id", "lat/lon을 안 보냈으면 AI 쪽 facility_id 비어 있음",
                  not fac.get("facility_id"), f"{fac.get('facility_id')!r}", level_if_bad="warn")
        if sent.get("location_tags") is not None:
            got = set(fac.get("location_tags") or [])
            want = set(sent["location_tags"])
            c.add("location_tags", "보낸 location_tags가 반영됨", want <= got, f"보냄 {sorted(want)} / 받음 {sorted(got)}")

    issues = resp.get("issues") or []
    allowed = set(((facs.get((fac or {}).get("type")) or {}).get("issues") or {}).keys()) if isinstance(fac, dict) else set()
    for i, iss in enumerate(issues):
        sev = iss.get("severity")
        c.add(f"issue{i}_severity", f"issues[{i}].severity가 0~3", isinstance(sev, int) and 0 <= sev <= 3, f"{sev!r}")
        c.add(f"issue{i}_bbox", f"issues[{i}].bbox가 [왼쪽, 위, 오른쪽, 아래] 0~1 비율", _bbox_ok(iss.get("bbox")), f"{iss.get('bbox')!r}")
        if allowed:
            c.add(f"issue{i}_type", f"issues[{i}].type이 시설에 맞는 문제 유형", iss.get("type") in allowed,
                  f"{iss.get('type')!r} / 허용: {sorted(allowed)}")

    dup = resp.get("duplicate_of")
    if dup is not None:
        c.add("duplicate_known", "duplicate_of가 보낸 open_cases 중 하나", dup in (sent.get("open_case_ids") or []), f"{dup!r}")

    if latency_ms is not None and status in ("ok", "needs_review"):
        if latency_ms > ANALYZE_LIMIT_MS:
            c.add("latency", f"응답 시간 {ANALYZE_LIMIT_MS // 1000}초 이내", False, f"{latency_ms}ms")
        else:
            c.add("latency", f"응답 시간 목표 {ANALYZE_TARGET_MS // 1000}초 이내", latency_ms <= ANALYZE_TARGET_MS,
                  f"{latency_ms}ms", level_if_bad="warn")
    if (resp.get("meta") or {}).get("replayed"):
        c.info("replayed", "같은 photo_id 재요청이라 저장된 결과를 돌려줌", "meta.replayed = true")
    return c.items


def check_verify(resp: dict, sent: dict, http_status: int = 200, latency_ms: int | None = None) -> list[dict]:
    c = _Checks()
    if not isinstance(resp, dict):
        c.add("json", "응답이 JSON 객체", False, f"{type(resp).__name__}")
        return c.items
    _common(c, resp, sent, http_status)
    mode = sent.get("mode", "repair")
    status, action, result = resp.get("status"), resp.get("action"), resp.get("result")
    c.add("mode_echo", "보낸 mode를 그대로 돌려줌", resp.get("mode") == mode, f"{resp.get('mode')!r}")
    c.add("status_enum", "status ∈ ok | retake | needs_review | error", status in ANALYZE_STATUS, f"{status!r}")
    c.add("action_enum", "action이 계약서 값 중 하나", action in VERIFY_ACTIONS, f"{action!r}")
    if result is not None:
        c.add("result_enum", f"{mode} 모드 result ∈ {' | '.join(sorted(VERIFY_RESULTS.get(mode, [])))}",
              result in VERIFY_RESULTS.get(mode, set()), f"{result!r}")
    if sent.get("case_id"):
        c.add("case_id_echo", "prior의 case_id를 돌려줌", resp.get("case_id") == sent["case_id"], f"{resp.get('case_id')!r}")

    if status == "ok":
        if mode == "repair":
            want = {"fixed": "close_case", "not_fixed": "re_request"}.get(result)
            c.add("repair_action", "repair: fixed→close_case, not_fixed→re_request", action == want, f"result={result}, action={action}")
        else:
            want = "regrade" if result == "worse" else "keep"
            c.add("change_action", "change: worse→regrade, 그 외→keep", action == want, f"result={result}, action={action}")
            if result == "worse":
                c.add("regrade_grade", "regrade면 new_grade 있음", resp.get("new_grade") in GRADES, f"{resp.get('new_grade')!r}")
    elif status == "retake":
        c.add("retake_action", "status=retake면 action = retake", action == "retake", f"{action!r}")
        c.add("retake_no_rerequest", "같은 시설 아님·부위 안 보임이면 재요청을 보내지 않음", action != "re_request")
    elif status == "needs_review":
        c.add("review_action", "status=needs_review면 action = human_review", action == "human_review", f"{action!r}")

    if resp.get("same_facility") not in (None, "yes"):
        c.add("different_place_retake", "같은 시설이 아니면 status = retake", status == "retake", f"same_facility={resp.get('same_facility')!r}")
    if latency_ms is not None and status in ("ok", "needs_review"):
        c.add("latency", f"응답 시간 {VERIFY_LIMIT_MS // 1000}초 이내", latency_ms <= VERIFY_LIMIT_MS, f"{latency_ms}ms", level_if_bad="warn")
    return c.items


def check_privacy_mask(resp: dict, sent: dict, http_status: int = 200) -> list[dict]:
    c = _Checks()
    c.add("http", "HTTP 200", http_status == 200, f"HTTP {http_status}")
    if not isinstance(resp, dict):
        return c.items
    c.add("media_id_echo", "보낸 media_id를 돌려줌", resp.get("media_id") == sent.get("media_id"), f"{resp.get('media_id')!r}")
    c.add("purpose_echo", "보낸 purpose를 돌려줌", resp.get("purpose") == sent.get("purpose"), f"{resp.get('purpose')!r}")
    c.add("status_enum", "status ∈ ok | error", resp.get("status") in {"ok", "error"}, f"{resp.get('status')!r}")
    if resp.get("status") == "ok":
        c.add("ok_image", "status=ok면 가린 사진 주소 있음", bool(resp.get("masked_image_url")))
        c.add("photo_key", "저장 이름이 m_<media_id>", resp.get("photo_id") == f"m_{sent.get('media_id')}", f"{resp.get('photo_id')!r}")
    else:
        c.add("error_no_image", "status=error면 저장하지 않음", resp.get("masked_image_url") is None)
    return c.items


def check_narrative(resp: dict, items: list[dict], http_status: int = 200) -> list[dict]:
    import re
    c = _Checks()
    c.add("http", "HTTP 200 (AI 오류여도 고정 문장으로 200)", http_status == 200, f"HTTP {http_status}")
    if not isinstance(resp, dict):
        return c.items
    c.add("source_enum", "source ∈ llm | template", resp.get("source") in {"llm", "template"}, f"{resp.get('source')!r}")
    text = resp.get("text") or ""
    c.add("text", "문장이 비어 있지 않음", bool(text.strip()))
    nums = {int(n.replace(",", "")) for n in re.findall(r"\d[\d,]*", text)}
    allowed = {int(i["value"]) for i in items}
    extra = sorted(nums - allowed)
    missing = sorted(allowed - nums)
    c.add("no_new_numbers", "입력에 없는 숫자가 문장에 없음", not extra, f"새 숫자: {extra}" if extra else "")
    c.add("no_missing_numbers", "입력 값이 모두 문장에 들어감", not missing, f"빠진 값: {missing}" if missing else "")
    if resp.get("source") == "template":
        c.info("template", "AI 문장 대신 고정 문장 사용", resp.get("note") or "")
    return c.items
