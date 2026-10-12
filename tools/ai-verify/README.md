# AI 판독 검증 콘솔

AI 판독 모듈(`ai/`)을 **앱 → 업무 백엔드 → AI** 흐름 그대로 시험하는 도구입니다. 실제 업무 백엔드(Spring Boot)와 앱이 완성되기 전에, AI 응답이 [판독 API 계약서 v2.0](../../ai/contract/API_CONTRACT.md)을 지키는지와 판독이 얼마나 맞는지를 화면에서 확인합니다.

```
웹 화면 (frontend/) ──▶ 검증용 백엔드 (backend/, :8100) ──X-API-Key──▶ AI 판독 모듈 (ai/, :8000)
                         · 계약 검사, 정답 비교
                         · 가린 사진 복사본, 결과 스냅샷, 알림 순번 (SQLite)
```

- 화면은 AI 서버를 직접 부르지 않습니다. AI API 키는 검증용 백엔드에만 둡니다.
- 검증용 백엔드는 계약서의 "백엔드 연동 시 꼭 맞출 것"을 그대로 지킵니다: 위치는 `location_tags`로 보내고 `lat`/`lon`은 보내지 않음, 앱 마스킹 보고 `appVersion` → `app_version` 변환, 가린 사진은 `X-Image-Revision`과 함께 복사, `GET /events`로 다시 가림·보류·재판독을 반영.
- 업무 백엔드를 대신하지 않습니다. 업무 상태(`nextAction`), 사건·검토 작업, 인증은 없습니다.
- **시험용 시니어 PWA**(`/pwa/`)와 사진 제출만 흉내 낸 시험용 업무 API(`/api/v1`)가 함께 뜹니다. 휴대폰 카메라로 찍고 브라우저에서 가린 사진을 업무 API 형식으로 보내 AI까지 이어 시험합니다. 사용법은 [pwa/README.md](pwa/README.md). React Native(Expo, iOS·Android) 시험용 앱은 [../ai-test-app/](../ai-test-app/README.md)이며, 마스킹은 같은 코드(`/pwa/mask-host.html`)를 씁니다.

## 실행

AI 모듈과 검증 콘솔을 각각 띄웁니다. (PowerShell 기준)

```powershell
# 1) AI 판독 모듈 (ai/.env: AI_PROVIDER=ollama, Ollama가 켜져 있어야 함)
cd ai
pip install -r requirements.txt
python -m uvicorn server:app --port 8000 --env-file .env

# 2) 검증 콘솔 (다른 창)
cd tools/ai-verify/backend
pip install -r requirements.txt
python -m uvicorn app:app --port 8100
```

브라우저에서 http://127.0.0.1:8100 을 엽니다. 두 창을 한 번에 띄우려면 `tools/start-test.ps1`.

**바깥에서는 휴대폰 앱이 쓰는 곳만 열립니다.** Tailscale Funnel 같은 터널로 열어도 `/api/v1/…`(시험용 업무 API)와 `/pwa/…`(시험용 PWA)만 응답하고, 콘솔 화면·콘솔 API(가린 사진 복사본, 기록 지우기)·`/docs`는 이 PC에서 연 요청이 아니면 403입니다. 터널은 이 PC 안에서 요청을 넘겨주므로 보낸 쪽 주소 대신 주소(Host)와 전달 헤더(`Tailscale-Funnel-Request`, `X-Forwarded-For` 등)로 가립니다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `AI_BASE_URL` | `http://127.0.0.1:8000` | AI 판독 모듈 주소 |
| `AI_API_KEY` | (없음) | AI 서버에 `API_KEYS`를 설정했으면 그중 하나 |
| `VERIFY_DATA_DIR` | `tools/ai-verify/data` | 기록 DB(`verify.db`)와 가린 사진 복사본 위치. `.gitignore`에 들어 있음 |

## 화면

