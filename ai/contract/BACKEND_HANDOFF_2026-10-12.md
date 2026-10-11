# AI 서버 변경 안내 (백엔드 담당) · 2026-10-12

AI 판독 서버를 점검하면서 고친 내용 중 백엔드 연동에 영향을 주는 것과, 백엔드에서 물어본 네 가지에 대한 답입니다. 전체 계약은 [API_CONTRACT.md](API_CONTRACT.md)를 기준으로 합니다. 예시 응답(`fixtures/`)도 다시 만들었습니다.

## 먼저 할 일

- [ ] 오류 응답은 HTTP 상태와 함께 `code`로 분기하도록 바꿉니다 (`detail` 문장으로 분기하지 않음).
- [ ] 422를 따로 처리하던 곳이 있으면 400으로 합칩니다. AI 서버는 이제 422를 쓰지 않습니다.
- [ ] `photo_id`를 정규식에 맞게 만들고, `m_`로 시작하지 않게 합니다.
- [ ] `/analyze`와 `/verify`에 같은 `photo_id`를 쓰지 않습니다. 쓰면 409입니다.
- [ ] `POST /audit/masking/{id}`의 `ok`는 JSON 불리언으로 보냅니다.
- [ ] `GET /health`의 `ok: false`를 장애로 감시합니다. 마스킹 검출기를 불러오지 못한 상태입니다.
- [ ] `fixtures/`를 새 파일로 바꿉니다.

## 오류 응답 형식

모든 오류의 본문은 같습니다.

```json
{"detail": "사람이 읽는 설명", "code": "PHOTO_HIDDEN"}
```

- `code`는 바뀌지 않는 값이니 분기에 씁니다. `detail` 문장은 바뀔 수 있으니 기록과 표시에만 씁니다.
- 형식 오류는 모두 **400**입니다. 필드 타입 오류나 필수 필드 누락처럼 예전에 FastAPI가 422로 돌려주던 것도 이제 400 `INVALID_REQUEST`입니다.
- 4xx 오류일 때 AI 서버는 사진도 결과도 저장하지 않습니다. 원인을 고친 뒤 **같은 `photo_id`로 다시 보내도 됩니다.**
- 사진을 받았지만 처리하지 못한 경우(읽을 수 없는 파일, 마스킹 실패, 검출기를 불러오지 못함)는 예전과 같이 HTTP 200에 `status: "error"`입니다. 이것도 저장하지 않으므로 같은 ID로 다시 보내면 됩니다.

| HTTP | code | 경우 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 타입이 틀리거나 필수 필드가 빠짐 (예: `lat`이 숫자가 아님, `after` 파일 없음) |
| 400 | `INVALID_JSON` | `context_json`, `prior_json`, `device_mask_report`가 JSON이 아니거나 객체(`{...}`)가 아님 |
| 400 | `INVALID_PHOTO_ID` | `photo_id` 형식 오류 (아래 1번) |
| 400 | `INVALID_CONTEXT` | `context_json` 안의 값 형식이 틀림 (예: `history.severity`가 0~3 정수가 아님) |
| 400 | `INVALID_PRIOR` | `prior_json`의 `facility_type`·`issue_type`이 시설 목록에 없음, `severity`·`bbox`·`location_tags` 형식 오류 |
| 400 | `INVALID_MODE` | `/verify`의 `mode`가 `repair`·`change`가 아님 |
| 400 | `BEFORE_REQUIRED` | `/verify`에 `before_photo_id`와 `before`가 모두 없음 |
| 400 | `INVALID_AUDIT` | `/audit/masking/{id}` 본문 형식 오류 (아래 4번) |
| 400 | `INVALID_MEDIA` | `/privacy/mask`의 `media_id`·`purpose` 형식 오류 |
| 400 | `INVALID_NARRATIVE` | `/report/narrative`의 `items`·`audience` 형식 오류 |
| 401 | `UNAUTHORIZED` | API 키 없음·틀림 |
| 404 | `PHOTO_NOT_FOUND` | `/images`의 사진 없음, `/verify`의 `before_photo_id` 사진 없음 |
| 404 | `RESULT_NOT_FOUND` | `/results/{photo_id}` 결과 없음 |
| 404 | `AUDIT_TARGET_NOT_FOUND` | `/audit/masking/{id}`의 대상 기록 없음 |
| 404 | `MEDIA_NOT_FOUND` | `/privacy/{media_id}` 기록 없음 |
| 404 | `NOT_FOUND` | 없는 주소 |
| 409 | `PHOTO_ID_CONFLICT` | `/analyze`에 쓴 `photo_id`로 `/verify`를 부르거나 그 반대 |
| 409 | `MEDIA_ID_CONFLICT` | `/privacy/mask`에서 같은 `media_id`로 다른 사진이 들어옴 |
| 410 | `PHOTO_HIDDEN` | 가림 점검에서 보류된 사진 (아래 3번) |
| 413 | `PHOTO_TOO_LARGE` | 사진이 15MB 초과 |

