# 판독 API 계약서 v2.0

작성일: 2026-10-10. 업무 백엔드가 AI 판독 모듈을 부를 때 지켜야 할 약속입니다. 이 문서는 `contract_version` **2.0** 기준이며, 응답의 `meta.contract_version`과 `GET /health`로 서버의 값을 확인할 수 있습니다. 형식을 바꿀 때는 AI 담당과 백엔드 담당이 먼저 합의하고 `contract_version`을 올립니다.

2026-10-11 문구 수정: 업무 시스템이 AI 위험도 등급을 쓰지 않기로 한 팀 결정과 3인 조 허용, 자체 서버 판독만 쓰는 결정을 반영했습니다. 응답 형식은 바뀌지 않아 `contract_version`은 2.0 그대로입니다.

2026-10-12 추가: 앱이 가린 위치 `device_mask_report.boxes`(선택)와 `meta.privacy.server_redetected`를 더했습니다. `boxes`를 보내지 않으면 요청·응답이 예전과 같으므로 `contract_version`은 2.0 그대로입니다.

## 호출 구조

```
앱(어르신 휴대폰) ──가린 사진──▶ 업무 백엔드 ──▶ AI 판독 모듈
                 ◀─진행 안내───            ◀── 판독 결과
```

- **앱은 AI 모듈을 직접 부르지 않습니다.** 앱은 업무 백엔드 API로 사진을 올리고, 백엔드가 이 계약대로 AI를 부릅니다. AI의 API 키는 백엔드에만 둡니다.
- 앱이 직접 할 일(기기 마스킹, 앱 안 카메라, 이전 사진 겹쳐 보기)은 `contract/APP_MASKING_GUIDE.md`에 있습니다.
- 어르신 화면 안내, 재촬영 횟수, 조원 동시 제출, 오프라인 보관과 재전송은 업무 백엔드 API 명세를 따릅니다. 이 계약은 AI 응답 값과 그 뜻만 정합니다.

## 개인정보 원칙

- **앱이 휴대폰에서 먼저 얼굴·번호판을 가린 뒤 가린 사진만 보냅니다.** 백엔드는 그 사진을 그대로 AI에 넘깁니다. AI는 받은 사진을 저장하기 전에 가림 상태를 한 번 더 확인하고, 혹시 남은 부분이 있으면 저장·판독 전에 가립니다. 그렇게 가린 수(`server_added`)는 앱 마스킹이 놓친 비율로 기록합니다. AI의 확인은 놓친 것이 퍼지지 않게 막는 안전망이며, "서버에는 가린 사진만 들어온다"는 원칙을 지키는 주 장치는 앱 마스킹입니다.
- AI가 받은 사진은 메모리에서만 처리하고 디스크에 저장하지 않습니다. 업로드 임시 버퍼도 요청이 끝나면 닫아서 지웁니다.
- AI는 얼굴·번호판을 가린 뒤 **가린 사진만** 저장하고, 그 주소를 `masked_image_url`로 돌려줍니다. 판독 모델(자체 서버의 Ollama)에도 가린 사진만 보내고, 외부 AI API로는 보내지 않습니다.
- 가리는 과정에서 오류가 나면 저장도, AI 전송도 하지 않고 `status: "error"`를 돌려줍니다.
- 백엔드와 담당자 화면은 `masked_image_url`로 받은 사진만 씁니다. 원본은 어디에도 없습니다. 가린 사진의 기기 보관(오프라인)은 업무 백엔드 계약을 따르고, 원본은 기기에도 남기지 않습니다.
- 처리 방식의 자세한 내용은 `MASKING_AND_SERVER.md`에 있습니다.

예시 응답은 `contract/fixtures/` 폴더에 경우별로 있습니다. AI 서버를 띄우지 않아도 이 파일과 mock 서버로 백엔드 연동 코드를 만들 수 있습니다.

## 서버 실행

```bash
pip install -r requirements.txt
AI_PROVIDER=mock uvicorn server:app --reload --port 8000
# 브라우저에서 http://localhost:8000/docs 를 열면 직접 시험해 볼 수 있음
```

Windows PowerShell에서는 이렇게 실행합니다.

