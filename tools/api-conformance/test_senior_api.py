"""시니어 앱이 쓰는 업무 API가 명세서(docs/API_명세서.md 2·6장)대로 답하는지 확인한다.

검증 콘솔(tools/ai-verify)과 업무 백엔드(Spring Boot) 어느 쪽에도 그대로 돌린다. 시험은 이 파일의 순서대로
진행되며, 장소 A~E(시도 없는 장소 5곳)를 하나씩 맡아 상태를 바꾼다. 실행: README.md
"""
import json

import pytest

from conftest import CAPTURE, FORBIDDEN_KEYS, MOVE_ON, NEXT, PHOTOS, RECEIPT, error_body, keys_deep, senior_body


# ---------------------------------------------------------------- 순회 조회
def test_내_순회_목록(api):
    r = api.get("/me/patrols")
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    assert isinstance(items, list)
    for p in items:
        assert isinstance(p["id"], str) and p["id"]


def test_순회_상세_장소와_구도(patrol):
    assert patrol["stops"], "장소가 없음"
    for s in patrol["stops"]:
        for k in ("id", "name", "captureSlots"):
            assert k in s, f"장소에 {k} 없음: {s}"
        for c in s["captureSlots"]:
            assert c.get("id") and c.get("label"), f"구도에 id·label 없음: {c}"
            assert c.get("receiptStatus", "NONE") in RECEIPT | {"NONE"}, c
    # 장소 조회에 시설 이상 내용은 없다 (명세서 6장)
    leaked = keys_deep(patrol) & (FORBIDDEN_KEYS - {"facility"})
    assert not leaked, f"순회 조회에 판독 내용이 들어 있음: {sorted(leaked)}"


def test_없는_순회는_404(api):
    error_body(api.get("/patrols/conformance-no-such-patrol"), 404)


# ---------------------------------------------------------------- 형식이 틀린 제출은 받지 않는다 (장소 A, 상태를 바꾸지 않음)
@pytest.mark.parametrize("why, over, status", [
    ("제출 ID가 UUID가 아님", {"clientSubmissionId": "not-a-uuid"}, 400),
    ("시도 ID가 UUID가 아님", {"attemptId": "123"}, 400),
    ("기기 마스킹 안 함", {"deviceMasked": "false"}, 400),
    ("마스킹 보고가 JSON이 아님", {"deviceMaskReport": "{"}, 400),
    ("마스킹 보고에 필수 키 없음", {"deviceMaskReport": json.dumps({"faces": 0})}, 400),
    ("촬영 시각 형식이 틀림", {"capturedAt": "어제"}, 400),
    ("다른 장소의 구도", {"captureSlotId": "00000000-0000-4000-8000-00000000dead"}, (400, 404)),
])
def test_형식이_틀린_제출은_거절(submit, stops, why, over, status):
    error_body(submit(stops["A"], **over).send(), status)


def test_사진이_아닌_파일은_415(submit, stops):
    error_body(submit(stops["A"]).send(b"this is not an image"), 415)


def test_10MiB_넘는_사진은_413(submit, stops):
    big = b"\xff\xd8\xff\xe0" + b"\0" * (10 * 1024 * 1024 + 1)
    error_body(submit(stops["A"]).send(big), 413)


# ---------------------------------------------------------------- 품질 실패 (AI 서버의 흐림·어두움 판정, 장소 A·B)
_blur_a = {}


def test_흐린_사진은_사진없이_재촬영(submit, stops):
    s = submit(stops["A"], "blur")
    r = s.send()
    assert r.status_code == 200, r.text   # 저장 없는 품질 실패도 200 (명세서 2·6장)
    b = senior_body(r)
    assert b["receiptStatus"] == "NO_MEDIA", b
    assert b["captureStatus"] == "RETAKE_REQUIRED", b
    assert b.get("nextAction") == "RETAKE", b
    assert b.get("retakesRemaining") == 2, f"첫 품질 실패 뒤 남은 재촬영은 2번: {b}"
    _blur_a.update(sub=s, body=b)


def test_같은_제출을_다시_보내면_같은_결과(stops):
    s, first = _blur_a["sub"], _blur_a["body"]
    r = s.send()
    assert r.status_code == 200, r.text
    b = senior_body(r)
    for k in ("receiptStatus", "captureStatus", "nextAction", "retakesRemaining"):
        assert b.get(k) == first.get(k), f"{k}: 처음 {first.get(k)} / 다시 {b.get(k)} (명세서 6장: 동일 ID·동일 입력은 기존 결과)"


