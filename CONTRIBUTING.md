# 협업 규칙

## 브랜치

- `main`: 항상 실행되는 상태. 직접 올리지 않고 PR로만 합칩니다.
- 작업 브랜치: `종류/기능ID-짧은설명`
  - `feat/SEN-03-device-masking`, `fix/FAC-09-stale-verification`, `docs/api-photo-submit`, `ai/report-narrative`
  - 기능 ID가 없는 작업은 ID를 빼고 씁니다.

## 커밋 메시지

`[기능ID] 무엇을 했는지` 한 줄로 시작합니다. 예: `[SEN-05] 품질 재촬영 횟수 구도별로 계산`

## PR

- PR 양식의 체크 항목을 채웁니다. 특히 **계약·문서 영향**과 **시연 표시(실제/모의)**.
- `CODEOWNERS`에 적힌 담당자의 리뷰를 받은 뒤 합칩니다.
- 업무 API, DB 구조, AI 응답 형식을 바꾸는 PR은 **코드와 문서를 같은 PR에서** 고칩니다. 문서만 늦게 고치면 다른 담당이 옛 문서를 보고 개발합니다.

## 문서가 서로 다를 때

[docs/README.md](docs/README.md)의 기준 순서를 따르고, 바로 '결정 필요' 이슈를 엽니다. 혼자 한쪽에 맞춰 고치지 않습니다.

## AI 판독 계약을 바꿀 때

응답 형식(필드·값)을 바꿀 때는 아래 1~4번을 모두 합니다.

1. AI 담당과 백엔드 담당이 먼저 합의합니다.
2. `ai/contract/API_CONTRACT.md` 수정, `ai/pipeline.py`의 `CONTRACT_VERSION` 올림
3. `cd ai && python contract/make_fixtures.py`로 예시 응답 재생성
4. `cd ai && python -m pytest -q` 통과 확인

설명 문구만 고칠 때(형식은 그대로)는 AI 담당 리뷰만 받고 `CONTRACT_VERSION`은 올리지 않습니다. 계약서 머리말에 고친 날짜와 이유를 한 줄 남깁니다.

## 올리면 안 되는 것

- `.env`, API 키, 토큰, 비밀번호
- **점검 사진** (가린 사진 포함), 전화번호, 참여자 실명. 평가용 실제 사진은 팀 공유 드라이브에 두고 경로만 적습니다. `ai/eval/photos/`는 Git에서 제외되어 있습니다.
- 빌드 결과물, `node_modules`, AI 서버 실행 중 생기는 `ai/data/images*`, `ai/data/results`
