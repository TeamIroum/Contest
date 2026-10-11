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
import uuid
from datetime import datetime, timedelta, timezone

import numpy as np

import activity
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
REPLAY_STATUSES = ("ok", "needs_review", "retake")

RETAKE_KEY = {
    "blur": "retake_blur",
    "dark": "retake_dark",
    "unreadable": "retake_blur",
    "out_of_frame": "retake_frame",
}



class RequestError(ValueError):
    """요청 값이 계약과 다름 (서버는 400). 사진을 저장하기 전에 확인한다.
    code는 백엔드가 분기에 쓰는 오류 코드 (contract/API_CONTRACT.md의 오류 코드 표)."""

    def __init__(self, message: str, code: str = "INVALID_REQUEST"):
        super().__init__(message)
        self.code = code


class PhotoIdConflict(Exception):
    """같은 photo_id가 이미 다른 종류(판독 ↔ 전후 비교)로 쓰임 (서버는 409)."""


class PhotoHidden(Exception):
    """가림 점검에서 보류된 사진을 비교에 쓰려 함 (서버는 410)."""


class BadModelOutput(RuntimeError):
    """AI 응답이 출력 형식과 다름. AI 실패와 같이 담당자 확인 + 나중에 다시 판독으로 처리한다."""


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
DEVICE_BOX_MAX = 200      # 앱이 보낸 가린 위치는 이만큼만 본다
DEVICE_BOX_COVER = 0.5    # 서버가 찾은 상자의 이 비율 이상을 앱이 이미 가렸으면 '앱이 가린 곳을 다시 찾은 것'


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
    out = {"faces": num(r.get("faces")), "plates": num(r.get("plates")), "vehicles": num(r.get("vehicles")),
           "detector": str(r.get("detector") or "")[:40], "app_version": str(r.get("app_version") or "")[:20]}
    boxes = _device_boxes(r.get("boxes"))
    if boxes is not None:
        out["boxes"] = len(boxes)   # 위치 목록은 기록하지 않고 개수만 남긴다
    return out


def _device_boxes(raw) -> list[list[float]] | None:
    """앱이 가린 위치 [{"kind", "box": [x1, y1, x2, y2]}] (0~1 비율, [x1, y1, x2, y2] 배열만 보내도 됨).
    형식이 틀린 상자는 버린다. 앱이 위치를 보내지 않았으면 None."""
    if not isinstance(raw, list):
        return None
    out = []
    for item in raw[:DEVICE_BOX_MAX]:
        box = item.get("box") if isinstance(item, dict) else item
        try:
            x1, y1, x2, y2 = (min(1.0, max(0.0, float(v))) for v in box)
        except (TypeError, ValueError):
            continue
        if x2 > x1 and y2 > y1:
            out.append([x1, y1, x2, y2])
    return out


def _server_added(rep, device: dict | None) -> dict:
    """저장 전 확인에서 서버가 가린 것 중 앱이 놓친 수(server_added).
    모자이크한 얼굴도 다시 얼굴로 검출되므로, 앱이 가린 위치(boxes)를 보냈으면 그 위치와 대부분 겹치는 검출은
    놓친 것으로 세지 않고 server_redetected로 따로 센다. 가리기는 둘 다 그대로 한다 (안전망).
    위치를 보내지 않은 앱은 예전처럼 서버가 가린 수 전체를 센다."""
    total = rep.faces + rep.plates + rep.vehicles
    boxes = _device_boxes(((device or {}).get("report") or {}).get("boxes"))
    if boxes is None:
        return {"server_added": total}
    again = sum(1 for b in rep.boxes if _union_fraction(b["box"], boxes) >= DEVICE_BOX_COVER)
    return {"server_added": total - again, "server_redetected": again}


def ingest(image_bytes: bytes, photo_id: str, device: dict | None = None) -> tuple[bytes | None, dict]:
    """가린 JPEG과 받기 단계 정보를 돌려준다. 가린 JPEG이 None이면 여기서 끝난 것(재촬영·오류).
    device: {"masked": bool, "report": {faces, plates, detector, app_version}} 앱이 1차로 가렸는지와 그 보고."""
    jpeg, info = mask_upload(image_bytes, photo_id, device)
    del image_bytes
    if jpeg is not None:
        save_ingested(photo_id, jpeg, info)
    return jpeg, info


