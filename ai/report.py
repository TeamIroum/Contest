"""주간 실적 보고 작성.

숫자는 항상 코드가 센다. LLM은 문장만 다듬고, 문장 속 숫자가 계산값과 다르면 버리고 템플릿 문장을 쓴다.
기획안의 'AI 문장 생성 (계산값만 인용)'을 실제로 보장하는 부분이다.
"""
import re
from collections import Counter

from providers import Provider


def weekly_stats(analyze_results: list[dict], verify_results: list[dict] | None = None,
                 corrections: int = 0, seniors: int | None = None) -> dict:
    verify_results = verify_results or []
    if seniors is None:  # 참여자 ID가 기록돼 있으면 직접 센다
        ids = {r.get("participant_id") for r in analyze_results + verify_results if r.get("participant_id")}
        seniors = len(ids) or None
    grades = Counter(r["grade"] for r in analyze_results if r.get("status") == "ok" and r.get("grade"))
    repair = [v for v in verify_results if v.get("mode") == "repair" and v.get("status") == "ok"]
    stats = {
        "photos": len(analyze_results),
        "urgent": grades.get("긴급", 0),
        "caution": grades.get("주의", 0),
        "observe": grades.get("관찰", 0),
        "normal": grades.get("정상", 0),
        "needs_review": sum(1 for r in analyze_results if r.get("status") == "needs_review"),
        "retake": sum(1 for r in analyze_results if r.get("status") == "retake"),
        "duplicates": sum(1 for r in analyze_results if r.get("duplicate_of")),
        "repair_checked": len(repair),
        "repair_fixed": sum(1 for v in repair if v["result"] == "fixed"),
        "repair_rerequest": sum(1 for v in repair if v["result"] == "not_fixed"),
        "corrections": corrections,
    }
    if seniors is not None:
        stats["seniors"] = seniors
    return stats


PRIORITY_TAGS = {"school_route": 2, "child_facility": 2, "roadside": 1}
ACTION_RANK = {"alert_now": 0, "human_review_urgent": 1, "weekly_list": 2}


def priority_list(analyze_results: list[dict], verify_results: list[dict] | None = None, limit: int = 20) -> list[dict]:
    """먼저 처리할 곳 목록.
    순서: 긴급 → 확인이 필요한 잠정 긴급 → 주의, 같은 등급 안에서는 통학로·어린이 시설·차도 인접, 악화, 오래된 순.
    중복으로 묶인 건과, 그 뒤에 수리 확인(close_case)된 시설은 뺀다."""
    closed_after = {}
    for v in verify_results or []:
        if v.get("action") == "close_case" and v.get("facility_id"):
            closed_after[v["facility_id"]] = max(closed_after.get(v["facility_id"], ""), v.get("captured_at") or "")
    items = []
    for r in analyze_results:
        if r.get("action") not in ACTION_RANK or r.get("duplicate_of"):
            continue
        fac = r.get("facility") or {}
        fid = fac.get("facility_id")
        if fid and closed_after.get(fid, "") > (r.get("captured_at") or ""):
            continue
        tags = fac.get("location_tags") or []
        worsened = any("나빠짐" in g for g in r.get("grade_reasons") or [])
        items.append({
            "photo_id": r["photo_id"], "facility_id": fid, "facility": fac.get("label"), "place": fac.get("place"),
            "grade": r.get("grade"), "action": r.get("action"),
            "issue": ", ".join(i.get("label", "") for i in r.get("issues") or []),
            "dept": r.get("dept"), "captured_at": r.get("captured_at"), "location_tags": tags, "worsened": worsened,
            "masked_image_url": r.get("masked_image_url"),
            "_key": (ACTION_RANK[r["action"]], -sum(PRIORITY_TAGS.get(t, 0) for t in tags), -int(worsened),
                     r.get("captured_at") or ""),
        })
    items.sort(key=lambda x: x["_key"])
    for i, it in enumerate(items, 1):
        it.pop("_key")
        it["rank"] = i
    return items[:limit]


def template_report(s: dict) -> str:
    lines = [
        f"이번 주 점검 사진은 {s['photos']}장입니다.",
        f"긴급 알림 {s['urgent']}건, 주의 전달 {s['caution']}건, 관찰 {s['observe']}건, 정상 {s['normal']}건으로 판정했습니다.",
        f"담당자 확인으로 넘긴 사진은 {s['needs_review']}장, 재촬영은 {s['retake']}장, 기존 접수 건에 묶은 사진은 {s['duplicates']}장입니다.",
        f"수리 확인은 {s['repair_checked']}건을 진행해 {s['repair_fixed']}건을 확인하고 {s['repair_rerequest']}건을 재요청했습니다.",
        f"담당자가 판정을 고친 건은 {s['corrections']}건입니다.",
    ]
    if "seniors" in s:
        lines.append(f"참여 어르신은 {s['seniors']}명입니다.")
    return "\n".join(lines)


def numbers_in(text: str) -> list[int]:
    return [int(n.replace(",", "")) for n in re.findall(r"\d[\d,]*", text)]


def numbers_match(text: str, stats: dict) -> bool:
    """문장 속 모든 숫자가 계산값 중 하나여야 한다."""
    allowed = {int(v) for v in stats.values() if isinstance(v, (int, float))}
    return all(n in allowed for n in numbers_in(text))


