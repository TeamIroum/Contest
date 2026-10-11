# 외부 AI 판독 연결 (사용 중지)

2026-10-11 결정으로 판독은 자체 서버의 공개 모델(Ollama)로만 합니다. 사진을 외부 AI API로 보내지 않으므로 Gemini·Claude 연결 코드를 `ai/`에서 빼고 여기에 보관합니다.

- `gemini_provider.py`, `claude_provider.py`: 예전 `ai/providers/`의 파일
- `test_free_providers.py`: 예전 `ai/tests/`의 테스트 (Ollama 테스트는 `ai/tests/test_ollama_provider.py`로 옮김)

이 폴더의 코드는 실행 경로에 연결되어 있지 않습니다.