## 문의에 대한 답

### 1. `photo_id`의 정확한 허용 형식

`/analyze`와 `/verify`의 `photo_id`에 적용합니다.

- 정규식: `^[A-Za-z0-9_-]{1,80}$` (영문 대소문자·숫자·`_`·`-`, 1~80자)
- 소문자 `m_`로 시작하면 안 됩니다. AI 서버 내부용(참고·보수 사진, 수리 전 사진 사본)과 겹치기 때문입니다. 대소문자를 구분하므로 `M_abc`는 허용됩니다.
- 비우거나 빈 문자열이면 AI 서버가 `p_` + 16진수 12자로 만들어 응답의 `photo_id`에 넣습니다. 이 경우 재요청이 같은 요청인지 알 수 없으므로, **운영에서는 백엔드가 만들어 보내는 것을 권합니다.**
- 위 형식이 아니면(한글, `.`, 공백, 81자 이상, `m_` 시작) 400 `INVALID_PHOTO_ID`이고 아무것도 저장하지 않습니다. 예전처럼 조용히 새 ID로 바꾸지 않습니다.
- `/verify`의 `before_photo_id`에는 AI가 돌려준 `photo_id`(`m_vb_…` 포함)나 `masked_image_url`(`/images/<id>_masked.jpg`)을 그대로 넣습니다.

### 2. 409가 나는 엔드포인트와 같은 요청 재시도

**409가 나는 경우**

| 엔드포인트 | code | 경우 |
|---|---|---|
| `POST /analyze` | `PHOTO_ID_CONFLICT` | 그 `photo_id`로 `/verify` 결과가 이미 저장돼 있음 |
| `POST /verify` | `PHOTO_ID_CONFLICT` | 그 `photo_id`로 `/analyze` 결과가 이미 저장돼 있음 |
| `POST /privacy/mask` | `MEDIA_ID_CONFLICT` | 같은 `media_id`로 다른 사진이 들어옴 (같은 사진이면 저장된 결과를 돌려줌) |

**같은 엔드포인트에 같은 `photo_id`로 다시 보낼 때**

- 저장된 결과가 있으면 다시 처리하지 않고 그 결과를 HTTP 200으로 돌려줍니다. 응답에 `meta.replayed: true`가 붙습니다. AI 판독도, 알림도 다시 일어나지 않습니다.
- 첫 요청이 아직 처리 중이면 두 번째 요청은 첫 요청이 끝날 때까지 기다렸다가 같은 결과를 받습니다. AI 판독은 한 번만 합니다.
- 저장된 결과로 돌려주는 경우는 `status`가 `ok`, `needs_review`, 사진이 저장된 `retake`(틀 밖)일 때입니다.
- 아래는 아무것도 저장하지 않으므로, 같은 ID로 다시 보내면 **새로 처리합니다.**
  - 흐림·어두움 `retake` (`masked_image_url: null`)
  - `status: "error"`
  - 모든 4xx 오류
- `/analyze`는 같은 ID에 다른 사진이 와도 막지 않고 저장된 결과를 돌려줍니다. 사진이 같은지는 백엔드가 입력 해시로 확인해 주세요. (`/privacy/mask`는 AI가 409로 막습니다.)
- 재시도 기다림: AI 판독 1회는 최대 12초입니다(`AI_TIMEOUT_S`). 첫 요청이 처리 중일 때 들어온 재요청은 그만큼 더 기다릴 수 있으니, 백엔드 쪽 요청 제한 시간은 30초 이상으로 두는 것을 권합니다.

### 3. 410의 오류 코드와 보류 해제 뒤 재요청

**오류 코드:** 두 경우 모두 HTTP 410, `code: "PHOTO_HIDDEN"`입니다.

| 엔드포인트 | 본문 |
|---|---|
| `GET /images/{id}_masked.jpg` | `{"detail": "가림 점검에서 보류된 사진입니다", "code": "PHOTO_HIDDEN"}` |
| `POST /verify` (보류된 `before_photo_id`) | `{"detail": "가림 점검에서 보류된 사진은 비교에 쓸 수 없습니다", "code": "PHOTO_HIDDEN"}` |

**보류 해제 뒤 다시 요청할 수 있습니다.**

1. `POST /audit/masking/{id}`에 `{"ok": true}`를 보내면 보류가 풀리고, `GET /events`에 `image_unhidden`이 남습니다.
2. 그 뒤로 `GET /images`와 `/verify`(그 사진을 `before_photo_id`로 쓰는 경우)가 정상 동작합니다.
3. 410을 받은 `/verify`는 아무것도 저장하지 않았으므로 **같은 `photo_id`로 다시 보내면 됩니다.** 보류 → 410 → 해제 → 같은 ID로 재요청 → 200 흐름을 실제로 확인했습니다.

