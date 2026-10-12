"""핵심 규칙 테스트. 규칙표(rules.json)를 바꾸면 여기 기대값도 같이 바꾼다.

실행: python -m pytest -q
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import report  # noqa: E402
import rules  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

SAMPLE = Path(__file__).resolve().parent.parent / "eval" / "sample"
IMG = (SAMPLE / "s01.jpg").read_bytes()


def issue(t, s):
    return {"type": t, "severity": s, "evidence": "테스트"}


# ---- 등급 규칙 ----
def test_no_issue_is_normal_even_on_school_route():
    assert rules.compute_grade("sidewalk", [], ["school_route"])[0] == "정상"


def test_severity_maps_to_base_grade():
    assert rules.compute_grade("sidewalk", [issue("lifted", 1)])[0] == "관찰"
    assert rules.compute_grade("sidewalk", [issue("lifted", 2)])[0] == "주의"
    assert rules.compute_grade("sidewalk", [issue("lifted", 3)])[0] == "긴급"


def test_location_bump_only_from_severity_2():
    assert rules.compute_grade("sidewalk", [issue("lifted", 1)], ["school_route"])[0] == "관찰"
    assert rules.compute_grade("sidewalk", [issue("lifted", 2)], ["school_route"])[0] == "긴급"


def test_location_bump_capped_at_one_step():
    g, reasons = rules.compute_grade("drain", [issue("covered", 2)], ["school_route", "roadside"])
    assert g == "긴급" and any("위치 가중치" in r for r in reasons)


def test_worsened_bump():
    assert rules.compute_grade("sidewalk", [issue("lifted", 1)], worsened=True)[0] == "주의"


def test_min_grade_by_issue():
    assert rules.compute_grade("drain", [issue("cover_missing", 1)])[0] == "긴급"


def test_part_detached_min_grade():
    for ftype in ("bench", "exercise"):
        assert rules.compute_grade(ftype, [issue("part_detached", 1)])[0] == "주의"
    assert rules.compute_grade("fence", [issue("joint_detached", 1)])[0] == "주의"


def test_five_facilities_only():
    from config import taxonomy
    assert set(taxonomy()["facilities"]) == {"sidewalk", "drain", "bench", "fence", "exercise"}


def test_issue_not_matching_facility_dropped():
    kept, _ = rules.filter_issues("drain", [issue("lifted", 3)], is_night_photo=False)
    assert kept == []


# ---- 파이프라인 (mock) ----
def run(scenario, **ctx):
    p = MockProvider()
    p.next_scenario = scenario
    return pipeline.analyze_photo(IMG, ctx, p)


def test_urgent_flow():
    r = run("sidewalk_danger", location_tags=[])
    assert (r["status"], r["grade"], r["action"], r["dept"]) == ("ok", "긴급", "alert_now", "도로 담당")
    assert r["senior_message"]["key"] == "danger_keep_away"


def test_low_certainty_goes_to_human_with_safety_message():
    r = run("sidewalk_uncertain", location_tags=["school_route"])
    assert r["status"] == "needs_review" and r["grade"] == "긴급"
    assert r["action"] == "human_review_urgent"
    assert r["senior_message"]["key"] == "danger_keep_away"


def test_out_of_frame_is_retake():
    r = run("out_of_frame")
    assert r["status"] == "retake" and r["senior_message"]["key"] == "retake_frame"


def test_unknown_facility_is_human_review_not_retake():
    r = run("unknown_facility")
    assert r["status"] == "needs_review" and r["action"] == "human_review"


def test_no_facility_with_bad_frame_is_not_retake():
    # 대상이 없으면 틀 판단은 보지 않는다 (보면 어르신이 같은 곳을 계속 다시 찍게 됨)
    r = run("unknown_bad_frame")
    assert r["status"] == "needs_review" and "out_of_frame" not in r["quality"]["problems"]


def test_bench_and_exercise_flow():
    r = run("bench_broken", lat=37.82580, lon=127.09180)
    assert r["facility"]["facility_id"] == "BN-0001"
    assert (r["grade"], r["dept"]) == ("긴급", "공원 담당")
    r = run("exercise_part", lat=37.82600, lon=127.09200)
    assert r["facility"]["facility_id"] == "EX-0001" and r["grade"] == "긴급"
    assert any("어린이 시설" in g for g in r["grade_reasons"])


def test_fence_on_school_roadside_bumped():
    r = run("fence_tilted", lat=37.82500, lon=127.09050)
    assert r["facility"]["facility_id"] == "FN-0002" and r["grade"] == "긴급"
    assert r["dept"] == "도로 담당"


def test_dark_photo_skips_ai():
    class Boom(MockProvider):
        def analyze(self, *a, **k):
            raise AssertionError("어두운 사진에 AI를 부르면 안 됨")
    r = pipeline.analyze_photo((SAMPLE / "dark.jpg").read_bytes(), {}, Boom())
    assert r["status"] == "retake" and r["quality"]["problems"] == ["dark"]


def test_api_error_does_not_crash():
    class Fail(MockProvider):
        def analyze(self, *a, **k):
            raise TimeoutError("시간 초과")
    r = pipeline.analyze_photo(IMG, {}, Fail())
    assert r["status"] == "needs_review"


def test_ledger_match_and_duplicate():
    r = run("sidewalk_danger", lat=37.82410, lon=127.08920,
            open_cases=[{"case_id": "C-1", "facility_id": "SW-0001", "issue_type": "lifted"}])
    assert r["facility"]["facility_id"] == "SW-0001"
    assert "school_route" in r["facility"]["location_tags"]
    assert r["duplicate_of"] == "C-1"


def test_worsened_from_history():
    r = run("drain_minor", location_tags=[], history={"severity": 0, "issue_type": "covered"})
    assert r["grade"] == "주의"


# ---- 수리 확인 ----
PRIOR = {"case_id": "C-9", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3,
         "bbox": [0.3, 0.4, 0.5, 0.7], "evidence": "블록 들뜸"}


def verify(scenario, mode="repair"):
    p = MockProvider()
    p.next_scenario = scenario
    return pipeline.verify_photo(IMG, IMG, PRIOR, mode, {}, p)


def test_verify_fixed_closes():
    assert verify("fixed")["action"] == "close_case"


def test_verify_not_fixed_rerequests():
    assert verify("not_fixed")["action"] == "re_request"


def test_verify_different_place_never_rerequests():
    r = verify("different_place")
    assert r["action"] == "retake" and r["senior_message"]["key"] == "verify_retake"


def test_change_mode_worse_regrades():
    r = verify("worse", mode="change")
    assert r["action"] == "regrade" and r["new_grade"] == "긴급"


# ---- 보고서 숫자 검증 ----
def test_numbers_match():
    s = report.weekly_stats([])
    s.update(photos=1284, urgent=1, caution=2, retake=5)
    ok = report.template_report(s).replace("\n", " ")
    assert report.numbers_match(ok, s)
    assert not report.numbers_match(ok.replace("1284", "1285"), s)                          # 새 숫자
    swapped = ok.replace("긴급 알림 1건, 주의 전달 2건", "긴급 알림 2건, 주의 전달 1건")
    assert swapped != ok and not report.numbers_match(swapped, s)                             # 서로 바꿔 씀
    assert not report.numbers_match(ok.replace("재촬영은 5장", "재촬영은 다섯 장"), s)          # 한글 숫자
    assert not report.numbers_match(ok.replace("이번 주 점검 사진은 1284장입니다.", ""), s)   # 빠뜨림
