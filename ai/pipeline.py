"""사진 한 장이 판독 결과(JSON)가 되기까지의 전체 흐름.

  [받기 ingest]   업로드 검증·방향 보정 → 품질 확인 → 마스킹 → 축소 → 가린 사진 저장
  [판단 judge]    AI 판독(동시 호출 제한) → 마스킹 누락 재가림 → 가림 영역 겹침 확인
                  → 시설 대장 매칭 → 등급 계산 → 담당자 확인 여부 → 중복 확인
  [기록]          결과 저장 (같은 photo_id 재요청에는 저장된 결과를 그대로 돌려줌)

개인정보 원칙
  - 운영 구조: 앱이 휴대폰에서 먼저 가리고(1차) 가린 사진만 보낸다(device_masked=true).
    서버는 받은 사진을 저장하기 전에 가림 상태를 확인하고, 남은 부분이 있으면 저장·AI 판독 전에 가린 뒤
    그 수를 기록한다. 원칙(서버에는 가린 사진만)을 지키는 주 장치는 앱이고, 이 확인은 퍼짐을 막는 안전망이다.
  - 앱 마스킹이 아직 없는 개발 단계에서는 서버가 1차로 가린다(server_only). REQUIRE_DEVICE_MASKING=1이면
    기기에서 가리지 않은 사진은 받지 않고 버린다.
  - 서버는 받은 사진을 메모리에서만 다룬다. 디스크에는 서버 검사까지 마친 가린 사진만 쓴다.
  - 마스킹이 실패하면 저장도, AI 전송도 하지 않는다 (status=error, 다시 찍기).
  - AI에는 가린 사진만 보낸다. AI가 가림이 덜 된 곳을 알려 주면 저장된 사진을 다시 가린다.

AI 실패 처리
  - 오류·시간 초과·혼잡이면 담당자 확인(needs_review)으로 돌려주고, 저장된 가린 사진으로
    jobs.RetryQueue가 나중에 다시 판독한다. 결과는 GET /results/{photo_id}로 다시 받는다.
"""
import logging
import os
import time
from datetime import datetime, timedelta, timezone

import images
import jobs
import ledger
import privacy
import prompts
import quality
import rules
import storage
from config import facility, issue_label, senior_message, taxonomy
from providers import Provider, get_provider

log = logging.getLogger("ai.pipeline")

CONTRACT_VERSION = "2.0"
KST = timezone(timedelta(hours=9))
REQUIRE_DEVICE_MASKING = os.environ.get("REQUIRE_DEVICE_MASKING", "0") == "1"
OVERLAP_REVIEW = 0.25        # 문제 위치가 가린 영역과 이만큼 겹치면 담당자 확인
MASKED_AREA_WARN = 0.30      # 사진의 이만큼이 가려지면 판독에 지장이 있을 수 있음

RETAKE_KEY = {
    "blur": "retake_blur",
    "dark": "retake_dark",
    "unreadable": "retake_blur",
    "out_of_frame": "retake_frame",
    "no_facility": "retake_no_facility",
}

_default_provider: Provider | None = None


def _provider(p: Provider | None) -> Provider:
    global _default_provider
    if p is not None:
        return p
    if _default_provider is None:
        _default_provider = get_provider()
    return _default_provider


def set_default_provider(p: Provider) -> None:
    global _default_provider
    _default_provider = p


def _meta(provider: Provider, prompt_version, t0: float, extra: dict | None = None) -> dict:
    m = {
        "contract_version": CONTRACT_VERSION,
        "provider": provider.name,
        "model": provider.model,
        "prompt_version": prompt_version,
        "taxonomy_version": taxonomy()["version"],
        "latency_ms": int((time.time() - t0) * 1000),
        "created_at": datetime.now(KST).isoformat(timespec="seconds"),
    }
    if extra:
        m.update(extra)
    return m