**알아 둘 점**

- 보류된 사진은 AI 서버가 재판독·재비교에도 쓰지 않습니다. 재판독을 기다리던 사진이 보류되면 그 재판독을 끝내고 담당자 확인으로 둡니다. 결과에 `retry.gave_up: true`가 들어가고, `result_updated` 알림이 갑니다.
- 보류를 풀어도 끝난 재판독이 자동으로 다시 시작되지는 않습니다. 그 건은 담당자 검토로 처리해 주세요.
- 보류·해제가 바뀌면 `GET /events`에 `image_hidden` / `image_unhidden`이 남습니다. 백엔드 복사본도 같은 상태로 맞춰 주세요.

### 4. `ok`가 들어가는 정확한 요청 구조

```http
POST /audit/masking/{id}
Content-Type: application/json
X-API-Key: <키>

{"ok": false, "reviewer": "주무관", "note": "번호판 일부 보임"}
```

| 위치 | 이름 | 필수 | 형식 | 설명 |
|---|---|---|---|---|
| 경로 | `id` | ○ | 문자열 | 판독 `photo_id`, `/privacy/mask`의 `media_id`, 수리 전 사본 ID(`m_vb_…`) 중 하나 |
| 본문 | `ok` | ○ | JSON 불리언 | `false`면 보류(사진을 내려주지 않음), `true`면 문제 없음 또는 보류 해제 |
| 본문 | `reviewer` | | 문자열 | 확인한 담당자 (그대로 기록) |
| 본문 | `note` | | 문자열 | 메모 (그대로 기록) |

- `ok`가 빠졌거나 `"false"`처럼 문자열이면 400 `INVALID_AUDIT`입니다. 예전에는 빠진 `ok`를 '문제 없음'으로 읽어 보류가 풀릴 수 있었습니다.
- 본문이 없거나 JSON 객체가 아니어도 400 `INVALID_AUDIT`입니다.
- 기록이 없는 `id`면 404 `AUDIT_TARGET_NOT_FOUND`입니다.
- 응답 예:

```json
{"ok": false, "reviewer": "주무관", "note": "번호판 일부 보임", "at": "2026-10-12T01:46:05+09:00", "hidden": true}
```

## 그 밖에 바뀐 동작

- **`/verify`의 `before` 업로드**
  - `after`와 같은 검사를 거칩니다: 기기 가림 확인(`REQUIRE_DEVICE_MASKING=1`이면 `device_masked=true` 필요), 크기 제한, 서버 가림, 긴 변 1568px로 줄여 저장.
  - 저장 이름은 `m_vb_` + 16진수 12자입니다. 이 ID로 가림 점검과 보류를 할 수 있습니다.
  - 두 사진 중 하나라도 가리지 못하면 아무것도 저장하지 않고 `status: "error"`입니다.
- **`GET /health`**
  - `ok`가 이제 `true`로 고정이 아닙니다. 켜 둔 마스킹 검출기(번호판 모델, 차량 모델)를 불러오지 못하면 `ok: false`이고, `detectors`에 어떤 검출기가 문제인지 나옵니다.
  - 이 상태에서는 사진을 약하게 가려 저장하지 않고 `status: "error"`로 거절합니다. 장애 감시 대상에 넣어 주세요.
- **AI 응답 형식 오류:** 모델이 형식에 맞지 않는 답을 보내면 500 대신 `status: "needs_review"`와 `retry.pending: true`로 돌려주고 나중에 다시 판독합니다(AI 지연과 같은 처리).
- **시설 안 보임:** `retake`가 아니라 `needs_review`입니다(코드는 원래 이렇게 동작했고, 계약 문서를 고쳤습니다).
- **전후 비교의 가림 겹침:** `prior.bbox`가 이번 사진의 가림 영역과 25% 이상 겹치면, AI가 `fixed`로 답해도 `status: "needs_review"`, `action: "human_review"`로 돌려줍니다.
- **날짜 계산:** `captured_at`을 UTC(`…Z`)로 보내도 활동 실적·주간 보고는 한국 날짜로 셉니다.
- **`retry.gave_up`:** 이제 항상 `reason`이 함께 옵니다.

## 예시 응답(fixtures) 변경

`fixtures/`를 다시 만들었습니다. 형식(키 구조)은 그대로이고 값만 바뀌었습니다.

- `meta.prompt_version`: 현재 프롬프트 값(`analyze-f7ffcf9`, `verify-f133865`)
- `05_urgent_duplicate`와 `05b_urgent_bench`가 서로 다른 `photo_id`(`p_example_05`, `p_example_05b`)를 씁니다.
- 전후 비교 예시(11~14)는 04번 사진을 `before_photo_id`로 써서 만들었습니다. 그래서 `get_facility_history.json`의 ID가 `verify_*.json`과 맞고, 실행할 때마다 내용이 같습니다.

질문은 AI 담당에게 주세요.
