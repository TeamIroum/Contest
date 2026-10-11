"""참여자별 활동 기록, 시설별 이력, 마스킹 표본 점검.

모두 판독 결과 저장소에 쌓인 기록에서 계산한다. 따로 입력할 것이 없다.

개인정보 원칙
  - 참여자는 이름이 아닌 가명 ID(participant_id)로 기록한다. 이름과의 연결은 수행기관 명부에서만 한다.
  - 이동 경로를 계속 추적하지 않는다. 남는 것은 '언제 어떤 시설을 찍었는가'뿐이다.
"""
import random
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

import storage

_KST = timezone(timedelta(hours=9))


def kst_time(t: str | None) -> str | None:
    """ISO 시각을 한국 시각 문자열로 바꾼다. 앱이 UTC(…Z)로 보내도 날짜·시각이 하루 밀리지 않게 한다.
    시간대가 없는 값은 이미 한국 시각으로 본다. 읽을 수 없으면 받은 값 그대로."""
    if not t:
        return None
    try:
        d = datetime.fromisoformat(t.replace("Z", "+00:00"))
    except ValueError:
        return t
    if d.tzinfo is not None:
        d = d.astimezone(_KST)
    return d.isoformat(timespec="seconds")


def day_of(r: dict) -> str | None:
    t = kst_time(r.get("captured_at") or (r.get("meta") or {}).get("created_at"))
    return t[:10] if t else None


def _in_range(r: dict, start: str | None, end: str | None) -> bool:
    d = day_of(r)
    if d is None:
        return False
    return (start is None or d >= start) and (end is None or d <= end)


def _facility_id(r: dict) -> str | None:
    return (r.get("facility") or {}).get("facility_id") or r.get("facility_id")


# ---------------------------------------------------------------------
# 참여자 활동 기록
# ---------------------------------------------------------------------
def participant_activity(participant_id: str, start: str | None = None, end: str | None = None) -> dict:
    """참여자 한 명의 활동 실적. 날짜는 'YYYY-MM-DD'."""
    rows = [r for r in storage.get_results().all()
            if r.get("participant_id") == participant_id and _in_range(r, start, end)]
    by_day = defaultdict(list)
    for r in rows:
        by_day[day_of(r)].append(r)

    days = []
    for d in sorted(by_day):
        rs = by_day[d]
        times = sorted(kst_time(r["captured_at"])[11:16] for r in rs if r.get("captured_at"))
        days.append({
            "date": d,
            "photos": len(rs),
            "facilities": len({_facility_id(r) for r in rs if _facility_id(r)}),
            "first_capture": times[0] if times else None,   # 그날 첫 촬영
            "last_capture": times[-1] if times else None,   # 그날 마지막 촬영
            "repair_checks": sum(1 for r in rs if r.get("mode") == "repair"),
        })

    grades = Counter(r.get("grade") for r in rows if r.get("grade"))
    return {
        "participant_id": participant_id,
        "period": {"start": start, "end": end},
        "days_active": len(days),
        "photos": len(rows),
        "facilities_checked": len({_facility_id(r) for r in rows if _facility_id(r)}),
        "retakes": sum(1 for r in rows if r.get("status") == "retake"),
        "repair_checks": sum(1 for r in rows if r.get("mode") == "repair"),
        "found": {g: grades.get(g, 0) for g in ("긴급", "주의", "관찰")},
        "days": days,
        "note": "활동 시간은 그날 첫 촬영과 마지막 촬영 시각 기준이며, 이동 경로는 기록하지 않습니다.",
    }


def all_participants(start: str | None = None, end: str | None = None) -> list[dict]:
    ids = sorted({r["participant_id"] for r in storage.get_results().all()
                  if r.get("participant_id") and _in_range(r, start, end)})
    return [participant_activity(pid, start, end) for pid in ids]