def _log(res: dict, kind: str) -> None:
    """운영 기록. 사진 내용이나 정확한 위치는 남기지 않는다."""
    m = res.get("meta") or {}
    p = m.get("privacy") or {}
    log.info("%s photo_id=%s status=%s grade=%s action=%s faces=%s plates=%s leaks_fixed=%s latency_ms=%s",
             kind, res.get("photo_id"), res.get("status"), res.get("grade") or res.get("result"),
             res.get("action"), p.get("faces"), p.get("plates"), p.get("leaks_fixed"), m.get("latency_ms"))


# =====================================================================
# 받기: 검증 → 품질 → 마스킹 → 저장
# =====================================================================
def _device_report(device: dict | None) -> dict | None:
    """앱이 보낸 가림 보고에서 필요한 값만 남긴다 (앱이 보낸 값은 검증 대상이지 사실이 아니다)."""
    if not device or not device.get("masked"):
        return None
    r = device.get("report") or {}
    def num(v):
        try:
            return max(0, int(v))
        except (TypeError, ValueError):
            return None
    return {"faces": num(r.get("faces")), "plates": num(r.get("plates")), "vehicles": num(r.get("vehicles")),
            "detector": str(r.get("detector") or "")[:40], "app_version": str(r.get("app_version") or "")[:20]}


def ingest(image_bytes: bytes, photo_id: str, device: dict | None = None) -> tuple[bytes | None, dict]:
    """가린 JPEG과 받기 단계 정보를 돌려준다. 가린 JPEG이 None이면 여기서 끝난 것(재촬영·오류).
    device: {"masked": bool, "report": {faces, plates, detector, app_version}} 앱이 1차로 가렸는지와 그 보고."""
    info = {"quality": None, "privacy": None, "stop": None}
    dev = _device_report(device)
    if dev is None and REQUIRE_DEVICE_MASKING:
        del image_bytes  # 받은 사진을 쓰지 않고 버린다
        info["stop"] = ("error", "retake", "retry_later", "기기에서 가리지 않은 사진은 받지 않음 (앱 업데이트 필요)")
        return None, info
    try:
        img = images.decode_upload(image_bytes)
    except images.ImageRejected as e:
        info["stop"] = ("error", "retake", "retry_later", f"사진을 받을 수 없음: {e.code}")
        return None, info

    q = quality.check_array(img)
    info["quality"] = q
    if not q["ok"]:  # 흐림·어두움이면 마스킹·저장·AI 모두 하지 않는다
        info["stop"] = ("retake", "retake", RETAKE_KEY[q["problems"][0]], None)
        return None, info

    try:
        masked, rep = privacy.mask_image(img)       # 서버 검사: 받은 해상도 그대로에서 검출
        del img                                      # 원본 배열 참조를 버린다
        jpeg = images.encode_jpeg(images.resize_max(masked, images.AI_MAX_SIDE))
    except Exception as e:  # fail-closed: 가리지 못한 사진은 어디에도 내보내지 않는다
        log.exception("마스킹 실패 photo_id=%s", photo_id)
        info["stop"] = ("error", "retake", "retry_later", f"마스킹 실패: {type(e).__name__}")
        return None, info

    priv = rep.as_dict()
    if dev is not None:
        # 기기에서 이미 가린 사진: 서버가 찾아 가린 것은 기기 마스킹이 놓친 것
        priv.update(mode="device+server", device=dev, server_added=rep.faces + rep.plates + rep.vehicles)
        if priv["server_added"]:
            log.warning("앱 마스킹 누락: 저장 전 확인에서 가림 photo_id=%s count=%d detector=%s",
                        photo_id, priv["server_added"], dev["detector"])
    else:
        priv.update(mode="server_only", device=None, server_added=None)
    info["privacy"] = priv
    info["masked_image_url"] = storage.get_store().save_masked(photo_id, jpeg)
    info["image_revision"] = storage.get_store().revision(photo_id)
    return jpeg, info


