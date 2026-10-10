"""앱 담당에게 넘길 예시 응답 파일을 만든다. 형식이 바뀌면 다시 실행해서 새 파일을 넘긴다.

실행: python contract/make_fixtures.py
"""
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import pipeline  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

OUT = ROOT / "contract" / "fixtures"
IMG = (ROOT / "eval" / "sample" / "s01.jpg").read_bytes()

ANALYZE_CASES = [
    ("01_normal", "drain_normal", {"lat": 37.82430, "lon": 127.08960}, IMG),
    ("02_observe", "drain_minor", {"location_tags": []}, IMG),
    ("03_caution", "fence_tilted", {"lat": 37.82380, "lon": 127.08850}, IMG),
    ("04_urgent", "sidewalk_danger", {"lat": 37.82410, "lon": 127.08920}, IMG),
    ("05_urgent_duplicate", "exercise_part",
     {"lat": 37.82600, "lon": 127.09200,
      "open_cases": [{"case_id": "C-0412", "facility_id": "EX-0001", "issue_type": "part_detached"}]}, IMG),
    ("05b_urgent_bench", "bench_broken", {"lat": 37.82580, "lon": 127.09180}, IMG),
    ("06_needs_review_urgent", "sidewalk_uncertain", {"location_tags": ["school_route"]}, IMG),
    ("07_needs_review_unknown", "unknown_facility", {}, IMG),
    ("08_retake_frame", "out_of_frame", {}, IMG),
    ("09_retake_dark", None, {}, (ROOT / "eval" / "sample" / "dark.jpg").read_bytes()),
    ("10_error_unreadable", None, {}, b"not an image"),
    ("15_needs_review_retry", "__fail__", {}, IMG),
    ("16_privacy_leak_fixed", "plate_leak", {}, IMG),
]


class _Fail(MockProvider):
    def analyze(self, *a, **k):
        raise TimeoutError("AI 응답 시간 초과")

VERIFY_CASES = [
    ("11_verify_fixed", "fixed", "repair"),
    ("12_verify_not_fixed", "not_fixed", "repair"),
    ("13_verify_different_place", "different_place", "repair"),
    ("14_change_worse", "worse", "change"),
]

DEVICE = {"masked": True, "report": {"faces": 0, "plates": 1, "detector": "yunet+plate-t384+effdet0", "app_version": "1.0.0"}}

PRIOR = {"case_id": "C-0388", "facility_id": "SW-0001", "facility_type": "sidewalk", "issue_type": "lifted", "severity": 3,
         "bbox": [0.31, 0.42, 0.55, 0.70], "evidence": "보행 동선 가운데 블록이 들려 단차가 보임",
         "location_tags": ["school_route"]}


def stable(res: dict, name: str) -> dict:
    res["photo_id"] = f"p_example_{name[:2]}"
    if res.get("masked_image_url"):
        res["masked_image_url"] = f"/images/{res['photo_id']}_masked.jpg"
    if res.get("meta"):
        res["meta"]["latency_ms"] = 2400  # 예시 값
        res["meta"]["created_at"] = "2026-10-08T09:14:03+09:00"
    if res.get("retry") and res["retry"].get("next_at"):
        res["retry"]["next_at"] = 1791427230  # 예시 값
    return res


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = tempfile.mkdtemp()  # 예시 생성용 사진·결과는 임시 폴더에
    storage.set_store(storage.LocalImageStore(tmp + "/images"))
    storage.set_results(storage.LocalResultStore(tmp + "/results"))
    storage.set_media(storage.LocalMediaStore(tmp + "/results/media"))
    storage.set_events(storage.EventLog(tmp + "/results/_events.jsonl"))
    import jobs
    jobs.retry_queue = jobs.RetryQueue()
    for name, scenario, ctx, img in ANALYZE_CASES:
        p = _Fail() if scenario == "__fail__" else MockProvider()
        p.next_scenario = None if scenario == "__fail__" else scenario
        full = dict({"participant_id": "P-001", "captured_at": "2026-10-08T09:14:00+09:00", "device": DEVICE}, **ctx)
        res = stable(pipeline.analyze_photo(img, dict(full, photo_id="ex_" + name), p), name)
        (OUT / f"analyze_{name}.json").write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, scenario, mode in VERIFY_CASES:
        p = MockProvider()
        p.next_scenario = scenario
        res = stable(pipeline.verify_photo(IMG, IMG, PRIOR, mode, {"participant_id": "P-001", "device": DEVICE,
                                                                     "captured_at": "2026-10-08T09:14:00+09:00"}, p), name)
        (OUT / f"verify_{name}.json").write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    # 조회 응답 예시 (위에서 만든 기록으로 계산)
    import activity
    import report
    extra = {
        "participant_activity": activity.participant_activity("P-001", "2026-10-05", "2026-10-11"),
        "facility_history": activity.facility_history("SW-0001"),
        "weekly_report": report.weekly_from_store("2026-10-05", "2026-10-11"),
        "masking_audit_sample": activity.masking_audit_sample("2026-10-05", "2026-10-11", n=5),
    }
    # 업무 백엔드 연동용: 개인정보 단독 검사, 보고 문장, 변경 알림
    media = pipeline.mask_media(IMG, "ref-0001", "reference", DEVICE)
    media["meta"].update(latency_ms=900, created_at="2026-10-08T08:30:00+09:00")
    extra["privacy_mask_reference"] = media
    extra["report_narrative"] = report.write_narrative([
        {"key": "photos", "label": "점검 사진", "value": 1240, "unit": "장"},
        {"key": "facilities", "label": "점검한 고유 시설", "value": 312, "unit": "곳"},
        {"key": "issues_opened", "label": "새로 등록한 사건", "value": 9},
        {"key": "urgent_alerts", "label": "긴급 알림", "value": 2},
        {"key": "review_pending", "label": "담당자 검토 대기", "value": 14},
        {"key": "repair_close_suggested", "label": "수리 확인 제안(담당자 확정 전)", "value": 5},
    ])
    activity.record_audit("m_ref-0001", False, "담당자", "번호판 일부가 보임")
    evs = storage.get_events().since(0, 50)
    for i, e in enumerate(evs["events"]):
        e["at"] = f"2026-10-08T09:{14 + i:02d}:05+09:00"
    extra["events"] = evs
    for name, data in extra.items():
        (OUT / f"get_{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(ANALYZE_CASES) + len(VERIFY_CASES) + len(extra)}개 파일 생성: {OUT}")


if __name__ == "__main__":
    main()
