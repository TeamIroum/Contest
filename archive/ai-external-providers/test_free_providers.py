"""무료 provider(Gemini, Ollama)가 요청을 올바르게 만들고 응답을 판독 결과로 바꾸는지 (네트워크는 가짜)."""
import json
import sys
from pathlib import Path

import httpx
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import prompts  # noqa: E402
from providers.gemini_provider import GeminiProvider, to_gemini_schema  # noqa: E402
from providers.ollama_provider import OllamaProvider  # noqa: E402

IMG = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()
ANSWER = {"facility_type": "bench", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
          "issues": [{"type": "board_broken", "severity": 3, "bbox": [0.2, 0.4, 0.7, 0.6],
                      "evidence": "좌판이 부러짐"}], "overall_severity": 3, "certainty": "high"}


def test_schema_conversion():
    g = to_gemini_schema(prompts.analyze_schema())
    assert g["type"] == "OBJECT" and g["properties"]["issues"]["items"]["type"] == "OBJECT"
    assert "enum" in g["properties"]["facility_type"]


def test_gemini_request_and_result(monkeypatch):
    seen = {}

    def handler(req: httpx.Request):
        seen["url"], seen["key"] = str(req.url), req.headers["x-goog-api-key"]
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(ANSWER)}]}}]})

    monkeypatch.setenv("AI_MIN_INTERVAL_S", "0")
    p = GeminiProvider(model="gemini-test", api_key="k", transport=httpx.MockTransport(handler))
    r = pipeline.analyze_photo(IMG, {"location_tags": []}, p)
    assert "gemini-test:generateContent" in seen["url"] and seen["key"] == "k"
    gc = seen["body"]["generationConfig"]
    assert gc["responseMimeType"] == "application/json" and gc["temperature"] == 0
    assert "inline_data" in seen["body"]["contents"][0]["parts"][0]
    assert (r["grade"], r["dept"], r["meta"]["provider"]) == ("긴급", "공원 담당", "gemini")


def test_gemini_retries_on_429(monkeypatch):
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"retry-after": "0.01"}, json={})
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(ANSWER)}]}}]})

    monkeypatch.setenv("AI_MIN_INTERVAL_S", "0")
    p = GeminiProvider(api_key="k", transport=httpx.MockTransport(handler))
    assert p.analyze(IMG, "p", prompts.analyze_schema())["facility_type"] == "bench" and calls["n"] == 2


def test_missing_fields_filled_conservatively(monkeypatch):
    partial = {"facility_type": "bench", "issues": [], "overall_severity": 0}

    def handler(req):
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(partial)}]}}]})

    monkeypatch.setenv("AI_MIN_INTERVAL_S", "0")
    p = GeminiProvider(api_key="k", transport=httpx.MockTransport(handler))
    d = p.analyze(IMG, "p", prompts.analyze_schema())
    assert d["certainty"] == "low" and d["privacy_leaks"] == [] and d["framing_ok"] is False


def test_ollama_request_and_result():
    seen = {}

    def handler(req):
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"message": {"content": json.dumps(ANSWER)}})

    p = OllamaProvider(model="qwen2.5vl:7b", transport=httpx.MockTransport(handler))
    r = pipeline.analyze_photo(IMG, {"location_tags": []}, p)
    assert seen["body"]["format"]["type"] == "object" and len(seen["body"]["messages"][1]["images"]) == 1
    assert r["grade"] == "긴급" and r["meta"]["provider"] == "ollama"


def test_gemini_bbox_converted_to_contract_order(monkeypatch):
    """Gemini가 낸 [ymin, xmin, ymax, xmax](0~1000)를 계약 [왼쪽, 위, 오른쪽, 아래](0~1)로 바꾼다."""
    from providers.gemini_provider import from_gemini_box
    seen = {}
    answer = dict(ANSWER, issues=[{"type": "board_broken", "severity": 3, "bbox": [635, 322, 795, 463], "evidence": "좌판이 부러짐"}])

    def handler(req: httpx.Request):
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(answer)}]}}]})

    monkeypatch.setenv("AI_MIN_INTERVAL_S", "0")
    p = GeminiProvider(api_key="k", transport=httpx.MockTransport(handler))
    raw = p.analyze(IMG, "판독", prompts.analyze_schema())
    assert raw["issues"][0]["bbox"] == [0.322, 0.635, 0.463, 0.795]
    assert "[ymin, xmin, ymax, xmax]" in seen["body"]["systemInstruction"]["parts"][0]["text"]
    bbox_spec = seen["body"]["generationConfig"]["responseSchema"]["properties"]["issues"]["items"]["properties"]["bbox"]
    assert bbox_spec["items"]["maximum"] == 1000
    assert prompts.analyze_schema()["properties"]["issues"]["items"]["properties"]["bbox"]["items"]["maximum"] == 1
    assert from_gemini_box([0.1, 0.2, 0.3, 0.4]) == [0.2, 0.1, 0.4, 0.3]   # 비율로 낸 경우
    assert from_gemini_box([800, 900, 100, 1200]) == [0.9, 0.1, 1.0, 0.8]  # 뒤집힘·범위 밖 보정