def _remask_leaks(photo_id: str, jpeg: bytes, leaks: list[dict], priv: dict) -> bytes:
    """AI가 알려 준 '아직 알아볼 수 있는 얼굴·번호판'을 저장된 사진에서 다시 가린다."""
    img = images.decode_jpeg(jpeg)
    H, W = img.shape[:2]
    for lk in leaks:
        x1, y1, x2, y2 = lk["bbox"]
        b = privacy.Box(int(x1 * W), int(y1 * H), int((x2 - x1) * W), int((y2 - y1) * H), lk["kind"], 1.0, "ai")
        p = b.padded(0.3, W, H)
        if p.w <= 0 or p.h <= 0:
            continue
        if lk["kind"] == "face":
            img[p.y:p.y + p.h, p.x:p.x + p.w] = privacy._pixelate(img[p.y:p.y + p.h, p.x:p.x + p.w])
        else:
            img[p.y:p.y + p.h, p.x:p.x + p.w] = privacy.PLATE_FILL
        priv["boxes"].append({"kind": lk["kind"], "source": "ai", "score": 1.0,
                              "box": [round(p.x / W, 4), round(p.y / H, 4), round((p.x + p.w) / W, 4), round((p.y + p.h) / H, 4)]})
    new = images.encode_jpeg(img)
    store = storage.get_store()
    store.save_masked(photo_id, new)
    priv["leaks_fixed"] = priv.get("leaks_fixed", 0) + len(leaks)
    storage.get_events().add("image_remasked", photo_id, image_revision=store.revision(photo_id),
                             reason="ai_leak", count=len(leaks))
    log.warning("마스킹 누락을 AI가 발견해 다시 가림 photo_id=%s count=%d kinds=%s",
                photo_id, len(leaks), [lk["kind"] for lk in leaks])
    return new


def _overlap(issue_bbox, mask_box) -> float:
    ix = max(0.0, min(issue_bbox[2], mask_box[2]) - max(issue_bbox[0], mask_box[0]))
    iy = max(0.0, min(issue_bbox[3], mask_box[3]) - max(issue_bbox[1], mask_box[1]))
    area = max(1e-6, (issue_bbox[2] - issue_bbox[0]) * (issue_bbox[3] - issue_bbox[1]))
    return ix * iy / area


# =====================================================================
# 판독
# =====================================================================
def _empty_result(photo_id: str) -> dict:
    return {
        "photo_id": photo_id,
        "participant_id": None,   # 촬영한 참여자 ID (이름이 아닌 가명 ID)
        "captured_at": None,      # 앱이 보낸 촬영 시각 (없으면 서버 시각)
        "status": None,           # ok | retake | needs_review | error
        "masked_image_url": None, # 서버에 저장된 가린 사진 주소 (흐림·어두움·오류면 None)
        "image_revision": None,   # 가린 사진 버전. 다시 가리면 오른다 (GET /events의 image_remasked)
        "quality": None,
        "facility": None,
        "issues": [],
        "ai_severity": None,
        "grade": None,            # 정상 | 관찰 | 주의 | 긴급 (needs_review면 잠정 등급)
        "grade_reasons": [],
        "dept": None,
        "action": None,           # alert_now | weekly_list | recheck_next | record_only | human_review | human_review_urgent | retake
        "senior_message": None,
        "duplicate_of": None,
        "retry": None,            # AI 실패로 나중에 다시 판독할 때 {pending, attempts, next_at}
        "notes": [],
        "meta": None,
    }


