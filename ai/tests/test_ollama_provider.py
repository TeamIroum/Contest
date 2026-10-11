"""Ollama provider가 요청을 올바르게 만들고 응답을 판독 결과로 바꾸는지 (네트워크는 가짜)."""
import json
import sys
from pathlib import Path

import httpx
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import prompts  # noqa: E402
from providers.ollama_provider import OllamaProvider  # noqa: E402

IMG = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()
ANSWER = {"facility_type": "bench", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
          "issues": [{"type": "board_broken", "severity": 3, "bbox": [0.2, 0.4, 0.7, 0.6],
                      "evidence": "좌판이 부러짐"}], "overall_severity": 3, "certainty": "high"}


def test_missing_fields_filled_conservatively():
    partial = {"facility_type": "bench", "issues": [], "overall_severity": 0}

    def handler(req):
        return httpx.Response(200, json={"message": {"content": json.dumps(partial)}})

    p = OllamaProvider(model="qwen3-vl:8b-instruct", transport=httpx.MockTransport(handler))
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