def test_claude_uses_structured_output_not_forced_tool():
    """Claude는 tool_choice 강제 대신 output_config.format으로 JSON을 받고, 뺀 제약은 받은 뒤 확인한다."""
    from types import SimpleNamespace
    import pytest
    from providers.claude_provider import ClaudeProvider, to_output_schema
    pytest.importorskip("anthropic")
    seen = {}

    class FakeMessages:
        def __init__(self, answer):
            self.answer = answer

        def create(self, **kw):
            seen.update(kw)
            return SimpleNamespace(stop_reason="end_turn", stop_details=None,
                                   content=[SimpleNamespace(type="text", text=json.dumps(self.answer))])

    def provider(answer):
        return ClaudeProvider(model="claude-test",
                              client=SimpleNamespace(beta=SimpleNamespace(messages=FakeMessages(answer))))

    full = dict(ANSWER, privacy_leaks=[])
    r = pipeline.analyze_photo(IMG, {"location_tags": []}, provider(full))
    assert r["grade"] == "긴급" and r["meta"]["provider"] == "claude"
    assert "tool_choice" not in seen and "tools" not in seen
    fmt = seen["output_config"]["format"]
    assert fmt["type"] == "json_schema" and fmt["schema"]["additionalProperties"] is False
    assert "maximum" not in json.dumps(fmt["schema"])           # 지원하지 않는 제약은 빼고 보냄
    assert seen["fallbacks"] == "default"

    bad = dict(full, issues=[{"type": "board_broken", "severity": 9, "bbox": [0.2, 0.4, 0.7, 0.6], "evidence": "x"}])
    with pytest.raises(RuntimeError):
        provider(bad).analyze(IMG, "판독", prompts.analyze_schema())
    assert to_output_schema({"type": "object", "properties": {"a": {"type": "integer", "minimum": 0}}}) == \
        {"type": "object", "properties": {"a": {"type": "integer"}}, "additionalProperties": False}


def test_gemini_429_long_wait_fails_fast(monkeypatch):
    """하루 한도처럼 오래 기다려야 하는 429는 요청 안에서 기다리지 않고 바로 실패로 돌린다."""
    import time as _time
    from providers.gemini_provider import RateLimited
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        return httpx.Response(429, headers={"retry-after": "3600"}, json={})

    monkeypatch.setenv("AI_MIN_INTERVAL_S", "0")
    p = GeminiProvider(api_key="k", transport=httpx.MockTransport(handler))
    t0 = _time.time()
    try:
        p.analyze(IMG, "p", prompts.analyze_schema())
        raise AssertionError("RateLimited가 나야 함")
    except RateLimited:
        pass
    assert calls["n"] == 1 and _time.time() - t0 < 2