def _judge(res: dict, jpeg: bytes, ctx: dict, provider: Provider, t0: float, attempt: int = 0) -> dict:
    priv = (res.get("meta") or {}).get("privacy") or {"faces": 0, "plates": 0, "boxes": []}
    try:
        raw = jobs.ai_gate.run(provider.analyze, jpeg, prompts.analyze_prompt(), prompts.analyze_schema())
    except Exception as e:  # 오류·시간 초과·혼잡: 담당자 확인 + 나중에 다시 판독
        log.warning("AI 판독 실패 photo_id=%s: %s: %s", res.get("photo_id"), type(e).__name__, e)
        res.update(status="needs_review", action="human_review", senior_message=senior_message("human_review"))
        res["notes"] = [f"판독 지연: {type(e).__name__}. 저장된 가린 사진으로 다시 판독합니다."]
        res["retry"] = jobs.retry_queue.enqueue("analyze", res["photo_id"], attempt)
        if res["retry"].get("gave_up"):
            res["notes"] = [f"판독 실패({type(e).__name__})가 반복돼 담당자 확인으로 둡니다."]
        res["meta"] = _meta(provider, prompts.ANALYZE_VERSION, t0, {"privacy": priv})
        return res

    res["retry"] = None
    res["notes"] = []
    leaks = raw.get("privacy_leaks") or []
    if leaks:
        jpeg = _remask_leaks(res["photo_id"], jpeg, leaks, priv)
        res["image_revision"] = storage.get_store().revision(res["photo_id"])
        res["notes"].append(f"마스킹이 놓친 {len(leaks)}곳을 다시 가렸습니다 (검출기 개선 자료로 기록).")
    meta_extra = {"privacy": priv, "raw_certainty": raw["certainty"]}
    if attempt:
        meta_extra["retried"] = attempt

    if not raw["framing_ok"]:
        res["quality"]["ok"] = False
        res["quality"]["problems"].append("out_of_frame")
        res.update(status="retake", action="retake", senior_message=senior_message("retake_frame"))
        res["meta"] = _meta(provider, prompts.ANALYZE_VERSION, t0, meta_extra)
        return res

    ftype = raw["facility_type"]
    if ftype == "unknown" or not raw["facility_visible"]:
        res.update(status="needs_review", action="human_review", senior_message=senior_message("human_review"))
        res["facility"] = {"type": "unknown", "label": "목록 외 시설", "certainty": raw["certainty"]}
        res["notes"].append("점검 목록에 없는 대상이거나 시설이 분명하지 않음")
        res["meta"] = _meta(provider, prompts.ANALYZE_VERSION, t0, meta_extra)
        return res

    f = facility(ftype)
    issues, notes = rules.filter_issues(ftype, raw["issues"], raw["is_night_photo"], ctx.get("capture_hour"))
    res["notes"].extend(notes)
    for i in issues:
        i["label"] = issue_label(ftype, i["type"])
    severity = max((i["severity"] for i in issues), default=0)

    match = ledger.match_facility(ctx.get("lat"), ctx.get("lon"), ftype)
    tags = ctx.get("location_tags")
    if tags is None:
        tags = match["tags"] if match else []
    res["facility"] = {
        "type": ftype,
        "label": f["label"],
        "certainty": raw["certainty"],
        "facility_id": match["facility_id"] if match else None,
        "place": match["place"] if match else None,
        "match_ambiguous": bool(match and match["ambiguous"]),
        "location_tags": tags,
    }
    if match is None and ctx.get("lat") is not None:
        res["notes"].append("시설 대장에서 가까운 같은 종류 시설을 찾지 못함")

    history = ctx.get("history")
    worsened = bool(history and severity > history.get("severity", 0))
    grade, reasons = rules.compute_grade(ftype, issues, tags, worsened)
    if history and not issues:
        reasons.append(f"이전 판독 정도 {history.get('severity')}/3 → 이번 0")
    res.update(issues=issues, ai_severity=severity, grade=grade, grade_reasons=reasons, dept=f["dept"])

    # 가린 영역이 판독을 가렸을 수 있는지
    hidden = [i for i in issues for b in priv.get("boxes", []) if _overlap(i["bbox"], b["box"]) >= OVERLAP_REVIEW]
    if priv.get("masked_area_ratio", 0) >= MASKED_AREA_WARN:
        res["notes"].append(f"사진의 {int(priv['masked_area_ratio'] * 100)}%가 가려져 판독에 지장이 있을 수 있음")
    if hidden:
        res["notes"].append("문제 위치가 개인정보 가림 영역과 겹침: 담당자 확인 필요")

    low = raw["certainty"] == "low" and rules.rules()["review"]["send_low_certainty_to_human"]
    if low or res["facility"]["match_ambiguous"] or hidden:
        if low:
            res["notes"].append("AI 확신 낮음: 담당자 확인 필요 (등급은 잠정)")
        if res["facility"]["match_ambiguous"]:
            res["notes"].append("가까운 같은 종류 시설이 여러 개라 위치 확인 필요")
        urgent = grade == "긴급"
        res.update(status="needs_review",
                   action="human_review_urgent" if urgent else "human_review",
                   senior_message=senior_message("danger_keep_away" if urgent else "human_review"))
    else:
        res.update(status="ok", action=rules.action_for_grade(grade),
                   senior_message=senior_message(rules.SENIOR_KEY_BY_GRADE[grade]))

    if issues:
        top = max(issues, key=lambda i: i["severity"])
        res["duplicate_of"] = ledger.find_duplicate(ctx.get("open_cases"), res["facility"]["facility_id"], top["type"])
        if res["duplicate_of"]:
            res["notes"].append(f"기존 접수 건 {res['duplicate_of']}에 묶음 (담당자 중복 알림 없음)")

    res["meta"] = _meta(provider, prompts.ANALYZE_VERSION, t0, meta_extra)
    return res


