"""Claude API로 판독하는 provider.

환경 변수
  ANTHROPIC_API_KEY  API 키 (필수)
  CLAUDE_MODEL       모델 이름 (기본값 아래 DEFAULT_MODEL)

도구 호출(tool use)을 강제해서 출력이 항상 스키마에 맞는 JSON으로 오게 한다.
"""
import base64
import os

from providers.base import Provider

DEFAULT_MODEL = "claude-sonnet-5-5"


def _image_block(jpeg: bytes) -> dict:
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.b64encode(jpeg).decode()},
    }


class ClaudeProvider(Provider):
    name = "claude"

    def __init__(self, model: str | None = None):
        import anthropic  # 설치 안 된 환경에서도 mock은 돌아가도록 여기서 import

        # 한 번 호출의 최대 대기 시간. 넘으면 담당자 확인으로 넘기고 jobs.RetryQueue가 나중에 다시 판독한다.
        # 재시도는 SDK가 아니라 jobs.RetryQueue가 맡는다 (어르신이 현장에서 오래 기다리지 않게)
        timeout = float(os.environ.get("AI_TIMEOUT_S", "12"))
        self.client = anthropic.Anthropic(max_retries=0, timeout=timeout)
        self.model = model or os.environ.get("CLAUDE_MODEL", DEFAULT_MODEL)

    def _call_tool(self, system: str, content: list, tool_name: str, schema: dict) -> dict:
        resp = self.client.messages.create(
            model=self.model,
            max_tokens=1500,
            system=system,
            tools=[{"name": tool_name, "description": "판독 결과 보고", "input_schema": schema}],
            tool_choice={"type": "tool", "name": tool_name},
            messages=[{"role": "user", "content": content}],
        )
        for block in resp.content:
            if block.type == "tool_use" and block.name == tool_name:
                return dict(block.input)
        raise RuntimeError("모델이 판독 결과를 돌려주지 않았습니다")

    def analyze(self, image_jpeg: bytes, prompt: str, schema: dict) -> dict:
        content = [_image_block(image_jpeg), {"type": "text", "text": "이 점검 사진을 판독해 보고해 주세요."}]
        return self._call_tool(prompt, content, "report_inspection", schema)

    def verify(self, before_jpeg: bytes, after_jpeg: bytes, prompt: str, schema: dict) -> dict:
        content = [
            {"type": "text", "text": "이전 사진:"},
            _image_block(before_jpeg),
            {"type": "text", "text": "이번에 다시 찍은 사진:"},
            _image_block(after_jpeg),
            {"type": "text", "text": "두 사진을 비교해 보고해 주세요."},
        ]
        return self._call_tool(prompt, content, "report_comparison", schema)

    def generate_text(self, prompt: str) -> str:
        resp = self.client.messages.create(
            model=self.model,
            max_tokens=1000,
            messages=[{"role": "user", "content": prompt}],
        )
        return "".join(b.text for b in resp.content if b.type == "text").strip()