def test_다시_보내도_재촬영_횟수는_한_번만_셈(api, patrol, stops):
    slot = stops["A"]["captureSlots"][0]["id"]
    s = next(c for st in api.get(f"/patrols/{patrol['id']}").json()["stops"] for c in st["captureSlots"] if c["id"] == slot)
    if "retakesRemaining" not in s:
        pytest.skip("순회 상세에 retakesRemaining이 없음 (sync-state로 확인하는 서버)")
    assert s["retakesRemaining"] == 2, s


def test_같은_제출_ID로_다른_사진은_409(submit, stops):
    s = _blur_a["sub"]
    error_body(s.send(PHOTOS["dark"].read_bytes()), 409, "SUBMISSION_CONFLICT")


def test_어두운_사진도_재촬영(submit, stops):
    r = submit(stops["B"], "dark").send()
    assert r.status_code == 200, r.text
    b = senior_body(r)
    assert (b["receiptStatus"], b["captureStatus"], b.get("nextAction")) == ("NO_MEDIA", "RETAKE_REQUIRED", "RETAKE"), b
    assert b.get("message"), "재촬영 안내 문구(message)가 없음"


def test_PNG도_받음(submit, stops):
    # 명세서 6장: JPEG/PNG 허용. 흐린 PNG라 품질 실패로 끝나야 한다 (형식으로 거절하면 안 됨)
    r = submit(stops["E"], "blur_png").send()
    assert r.status_code == 200, f"PNG를 받지 않음: HTTP {r.status_code} {r.text[:200]}"
    assert senior_body(r)["captureStatus"] == "RETAKE_REQUIRED"


# ---------------------------------------------------------------- 재촬영 상한 (장소 C): 최초 + 재촬영 2회, 3번째 품질 실패면 검토로 넘기고 다음으로
def test_세번째_품질_실패는_검토로_넘기고_다음으로(submit, stops):
    left = []
    for i in range(3):
        r = submit(stops["C"], "blur").send()   # 매번 새 제출 ID (실제 새 촬영)
        assert r.status_code == 200, r.text
        b = senior_body(r)
        left.append(b.get("retakesRemaining"))
        if i < 2:
            assert b["captureStatus"] == "RETAKE_REQUIRED", (i, b)
    assert left[:2] == [2, 1], f"남은 재촬영 2 → 1이어야 함: {left}"
    assert b["captureStatus"] == "REVIEW_REQUESTED", b
    assert b.get("nextAction") in MOVE_ON, f"3번째 실패 뒤에는 다음 구도·장소로: {b}"


def test_검토로_넘긴_구도에_새_사진은_409(submit, stops):
    # 명세서 6장: 검토로 인계한 구도에는 새 현장 시도를 자동 접수하지 않는다
    error_body(submit(stops["C"], "blur").send(), 409, "SLOT_RESOLVED")


# ---------------------------------------------------------------- 정상 접수 (장소 D, AI 판독까지)
_good = {}


@pytest.mark.ai
def test_잘_찍은_사진은_접수(submit, stops):
    r = submit(stops["D"], "good").send()
    assert r.status_code in (200, 202), r.text
    b = senior_body(r)
    assert b["receiptStatus"] == "RECEIVED", b
    assert b["captureStatus"] in CAPTURE, b
    assert b.get("inspectionId"), "접수했으면 inspectionId가 있어야 함"
    if b.get("nextAction") is not None:
        assert b["nextAction"] in NEXT, b
    _good.update(body=b)


@pytest.mark.ai
def test_채택된_구도에_새_사진은_중복으로_알림(submit, stops):
    first = _good.get("body")
    if not first or first.get("captureStatus") != "ACCEPTED":
        pytest.skip(f"앞 시험에서 채택되지 않음: {first and first.get('captureStatus')}")
    r = submit(stops["D"], "other").send()
    # 명세서 6장: 채택 완료면 200 + duplicate=true + 기존 inspectionId
    assert r.status_code == 200, f"HTTP {r.status_code} (기대 200 + duplicate=true): {r.text[:200]}"
    b = senior_body(r)
    assert b.get("duplicate") is True, b
    assert b.get("inspectionId") == first["inspectionId"], b