def save_ingested(photo_id: str, jpeg: bytes, info: dict) -> None:
    info["masked_image_url"] = storage.get_store().save_masked(photo_id, jpeg)
    info["image_revision"] = storage.get_store().revision(photo_id)


def mask_upload(image_bytes: bytes, photo_id: str, device: dict | None = None,
                check_quality: bool = True) -> tuple[bytes | None, dict]:
    """ingest에서 저장만 뺀 것. 사진 두 장을 모두 가린 뒤에 함께 저장해야 할 때(전후 비교) 쓴다."""
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

    if check_quality:
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
        # 기기에서 이미 가린 사진: 서버가 찾아 가린 것 중 앱이 가린 곳과 겹치지 않는 것이 기기 마스킹이 놓친 것
        priv.update(mode="device+server", device=dev, **_server_added(rep, device))
        if priv["server_added"]:
            log.warning("앱 마스킹 누락: 저장 전 확인에서 가림 photo_id=%s count=%d detector=%s",
                        photo_id, priv["server_added"], dev["detector"])
    else:
        priv.update(mode="server_only", device=None, server_added=None)
    info["privacy"] = priv
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
        priv.setdefault("boxes", []).append(
            {"kind": lk["kind"], "source": "ai", "score": 1.0,
             "box": [round(p.x / W, 4), round(p.y / H, 4), round((p.x + p.w) / W, 4), round((p.y + p.h) / H, 4)]})
        count_key = "faces" if lk["kind"] == "face" else "plates"
        priv[count_key] = (priv.get(count_key) or 0) + 1
    priv["masked_area_ratio"] = round(_union_fraction([0.0, 0.0, 1.0, 1.0], [b["box"] for b in priv["boxes"]]), 4)
    new = images.encode_jpeg(img)
    store = storage.get_store()
    store.save_masked(photo_id, new)
    priv["leaks_fixed"] = priv.get("leaks_fixed", 0) + len(leaks)
    storage.get_events().add("image_remasked", photo_id, image_revision=store.revision(photo_id),
                             reason="ai_leak", count=len(leaks))
    log.warning("마스킹 누락을 AI가 발견해 다시 가림 photo_id=%s count=%d kinds=%s",
                photo_id, len(leaks), [lk["kind"] for lk in leaks])
    return new


def _union_fraction(region, boxes, grid: int = 64) -> float:
    """region(정규화 좌표 [x1, y1, x2, y2]) 중 boxes 합집합이 덮는 비율. 상자 여러 개가 조금씩 덮는 경우도
    합쳐서 센다. 격자 점으로 어림하므로 1/grid 정도의 오차가 있다."""
    x1, y1, x2, y2 = region
    if x2 <= x1 or y2 <= y1 or not boxes:
        return 0.0
    xs = x1 + (np.arange(grid) + 0.5) * (x2 - x1) / grid
    ys = y1 + (np.arange(grid) + 0.5) * (y2 - y1) / grid
    gx, gy = np.meshgrid(xs, ys)
    hit = np.zeros(gx.shape, bool)
    for b in boxes:
        hit |= (gx >= b[0]) & (gx <= b[2]) & (gy >= b[1]) & (gy <= b[3])
    return float(hit.mean())


def _hidden_by_mask(bbox, priv: dict | None) -> bool:
    """문제 위치가 가린 영역(모든 가림 상자의 합집합)과 OVERLAP_REVIEW 이상 겹치는지."""
    boxes = [b["box"] for b in (priv or {}).get("boxes") or []]
    return _valid_bbox(bbox) and _union_fraction(bbox, boxes) >= OVERLAP_REVIEW


def _masked_area_note(priv: dict | None, notes: list) -> None:
    ratio = (priv or {}).get("masked_area_ratio") or 0
    if ratio >= MASKED_AREA_WARN:
        notes.append(f"사진의 {int(ratio * 100)}%가 가려져 판독에 지장이 있을 수 있음")