```powershell
pip install -r requirements.txt
$env:AI_PROVIDER="mock"; python -m uvicorn server:app --reload --port 8000
```

Windows에서는 프로젝트를 한글이 없는 경로(예: `C:\contest\ai`)에 두어야 합니다. 경로에 한글이 있으면 얼굴 검출 모델을 읽지 못해 서버가 시작되지 않습니다.

`API_KEYS`를 설정한 서버에는 모든 요청(`/health` 제외)에 `X-API-Key` 헤더를 붙여야 합니다. 없거나 틀리면 401입니다.

mock 모드에서는 `X-Mock-Scenario` 헤더로 원하는 결과를 고를 수 있습니다.
analyze용: `sidewalk_danger`, `drain_normal`, `drain_minor`, `fence_tilted`, `bench_broken`, `exercise_part`, `sidewalk_uncertain`, `out_of_frame`, `unknown_facility`, `plate_leak`, `issue_under_mask`
verify용: `fixed`, `not_fixed`, `different_place`, `worse`

## 엔드포인트

| 메서드 | 경로 | 용도 | 응답 시간 목표 |
|---|---|---|---|
| GET | `/taxonomy` | 시설 이름, 문제 유형, 등급, 안내 문구 전체 | 즉시 |
| POST | `/analyze` | 사진 한 장 판독 | 흐림·어두움 1초, 판독 보통 10초, 늦어도 17초 |
| POST | `/verify` | 수리 확인(repair) 또는 변화 비교(change) | 15초 이내 |
| GET | `/images/{photo_id}_masked.jpg` | 저장된 가린 사진 | 즉시 |
| GET | `/results/{photo_id}` | 최신 판독 결과 (늦게 끝난 재판독 반영) | 즉시 |
| POST | `/privacy/mask` | 참고·보수 사진의 얼굴·번호판만 검사하고 가림. 판독·실적에 들어가지 않음 | 3초 이내 |
| GET | `/privacy/{media_id}` | 개인정보 단독 검사 기록 | 즉시 |
| POST | `/report/narrative` | 백엔드가 확정한 집계값으로 보고 문장만 생성 | 30초 이내 |
| GET | `/events?after=&limit=` | 응답 뒤에 바뀐 것(다시 가림, 보류, 재판독 결과) 목록 | 즉시 |
| GET | `/audit/masking?start=&end=&n=20` | 담당자 가림 점검용 표본 사진 목록 | 즉시 |
| POST | `/audit/masking/{photo_id}` | 가림 점검 결과 기록 (application/json) `{"ok": false, "reviewer": "...", "note": "..."}`. `ok`는 필수 JSON 불리언, `reviewer`·`note`는 선택 문자열. `false`면 그 사진은 내려주지 않음(410), `true`면 보류 해제 | 즉시 |
| GET | `/health` | 서버 상태, 계약 버전, 얼굴 검출기 종류, 재판독 대기 건수, 마지막 알림 순번 (인증 없음) | 즉시 |