def analyze_photo(image_bytes: bytes, context: dict | None = None, provider: Provider | None = None) -> dict:
    """context(모두 선택):
    photo_id, lat, lon, capture_hour(0~23), location_tags(대장 대신 직접 지정),
    history({"severity": int, "issue_type": str}: 같은 시설 직전 판독), open_cases([{case_id, facility_id, issue_type}])
    """
    t0 = time.time()
    ctx = dict(context or {})
    provider = _provider(provider)
    photo_id = storage.safe_photo_id(ctx.get("photo_id"))
    results = storage.get_results()

    # 같은 photo_id 재요청(네트워크 재시도): 저장된 결과를 그대로 돌려준다 → 알림 중복 없음
    if ctx.get("photo_id") == photo_id:
        prev = results.load(photo_id)
        if prev and prev.get("status") in ("ok", "needs_review", "retake"):
            prev.setdefault("meta", {})["replayed"] = True
            return prev

    res = _empty_result(photo_id)
    res["participant_id"] = ctx.get("participant_id")
    res["captured_at"] = ctx.get("captured_at") or datetime.now(KST).isoformat(timespec="seconds")
    jpeg, info = ingest(image_bytes, photo_id, ctx.get("device"))
    del image_bytes
    res["quality"] = info["quality"] and {k: info["quality"][k] for k in ("ok", "problems", "metrics")}
    if jpeg is None:
        status, action, msg, note = info["stop"]
        res.update(status=status, action=action, senior_message=senior_message(msg))
        if note:
            res["notes"].append(note)
        res["meta"] = _meta(provider, None, t0)
        _log(res, "analyze")
        return res  # 저장할 사진이 없으므로 결과도 저장하지 않는다 (같은 ID로 다시 찍어도 됨)

    res["masked_image_url"] = info["masked_image_url"]
    res["image_revision"] = info["image_revision"]
    res["meta"] = {"privacy": info["privacy"]}
    stored_ctx = {k: ctx.get(k) for k in ("lat", "lon", "capture_hour", "location_tags", "history", "open_cases",
                                          "participant_id")}
    res = _judge(res, jpeg, stored_ctx, provider, t0)
    results.save(res, stored_ctx)
    _log(res, "analyze")
    return res


def reanalyze(photo_id: str, attempt: int = 1, provider: Provider | None = None) -> dict | None:
    """저장된 가린 사진과 맥락으로 다시 판독한다 (RetryQueue가 호출). 원본은 필요 없다."""
    t0 = time.time()
    provider = _provider(provider)
    results = storage.get_results()
    prev, ctx = results.load(photo_id), results.load_ctx(photo_id) or {}
    jpeg = storage.get_store().load_masked(photo_id)
    if prev is None or jpeg is None:
        return None
    res = _empty_result(photo_id)
    res.update(masked_image_url=prev["masked_image_url"], quality=prev["quality"],
               participant_id=prev.get("participant_id"), captured_at=prev.get("captured_at"),
               image_revision=storage.get_store().revision(photo_id))
    if prev.get("privacy_audit"):
        res["privacy_audit"] = prev["privacy_audit"]
    res["meta"] = {"privacy": (prev.get("meta") or {}).get("privacy")}
    res = _judge(res, jpeg, ctx, provider, t0, attempt=attempt)
    results.save(res)
    _result_updated(res)
    _log(res, "reanalyze")
    return res