| 탭 | 하는 일 |
|---|---|
| 개요 | AI 서버 상태(계약 버전, 인증, 앱 마스킹 필수 설정), 계약 위반 집계, 판독 정확도(긴급 재현율, 등급 일치율, 등급 혼동표, 놓친 긴급 목록), 전후 비교 정확도(잘못된 재요청, 안 고쳐졌는데 종료) |
| 판독 시험 | `POST /analyze`. 사진 여러 장을 같은 설정으로 차례로 판독. 위치 태그, 앱 마스킹 보고, 열린 사건, mock 시나리오, 정답 라벨 입력. 결과 사진 위에 문제 위치(빨간 네모)와 가린 위치(점선) 표시 |
| 전후 비교 | `POST /verify`. 문제가 판독된 기록을 처음 사진으로 골라 `before_photo_id`로 비교. `prior_json`은 처음 판독 결과에서 자동으로 꺼냄 |
| 시나리오 자동 점검 | mock 모드: `ai/contract/fixtures/`의 예시 응답 16개를 같은 입력으로 다시 만들어 비교. 모든 모드: 400·401·404·409, 같은 `photo_id` 재요청, 다시 가림 알림·사진 버전 헤더, 보고 문장 숫자 검사 |
| 기록 | 모든 호출과 계약 검사 결과. 정답 라벨을 나중에 붙이거나 고침. `GET /results`로 최신 결과를 받아 스냅샷으로 쌓음 |
| 알림·가림 점검 | `GET /events`를 받아(화면이 열려 있으면 15초마다 자동) 업무 백엔드처럼 처리(복사본 교체, 조회 차단, 결과 스냅샷 추가). 가림 점검 표본을 보고 "충분/부족" 기록 |
| 보고 문장·개인정보 | `POST /report/narrative`(새 숫자·빠진 숫자 검사), `POST /privacy/mask`, AI 쪽 시연용 집계 조회 |

주소 뒤에 `#analyze`, `#runs`, `#run-12`처럼 붙이면 그 화면으로 바로 열립니다.

## 계약 검사

응답마다 `backend/checks.py`가 계약서 항목을 검사하고 결과를 `통과 / 위반 / 확인 / 참고`로 보여 줍니다. 주요 항목은 다음과 같습니다.

- `meta.contract_version = 2.0`, 보낸 `photo_id`·`mode`·`case_id`를 그대로 돌려줌
- `status`·`action`·`grade`·`result`가 계약서 값 중 하나, 등급과 처리 제안이 맞음 (긴급→`alert_now` 등)
- `masked_image_url`과 `image_revision`이 함께 있거나 함께 없음, `error`·흐림·어두움은 사진을 저장하지 않음
- 재판독 대기(`retry.pending`)면 `needs_review`이고 사진이 저장되어 있음
- 문제 유형이 시설에 맞음, `severity` 0~3, `bbox`가 0~1 비율이고 왼쪽<오른쪽·위<아래
- `lat`/`lon`을 안 보냈으면 `match_ambiguous = false`, 보낸 `location_tags`가 반영됨
- 앱이 가렸으면 `meta.privacy.mode = device+server`, `server_added = 0`(아니면 "확인"), 앱 보고 키가 빠짐없이 기록됨
- 수리 확인: `fixed`→`close_case`, `not_fixed`→`re_request`, 같은 시설이 아니면 `retake`이고 재요청을 보내지 않음
- 응답 시간: 판독 10초 목표·17초 한도, 비교 15초

검사 규칙을 고쳤으면 예시 응답이 모두 통과하는지 확인합니다.

```bash
python -m pytest -q tools/ai-verify/tests
```

## 정확도 집계

판독 시험에서 정답 라벨(시설·문제·등급)을, 전후 비교에서 정답 결과(`fixed`, `not_fixed`, `worse`, `retake` 등)를 붙인 기록만 집계합니다. 기준은 `ai/eval/run_eval.py`와 같습니다. 많은 사진을 한 번에 평가할 때는 `run_eval.py`를 쓰고, 이 콘솔은 사진을 한 장씩 보며 틀린 이유를 확인할 때 씁니다.

- **긴급 재현율**: 정답이 긴급인 사진 중 AI가 긴급으로 잡은 비율 (`needs_review`의 잠정 긴급 포함)
- **잘못된 재요청**: 고쳐졌거나 다른 장소인데 `re_request`를 낸 건수 (담당자 신뢰를 무너뜨리는 실수)

## 알려진 한계

- mock 시나리오는 AI 서버 전체에 한 번 적용되는 값이라, 여러 사람이 같은 mock 서버에 동시에 시험하면 시나리오가 섞일 수 있습니다.
- 재판독 대기(`analyze_15`) 예시는 AI 호출 실패를 HTTP로 일으킬 수 없어 자동 점검에서 건너뜁니다. `ai/tests`에서 확인합니다.
- 계약 예시 재현 중 일부(01, 03, 04, 05, 05b)는 예시 파일을 만들 때처럼 `lat`/`lon`을 보냅니다. 업무 연동 방식과 다르지만 예시와 같은 입력을 쓰기 위해서입니다.
- 기록 지우기는 이 콘솔의 기록과 사진 복사본만 지웁니다. AI 서버에 저장된 결과·사진은 그대로입니다.