def write_report(stats: dict, provider: Provider | None = None) -> dict:
    base = template_report(stats)
    if provider is None or provider.name == "mock":
        return {"text": base, "source": "template"}
    prompt = (
        "다음은 시청 시설 담당 부서에 보낼 주간 점검 실적이다. 공문 보고체로 3~5문장으로 다듬어라.\n"
        "숫자는 아래 문장에 있는 숫자만 그대로 쓰고, 새 숫자(비율, 합계, 날짜 포함)를 만들지 마라.\n\n" + base
    )
    try:
        text = provider.generate_text(prompt)
    except Exception:
        return {"text": base, "source": "template"}
    if numbers_match(text, stats):
        return {"text": text, "source": "llm"}
    return {"text": base, "source": "template", "note": "생성 문장의 숫자가 계산값과 달라 템플릿 사용"}


def weekly_from_store(start: str, end: str, corrections: int = 0, provider: Provider | None = None) -> dict:
    """결과 저장소에서 기간(YYYY-MM-DD~YYYY-MM-DD)의 기록을 모아 주간 보고를 만든다."""
    import storage

    def day(r):
        t = r.get("captured_at") or (r.get("meta") or {}).get("created_at") or ""
        return t[:10]

    rows = [r for r in storage.get_results().all() if start <= day(r) <= end]
    analyze = [r for r in rows if not r.get("mode")]
    verify = [r for r in rows if r.get("mode")]
    stats = weekly_stats(analyze, verify, corrections)
    return {"period": {"start": start, "end": end}, "stats": stats,
            "priority": priority_list(analyze, verify), **write_report(stats, provider)}


# ---------------------------------------------------------------------
# 집계값 → 보고 문장 (백엔드 DB 집계용)
# ---------------------------------------------------------------------
MAX_ITEMS = 30
_LABEL_RE = re.compile(r"^[^\n\r]{1,40}$")


class NarrativeInputError(ValueError):
    pass


def validate_items(items) -> list[dict]:
    """[{key, label, value, unit}] 형식 확인. 숫자는 0 이상의 정수만 받는다(비율·소수는 백엔드가 문장 밖에서 표시)."""
    if not isinstance(items, list) or not items:
        raise NarrativeInputError("items는 비어 있지 않은 목록이어야 합니다")
    if len(items) > MAX_ITEMS:
        raise NarrativeInputError(f"items는 {MAX_ITEMS}개 이하")
    out, keys = [], set()
    for i, it in enumerate(items):
        if not isinstance(it, dict):
            raise NarrativeInputError(f"items[{i}]는 객체여야 합니다")
        key, label, value, unit = it.get("key"), it.get("label"), it.get("value"), it.get("unit", "건")
        if not isinstance(key, str) or not re.match(r"^[A-Za-z0-9_]{1,40}$", key) or key in keys:
            raise NarrativeInputError(f"items[{i}].key가 없거나 중복 (영문·숫자·_)")
        if not isinstance(label, str) or not _LABEL_RE.match(label):
            raise NarrativeInputError(f"items[{i}].label은 한 줄 40자 이하")
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise NarrativeInputError(f"items[{i}].value는 0 이상의 정수")
        if not isinstance(unit, str) or len(unit) > 5 or re.search(r"\d", unit):
            raise NarrativeInputError(f"items[{i}].unit은 숫자 없는 5자 이하")
        keys.add(key)
        out.append({"key": key, "label": label.strip(), "value": value, "unit": unit})
    return out


def template_narrative(items: list[dict]) -> str:
    return "\n".join(f"{it['label']}: {it['value']:,}{it['unit']}" for it in items)


def narrative_ok(text: str, items: list[dict], base: str) -> bool:
    """생성 문장 검사.
    1) 문장 속 모든 숫자가 입력(항목 값, 이름·단위에 들어 있던 숫자) 중 하나여야 한다 → 새 숫자 금지
    2) 모든 항목 값이 문장에 한 번 이상 나와야 한다 → 빠뜨리기 금지"""
    allowed = set(numbers_in(base))
    found = numbers_in(text)
    return all(n in allowed for n in found) and all(it["value"] in found for it in items)


def write_narrative(items, provider: Provider | None = None, audience: str = "시청 시설 담당 부서") -> dict:
    items = validate_items(items)
    base = template_narrative(items)
    result = {"items": items, "template": base}
    if provider is None or provider.name == "mock":
        return {**result, "text": base, "source": "template"}
    lines = "\n".join(f"- {it['label']}: {it['value']:,}{it['unit']}" for it in items)
    prompt = (
        f"다음은 {audience}에 보낼 점검 실적 집계값이다. 공문 보고체로 3~6문장으로 정리하라.\n"
        "규칙:\n"
        "1. 아래 항목의 숫자를 모두, 그대로 쓴다. 하나도 빼지 않는다.\n"
        "2. 새 숫자(합계, 비율, 증감, 날짜, 순번)를 만들지 않는다.\n"
        "3. 항목 이름의 뜻을 바꾸지 않는다. 예를 들어 '수리 확인 제안'을 '수리 완료'로 쓰지 않는다.\n"
        "4. 문장만 출력한다. 제목·목록·따옴표를 쓰지 않는다.\n\n" + lines
    )
    try:
        text = provider.generate_text(prompt).strip()
    except Exception as e:
        return {**result, "text": base, "source": "template", "note": f"문장 생성 실패: {type(e).__name__}"}
    if text and narrative_ok(text, items, base):
        return {**result, "text": text, "source": "llm"}
    return {**result, "text": base, "source": "template", "note": "생성 문장의 숫자가 집계값과 달라 템플릿 사용"}