아래는 AI 모듈만으로 시연할 때 쓰는 집계 기능입니다. AI 쪽 기록(촬영자 기준)으로 세므로, 업무 실적·출결·주간 보고의 공식 수치로 쓰지 않습니다. 업무 보고는 백엔드 DB 집계 + `/report/narrative`를 씁니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/participants/{participant_id}/activity?start=&end=` | 촬영자별 활동 (날짜별 촬영 수, 첫·마지막 촬영 시각) |
| GET | `/participants?start=&end=` | 기간 내 모든 촬영자의 활동 |
| GET | `/facilities/{facility_id}/history` | AI 쪽 시설 대장 기준 판독·수리 확인 이력 |
| GET | `/report/weekly?start=&end=` | AI 쪽 기록으로 만든 주간 보고 + 우선 처리 목록 |
| POST | `/report` | AI 판독 결과 형식을 받아 집계와 보고 문장 |

시설 이름과 문제 유형 이름은 응답 안의 `label`이나 `/taxonomy`를 쓰면 `taxonomy.json` 한 곳만 고쳐 문구를 바꿀 수 있습니다.

### POST /analyze (multipart/form-data)

| 필드 | 필수 | 설명 |
|---|---|---|
| `image` | O | 앱이 가린 사진 (JPEG/PNG, 15MB 이하). 회전 정보는 AI가 반영하고 메타데이터는 버림. HEIC·AVIF·WebP는 `status: "error"`(`unsupported_type`)로 거절되므로 앱에서 JPEG으로 바꿔 올림 |
| `photo_id` | 권장 | 백엔드가 AI 호출마다 만든 ID (영문·숫자·`_`·`-`, 80자 이하). 같은 ID로 다시 보내면 처리하지 않고 저장된 결과를 돌려줌 (`meta.replayed: true`). 첫 요청이 아직 처리 중이면 끝날 때까지 기다렸다가 그 결과를 돌려줌. 통신이 끊겨 다시 보낼 때는 같은 ID, 새 촬영은 새 ID. 형식이 틀리거나 `m_`로 시작하면(서버 내부용) 400 |
| `participant_id` | 권장 | 실제로 사진을 올린 참여자의 가명 ID (이름·전화번호 금지) |
| `captured_at` | 권장 | 촬영 시각 ISO 8601 (`2026-10-08T09:14:00+09:00`) |
| `device_masked` | 운영 필수 | 앱에서 이미 가렸으면 `true` |
| `device_mask_report` | 운영 필수 | 앱 보고를 snake_case로 바꿔 전달 `{"faces": 1, "plates": 0, "vehicles": 0, "detector": "yunet+plate-t384+effdet0", "app_version": "1.0.0", "boxes": [{"kind": "face", "box": [0.41, 0.12, 0.47, 0.21]}]}`. `boxes`(선택)는 앱이 가린 위치(0~1 비율 `[x1, y1, x2, y2]`, 최대 200개)이며, 서버가 이미 가린 곳에서 다시 찾은 것을 `server_added`에서 빼는 데만 씁니다. 위치 목록은 저장하지 않고 개수만 남깁니다 |
| `location_tags` | | 쉼표 구분 (`school_route`, `child_facility`, `roadside`). 백엔드 시설 대장의 태그를 넣음. 태그가 없으면 빈 문자열. 위치 가중치가 이 값으로 계산됨 |
| `lat`, `lon` | | 업무 연동에서는 보내지 않음 (아래 "백엔드 연동 시 꼭 맞출 것" 1번). 보내면 AI 쪽 시설 대장과 맞춰 `facility_id`와 위치 태그를 채움. `0`을 보내면 위도 0·경도 0으로 처리되므로 없으면 필드를 아예 빼야 함 |
| `capture_hour` | | 촬영 시각(0~23). 기록용 (지금 등급 규칙에는 쓰지 않음) |
| `context_json` | | `{"history": {"severity": 1, "issue_type": "covered"}, "open_cases": [{"case_id": "C-1", "facility_id": "DR-0001", "issue_type": "covered"}]}` |

### POST /verify (multipart/form-data)

| 필드 | 필수 | 설명 |
|---|---|---|
| `after` | O | 이번 재촬영 사진. 앱이 가린 사진이며, AI가 저장 전에 한 번 더 확인함 |
| `prior_json` | O | `{"case_id", "facility_id", "facility_type", "issue_type", "severity", "bbox", "evidence", "location_tags"}` (처음 판독 결과에서 그대로 꺼내 씀) |
| `before_photo_id` | △ | 처음 판독한 사진의 AI `photo_id` 또는 `masked_image_url`. AI에 저장된 가린 사진으로 비교함 (권장) |
| `before` | △ | `before_photo_id`를 쓸 수 없을 때만(AI 저장소가 비워졌을 때 등) 백엔드에 복사해 둔 처리 전 가린 사진을 직접 보냄. `after`와 같은 검사(기기 가림 확인, 크기 제한, 서버 가림)를 거쳐 `m_vb_`로 시작하는 이름으로 저장되고, 가림 점검으로 보류할 수 있음 |
| `mode` | | `repair`(처리 완료 후 수리 확인, 기본) 또는 `change`(미처리 건 변화 비교) |
| `photo_id` | | 재촬영 사진의 AI 호출 ID. `/analyze`와 다른 새 ID (`/analyze`에 쓴 ID면 409) |
| `participant_id`, `captured_at`, `device_masked`, `device_mask_report` | | `/analyze`와 같음 |

`before_photo_id`와 `before` 중 하나는 꼭 보내야 합니다. 저장된 사진이 없으면 404, 가림 점검에서 보류된 사진이면 410을 돌려줍니다. `prior_json`의 `facility_type`·`issue_type`이 시설 목록에 없으면 400입니다. 두 사진 중 하나라도 가리지 못하면 아무것도 저장하지 않고 `status: "error"`입니다.

### GET /images/{photo_id}_masked.jpg

가린 사진을 JPEG로 내려줍니다. 저장소에는 원본이 없으므로 원본을 받을 방법은 없습니다. API 키가 필요하므로 백엔드가 받아서 자기 저장소(S3 등)에 복사해 두고 담당자 화면에 제공합니다.

응답 헤더 `X-Image-Revision`에 사진 버전이 들어 있습니다(`ETag`도 `"<photo_id>-r<버전>"`). 백엔드는 사진을 복사할 때 이 버전을 함께 저장하고, `GET /events`의 `image_remasked`가 더 높은 버전을 알리면 새로 받아 교체합니다. 가림 점검에서 보류된 사진은 410입니다.

### POST /privacy/mask (multipart/form-data)

참고 사진(재촬영 안내용), 보수 증거 사진처럼 **판독하지 않는 사진**의 개인정보만 검사합니다. 품질(흐림·어두움)은 보지 않고, 판독 결과·활동 실적·주간 보고·가림 표본 점검 목록에 들어가지 않습니다.

| 필드 | 필수 | 설명 |
|---|---|---|
| `image` | O | JPEG/PNG, 15MB 이하 |
| `media_id` | O | 백엔드의 media ID (영문·숫자·`_`·`-`, 78자 이하). 저장 이름은 `m_<media_id>` |
| `purpose` | O | `reference` 또는 `repair_evidence` |
| `device_masked`, `device_mask_report` | 운영 필수 | `/analyze`와 같음 |

응답: `{media_id, photo_id, purpose, status, masked_image_url, image_revision, privacy, notes, meta}`. `status`는 `ok` 또는 `error`(형식 오류·마스킹 실패, 아무것도 저장하지 않음). 같은 `media_id`·같은 사진이면 저장된 결과(`meta.replayed: true`), **다른 사진이면 409**입니다. 가림 점검은 `POST /audit/masking/{media_id}`로 그대로 기록합니다. 예시: `fixtures/get_privacy_mask_reference.json`

### POST /report/narrative (application/json)

숫자는 백엔드가 DB로 확정하고, AI는 그 숫자로 문장만 만듭니다.

```json
{"items": [
  {"key": "photos", "label": "점검 사진", "value": 1240, "unit": "장"},
  {"key": "urgent_alerts", "label": "긴급 알림", "value": 2},
  {"key": "repair_close_suggested", "label": "수리 확인 제안(담당자 확정 전)", "value": 5}
 ],
 "audience": "시청 시설 담당 부서"}
