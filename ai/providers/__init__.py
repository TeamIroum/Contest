"""판독 모델 교체 지점.

새 모델을 붙이려면 Provider를 상속한 파일을 하나 만들고 아래 get_provider에 이름을 추가한다.
파이프라인, 등급 규칙, 앱 연동 코드는 바꾸지 않는다.
"""
import os

from providers.base import Provider


def get_provider(name: str | None = None) -> Provider:
    name = (name or os.environ.get("AI_PROVIDER", "mock")).lower()
    if name == "mock":
        from providers.mock_provider import MockProvider
        return MockProvider()
    if name == "claude":
        from providers.claude_provider import ClaudeProvider
        return ClaudeProvider()
    if name == "gemini":  # 무료 등급 사용 가능 (Google AI Studio 키)
        from providers.gemini_provider import GeminiProvider
        return GeminiProvider()
    if name == "ollama":  # 내 컴퓨터의 공개 모델, 비용 없음
        from providers.ollama_provider import OllamaProvider
        return OllamaProvider()
    raise ValueError(f"알 수 없는 provider: {name} (mock, gemini, ollama, claude 중 하나)")
