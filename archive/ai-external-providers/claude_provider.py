"""Claude API로 판독하는 provider.

환경 변수
  ANTHROPIC_API_KEY  API 키 (필수)
  CLAUDE_MODEL       모델 이름 (기본값 아래 DEFAULT_MODEL)
  CLAUDE_EFFORT      판단에 들이는 노력 low | medium | high (기본 medium). 높을수록 정확하지만 느리고 비싸다

출력 형식(structured outputs, output_config.format)을 지정해 답이 항상 스키마에 맞는 JSON으로 오게 한다.
Sonnet 5.5·Opus 5.5는 도구 호출 강제(tool_choice: tool)를 받지 않아(400) 이 방식을 쓴다.
"""
import base64
import json
import os

from providers.base import Provider

DEFAULT_MODEL = "claude-sonnet-5-5"
# structured outputs가 받지 않는 JSON Schema 제약. 보내기 전에 빼고, 받은 뒤 _check로 확인한다
_UNSUPPORTED = {"minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "multipleOf"}


def _image_block(jpeg: bytes) -> dict:
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.b64encode(jpeg).decode()},
    }


def to_output_schema(s):
    """판독 스키마를 structured outputs 형식으로 바꾼다: 지원하지 않는 제약을 빼고 모든 객체에 additionalProperties: false."""
    if isinstance(s, list):
        return [to_output_schema(x) for x in s]
    if not isinstance(s, dict):
        return s
    out = {k: to_output_schema(v) for k, v in s.items() if k not in _UNSUPPORTED}
    if out.get("type") == "object":
        out["additionalProperties"] = False
    return out


def _check(value, schema, path="$"):
    """보내기 전에 뺀 제약(범위·배열 길이)을 받은 값에 대해 확인한다. 어긋나면 오류 → 담당자 확인·재판독."""
    if isinstance(value, dict):
        for k, sub in (schema.get("properties") or {}).items():
            if k in value:
                _check(value[k], sub, f"{path}.{k}")
    elif isinstance(value, list):
        n = len(value)
        if ("minItems" in schema and n < schema["minItems"]) or ("maxItems" in schema and n > schema["maxItems"]):
            raise RuntimeError(f"Claude 응답 {path}의 항목 수가 형식과 다름: {n}")
        for i, v in enumerate(value):
            _check(v, schema.get("items") or {}, f"{path}[{i}]")
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        if ("minimum" in schema and value < schema["minimum"]) or ("maximum" in schema and value > schema["maximum"]):
            raise RuntimeError(f"Claude 응답 {path} 값이 범위를 벗어남: {value}")


class ClaudeProvider(Provider):
    name = "claude"

    def __init__(self, model: str | None = None, client=None):
        import anthropic  # 설치 안 된 환경에서도 mock은 돌아가도록 여기서 import

        # 한 번 호출의 최대 대기 시간. 넘으면 담당자 확인으로 넘기고 jobs.RetryQueue가 나중에 다시 판독한다.
        # 재시도는 SDK가 아니라 jobs.RetryQueue가 맡는다 (어르신이 현장에서 오래 기다리지 않게)
        timeout = float(os.environ.get("AI_TIMEOUT_S", "12"))
        self.client = client or anthropic.Anthropic(max_retries=0, timeout=timeout)
        self.model = model or os.environ.get("CLAUDE_MODEL", DEFAULT_MODEL)
        self.effort = os.environ.get("CLAUDE_EFFORT", "medium")

    def _json_call(self, system: str, content: list, schema: dict) -> dict:
        resp = self.client.beta.messages.create(
            model=self.model,
            max_tokens=16000,  # 생각(thinking)에도 토큰을 쓰므로 넉넉히. 답 자체는 짧다
            system=system,
            messages=[{"role": "user", "content": content}],
            output_config={"effort": self.effort,
                           "format": {"type": "json_schema", "schema": to_output_schema(schema)}},
            # 안전 분류기가 드물게 거절하면 같은 요청을 다른 모델로 이어서 처리한다
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        if resp.stop_reason == "refusal":
            category = resp.stop_details.category if resp.stop_details else None
            raise RuntimeError(f"Claude가 판독을 거절함 (분류: {category})")
        if resp.stop_reason == "max_tokens":
            raise RuntimeError("Claude 응답이 길이 제한에서 잘림")
        text = next((b.text for b in resp.content if b.type == "text"), None)
        if text is None:
            raise RuntimeError("Claude가 판독 결과를 돌려주지 않았습니다")
        data = json.loads(text)
        _check(data, schema)
        return data

    def analyze(self, image_jpeg: bytes, prompt: str, schema: dict) -> dict:
        content = [_image_block(image_jpeg), {"type": "text", "text": "이 점검 사진을 판독해 형식에 맞춰 보고해 주세요."}]
        return self._json_call(prompt, content, schema)

    def verify(self, before_jpeg: bytes, after_jpeg: bytes, prompt: str, schema: dict) -> dict:
        content = [
            {"type": "text", "text": "이전 사진:"},
            _image_block(before_jpeg),
            {"type": "text", "text": "이번에 다시 찍은 사진:"},
            _image_block(after_jpeg),
            {"type": "text", "text": "두 사진을 비교해 형식에 맞춰 보고해 주세요."},
        ]
        return self._json_call(prompt, content, schema)

    def generate_text(self, prompt: str) -> str:
        resp = self.client.messages.create(
            model=self.model,
            max_tokens=4000,
            output_config={"effort": "low"},
            messages=[{"role": "user", "content": prompt}],
        )
        return "".join(b.text for b in resp.content if b.type == "text").strip()
