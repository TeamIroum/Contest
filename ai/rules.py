"""위험도 등급 계산. 결과가 늘 같아야 하므로 AI가 아니라 코드가 한다.

입력: AI가 판단한 시설·문제·정도 + 시설 대장의 위치 태그 + 이전 기록 대비 악화 여부
출력: 4단계 등급과 그 근거 문장 목록 (담당자 화면의 '판정 근거'에 그대로 쓴다)
수치는 rules.json에 있고, 이 파일은 그 규칙을 적용만 한다.
"""
from config import facility, grade_index, grade_name, issue_label, rules, taxonomy


def filter_issues(ftype: str, issues: list[dict], is_night_photo: bool = False,
                  capture_hour: int | None = None) -> tuple[list[dict], list[str]]:
    """시설에 맞지 않는 문제 유형(예: 빗물받이에 '들뜸')을 걸러낸다.
    is_night_photo, capture_hour는 지금 규칙에서는 쓰지 않지만, 시간대별 규칙을 다시 넣을 때를 위해 받아 둔다."""
    f = facility(ftype)
    kept, notes = [], []
    for issue in issues:
        if f is None or issue["type"] not in f["issues"]:
            notes.append(f"시설과 맞지 않는 문제 유형 제외: {issue['type']}")
            continue
        kept.append(issue)
    return kept, notes


def compute_grade(ftype: str, issues: list[dict], location_tags: list[str] | None = None,
                  worsened: bool = False) -> tuple[str, list[str]]:
    r = rules()
    tag_labels = taxonomy()["location_tags"]
    location_tags = location_tags or []

    if not issues:
        return grade_name(0), ["문제가 보이지 않음"]

    top = max(issues, key=lambda i: i["severity"])
    severity = top["severity"]
    level = grade_index(r["base_grade_by_severity"][str(severity)])
    reasons = [f"{issue_label(ftype, top['type'])}: {top['evidence']} (AI 판단 정도 {severity}/3)"]

    loc = r["location_bump"]
    if severity >= loc["min_severity"]:
        hits = [t for t in location_tags if t in loc["tags"]]
        steps = min(len(hits), loc["max_steps"])
        if steps:
            level += steps
            names = ", ".join(tag_labels.get(t, t) for t in hits)
            reasons.append(f"{names} 위치 가중치 적용")

    wb = r["worsened_bump"]
    if worsened and severity >= wb["min_severity"]:
        level += wb["steps"]
        reasons.append("이전 기록보다 상태가 나빠짐")

    level = min(level, len(taxonomy()["grades"]) - 1)

    for issue in issues:
        key = f"{ftype}.{issue['type']}"
        floor = r["min_grade_by_issue"].get(key)
        if floor and grade_index(floor) > level:
            level = grade_index(floor)
            reasons.append(f"'{issue_label(ftype, issue['type'])}'은(는) 최소 {floor} 등급")

    return grade_name(level), reasons


def action_for_grade(grade: str) -> str:
    return taxonomy()["grade_actions"][grade]


SENIOR_KEY_BY_GRADE = {
    "긴급": "danger_keep_away",
    "주의": "caution_reported",
    "관찰": "observe_recheck",
    "정상": "normal_recorded",
}
