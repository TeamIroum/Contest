"""계약 예시 응답(ai/contract/fixtures)은 계약 검사를 모두 통과해야 한다.

실행: python -m pytest -q tools/ai-verify/tests
"""
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import checks  # noqa: E402
from ai_client import to_snake  # noqa: E402

FIXTURES = ROOT.parents[1] / "ai" / "contract" / "fixtures"
TAXONOMY = json.loads((ROOT.parents[1] / "ai" / "taxonomy.json").read_text(encoding="utf-8"))
DEVICE = {"faces": 0, "plates": 1, "detector": "yunet+plate-t384+effdet0", "app_version": "1.0.0"}
LATLON = {"01_normal", "03_caution", "04_urgent", "05_urgent_duplicate", "05b_urgent_bench"}


def _load(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _fails(items):
    return [c for c in items if c["level"] == "fail"]


@pytest.mark.parametrize("path", sorted(FIXTURES.glob("analyze_*.json")), ids=lambda p: p.stem)
def test_analyze_fixtures_pass(path):
    resp = _load(path.name)
    name = path.stem.removeprefix("analyze_")
    sent = {"photo_id": resp["photo_id"], "device_masked": True, "device_report": DEVICE,
            "has_latlon": name in LATLON, "open_case_ids": ["C-0412"]}
    assert _fails(checks.check_analyze(resp, sent, TAXONOMY, 200, 2400)) == []


@pytest.mark.parametrize("path", sorted(FIXTURES.glob("verify_*.json")), ids=lambda p: p.stem)
def test_verify_fixtures_pass(path):
    resp = _load(path.name)
    sent = {"photo_id": resp["photo_id"], "mode": resp["mode"], "case_id": "C-0388", "device_masked": True,
            "device_report": DEVICE}
    assert _fails(checks.check_verify(resp, sent, 200, 2400)) == []


def test_privacy_and_narrative_fixtures_pass():
    resp = _load("get_privacy_mask_reference.json")
    assert _fails(checks.check_privacy_mask(resp, {"media_id": "ref-0001", "purpose": "reference"})) == []
    resp = _load("get_report_narrative.json")
    assert _fails(checks.check_narrative(resp, resp["items"])) == []


def test_detects_contract_violations():
    resp = _load("analyze_04_urgent.json")
    bad = dict(resp, action="weekly_list", image_revision=None)
    bad["meta"] = dict(resp["meta"], contract_version="1.0")
    ids = {c["id"] for c in _fails(checks.check_analyze(bad, {"has_latlon": True}, TAXONOMY))}
    assert {"grade_action", "contract_version", "revision_pair"} <= ids

    bad = dict(resp, issues=[{"type": "covered", "severity": 5, "bbox": [0.5, 0.1, 0.2, 0.9]}])
    ids = {c["id"] for c in _fails(checks.check_analyze(bad, {"has_latlon": True}, TAXONOMY))}
    assert {"issue0_type", "issue0_severity", "issue0_bbox"} <= ids

    v = _load("verify_13_verify_different_place.json")
    bad = dict(v, action="re_request")
    ids = {c["id"] for c in _fails(checks.check_verify(bad, {"mode": "repair"}))}
    assert "retake_no_rerequest" in ids


def test_narrative_number_check():
    items = [{"key": "a", "label": "사진", "value": 1240}, {"key": "b", "label": "긴급", "value": 2}]
    ok = checks.check_narrative({"source": "llm", "text": "사진 1,240장, 긴급 2건"}, items)
    assert _fails(ok) == []
    bad = checks.check_narrative({"source": "llm", "text": "사진 1,240장, 긴급 3건"}, items)
    assert {"no_new_numbers", "no_missing_numbers"} <= {c["id"] for c in _fails(bad)}


def test_device_report_snake_case():
    assert to_snake({"faces": 1, "appVersion": "1.0.0"}) == {"faces": 1, "app_version": "1.0.0"}


def test_location_tags_only_checked_for_known_facility():
    # 목록 외 시설(unknown)은 facility에 위치 태그가 없다 (계약 예시 07). 보낸 태그가 있어도 위반이 아님
    sent = {"location_tags": ["school_route"], "has_latlon": False}
    unknown = _load("analyze_07_needs_review_unknown.json")
    assert "location_tags" not in {c["id"] for c in _fails(checks.check_analyze(unknown, sent, TAXONOMY))}
    # 아는 시설인데 태그가 빠지면 위반
    known = _load("analyze_01_normal.json")
    known["facility"]["location_tags"] = []
    assert "location_tags" in {c["id"] for c in _fails(checks.check_analyze(known, sent, TAXONOMY))}