def _result_updated(res: dict) -> None:
    """재판독·재비교로 결과가 바뀌었음을 알린다. 아직 재시도 중이어도 attempts가 바뀌었으므로 알린다."""
    storage.get_events().add("result_updated", res["photo_id"], status=res.get("status"),
                             retry=res.get("retry"), image_revision=res.get("image_revision"))


def give_up_retry(photo_id: str, attempt: int, reason: str) -> dict | None:
    """재판독을 더 할 수 없을 때(사진·맥락이 없거나 반복 예외) 대기 상태를 끝내고 담당자 확인으로 둔다.
    이렇게 하지 않으면 결과가 '재판독 중'으로 영원히 남고 서버를 켤 때마다 다시 집어 든다."""
    results = storage.get_results()
    r = results.load(photo_id)
    if r is None:
        return None
    r["retry"] = {"pending": False, "attempts": attempt, "gave_up": True, "reason": reason}
    r.update(status="needs_review", action="human_review")
    r["notes"] = list(r.get("notes") or []) + [f"다시 판독할 수 없어 담당자 확인으로 둡니다 ({reason})."]
    results.save(r)
    _result_updated(r)
    log.warning("재판독 포기 photo_id=%s attempt=%d reason=%s", photo_id, attempt, reason)
    return r


# =====================================================================
# 전후 비교
# =====================================================================
def _verify_core(res: dict, before: bytes, after: bytes, prior: dict, mode: str, provider: Provider,
                 t0: float, priv: dict, attempt: int = 0) -> dict:
    try:
        raw = jobs.ai_gate.run(provider.verify, before, after, prompts.verify_prompt(prior, mode),
                               prompts.verify_schema(mode))
    except Exception as e:
        res.update(status="needs_review", action="human_review", senior_message=senior_message("human_review"))
        res["notes"] = [f"비교 지연: {type(e).__name__}. 저장된 가린 사진으로 다시 비교합니다."]
        res["retry"] = jobs.retry_queue.enqueue("verify", res["photo_id"], attempt)
        if res["retry"].get("gave_up"):
            res["notes"] = [f"비교 실패({type(e).__name__})가 반복돼 담당자 확인으로 둡니다."]
        res["meta"] = _meta(provider, prompts.VERIFY_VERSION, t0, {"privacy": priv})
        return res

    res["retry"], res["notes"] = None, []
    res.update(same_facility=raw["same_facility"], current_severity=raw["current_severity"], evidence=raw["evidence"])
    if raw["same_facility"] != "yes" or not raw["clearly_visible"]:
        res.update(status="retake", action="retake", senior_message=senior_message("verify_retake"))
        res["notes"].append("같은 시설인지 확인되지 않거나 문제 부위가 잘 보이지 않음")
    elif raw["result"] == "unclear" or raw["certainty"] == "low":
        res.update(status="needs_review", action="human_review", result=raw["result"],
                   senior_message=senior_message("human_review"))
        res["notes"].append("AI 판단이 애매해 담당자 확인 필요")
    elif mode == "repair":
        fixed = raw["result"] == "fixed"
        res.update(status="ok", result=raw["result"], action="close_case" if fixed else "re_request",
                   senior_message=senior_message("verify_fixed" if fixed else "verify_not_fixed"))
    else:
        res.update(status="ok", result=raw["result"])
        if raw["result"] == "worse":
            issue = {"type": prior["issue_type"], "severity": max(1, raw["current_severity"]), "evidence": raw["evidence"]}
            grade, reasons = rules.compute_grade(prior["facility_type"], [issue], prior.get("location_tags"), worsened=True)
            res.update(action="regrade", new_grade=grade, grade_reasons=reasons,
                       senior_message=senior_message(rules.SENIOR_KEY_BY_GRADE[grade]))
        else:
            res.update(action="keep", senior_message=senior_message("observe_recheck"))
    extra = {"privacy": priv}
    if attempt:
        extra["retried"] = attempt
    res["meta"] = _meta(provider, prompts.VERIFY_VERSION, t0, extra)
    return res