# =====================================================================
# 입력·AI 응답 형식 확인
# =====================================================================
def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _valid_bbox(b) -> bool:
    return (isinstance(b, (list, tuple)) and len(b) == 4
            and all(isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v <= 1 for v in b)
            and b[0] < b[2] and b[1] < b[3])


def _str_list(v) -> bool:
    return isinstance(v, list) and all(isinstance(t, str) for t in v)


def check_context(ctx: dict) -> None:
    """앱·백엔드가 보낸 맥락 값의 형식. 틀리면 RequestError. 사진을 저장하기 전에 부른다."""
    for k in ("lat", "lon"):
        v = ctx.get(k)
        if v is not None and (isinstance(v, bool) or not isinstance(v, (int, float))):
            raise RequestError(f"{k}는 숫자여야 합니다", "INVALID_CONTEXT")
    h = ctx.get("capture_hour")
    if h is not None and not (_is_int(h) and 0 <= h <= 23):
        raise RequestError("capture_hour는 0~23 정수여야 합니다", "INVALID_CONTEXT")
    for k in ("participant_id", "captured_at"):
        if ctx.get(k) is not None and not isinstance(ctx[k], str):
            raise RequestError(f"{k}는 문자열이어야 합니다", "INVALID_CONTEXT")
    if ctx.get("location_tags") is not None and not _str_list(ctx["location_tags"]):
        raise RequestError("location_tags는 문자열 목록이어야 합니다", "INVALID_CONTEXT")
    hist = ctx.get("history")
    if hist is not None:
        if not isinstance(hist, dict):
            raise RequestError("history는 JSON 객체여야 합니다", "INVALID_CONTEXT")
        sev = hist.get("severity", 0)
        if not (_is_int(sev) and 0 <= sev <= 3):
            raise RequestError("history.severity는 0~3 정수여야 합니다", "INVALID_CONTEXT")
    cases = ctx.get("open_cases")
    if cases is not None and not (isinstance(cases, list) and all(isinstance(c, dict) for c in cases)):
        raise RequestError("open_cases는 JSON 객체 목록이어야 합니다", "INVALID_CONTEXT")


def check_prior(prior) -> None:
    """전후 비교에 쓰는 이전 판독 정보의 형식. 틀리면 RequestError."""
    if not isinstance(prior, dict):
        raise RequestError("prior_json은 JSON 객체여야 합니다", "INVALID_PRIOR")
    ftype = prior.get("facility_type")
    f = facility(ftype) if isinstance(ftype, str) else None
    if f is None:
        raise RequestError("prior.facility_type이 시설 목록에 없습니다", "INVALID_PRIOR")
    if prior.get("issue_type") not in f["issues"]:
        raise RequestError("prior.issue_type이 그 시설의 문제 유형에 없습니다", "INVALID_PRIOR")
    sev = prior.get("severity")
    if sev is not None and not (_is_int(sev) and 0 <= sev <= 3):
        raise RequestError("prior.severity는 0~3 정수여야 합니다", "INVALID_PRIOR")
    if prior.get("bbox") is not None and not _valid_bbox(prior["bbox"]):
        raise RequestError("prior.bbox는 0~1 사이 [x1, y1, x2, y2]여야 합니다", "INVALID_PRIOR")
    if prior.get("location_tags") is not None and not _str_list(prior["location_tags"]):
        raise RequestError("prior.location_tags는 문자열 목록이어야 합니다", "INVALID_PRIOR")


