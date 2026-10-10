"""내 컴퓨터에서 돌리는 공개 비전 모델로 판독하는 provider (Ollama). 비용 없음, 사진이 컴퓨터 밖으로 나가지 않음.

준비
  1) ollama.com에서 Ollama 설치
  2) 비전 모델 내려받기 (예: `ollama pull qwen2.5vl:7b`, 사양이 낮으면 `ollama pull gemma3:4b`)
  3) Ollama 실행 상태에서 AI_PROVIDER=ollama 로 서버나 평가 스크립트 실행

환경 변수
  OLLAMA_MODEL   모델 이름 (기본 qwen2.5vl:7b)
  OLLAMA_URL     기본 http://localhost:11434

주의: 사진 한 장 판독에 컴퓨터 사양에 따라 수 초~수십 초가 걸리고, 큰 상용 모델보다 정확도가 낮을 수 있다.
      평가 스크립트로 Gemini 결과와 비교해 어느 쪽을 쓸지 정한다.
"""
import base64
import json
import os

import httpx

from providers.base import Provider
from providers.gemini_provider import _fill_defaults

DEFAULT_MODEL = "qwen2.5vl:7b"


class OllamaProvider(Provider):
    name = "ollama"

    def __init__(self, model: str | None = None, url: str | None = None, transport=None):
        self.model = model or os.environ.get("OLLAMA_MODEL", DEFAULT_MODEL)
        self.url = (url or os.environ.get("OLLAMA_URL", "http://localhost:11434")).rstrip("/")
        self.client = httpx.Client(timeout=float(os.environ.get("AI_TIMEOUT_S", "120")), transport=transport)

    def _chat(self, system: str, text: str, images: list[bytes], schema: dict | None) -> str:
        body = {
            "model": self.model,
            "stream": False,
            "options": {"temperature": 0},
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": text, "images": [base64.b64encode(i).decode() for i in images]},
            ],
        }
        if schema:
            body["format"] = schema  # Ollama는 JSON Schema로 출력 형식을 강제할 수 있다
        r = self.client.post(f"{self.url}/api/chat", json=body)
        r.raise_for_status()
        return r.json()["message"]["content"]

    def _json(self, system, text, images, schema):
        raw = self._chat(system, text, images, schema)
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise RuntimeError(f"모델이 JSON이 아닌 답을 보냄: {raw[:200]}")
        return _fill_defaults(data, schema)

    def analyze(self, image_jpeg, prompt, schema):
        return self._json(prompt, "이 점검 사진을 판독해 형식에 맞춰 JSON으로 보고해 주세요.", [image_jpeg], schema)

    def verify(self, before_jpeg, after_jpeg, prompt, schema):
        return self._json(prompt, "첫 번째 이미지는 이전 사진, 두 번째 이미지는 이번에 다시 찍은 사진입니다. "
                                  "두 사진을 비교해 형식에 맞춰 JSON으로 보고해 주세요.", [before_jpeg, after_jpeg], schema)

    def generate_text(self, prompt):
        return self._chat("", prompt, [], None).strip()