def verify_photo(before_jpeg: bytes, after_bytes: bytes, prior: dict, mode: str = "repair",
                 context: dict | None = None, provider: Provider | None = None) -> dict:
    """prior: {case_id, facility_type, issue_type, severity, bbox, evidence, location_tags?}
    mode: repair(처리 완료 후 수리 확인) | change(미처리 건 변화 비교)
    before_jpeg: 처음 판독 때 저장된 가린 사진. context의 before_photo_id가 있으면 재비교에 쓴다.
    """
    t0 = time.time()
    ctx = dict(context or {})
    provider = _provider(provider)
    photo_id = storage.safe_photo_id(ctx.get("photo_id"))
    results = storage.get_results()
    if ctx.get("photo_id") == photo_id:
        prev = results.load(photo_id)
        if prev and prev.get("mode") and prev.get("status") in ("ok", "needs_review", "retake"):
            prev.setdefault("meta", {})["replayed"] = True
            return prev

    res = {
        "photo_id": photo_id, "case_id": prior.get("case_id"), "facility_id": prior.get("facility_id"),
        "participant_id": ctx.get("participant_id"),
        "captured_at": ctx.get("captured_at") or datetime.now(KST).isoformat(timespec="seconds"),
        "masked_image_url": None, "image_revision": None, "mode": mode,
        "status": None, "result": None, "action": None, "same_facility": None, "current_severity": None,
        "evidence": None, "new_grade": None, "grade_reasons": [], "senior_message": None,
        "retry": None, "notes": [], "meta": None,
    }
    after, info = ingest(after_bytes, photo_id, ctx.get("device"))
    del after_bytes
    if after is None:
        status, action, msg, note = info["stop"]
        res.update(status=status, action=action, senior_message=senior_message(msg))
        if note:
            res["notes"].append(note)
        res["meta"] = _meta(provider, None, t0)
        return res
    res["masked_image_url"] = info["masked_image_url"]
    res["image_revision"] = info["image_revision"]
    if not ctx.get("before_photo_id"):
        # 처리 전 사진을 직접 받은 경우: 한 번 더 가려서 저장해 두어야 나중에 다시 비교할 수 있다
        before_jpeg, _ = privacy.anonymize(before_jpeg)
        ctx["before_photo_id"] = photo_id + "_before"
        storage.get_store().save_masked(ctx["before_photo_id"], before_jpeg)
    before = images.encode_jpeg(images.resize_max(images.decode_jpeg(before_jpeg), images.AI_MAX_SIDE))
    res = _verify_core(res, before, after, prior, mode, provider, t0, info["privacy"])
    results.save(res, {"prior": prior, "mode": mode, "before_photo_id": ctx.get("before_photo_id")})
    _log(res, "verify")
    return res


def reverify(photo_id: str, attempt: int = 1, provider: Provider | None = None) -> dict | None:
    t0 = time.time()
    provider = _provider(provider)
    results = storage.get_results()
    prev, ctx = results.load(photo_id), results.load_ctx(photo_id) or {}
    after = storage.get_store().load_masked(photo_id)
    before = storage.get_store().load_masked(ctx.get("before_photo_id") or "")
    if prev is None or after is None or before is None:
        return None
    res = _verify_core(dict(prev), before, after, ctx["prior"], ctx["mode"], provider, t0,
                       (prev.get("meta") or {}).get("privacy"), attempt=attempt)
    results.save(res)
    _result_updated(res)
    _log(res, "reverify")
    return res


# =====================================================================
# 개인정보 단독 검사 (참고 사진·보수 증거 사진)
# =====================================================================
MEDIA_PURPOSES = {"reference", "repair_evidence"}