def check_analyze_output(raw) -> None:
    """AI 판독 응답이 출력 형식(prompts.analyze_schema)을 지키는지 다시 확인한다. 어기면 BadModelOutput.
    Ollama의 형식 강제가 값 범위·필수 항목까지 늘 지켜 주지는 않기 때문이다."""
    def bad(what):
        raise BadModelOutput(f"AI 응답 형식 오류: {what}")
    if not isinstance(raw, dict):
        bad("JSON 객체가 아님")
    if not isinstance(raw.get("facility_type"), str):
        bad("facility_type")
    for k in ("facility_visible", "framing_ok", "is_night_photo"):
        if not isinstance(raw.get(k), bool):
            bad(k)
    if raw.get("certainty") not in ("high", "medium", "low"):
        bad("certainty")
    if not isinstance(raw.get("issues"), list):
        bad("issues")
    for i in raw["issues"]:
        if not (isinstance(i, dict) and isinstance(i.get("type"), str) and _is_int(i.get("severity"))
                and 1 <= i["severity"] <= 3 and _valid_bbox(i.get("bbox")) and isinstance(i.get("evidence"), str)):
            bad(f"issues 항목 {str(i)[:80]}")
    leaks = raw.setdefault("privacy_leaks", [])
    if not isinstance(leaks, list):
        bad("privacy_leaks")
    for lk in leaks:
        if not (isinstance(lk, dict) and lk.get("kind") in ("face", "plate") and _valid_bbox(lk.get("bbox"))):
            bad(f"privacy_leaks 항목 {str(lk)[:80]}")


def check_verify_output(raw, mode: str) -> None:
    def bad(what):
        raise BadModelOutput(f"AI 응답 형식 오류: {what}")
    if not isinstance(raw, dict):
        bad("JSON 객체가 아님")
    if raw.get("same_facility") not in ("yes", "no", "unclear"):
        bad("same_facility")
    if not isinstance(raw.get("clearly_visible"), bool):
        bad("clearly_visible")
    if raw.get("result") not in prompts.RESULT_ENUMS[mode]:
        bad("result")
    if not (_is_int(raw.get("current_severity")) and 0 <= raw["current_severity"] <= 3):
        bad("current_severity")
    if not isinstance(raw.get("evidence"), str):
        bad("evidence")
    if raw.get("certainty") not in ("high", "medium", "low"):
        bad("certainty")


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
        check_analyze_output(raw)
    except Exception as e:  # 오류·시간 초과·혼잡·응답 형식 오류: 담당자 확인 + 나중에 다시 판독
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
    if ftype == "unknown" or facility(ftype) is None or not raw["facility_visible"]:
        if not rules.rules()["review"]["send_unknown_facility_to_human"]:  # 담당자 대신 어르신께 다시 찍기 안내
            res.update(status="retake", action="retake", senior_message=senior_message("retake_no_facility"))
            res["notes"].append("점검 목록에 없는 대상이거나 시설이 분명하지 않음")
            res["meta"] = _meta(provider, prompts.ANALYZE_VERSION, t0, meta_extra)
            return res
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
    hidden = [i for i in issues if _hidden_by_mask(i["bbox"], priv)]
    _masked_area_note(priv, res["notes"])
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

    if issues and rules.rules()["duplicate"]["same_facility_and_issue_while_open"]:
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
    check_context(ctx)
    photo_id = _photo_id(ctx)
    # 같은 photo_id의 요청은 한 번에 하나씩: 앱이 응답을 기다리다 다시 보내도 AI 판독은 한 번만 하고,
    # 두 번째 요청은 첫 요청이 저장한 결과를 돌려받는다
    with storage.photo_lock(photo_id):
        return _analyze_locked(image_bytes, ctx, provider, photo_id, t0)


def _photo_id(ctx: dict) -> str:
    """요청의 photo_id. 없으면 새로 만들고, 형식이 틀리면 RequestError (조용히 바꾸면 재요청 확인이 깨진다)."""
    pid = ctx.get("photo_id")
    if pid is None or pid == "":
        return storage.safe_photo_id(None)
    if not isinstance(pid, str) or not storage.is_client_photo_id(pid):
        raise RequestError("photo_id는 영문·숫자·_·- 80자 이하이며 m_로 시작할 수 없습니다", "INVALID_PHOTO_ID")
    return pid