```

- `value`는 0 이상의 정수만 받습니다. 비율·소수가 필요하면 문장 밖(표)에서 보여 주세요. `unit`은 숫자 없는 5자 이하(기본 `건`), `label`은 40자 이하, 항목은 30개 이하입니다.
- 생성 문장은 두 가지를 검사합니다. 입력에 없는 숫자(합계·비율·날짜 포함)가 하나라도 있으면, 또는 항목 값 중 하나라도 빠지면 버리고 고정 문장(`template`)을 씁니다.
- 응답: `{items, template, text, source: "llm"|"template", note?}`. AI 오류여도 200과 고정 문장을 돌려주므로 보고서 생성이 멈추지 않습니다.
- 보고 기간·집계 기준 같은 날짜는 문장에 넣지 말고 화면 제목에 표시하세요(날짜 숫자가 검사에 걸립니다).
- "수리 확인 제안"처럼 확정 전 값은 `label`에 그렇게 적어 주세요. 문장에서 뜻을 바꾸지 말라고 지시하지만 최종 확인은 사람이 합니다.

예시: `fixtures/get_report_narrative.json`

### GET /events?after=0&limit=100

응답을 돌려준 **뒤에** 바뀐 것을 순번(`seq`)대로 내려줍니다. 백엔드는 마지막으로 처리한 `seq`를 저장해 두고 `after`로 넘기면 됩니다(`limit` 최대 500). 응답: `{events, next_after, last_seq}`.

| type | 뜻 | 백엔드가 할 일 |
|---|---|---|
| `image_remasked` | 저장된 가린 사진을 다시 가림 (`image_revision` 오름, `reason: "ai_leak"`) | 복사본을 새 버전으로 교체. 이전 버전 복사본은 조회 중지 |
| `image_hidden` | 가림 점검에서 부족 판정 | 해당 사진 조회·판독 사용 차단, 재검토 |
| `image_unhidden` | 가림 점검에서 다시 괜찮다고 기록 | 재승인 여부는 백엔드 정책대로 (자동 재승인 안 해도 됨) |
| `result_updated` | 재판독·재비교로 결과가 바뀜 (`status`, `retry` 포함) | `GET /results/{photo_id}`로 새 결과를 받아 스냅샷 추가 |
| `image_deleted` | 보관 기간이 지나 AI 서버의 가린 사진을 지움 (`policy`: `normal` 또는 `case`, `days`). 판독 결과는 남고 `masked_image_url`은 `null`, `image_deleted`가 붙음 | 백엔드 사본은 백엔드 보관 정책대로 정리. 이후 이 사진으로 `/verify`의 `before_photo_id`를 쓰면 `PHOTO_NOT_FOUND` |

`/analyze` 처리 중에 AI가 가림 누락을 찾아 다시 가린 경우에는 응답의 `image_revision`이 이미 2 이상이고 알림도 함께 남습니다. 백엔드가 알고 있는 버전 이하의 알림은 무시하면 됩니다. 알림 기록은 작업자 1개 기준이라, 서버 작업자를 늘리려면 DB·Redis로 옮겨야 합니다. 예시: `fixtures/get_events.json`

### GET /results/{photo_id}

저장된 최신 결과를 돌려줍니다. AI 지연으로 `needs_review`였던 건은 AI가 저장된 가린 사진으로 다시 판독하므로, 시간이 지나면 등급이 채워집니다. 결과가 바뀌면 `GET /events`에 `result_updated`가 남으니, 계속 조회하지 말고 알림을 받은 뒤 가져가면 됩니다. 결과가 없으면 404입니다.

### 오류 응답

모든 오류 응답의 본문은 `{"detail": "사람이 읽는 설명", "code": "오류 코드"}`입니다. 백엔드는 `code`로 분기하고, `detail`은 기록·표시에만 씁니다(문장은 바뀔 수 있음). 형식 오류는 모두 400이며 422는 쓰지 않습니다. 4xx 오류는 사진을 저장하지 않으므로, 원인을 고친 뒤 같은 `photo_id`로 다시 보내도 됩니다.

| HTTP | code | 경우 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 타입이 틀리거나 필수 필드가 빠짐 (예: `lat`이 숫자가 아님, `after` 파일 없음, JSON 본문이 객체가 아님) |
| 400 | `INVALID_JSON` | `context_json`, `prior_json`, `device_mask_report`가 JSON이 아니거나 객체(`{...}`)가 아님 |
| 400 | `INVALID_PHOTO_ID` | `photo_id`가 `^[A-Za-z0-9_-]{1,80}$`가 아니거나 `m_`로 시작함 |
| 400 | `INVALID_CONTEXT` | `context_json` 안의 값 형식이 틀림 (예: `history.severity`가 0~3 정수가 아님, `open_cases`가 객체 목록이 아님) |
| 400 | `INVALID_PRIOR` | `prior_json`의 `facility_type`·`issue_type`이 시설 목록에 없음, `severity`·`bbox`·`location_tags` 형식 오류 |
| 400 | `INVALID_MODE` | `/verify`의 `mode`가 `repair`·`change`가 아님 |
| 400 | `BEFORE_REQUIRED` | `/verify`에 `before_photo_id`와 `before`가 모두 없음 |
| 400 | `INVALID_AUDIT` | `/audit/masking/{id}` 본문이 객체가 아님, `ok`가 JSON 불리언이 아님, `reviewer`·`note`가 문자열이 아님 |
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
| 410 | `PHOTO_HIDDEN` | 가림 점검에서 보류된 사진 (`GET /images`, `/verify`의 `before_photo_id`). `{"ok": true}`로 보류를 풀면 다시 쓸 수 있음 |
| 413 | `PHOTO_TOO_LARGE` | 사진이 15MB 초과 |

사진 형식이 틀리거나 읽을 수 없는 경우, 마스킹에 실패한 경우는 HTTP 200에 `status: "error"`로 돌려줍니다. **HTTP 200이어도 접수 성공이 아닙니다.** 백엔드는 이를 기술·마스킹 실패로 기록하고(품질 재촬영 횟수에는 넣지 않음) 앱에 다시 찍기 안내를 보냅니다.

## 응답에서 백엔드가 보는 값

### status: 판독이 어떻게 끝났나

어르신 화면에 무엇을 띄울지는 백엔드가 이 값을 업무 상태(`nextAction` 등)로 바꿔 정합니다.

| 값 | 뜻 | 백엔드 처리 |
|---|---|---|
| `ok` | 판독 완료 | 결과 저장, 사진 채택. `action`은 담당자 쪽 처리 제안 |
| `retake` | 다시 찍어야 함 (흐림·어두움·틀 밖) | 품질 실패로 기록. `masked_image_url`이 있으면 사진이 저장된 실패, `null`이면 사진 없는 실패 |
| `needs_review` | 사람이 확인해야 함 (목록 외 시설·시설 안 보임, 확신 낮음, 문제 위치가 가림 영역과 겹침, AI 지연·응답 형식 오류) | 담당자 검토로 넘김. `retry.pending`이면 재판독 중이므로 `result_updated` 알림을 기다림 |
| `error` | 사진을 처리하지 못함 (형식 오류, 마스킹 실패, 마스킹 검출기를 불러오지 못함). 아무것도 저장하지 않음 | 접수 실패로 기록. 품질 재촬영 횟수에는 넣지 않음 |

### action: 담당자 쪽 처리 제안

AI의 `action`은 제안입니다. 알림을 보낼지, 사건을 만들지, 종결할지는 백엔드 규칙과 담당자가 정합니다.

업무 시스템은 등급에서 나오는 값(`alert_now`, `weekly_list`, `recheck_next`, `record_only`, `human_review_urgent`, `regrade`의 `new_grade`)을 쓰지 않습니다. 검토 분류는 `status`와 `issues`로 정합니다(`needs_review`와 문제가 있는 `ok`는 확인 필요, 문제가 없는 `ok`는 이상 미발견). 수리 확인의 `close_case`·`re_request`만 담당자 참고 제안으로 씁니다.

| 값 | 뜻 |
|---|---|
| `alert_now` | 긴급 판정 |
| `weekly_list` | 주의 판정 |
| `recheck_next` | 관찰 판정. 다음 순회 재촬영 후보 |
| `record_only` | 정상 판정 |
| `human_review_urgent` | 잠정 긴급인데 AI 확신이 낮음. 담당자 확인을 서두를 대상 |
| `human_review` | 담당자 확인 대기 |
| `retake` | 재촬영 |
| `close_case` | (verify repair) 수리된 것으로 보임. 담당자 확정 전 |
| `re_request` | (verify repair) 수리되지 않은 것으로 보임. 담당자 확정 전 |
| `regrade` | (verify change) 나빠짐. `new_grade`는 제안 등급 |
| `keep` | (verify change) 변화 없음 |

### senior_message: 어르신 안내 문구 (참고값)

AI 응답의 `senior_message`는 상황별로 고정된 안내 문구(`taxonomy.json`)입니다. 위험도·이상 여부가 드러나는 문구도 있으므로 **어르신 화면에 그대로 띄우지 않습니다.** 백엔드가 재촬영·진행 안내로 바꿔 내려보냅니다. 잠정 긴급일 때의 접근 금지 안내(`danger_keep_away`)도 어르신에게 보여 주지 않습니다(2026-10-11 팀 결정).

### 그 밖의 주요 필드

- `image_revision`: 가린 사진 버전(처음 1). 다시 가리면 오르고 `GET /events`에 `image_remasked`로 알림. 사진이 저장되지 않았으면 `null`
- `masked_image_url`: AI에 저장된 가린 사진 주소. 백엔드가 받아 복사본을 만듭니다. 흐림·어두움 재촬영, `error`이면 `null`입니다. `/verify` 응답에는 재촬영 사진의 주소가 들어 있습니다.
- `grade`: `정상 | 관찰 | 주의 | 긴급`. `needs_review`일 때는 잠정 등급. AI 내부 판정값이며 업무 시스템은 화면·판정·알림·우선순위에 쓰지 않습니다. `/verify`의 `prior_json.severity`에 넘기기 위해 원문 결과에는 보관합니다
- `grade_reasons`: 등급 판정 근거 문장 목록. 업무 시스템은 등급을 쓰지 않으므로 화면에 표시하지 않습니다. 담당자 화면의 판독 근거는 `issues[]`(문제 유형·위치·근거)로 보여 줍니다
- `issues[].bbox`: 문제 위치. 사진 대비 비율 `[왼쪽, 위, 오른쪽, 아래]`. 사진 위에 네모로 표시할 때 사용
- `facility.facility_id`: AI 쪽 시설 대장 ID. 업무 연동에서는 `lat`/`lon`을 보내지 않으므로 비어 있고, 시설 연결은 백엔드 대장이 기준
- `facility.match_ambiguous`: AI 쪽 대장에서 가까운 같은 종류 시설이 여럿이라 위치 확인이 필요함. `lat`/`lon`을 보내지 않으면 늘 `false`
- `duplicate_of`: `context_json.open_cases`로 넘긴 건 중 같은 시설·같은 문제의 ID. 참고값이며 사건 연결은 백엔드 규칙과 담당자가 정함
- `retry`: AI 지연으로 나중에 다시 판독할 때 `{pending, attempts, next_at}`. 세 번 실패하면 `gave_up: true`. 재판독할 사진·맥락이 사라졌거나 재판독 중 오류가 반복돼도 `gave_up: true`와 `reason`을 남기고 끝냅니다. AI 서버가 다시 켜져도 원래 예정 시각대로 이어 갑니다
- `meta.privacy`: `mode`(`device+server` 앱 마스킹 + 서버 저장 전 확인, `server_only` 시험용 서버 마스킹), 앱 보고(`device`, `boxes`를 보냈으면 그 개수 포함), 저장 전 확인에서 앱이 놓친 것을 가린 수(`server_added`, 0이 정상), 앱이 이미 가린 곳에서 서버가 다시 찾아 가린 수(`server_redetected`, `boxes`를 보냈을 때만. 모자이크한 얼굴도 다시 얼굴로 검출되기 때문이며 앱 누락이 아님), 서버 확인 단계에서 가린 얼굴·번호판·차량 수(`faces`, `plates`, `vehicles`), 가린 위치(`boxes`, 비율 좌표), 사용한 검출기(`detectors`), 가린 면적 비율, AI가 찾아 추가로 가린 수(`leaks_fixed`)
- `meta`: 그 밖에 모델, 프롬프트 식별값, 처리 시간. 담당자 판정 기록과 함께 저장하면 나중에 정확도 분석에 씀

## 백엔드 담당에게 부탁할 것

1. AI의 `photo_id`, AI 결과(`status`, `issues`)와 담당자 판정(이상 확인/이상 미발견/추가 확인), 판정 이유를 함께 저장해 주세요. AI와 담당자 판정의 일치율로 판독 정확도를 평가하고 기준표를 고치는 데 쓰는 가장 중요한 자료입니다. 업무 시스템은 등급을 쓰지 않으므로 등급 정정 기록은 남기지 않습니다.
2. AI 결과는 스냅샷으로 쌓고, 담당자 판단이 어느 스냅샷을 보고 내려졌는지 남겨 주세요. 재판독으로 결과가 바뀌어도 사람 판단을 덮어쓰지 않게 하기 위해서입니다.
3. AI가 오류를 내거나 늦어도 어르신 화면이 멈추지 않게, 백엔드가 담당자 검토로 넘기고 앱에는 다음 장소 안내를 보내 주세요. 사진이 저장된 경우(`masked_image_url` 있음) AI가 나중에 다시 판독하므로, 결과는 `result_updated` 알림으로 받습니다.
4. 앱 마스킹 품질을 보기 위해 `meta.privacy.server_added`(0이 정상)를 주 단위로 모아 AI 담당에게 공유해 주세요. 앱이 `deviceMaskReport.boxes`를 보내야 이 값이 '앱이 놓친 수'가 됩니다. 보내지 않으면 서버가 앱이 가린 얼굴을 다시 세어 실제보다 크게 나옵니다.

## 백엔드 연동 시 꼭 맞출 것

오류 없이 조용히 결과가 틀어질 수 있는 지점입니다.

1. **위치는 `location_tags`로 보내고 `lat`/`lon`은 보내지 마세요.** 위치를 보내면 AI가 자기 쪽 시설 대장(`data/sample_ledger.csv`, 가짜 좌표)과 맞춰 엉뚱한 `facility_id`를 붙이거나, 근처 후보가 여럿이라는 이유로 `needs_review`로 바꿀 수 있습니다. 백엔드 시설 대장의 태그를 쉼표로 넣으면 위치 가중치가 그 태그로 계산됩니다. 태그가 없으면 빈 문자열을 보냅니다. 응답의 `facility.facility_id`, `dept`, `duplicate_of`는 참고값이며 백엔드 대장·사건 규칙이 우선합니다.
2. **`device_mask_report`의 키는 snake_case입니다.** 앱이 `appVersion`으로 보내면 백엔드가 `app_version`으로 바꿔 넘겨 주세요. 바꾸지 않으면 오류 없이 값만 빠집니다.
3. **`/analyze`의 같은 `photo_id`에 다른 사진을 보내도 AI는 막지 않고 저장된 결과를 돌려줍니다.** 입력 해시 검사는 백엔드가 합니다. (`/privacy/mask`는 AI가 409로 막습니다.)
4. **재촬영(`status: "retake"`)이어도 사진이 저장된 경우가 있습니다.** 흐림·어두움은 저장하지 않지만(`masked_image_url: null`), 틀 밖 판정은 AI가 사진을 본 뒤라 저장되어 있습니다. (시설 안 보임은 `retake`가 아니라 `needs_review`입니다.) `masked_image_url` 유무로 구분하세요.
5. **전후 비교 결과 값**: repair 모드는 `fixed | not_fixed | unclear`, change 모드는 `worse | same | better | unclear`입니다. 같은 시설이 아니거나 부위가 안 보이면 `status: "retake"`입니다. 업무 분류로는 `fixed`=수리 확인 제안, `not_fixed`=미수리 의심, `worse`=악화 의심, `retake`·`unclear`·확신 낮음=비교 불가로 연결합니다. `close_case`·`re_request`·`regrade`는 제안이며 확정은 담당자가 합니다.
6. **재판독은 백엔드도 기한을 두세요.** AI는 30초·2분·10분 간격으로 세 번 다시 판독하고 끝냅니다. `result_updated` 알림을 받거나, 정해 둔 시간 안에 결과가 확정되지 않으면 담당자 검토로 넘깁니다.
7. **운영 설정**: `REQUIRE_DEVICE_MASKING=1`(앱이 가리지 않은 사진 거절), `API_KEYS` 설정, 서버 작업자 1개. 현재 값은 `GET /health`의 `require_device_masking`으로 확인합니다. `GET /health`의 `ok`가 `false`이면 마스킹 검출기(`detectors`)를 불러오지 못한 상태이며, 그동안 들어온 사진은 가리지 않고 `status: "error"`로 거절합니다. AI 서버의 가린 사진은 보관 기간이 지나면 지웁니다(`RETENTION_NORMAL_DAYS` 기본 180일: 이상 없는 정상 사진, `RETENTION_CASE_DAYS` 기본 1095일: 이상·담당자 확인·수리 확인·보수 증거 사진, 참고 사진은 지우지 않음). 지울 때 `GET /events`에 `image_deleted`가 남습니다.