def test_ollama_pixel_bbox_and_image_size():
    """Qwen이 바꾸지 않을 크기(28px 배수, 약 80만 화소 이상)로 맞춰 보내고, 픽셀 좌표를 비율로 바꾼다."""
    import base64 as _b64
    import images
    from providers.ollama_provider import MIN_PIXELS, fit_for_model, to_ratio_box
    seen = {}

    def handler(req):
        seen["body"] = json.loads(req.content)
        sent = images.decode_jpeg(_b64.b64decode(seen["body"]["messages"][1]["images"][0]))
        h, w = sent.shape[:2]
        seen["size"] = (w, h)
        ans = dict(ANSWER, issues=[{"type": "board_broken", "severity": 3, "bbox": [w * 0.32, h * 0.63, w * 0.46, h * 0.8],
                                    "evidence": "좌판이 부러짐"}])
        return httpx.Response(200, json={"message": {"content": json.dumps(ans)}})

    p = OllamaProvider(model="qwen2.5vl:7b", transport=httpx.MockTransport(handler))   # 픽셀 좌표로 답하는 모델
    raw = p.analyze(IMG, "판독", prompts.analyze_schema())
    w, h = seen["size"]
    assert w % 28 == 0 and h % 28 == 0 and w * h >= MIN_PIXELS
    assert raw["issues"][0]["bbox"] == [0.32, 0.63, 0.46, 0.8]
    assert seen["body"]["options"]["num_ctx"] >= 8192
    assert f"가로 {w}, 세로 {h}" in seen["body"]["messages"][0]["content"]
    _, W, H = fit_for_model(images.encode_jpeg(np.zeros((1176, 1568, 3), np.uint8)))
    assert (W, H) == (1568, 1176)                                   # 이미 맞는 크기는 그대로
    assert to_ratio_box([0.1, 0.2, 0.3, 0.4], 100, 100) == [0.1, 0.2, 0.3, 0.4]   # 이미 비율이면 그대로


def test_ollama_qwen3_uses_0_1000_boxes():
    """qwen3-vl은 픽셀이 아니라 0~1000 비율로 답하므로 1000으로 나눈다."""
    from providers.ollama_provider import bbox_mode, to_ratio_box
    seen = {}

    def handler(req):
        seen["body"] = json.loads(req.content)
        ans = dict(ANSWER, issues=[{"type": "board_broken", "severity": 3, "bbox": [300, 700, 500, 850], "evidence": "x"}])
        return httpx.Response(200, json={"message": {"content": json.dumps(ans)}})

    p = OllamaProvider(model="qwen3-vl:8b", transport=httpx.MockTransport(handler))
    raw = p.analyze(IMG, "판독", prompts.analyze_schema())
    assert raw["issues"][0]["bbox"] == [0.3, 0.7, 0.5, 0.85]
    assert "0~1000" in seen["body"]["messages"][0]["content"]
    assert bbox_mode("qwen3-vl:8b") == "norm1000" and bbox_mode("qwen2.5vl:7b") == "pixel"
    assert to_ratio_box([300, 700, 500, 850], 1568, 1176, "pixel") != [0.3, 0.7, 0.5, 0.85]


def test_ollama_keeps_model_loaded_and_warms_up(monkeypatch):
    """활동 중 5분 넘게 사진이 없어도 모델이 내려가지 않게 keep_alive를 보내고, 시작할 때 모델을 미리 올린다."""
    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        if not seen[-1]["messages"]:                          # 미리 올리기: 빈 대화
            return httpx.Response(200, json={"done": True})
        return httpx.Response(200, json={"message": {"content": json.dumps(dict(ANSWER, privacy_leaks=[]))}})

    monkeypatch.setenv("OLLAMA_KEEP_ALIVE", "4h")
    p = OllamaProvider(model="qwen3-vl:8b-instruct", transport=httpx.MockTransport(handler))
    p.warm_up()
    p.analyze(IMG, "판독", prompts.analyze_schema())
    warm, judge = seen
    assert warm["messages"] == [] and warm["keep_alive"] == "4h"
    assert judge["keep_alive"] == "4h"
    assert warm["options"]["num_ctx"] == judge["options"]["num_ctx"]   # 다르면 판독 때 모델을 다시 올림


def test_ollama_warm_up_waits_longer_than_judgment_timeout(monkeypatch):
    """운영 판독 제한은 12초지만 모델을 GPU에 올리는 데는 1분 가까이 걸리므로, 미리 올리기는 따로 길게 기다린다."""
    timeouts = []

    def handler(req):
        timeouts.append((json.loads(req.content)["messages"] == [], req.extensions["timeout"]["read"]))
        if timeouts[-1][0]:
            return httpx.Response(200, json={"done": True})
        return httpx.Response(200, json={"message": {"content": json.dumps(dict(ANSWER, privacy_leaks=[]))}})

    monkeypatch.setenv("AI_TIMEOUT_S", "12")
    monkeypatch.delenv("OLLAMA_WARMUP_TIMEOUT_S", raising=False)
    p = OllamaProvider(model="qwen3-vl:8b-instruct", transport=httpx.MockTransport(handler))
    p.warm_up()
    p.analyze(IMG, "판독", prompts.analyze_schema())
    assert timeouts == [(True, 300.0), (False, 12.0)]