def _replay(photo_id: str, want_verify: bool) -> dict | None:
    """같은 photo_id 재요청(네트워크 재시도)이면 저장된 결과를 그대로 돌려준다 → 알림 중복 없음.
    판독 결과가 있는 ID로 전후 비교를 요청하거나 그 반대면 PhotoIdConflict."""
    prev = storage.get_results().load(photo_id)
    if prev is None:
        return None
    if bool(prev.get("mode")) != want_verify:
        raise PhotoIdConflict(photo_id)
    if prev.get("status") in REPLAY_STATUSES:
        prev.setdefault("meta", {})["replayed"] = True
        return prev
    return None


def _analyze_locked(image_bytes: bytes, ctx: dict, provider: Provider, photo_id: str, t0: float) -> dict:
    results = storage.get_results()
    prev = _replay(photo_id, want_verify=False)
    if prev is not None:
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
    with storage.photo_lock(photo_id):  # 그동안 들어온 가림 점검 기록을 덮어쓰지 않도록
        return _reanalyze_locked(photo_id, attempt, _provider(provider))


def _reanalyze_locked(photo_id: str, attempt: int, provider: Provider) -> dict | None:
    t0 = time.time()
    results = storage.get_results()
    prev, ctx = results.load(photo_id), results.load_ctx(photo_id) or {}
    jpeg = storage.get_store().load_masked(photo_id)
    if prev is None or jpeg is None:
        return None
    if activity.is_hidden(photo_id):  # 보류된 사진은 다시 가릴 때까지 AI로 보내지 않는다
        return give_up_retry(photo_id, attempt, "가림 점검에서 보류된 사진이라 다시 판독하지 않음")
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
    with storage.photo_lock(photo_id):
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
        check_verify_output(raw, mode)
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
    # 문제 부위가 이번 사진에서 가려졌으면 AI가 '고쳐짐'으로 잘못 볼 수 있으므로 담당자가 확인한다
    _masked_area_note(priv, res["notes"])
    if res["status"] == "ok" and _hidden_by_mask(prior.get("bbox"), priv):
        res.update(status="needs_review", action="human_review", senior_message=senior_message("human_review"))
        res["notes"].append("문제 위치가 개인정보 가림 영역과 겹침: 담당자 확인 필요")
    extra = {"privacy": priv}
    if attempt:
        extra["retried"] = attempt
    res["meta"] = _meta(provider, prompts.VERIFY_VERSION, t0, extra)
    return res


def verify_photo(before_jpeg: bytes | None, after_bytes: bytes, prior: dict, mode: str = "repair",
                 context: dict | None = None, provider: Provider | None = None) -> dict:
    """prior: {case_id, facility_type, issue_type, severity, bbox, evidence, location_tags?}
    mode: repair(처리 완료 후 수리 확인) | change(미처리 건 변화 비교)
    context의 before_photo_id가 있으면(권장) 처음 판독 때 저장된 가린 사진을 쓴다. before_jpeg를 함께 주면
    그 저장본으로 보고, 비우면 저장소에서 읽는다. before_photo_id가 없으면 before_jpeg는 앱이 올린 사진이므로
    판독 사진과 같은 검사(기기 가림 확인·크기 제한·가림)를 거쳐 m_vb_로 시작하는 이름으로 따로 저장한다.
    """
    t0 = time.time()
    ctx = dict(context or {})
    provider = _provider(provider)
    if mode not in prompts.MODE_INSTRUCTIONS:
        raise RequestError("mode는 repair 또는 change", "INVALID_MODE")
    check_prior(prior)
    check_context(ctx)
    photo_id = _photo_id(ctx)
    with storage.photo_lock(photo_id):
        return _verify_locked(before_jpeg, after_bytes, prior, mode, ctx, provider, photo_id, t0)


