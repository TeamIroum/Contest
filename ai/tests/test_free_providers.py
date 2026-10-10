"""무료 provider(Gemini, Ollama)가 요청을 올바르게 만들고 응답을 판독 결과로 바꾸는지 (네트워크는 가짜)."""
import json
import sys
from pathlib import Path

import httpx

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