# ---------------------------------------------------------------------
# 시설별 이력
# ---------------------------------------------------------------------
def facility_history(facility_id: str) -> dict:
    """한 시설의 판독·수리 확인 기록을 시간 순서로."""
    rows = [r for r in storage.get_results().all() if _facility_id(r) == facility_id]
    rows.sort(key=lambda r: r.get("captured_at") or "")
    events = []
    for r in rows:
        if r.get("mode"):
            events.append({"kind": "verify", "at": r.get("captured_at"), "photo_id": r["photo_id"],
                           "mode": r["mode"], "result": r.get("result"), "action": r.get("action"),
                           "case_id": r.get("case_id"), "masked_image_url": r.get("masked_image_url")})
        else:
            events.append({"kind": "inspect", "at": r.get("captured_at"), "photo_id": r["photo_id"],
                           "grade": r.get("grade"), "issues": [i.get("label") for i in r.get("issues") or []],
                           "action": r.get("action"), "masked_image_url": r.get("masked_image_url")})
    grades = [e["grade"] for e in events if e["kind"] == "inspect" and e.get("grade")]
    return {
        "facility_id": facility_id,
        "events": events,
        "inspections": len(grades),
        "problems_found": sum(1 for g in grades if g != "정상"),
        "repairs_confirmed": sum(1 for e in events if e["kind"] == "verify" and e.get("result") == "fixed"),
        "re_requests": sum(1 for e in events if e["kind"] == "verify" and e.get("result") == "not_fixed"),
    }


# ---------------------------------------------------------------------
# 마스킹 표본 점검
# ---------------------------------------------------------------------
def masking_audit_sample(start: str | None = None, end: str | None = None, n: int = 20, seed: str = "") -> list[dict]:
    """담당자가 가림 상태를 눈으로 확인할 사진 목록.
    서버 저장 전 확인이나 AI가 누락을 찾아 가린 사진은 모두 넣고, 나머지는 무작위로 채운다."""
    rows = [r for r in storage.get_results().all()
            if r.get("masked_image_url") and _in_range(r, start, end) and not r.get("privacy_audit")]

    def flagged(r):
        p = (r.get("meta") or {}).get("privacy") or {}
        return (p.get("server_added") or 0) > 0 or (p.get("leaks_fixed") or 0) > 0 or p.get("mode") == "server_only"

    must = [r for r in rows if flagged(r)]
    rest = [r for r in rows if not flagged(r)]
    random.Random(f"{start}{end}{seed}").shuffle(rest)
    picked = must + rest[:max(0, n - len(must))]
    out = []
    for r in picked:
        p = (r.get("meta") or {}).get("privacy") or {}
        out.append({"photo_id": r["photo_id"], "masked_image_url": r["masked_image_url"],
                    "captured_at": r.get("captured_at"), "reason": "누락 보정 이력" if flagged(r) else "무작위 표본",
                    "mode": p.get("mode"), "faces": p.get("faces"), "plates": p.get("plates"),
                    "server_added": p.get("server_added"), "leaks_fixed": p.get("leaks_fixed")})
    return out


def _audit_store(photo_id: str):
    """판독 사진이면 결과 저장소, 참고·보수 사진(m_로 시작)이면 개인정보 검사 기록 저장소."""
    if storage.get_results().load(photo_id) is not None:
        return storage.get_results()
    if photo_id.startswith(storage.MEDIA_PREFIX) and storage.get_media().load(photo_id) is not None:
        return storage.get_media()
    return None


def record_audit(photo_id: str, ok: bool, reviewer: str | None = None, note: str | None = None) -> dict | None:
    """점검 결과 기록. 가림이 부족하다고(ok=False) 하면 그 사진은 다시 가릴 때까지 내려주지 않는다.
    보류·해제는 GET /events에 image_hidden / image_unhidden으로 남아 백엔드 복사본에도 전달된다."""
    if not isinstance(ok, bool):  # "false" 같은 문자열을 '문제 없음'으로 읽어 보류를 풀지 않도록
        raise ValueError("ok는 true 또는 false여야 합니다")
    st = _audit_store(photo_id)
    if st is None:
        return None
    with storage.photo_lock(photo_id):  # 진행 중인 재판독이 끝난 뒤에 기록해, 재판독 저장이 보류를 덮어쓰지 않게
        was_hidden = bool((st.load(photo_id).get("privacy_audit") or {}).get("hidden"))
        audit = {"ok": ok, "reviewer": reviewer, "note": note,
                 "at": datetime.now(storage_tz()).isoformat(timespec="seconds"), "hidden": not ok}
        r = st.update(photo_id, privacy_audit=audit)
    rev = storage.get_store().revision(photo_id)
    if not ok and not was_hidden:
        storage.get_events().add("image_hidden", photo_id, image_revision=rev, reason=note)
    elif ok and was_hidden:
        storage.get_events().add("image_unhidden", photo_id, image_revision=rev, reason=note)
    return r


def is_hidden(photo_id: str) -> bool:
    st = _audit_store(photo_id)
    r = st.load(photo_id) if st else None
    return bool(r and (r.get("privacy_audit") or {}).get("hidden"))


def storage_tz():
    from pipeline import KST
    return KST