def _verify_locked(before_jpeg, after_bytes, prior, mode, ctx, provider, photo_id, t0) -> dict:
    results = storage.get_results()
    prev = _replay(photo_id, want_verify=True)
    if prev is not None:
        return prev

    before_id = ctx.get("before_photo_id")
    if before_id:
        if activity.is_hidden(before_id):
            raise PhotoHidden(before_id)  # 보류된 사진은 비교(AI 전송)에 쓰지 않는다
        if before_jpeg is None:
            before_jpeg = storage.get_store().load_masked(before_id)
            if before_jpeg is None:
                raise RequestError(f"저장된 가린 사진이 없습니다: {before_id}", "PHOTO_NOT_FOUND")
    elif before_jpeg is None:
        raise RequestError("before_photo_id 또는 before 사진 중 하나가 필요합니다", "BEFORE_REQUIRED")

    res = {
        "photo_id": photo_id, "case_id": prior.get("case_id"), "facility_id": prior.get("facility_id"),
        "participant_id": ctx.get("participant_id"),
        "captured_at": ctx.get("captured_at") or datetime.now(KST).isoformat(timespec="seconds"),
        "masked_image_url": None, "image_revision": None, "mode": mode,
        "status": None, "result": None, "action": None, "same_facility": None, "current_severity": None,
        "evidence": None, "new_grade": None, "grade_reasons": [], "senior_message": None,
        "retry": None, "notes": [], "meta": None,
    }

    def stop(info, which: str = ""):
        status, action, msg, note = info["stop"]
        res.update(status=status, action=action, senior_message=senior_message(msg))
        if note:
            res["notes"].append(which + note)
        res["meta"] = _meta(provider, None, t0)
        return res

    # 두 장을 모두 가린 뒤에 함께 저장한다. 한 장이 실패하면 아무것도 저장하지 않는다.
    after, info = mask_upload(after_bytes, photo_id, ctx.get("device"))
    del after_bytes
    if after is None:
        return stop(info)
    if not before_id:
        before_id = storage.MEDIA_PREFIX + "vb_" + uuid.uuid4().hex[:12]
        before_jpeg, before_info = mask_upload(before_jpeg, before_id, ctx.get("device"), check_quality=False)
        if before_jpeg is None:
            return stop(before_info, "수리 전 사진: ")
        save_ingested(before_id, before_jpeg, before_info)
        _save_before_record(before_id, before_info, t0)
    save_ingested(photo_id, after, info)
    res["masked_image_url"] = info["masked_image_url"]
    res["image_revision"] = info["image_revision"]

    before = images.encode_jpeg(images.resize_max(images.decode_jpeg(before_jpeg), images.AI_MAX_SIDE))
    res = _verify_core(res, before, after, prior, mode, provider, t0, info["privacy"])
    results.save(res, {"prior": prior, "mode": mode, "before_photo_id": before_id})
    _log(res, "verify")
    return res


def _save_before_record(before_id: str, info: dict, t0: float) -> None:
    """앱이 직접 올린 수리 전 사진의 가림 기록. 참고·보수 사진 기록과 같은 곳에 두어 가림 점검(보류)을 받을 수 있게 한다.
    판독 결과가 아니므로 활동 실적·주간 보고에는 들어가지 않는다."""
    rec = {"media_id": before_id[len(storage.MEDIA_PREFIX):], "photo_id": before_id, "purpose": "verify_before",
           "status": "ok", "masked_image_url": info["masked_image_url"], "image_revision": info["image_revision"],
           "privacy": info["privacy"], "notes": []}
    storage.get_media().save(_media_meta(rec, t0))


def reverify(photo_id: str, attempt: int = 1, provider: Provider | None = None) -> dict | None:
    with storage.photo_lock(photo_id):
        return _reverify_locked(photo_id, attempt, _provider(provider))


def _reverify_locked(photo_id: str, attempt: int, provider: Provider) -> dict | None:
    t0 = time.time()
    results = storage.get_results()
    prev, ctx = results.load(photo_id), results.load_ctx(photo_id) or {}
    before_id = ctx.get("before_photo_id") or ""
    after = storage.get_store().load_masked(photo_id)
    before = storage.get_store().load_masked(before_id)
    if prev is None or after is None or before is None:
        return None
    if activity.is_hidden(photo_id) or activity.is_hidden(before_id):
        return give_up_retry(photo_id, attempt, "가림 점검에서 보류된 사진이라 다시 비교하지 않음")
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
        priv.update(mode="device+server", device=dev, **_server_added(rep, device))
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
