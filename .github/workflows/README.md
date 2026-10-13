# CI

| 파일 | 대상 | 하는 일 |
|---|---|---|
| `ai-ci.yml` | `ai/` 변경 | AI 판독 모듈 테스트 (규칙, 마스킹, 저장 원칙, 서버 처리, 백엔드 연동), 시니어 PWA 얼굴 검출 코드(`yunet.mjs`)가 서버와 같은 결과를 내는지 확인 |
| `no-ai-coauthor.yml` | 모든 PR, main push | 커밋 작성자·`Co-authored-by` 줄·PR 본문에 AI 도구(Claude, ChatGPT·Codex, Copilot, Gemini 등)가 기여자로 올라가 있으면 실패 (`.github/scripts/check-ai-coauthor.sh`, 로컬 훅 `.githooks/commit-msg`도 같은 검사) |

백엔드·프론트 CI는 빌드 도구와 프레임워크가 정해지면 같은 방식(경로별로 나눠 실행)으로 추가합니다. EC2 자동 배포는 첫 수동 배포를 마친 뒤 추가합니다.