class MediaConflict(Exception):
    """같은 media_id로 다른 사진이 들어옴."""


def mask_media(image_bytes: bytes, media_id: str, purpose: str, device: dict | None = None) -> dict:
    """판독 없이 얼굴·번호판만 검사하고 가린다. 판독 결과·활동 실적·주간 보고에 들어가지 않는다.

    - 품질(흐림·어두움)은 보지 않는다. 참고·증거 사진은 사람이 보는 자료이기 때문이다.
    - fail-closed: 가리지 못하면 저장하지 않고 status=error.
    - 같은 media_id·같은 사진이면 저장된 결과를 돌려주고(meta.replayed), 다른 사진이면 MediaConflict.
    """
    import hashlib
    t0 = time.time()
    if purpose not in MEDIA_PURPOSES:
        raise ValueError(f"purpose는 {sorted(MEDIA_PURPOSES)} 중 하나")
    key = storage.media_photo_id(media_id)
    if key is None:
        raise ValueError("media_id는 영문·숫자·_·- 78자 이하")
    digest = hashlib.sha256(image_bytes).hexdigest()
    records = storage.get_media()
    prev = records.load(key)
    if prev is not None:
        if prev.get("input_sha256") != digest:
            raise MediaConflict(media_id)
        if prev["status"] == "ok":
            out = dict(prev)
            out["meta"] = dict(out.get("meta") or {}, replayed=True)
            out["image_revision"] = storage.get_store().revision(key)
            return _public_media(out)

    rec = {"media_id": media_id, "photo_id": key, "purpose": purpose, "status": None, "masked_image_url": None,
           "image_revision": None, "privacy": None, "notes": [], "input_sha256": digest, "meta": None}
    dev = _device_report(device)
    if dev is None and REQUIRE_DEVICE_MASKING:
        del image_bytes
        rec.update(status="error", notes=["기기에서 가리지 않은 사진은 받지 않음 (앱 업데이트 필요)"])
        return _public_media(_media_meta(rec, t0))
    try:
        img = images.decode_upload(image_bytes)
    except images.ImageRejected as e:
        rec.update(status="error", notes=[f"사진을 받을 수 없음: {e.code}"])
        return _public_media(_media_meta(rec, t0))
    del image_bytes
    try:
        masked, rep = privacy.mask_image(img)
        del img
        jpeg = images.encode_jpeg(images.resize_max(masked, images.AI_MAX_SIDE))
    except Exception as e:
        log.exception("마스킹 실패 media_id=%s", media_id)
        rec.update(status="error", notes=[f"마스킹 실패: {type(e).__name__}"])
        return _public_media(_media_meta(rec, t0))

    priv = rep.as_dict()
    if dev is not None:
        priv.update(mode="device+server", device=dev, server_added=rep.faces + rep.plates + rep.vehicles)
    else:
        priv.update(mode="server_only", device=None, server_added=None)
    store = storage.get_store()
    rec.update(status="ok", privacy=priv, masked_image_url=store.save_masked(key, jpeg))
    rec["image_revision"] = store.revision(key)
    if priv.get("masked_area_ratio", 0) >= MASKED_AREA_WARN:
        rec["notes"].append(f"사진의 {int(priv['masked_area_ratio'] * 100)}%가 가려져 자료로 쓰기 어려울 수 있음")
    rec = _media_meta(rec, t0)
    records.save(rec)
    log.info("media photo_id=%s purpose=%s faces=%s plates=%s vehicles=%s", key, purpose,
             priv["faces"], priv["plates"], priv["vehicles"])
    return _public_media(rec)


def _media_meta(rec: dict, t0: float) -> dict:
    rec["meta"] = {"contract_version": CONTRACT_VERSION, "latency_ms": int((time.time() - t0) * 1000),
                   "created_at": datetime.now(KST).isoformat(timespec="seconds")}
    return rec


def _public_media(rec: dict) -> dict:
    return {k: v for k, v in rec.items() if k != "input_sha256"}
