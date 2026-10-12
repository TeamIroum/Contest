# 업무 API 확인 시험 (시니어 앱용)

시니어 앱이 쓰는 업무 API가 [API 명세서](../../docs/API_명세서.md) 2·6장대로 답하는지 확인하는 시험입니다. **주소만 바꿔** 검증 콘솔(`tools/ai-verify`, 지금 쓰는 시험용 업무 API)과 업무 백엔드(`backend/`, Spring Boot) 양쪽에 똑같이 돌립니다. 업무 백엔드가 이 시험을 통과하면 시니어 앱은 `VITE_API_ORIGIN`만 바꿔 옮겨 갈 수 있습니다.

## 실행

```powershell
pip install pytest httpx

# 검증 콘솔 (AI 서버 :8000, 검증 콘솔 :8100이 켜져 있어야 함. tools/start-test.ps1)
$env:RESET_URL = "http://127.0.0.1:8100/api/v1/patrols/patrol-test/test-reset"
python -m pytest tools/api-conformance -q

# 업무 백엔드
$env:API_BASE = "http://localhost:8080/api/v1"
$env:API_TOKEN = "<시험 참여자 액세스 토큰>"
$env:PATROL_ID = "<시험용 순회 ID>"        # 없으면 GET /me/patrols의 첫 순회
python -m pytest tools/api-conformance -q
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:8100/api/v1` | 업무 API 주소 |
| `API_TOKEN` | (없음) | 있으면 `Authorization: Bearer <값>` |
| `PATROL_ID` | `GET /me/patrols`의 첫 순회 | 시험할 순회 |
| `RESET_URL` | (없음) | 있으면 시작 전에 POST해서 구도 상태를 처음으로 되돌림 |
| `API_TIMEOUT_S` | 120 | 요청 하나를 기다리는 최대 시간 (AI 판독 포함) |

**시험 순회 준비**: 아직 아무 시도가 없는 장소가 **5곳**(각각 구도 1개 이상) 필요합니다. 시험이 장소마다 상태를 바꾸므로(재촬영 3번, 채택 등) 다시 돌리려면 새로 준비해야 합니다. 업무 백엔드는 시험용 순회를 다시 만드는 스크립트나 시험 환경 전용 초기화 API를 두고 `RESET_URL`로 넘기면 편합니다.

**AI 서버**: 사진 제출은 업무 API가 AI 서버(`ai/`)를 불러야 끝납니다. 흐림·어두움 시험은 AI 서버의 품질 검사만 거쳐 결과가 늘 같고, `ai` 표시가 붙은 2개는 실제 판독(Ollama)까지 갑니다. 판독 없이 빨리 돌리려면 `-m "not ai"`.

## 확인하는 것

시험은 파일 순서대로 진행합니다. 장소 A~E를 하나씩 맡습니다.

| 시험 | 장소 | 기대 (명세서) |
|---|---|---|
| 내 순회 목록, 순회 상세 | — | `items[]`, 장소에 `id·name·captureSlots`, 구도에 `id·label`. 판독 내용(등급·위험도)이 없음 (6장) |
| 없는 순회 | — | 404 + `code`·`message` (2장 오류 형식) |
| 형식이 틀린 제출 7가지 | A | UUID 아님, `deviceMasked=false`, 마스킹 보고 형식, 촬영 시각 형식, 다른 장소의 구도 → 400/404 + `code`·`message`. 상태는 바뀌지 않음 |
| 사진이 아닌 파일, 10 MiB 초과 | A | 415, 413 |
| 흐린 사진 | A | 200, `NO_MEDIA` / `RETAKE_REQUIRED` / `RETAKE`, 남은 재촬영 2 |
| 같은 제출 다시 보내기 | A | 처음과 같은 결과. 재촬영 횟수를 두 번 세지 않음 (6장 "동일 ID·동일 입력은 기존 결과") |
| 같은 제출 ID로 다른 사진 | A | 409 `SUBMISSION_CONFLICT` |
| 어두운 사진 | B | 200, 재촬영 + 안내 문구(`message`) |
| PNG 사진 | E | 받음 (6장 "JPEG/PNG"). 흐린 PNG라 재촬영으로 끝남 |
| 품질 실패 3번 | C | 남은 재촬영 2 → 1 → 3번째에 `REVIEW_REQUESTED` + 다음 구도·장소로 (6장 "최초 + 재촬영 2회") |
| 검토로 넘긴 구도에 새 사진 | C | 409 `SLOT_RESOLVED` |
| 잘 찍은 사진 (`ai`) | D | 200/202, `RECEIVED`, `inspectionId` 있음 |
| 채택된 구도에 새 사진 (`ai`) | D | 200 + `duplicate=true` + 기존 `inspectionId` (6장) |

모든 사진 제출 응답에서 AI 등급·문제·`senior_message` 원문 같은 판독 내용이 섞이지 않았는지도 함께 봅니다(`conftest.py`의 `FORBIDDEN_KEYS`).

아직 시험하지 않는 것: `capture-status`·`sync-state`·`offline-package` 조회, 출석·시작·건너뛰기·복귀 이벤트, 동시 제출(`SLOT_PROCESSING`·`SUBMISSION_PROCESSING`), 로그인·권한. 업무 백엔드가 만들어지는 대로 덧붙입니다.

## 검증 콘솔 결과 (2026-10-12)

22개 모두 통과 (약 5초). 처음에는 2개가 실패해서 검증 콘솔을 명세서에 맞게 고쳤습니다.

| 처음 실패한 시험 | 고치기 전 검증 콘솔 | 명세서 (지금 검증 콘솔) |
|---|---|---|
| PNG 사진 | 415 (JPEG만 받음) | JPEG/PNG 허용 |
| 채택된 구도에 새 사진 | 409 `SLOT_RESOLVED` | 200 + `duplicate=true` + 기존 `inspectionId` |

## 시험 사진

| 파일 | 내용 |
|---|---|
| `ai/eval/sample/blur.jpg`, `dark.jpg` | 흐린·어두운 합성 사진 (품질 검사에서 늘 실패) |
| `ai/eval/sample/s02.jpg` | 합성 무늬 사진 (채택된 구도에 다시 보내는 용도) |
| `photos/blur.png` | `blur.jpg`를 PNG로 바꾼 것 |
| `photos/sidewalk_cc0.jpg` | 보도 사진. [Watch your step.jpg](https://commons.wikimedia.org/wiki/File:Watch_your_step.jpg) (Tn.kuvat, **CC0**) 을 긴 변 1280px로 줄임. AI가 정상 접수함 |
