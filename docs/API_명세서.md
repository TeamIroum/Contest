# 시니어 동네 점검단 API 명세서 v1.0

작성일: 2026-10-10. 모바일 대응 웹과 관리자 웹의 공통 REST 개발 기준이다. 기존 v0.2의 최신 수정 내용과 DB 트랜잭션 보완을 기준본 v1.0으로 정리했다. 실제 구현·기관 승인·운영 인증을 의미하지 않는다. 시연용 고정 인증은 운영 인증과 구분하며, 아래 경로는 `/api/v1` 기준이다. 미정 운영값과 미지원 AI 연동은 12절에 별도로 남긴다.

2026-10-10 AI 계약 대조 수정: 업무 API의 기준 문서는 이 파일이다. [AI_연동_계약.md](AI_연동_계약.md)는 제공된 AI API와의 변환·연동 선결 사항을 정의한다. [프론트_전달용_API.md](프론트_전달용_API.md)는 이 계약의 요약이다. AI 판독 모듈의 호출 형식은 [AI 판독 API 계약서 v2.0](../ai/contract/API_CONTRACT.md)을 따른다. 앱은 AI를 직접 호출하지 않고, AI의 사건 종결·재요청 제안은 사람 판단 전에는 업무 상태를 바꾸지 않는다.

2026-10-10 PWA 확정 반영: 시니어 앱은 PWA로 확정했다. 네이티브 앱 전용 인증 경로(응답 본문의 갱신 토큰, OS 보안 저장소)를 이 계약에서 뺐다. AI 계약 v2.0에 따라 개인정보 단독 검사, 보고 문장 생성, 사진 버전·보류 전파를 연동 가능 항목으로 고쳤다.

## 1. 설계 원칙

1. 개인이 로그인하고 조 단위 순회를 공유한다. 로그인과 출석은 다르다.
2. 시설, 코스의 장소, 날짜별 순회 장소, 사진 시도를 별도 ID로 식별한다.
3. 활동 완료, 사진 전송, AI 분석, 사람 검토, 시설 문제 종결을 분리한다.
4. 시니어에게 시설 이상·AI 위험도를 반환하지 않는다. 품질 재촬영과 진행 안내만 제공한다.
5. 기관·지역·시설 관할은 서버에서 검증한다. 프론트가 보내는 조직 ID만으로 권한을 부여하지 않는다.
6. 실제 부서 배정은 시설 대장의 관리 주체를 우선한다. AI 시설 분류는 검증 자료다.
7. 업무 변경은 수행자·시간·전후 값·사유를 감사 이력으로 남긴다.

## 2. 공통 계약

- JSON camelCase, ID 문자열(UUID 등), 시간 ISO 8601 UTC, 활동 날짜 Asia/Seoul 기준 YYYY-MM-DD.
- 인증은 Spring Security + 짧은 Access JWT + 회전하는 Refresh Token + Redis로 구성한다. 시니어 PWA와 관리자 웹이 공통 업무 API를 사용한다. 네이티브 앱은 현재 범위에 없다.
- API 요청은 Authorization: Bearer <accessToken>을 사용한다. 갱신 토큰은 모든 클라이언트에서 Secure/HttpOnly 쿠키로만 주고받는다. 쿠키 기반 갱신·로그아웃은 CSRF 방어를 적용한다. Access Token은 메모리에 보관하고 localStorage 등 영속 저장소에 두지 않는다. CORS는 허용 Origin 목록을 지정한다.
- `GET /auth/csrf`가 csrfToken을 반환하고 변경 요청은 `X-CSRF-Token`으로 전송하는 계약 제안. 인증번호 요청 등 로그인 전 변경에도 정책을 적용한다.
- 배정·판정·보수 등 생성 작업은 `Idempotency-Key: UUID` 필수. 동일 사용자·대상 경로·키·내용 재시도는 기존 결과, 같은 키의 다른 내용은 409. 사진 제출은 clientSubmissionId, 순회 이벤트는 clientEventId를 멱등 키로 사용하므로 별도 헤더는 요구하지 않는다. 요청 해시는 대상 ID·파일 해시·변경에 영향을 주는 메타데이터를 포함한다.
- 기존 자원 수정은 version을 전달한다. 충돌은 409 VERSION_CONFLICT 후 최신 조회. 기기 사진 제출은 version 대신 제출 ID·구도 고유 제약 사용.
- 일반 성공 200, 생성 201, 영속 접수 후 후속 작업이 남으면 202. 사진 제출의 저장 없는 품질 실패도 200으로 결과를 반환하므로 receiptStatus를 반드시 확인한다. 목록은 items/page/size/totalElements, 기본 page=0,size=20, 최대 size=100.
- 모든 목록 필터는 접근 범위 안에서 적용한다. 정렬 허용 필드·안정적 ID 보조 정렬을 정의한다.
- 예시 ID·시각·문구는 설명용이다.

```json
{
  "code":"VERSION_CONFLICT",
  "message":"다른 사용자가 내용을 변경했습니다. 다시 확인해 주세요.",
  "requestId":"req-001",
  "fieldErrors":[]
}
```

400 입력 / 401 세션 없음·만료 / 403 권한 / 404 없음 또는 숨김 / 409 상태·중복 충돌 / 413 크기 / 415 파일 형식 / 422 업무 조건 불충족 / 429 제한(Retry-After) / 503 일시 장애. 메시지에 내부 경로·인증 코드·사진 접근 URL·비밀 값을 넣지 않는다.

## 3. 역할과 접근 범위

| 역할 | 허용 범위 |
|---|---|
| SENIOR | 본인의 날짜별 참여 배정과 해당 조 공동 순회, 본인 개인정보 |
| ACTIVITY_MANAGER | 담당 수행기관·거점의 참여자, 조, 출결, 코스 배정, 재촬영 일정 |
| FACILITY_MANAGER | 본인 부서·시설 관할의 사진 검토, 사건, 보수, 수리 확인 |
| PROGRAM_MANAGER | 승인된 시/사업 범위의 기관·역할·정책 관리와 전체 집계 |

계정은 여러 역할을 가질 수 있다. 활동 관리자에게 사진 판정·보수 변경 권한을 자동 부여하지 않는다. 시니어에게 다른 조원 전화번호·민감한 건강 정보 등을 기본 노출하지 않는다.

## 4. 계정·인증

### JWT + Redis

- Access Token은 JWT, Refresh Token은 예측 불가능한 임의 문자열이다. Redis에는 Refresh Token 해시·사용자·로그인 세션 ID·만료·회전 상태를 저장한다.
- 하나의 계정이 여러 기기에 로그인할 수 있다. 시니어 두 명은 서로 다른 계정이며, 기기별 세션과 조별 순회는 별개다.
- SMS 검증 또는 관리자 MFA 최종 성공 시 토큰을 발급한다. MFA 전 중간 응답에는 업무용 토큰을 발급하지 않는다.
- 갱신 토큰은 응답 본문으로 반환하지 않고 Secure/HttpOnly 쿠키로만 전달한다. 클라이언트(시니어 PWA, 관리자 웹)는 등록된 클라이언트 정책과 요청 검증으로 구분하며 비밀 값이 아닌 clientId만으로 신뢰를 부여하지 않는다.
- PWA 페이지를 다시 열면 메모리의 Access Token이 없으므로 쿠키 갱신으로 새 토큰을 받는다. 오프라인으로 쌓인 사진·이벤트는 갱신 성공 후 전송한다.
- API는 Access JWT의 서명·만료·issuer·audience를 검증하고, 세션/계정 상태·권한 버전도 확인해 계정 중지·권한 변경을 반영한다. Redis 장애 시 보호 API를 임의 허용하지 않는다. 가용성·복구 정책은 별도 설계한다.
- 갱신은 기존 토큰 소모와 새 토큰 발급을 원자적으로 처리한다. 이전 토큰 재사용 탐지 시 해당 로그인 묶음을 종료한다. 다중 탭·동시 요청은 클라이언트에서 갱신을 단일 실행하도록 하고 네트워크 재시도 정책을 합의한다.

| API | 계약 |
|---|---|
| GET `/auth/csrf` | csrfToken |
| POST `/auth/sms-challenges` | phoneNumber → challengeId, expiresAt, resendAvailableAt. 202 |
| POST `/auth/sms-verifications` | challengeId, code, clientId, deviceLabel → 인증 성공 시 토큰 응답 |
| POST `/auth/admin/sessions` | loginId, password, clientId, deviceLabel → MFA 요청 또는 최종 토큰 응답 |
| POST `/auth/admin/mfa-verifications` | mfaChallengeId, code → 최종 토큰 응답 |
| POST `/auth/token/refresh` | HttpOnly 쿠키 + CSRF → 새 Access 발급, 새 Refresh는 쿠키로 회전 |
| DELETE `/auth/session` | 현재 로그인 세션 폐기, 웹 쿠키 제거 |
| GET `/me/sessions` | 본인 기기별 세션 목록: id, deviceLabel, createdAt, lastUsedAt, current |
| DELETE `/me/sessions/{sessionId}` | 본인 특정 기기 로그아웃 |
| DELETE `/me/sessions` | 본인 모든 기기 로그아웃 |
| GET `/me` | id, name, roles, 기관 범위·계정 상태 |

```json
{
  "tokenType":"Bearer",
  "accessToken":"jwt",
  "accessTokenExpiresAt":"2026-10-10T01:15:00Z",
  "refreshTokenExpiresAt":"2026-10-24T01:00:00Z",
  "sessionId":"session-01",
  "user":{"id":"u-01","name":"김○○","roles":["SENIOR"]}
}
```

시간은 예시이며 만료 정책은 미확정이다. Refresh Token 값은 응답 본문에 넣지 않고 쿠키로만 전달한다(만료 시각만 본문에 표시). JWT에는 전화번호·사진 주소 같은 개인정보를 넣지 않는다. 로그·오류 수집에 토큰을 기록하지 않는다. Redis에는 인증 상태·SMS 검증 정보·시도 제한을 저장하고, 업무 데이터의 원본은 PostgreSQL에 둔다.

관리자가 참여자를 등록하고 계정을 활성화한다. 미등록 번호는 공개 가입으로 연결하지 않는다. SMS는 번호 소유 확인이며 통신사 본인확인과 구분한다.

SMS 발송·검증 횟수, 만료, 재전송 제한, 전화번호 정규화를 서버에서 적용한다. 계정 존재 노출을 줄이도록 공개 응답은 통일하고 안내·기관 문의 동작을 정의한다. 계정 중지 시 기존 세션도 차단한다. 관리자 MFA 정책·세션 만료·장기 로그인은 운영 전에 확정한다.

시연 전용 `POST /auth/demo/otp-requests`, `POST /auth/demo/otp-verifications`는 시연 설정에서만 활성화하고 운영에서는 404다. 검증 성공 응답·갱신 방식은 위 계약과 같다. 관리자 로그인은 시연도 `/auth/admin/sessions`를 사용한다. 고정 시연 코드·MFA 생략은 시연 계정에만 적용하며 실제 운영 인증과 구분한다.

## 5. 기준 데이터와 참여자 운영

각 생성은 201 + 생성 자원, 수정은 최신 자원 + version을 반환한다. DELETE는 관련 이력이 있으면 물리 삭제 대신 비활성화를 사용한다.

| API | 역할·핵심 필드 |
|---|---|
| GET/POST `/admin/organizations` | 총괄: 수행기관·부서·거점(유형과 관계) |
| PATCH `/admin/organizations/{id}` | 이름·상태·version |
| GET/POST `/admin/participants` | 활동 담당: name, phoneNumber, organizationId, 활동 가능 일정 |
| GET/PATCH `/admin/participants/{id}` | 상세·허용 프로필 수정, version |
| POST `/admin/participants/{id}/phone-change-requests` | 새 번호 소유 확인·기관 확인 절차 시작 |
| POST `/admin/participants/{id}/status-changes` | ACTIVE/SUSPENDED/ENDED, reason, version; 세션 처리 포함 |
| GET/POST `/admin/teams` | 조명·기관·조원 두 명 |
| PATCH `/admin/teams/{id}` | 조명·기본 구성 변경, effectiveFrom, version |
| GET/POST `/admin/facilities` | 관할 담당: 시설 종류·좌표·관리 부서·구역·상태 |
| GET/PATCH `/admin/facilities/{id}` | 시설 정보 수정·담당 변경, version |
| GET/POST `/admin/routes` | 활동 담당: 출발/복귀 거점·순서 있는 시설·구도·안내 |
| GET/PATCH `/admin/routes/{id}` | 코스 정보 조회·활성 상태 수정. 공개 버전 내용은 수정 금지 |
| POST `/admin/routes/{id}/versions` | 장소·구도·출발/복귀 거점으로 새 버전 생성 |
| POST `/admin/patrols` | date, teamId, routeVersionId, scheduledStartAt, expectedReturnAt |
| POST `/admin/patrols/{id}/reassignments` | 출발 전 실제 조원·코스 변경, reason, version |
| POST `/admin/patrols/{id}/cancellations` | reason, version |
| POST `/admin/patrols/{id}/stops/{stopId}/capture-slots` | 출발 전 재점검 구도 추가: purpose=REINSPECTION, label, instruction, required, referenceMediaId, version(순회). 같은 시설의 승인 참고 사진만 허용 |

시설 유형 SIDEWALK/DRAIN/BENCH/SAFETY_FENCE/EXERCISE_EQUIPMENT. 코스 저장 시 순서·필수 구도·안내를 검증한다. 순회는 배정 당시 구성과 코스를 스냅샷으로 보존한다. 동일 사람의 시간 중복 배정을 막는다. 출발 후 조원·코스 변경은 일반 수정으로 허용하지 않고 예외 처리 이력 정책을 별도 정의한다.

## 6. 시니어 순회 API

| API | 책임 |
|---|---|
| GET `/me/patrols?date=` | 본인 배정 목록; 없으면 items=[] |
| GET `/patrols/{id}` | 조 공동 진행·현재 장소·개인 출결·참고 안내 |
| GET `/patrols/{id}/offline-package` | 버전 있는 코스·참고 사진·구도·안내 사전 저장 |
| POST `/patrols/{id}/stops/{stopId}/inspections` | multipart, 마스킹 사진 접수 |
| GET `/inspections/{id}/capture-status` | 품질 상태·허용 다음 행동만 반환 |
| POST `/inspections/{id}/processing-retries` | 영속 접수한 사진의 기술 재처리. Idempotency-Key 필수 |
| POST `/patrols/{id}/stops/{stopId}/skip-events` | reason=UNSAFE/NOT_FOUND/OTHER, clientEventId, occurredAt |
| POST `/patrols/{id}/pause-events` | type=PAUSE_STARTED/PAUSE_ENDED, clientEventId, occurredAt |
| POST `/patrols/{id}/return-requests` | returnReason=NORMAL/ABORT, abortReason=HEALTH/DEVICE/OTHER(ABORT 때), clientEventId, occurredAt |
| GET `/patrols/{id}/sync-state` | revision, 장소·구도 접수/품질 결과, 사용자 권한·동기화 허용 상태 |

시니어 요청의 사용자 ID를 신뢰하지 않고 세션 사용자에서 제출자를 결정한다. 초기 동기화는 온라인 활성 화면 5초 주기 조회 제안. WebSocket/SSE는 이후 선택한다. 권한 철회·계정 중지 시 동기화 중단과 기관 문의를 안내한다.

### 순회 상세 응답 예시

```json
{
  "id":"patrol-01",
  "version":3,
  "revision":7,
  "date":"2026-10-10",
  "activityStatus":"IN_PROGRESS",
  "returnReason":null,
  "routeName":"마을회관 코스",
  "members":[
    {"participantId":"participant-01","userId":"u-01","name":"김○○","attendance":"PRESENT","returned":false},
    {"participantId":"participant-02","userId":"u-02","name":"박○○","attendance":"PRESENT","returned":false}
  ],
  "progress":{"total":3,"captured":1,"skipped":0,"reviewRequested":0},
  "currentStopId":"stop-02",
  "stops":[{
    "id":"stop-02","order":2,"facilityId":"fac-02","facilityType":"DRAIN",
    "name":"마을회관 앞 빗물받이","visitStatus":"PENDING",
    "latitude":37.0,"longitude":127.0,
    "instruction":"덮개 전체와 주변 바닥을 찍어 주세요.",
    "captureSlots":[{
      "id":"slot-02","label":"전체 모습","required":true,"purpose":"REGULAR","receiptStatus":"NONE",
      "activeInspectionId":null,"acceptedInspectionId":null,"referenceMediaId":"media-ref-02"
    }]
  }],
  "contact":{"name":"활동 담당자","phoneNumber":"연락처"},
  "expectedReturnAt":"2026-10-10T03:00:00Z"
}
```

장소별 촬영 구도 수는 서버 지정이다. 고정 수치·참고 좌표는 예시다. 장소 조회에 시설 이상 내용은 없다. 접수·진행을 GET으로 변경하지 않는다. 필요한 상태 갱신은 작업 처리·명시된 변경 API에서 수행한다.

`version`은 관리자 변경의 낙관적 잠금용, `revision`은 자식 구도·사진 결과까지 포함한 순회 동기화용 증가 번호다. 서로 대체하지 않는다. 예시의 slot-02 등은 설명용이며 실제 구도 ID는 UUID다. 서버는 순회→장소→구도 관계를 함께 검증한다.

사진 처리·복귀·건너뛰기가 함께 갱신하는 DB 행은 순회→구도→사진 시도→media 순서로 잠근다. 구도를 먼저 잡고 순회 revision을 나중에 잠그면 복귀 처리와 교착할 수 있다. 상세 잠금 순서와 독립 연결 실행 검증은 [DB 설계](DB_설계.md)의 트랜잭션 절을 따른다.

offline-package는 packageVersion, patrol(동일 순회 형식), assets[{mediaId,mediaVersion,contentPath}]를 반환한다. contentPath는 권한 검사되는 `/media/{id}/content`다. sync-state는 patrolId, revision, activityStatus, stops/captureSlots, syncAllowed, syncDeadlineAt, revokedMediaIds를 포함한다. 현재 허용된 참고 사진 목록·버전을 함께 반환하여 누락된 증분 통지에도 로컬 폐기 여부를 재검증할 수 있어야 한다. 오프라인 자료는 즉시 회수할 수 없으며 다음 연결 때 폐기한다.

### 사진 제출 계약

multipart: file, clientSubmissionId(UUID), captureSlotId(UUID), attemptId(UUID), capturedAt, maskingVersion, deviceMasked(true), deviceMaskReport(JSON: faces, plates, vehicles, detector, appVersion), 선택 위치(latitude/longitude/locationAccuracyMeters).

- JPEG/PNG, 파일당 10 MiB(10,485,760 bytes) 이하로 정리한다. AI 입력 15MB 한도보다 작은 업무 제한이다. 실제 MIME·디코딩 결과·픽셀 수를 서버 검증한다. 시니어 PWA는 앱 안 카메라 화면을 캔버스에서 마스킹한 뒤 JPEG로 만들어 전송한다(EXIF 없음). HEIC/AVIF/WebP는 받지 않는다.
- 파일은 기기 내 마스킹 완료본만 전송한다. 클라이언트 주장만 신뢰하지 않고 서버 재검사한다. 기기 마스킹 기준은 [앱 마스킹 구현 안내서](../ai/contract/APP_MASKING_GUIDE.md)를 따른다.
- AI 호출 시 deviceMaskReport는 snake_case(`app_version` 등)로 바꿔 넘기고, 위치는 좌표 대신 시설 대장의 위치 태그(`location_tags`)로 넘긴다([AI 계약서](../ai/contract/API_CONTRACT.md) "백엔드 연동 시 꼭 맞출 것").
- 같은 사진 재전송은 같은 clientSubmissionId/attemptId. 새 재촬영은 새 ID.
- 구도별 최초 촬영 + 품질 재촬영 2회로 수정안을 통일한다. 3번째 품질 실패 시 검토 요청을 만들고 다음 구도/장소로 진행한다. 기술 장애·마스킹 장애는 횟수에서 제외한다.

```json
{
  "inspectionId":"insp-01","clientSubmissionId":"uuid","receiptStatus":"RECEIVED",
  "captureStatus":"REVIEW_REQUESTED","duplicate":false,"patrolRevision":8
}
```

위 예시는 사진 저장 후 AI 재판독이 남은 202 응답이다. 현재 AI `/analyze`는 통합 동기 호출이므로 응답 전 서버 검사·AI 응답을 기다리고, 검사 완료 사진의 S3 저장과 DB 커밋 후에만 RECEIVED를 반환한다. 판독까지 끝났으면 200, 후속 작업이 남으면 202다. 저장 없는 품질 실패도 200 + NO_MEDIA/RETAKE_REQUIRED로 반환한다. 상세 접수·리스 복구는 [AI 연동 계약 2절](AI_연동_계약.md#2-현재-ai-계약에-맞춘-접수-방식)을 따른다. 검사 전 바이트를 먼저 S3에 저장하지 않는다.

receiptStatus: RECEIVING(예약/수신 중), RECEIVED(사진 영속 저장 완료), NO_MEDIA(사진 없는 품질 실패), RETRY_REQUIRED(사진 재전송 필요). NONE은 아직 시도 없는 구도 조회 전용이다. 503/연결 단절은 접수 성공이 아니며 같은 제출 ID로 복구한다. AI HTTP 200 + status:error를 202로 변환하지 않는다.

동일 ID·동일 입력은 기존 결과를 반환하고, 다른 내용은 409 SUBMISSION_CONFLICT다. 다른 조원이 처리 중이면 409 SLOT_PROCESSING + existingInspectionId, 채택 완료면 200 + duplicate=true + 기존 inspectionId를 반환한다. 동일 제출 자체가 아직 수신 중이면 409 SUBMISSION_PROCESSING + 기존 ID를 반환하고 사진을 보관한다. NO_MEDIA 최종 결과도 같은 ID로 재현한다. 실제 새 촬영은 새 clientSubmissionId/attemptId다.

검토로 인계하거나 건너뛴 구도에는 새 현장 시도를 자동 접수하지 않는다(409 SLOT_RESOLVED). 필요하면 다음 순회에 새 재점검 구도를 배정한다. 기존 접수의 늦은 분석 결과는 보존하되 진행 상태를 과거로 되돌리지 않는다. 오류에 기존 inspectionId를 포함하더라도 동일 순회 접근 권한을 확인한다.

### 상태 조회

```json
{
  "inspectionId":"insp-01",
  "receiptStatus":"RECEIVED",
  "captureStatus":"ACCEPTED",
  "nextAction":"NEXT_STOP",
  "retakesRemaining":2,
  "nextStopId":"stop-03",
  "nextCaptureSlotId":null,
  "patrolRevision":9
}
```

captureStatus: PROCESSING/ACCEPTED/RETAKE_REQUIRED/TECHNICAL_FAILED/REVIEW_REQUESTED.

nextAction: WAIT/RETAKE/RETRY/NEXT_CAPTURE/NEXT_STOP/RETURN_TO_CENTER.

`POST /inspections/{id}/processing-retries`: 이미 접수한 사진의 기술 처리 재시도. 재촬영을 요구하지 않고 동일 작업을 중복 실행하지 않는다.

이 API도 Idempotency-Key를 요구한다. RECEIVED일 때만 서버 보관본으로 재처리한다. RETRY_REQUIRED이면 409 PHOTO_RESUBMISSION_REQUIRED로 기존 사진 업로드 경로에 같은 ID·파일 재전송을 안내한다. AI 내부 retry.pending이면 재실행 대신 기존 추적 상태를 반환한다. 구도 잠금과 작업 세대 확인 후 active를 재획득해야 하며 다른 시도가 채택되었으면 이전 시도를 재활성화하지 않는다.

검토로 인계된 구도는 시니어 재시도로 다시 열지 않는다. 저장본을 새로 분석해야 하면 관리자/워커가 같은 inspection에 새 작업 세대·provider_photo_id·입력 해시를 만들고 결과만 추가한다. 기존 AI photo_id로 다른 바이트를 재전송하지 않는다.

필수 구도 각각이 채택되거나 검토 요청으로 인계되면 다음 장소를 안내한다. AI 계약상 현재는 품질 결과만 먼저 분리해 받을 수 없으므로 통합 응답 후 진행하며, 지연 판독은 REVIEW_REQUESTED로 인계 후 기다리지 않는다. 필수 구도가 모두 채택되면 장소 CAPTURED, 하나라도 검토로 인계되면 REVIEW_REQUESTED다. 개인정보 검사 실패 사진은 공개·시설 판독에 사용하지 않는다. AI grade/action/notes/원문 senior_message는 시니어 응답에서 제외한다.

## 7. 오프라인·중복·상태 동기화

- 마스킹 사진과 촬영·건너뛰기·복귀 이벤트를 브라우저에 영속 저장한다. 저장 성공 후 로컬 진행을 갱신한다.
- 연결 복구·페이지 재개 시 sync-state 조회 후 필요한 데이터만 재전송한다. 브라우저 백그라운드 실행에 의존하지 않는다.
- 중복 기준은 순회 장소 + 구도 + 재촬영 작업이다. 시설 ID만 사용하지 않는다.
- A의 사진 대기 중 B가 정식 접수했다면 A는 상태 확인 후 중복 전송을 억제한다. 서버도 고유 제약·트랜잭션으로 경쟁 요청을 처리한다.
- 선행 사진의 품질이 실패했다면 성공 사진으로 취급하지 않는다. 상한 미만일 때 상대 폰의 아직 미접수한 대기 사진을 새로운 시도로 접수할 수 있다. 촬영 시각은 유지하고 제출/시도 ID는 해당 사진에 부여한 고유 값을 사용한다. 이미 접수된 실패 사진을 새 ID로 자동 재제출하지 않는다.
- 활동 완료 후 늦게 도착한 사진은 촬영 당시 배정과 허용 동기화 기간을 검증한다. 기기 시각만 신뢰하지 않는다.
- 늦은 이벤트가 최신 상태를 되돌리지 않도록 이력과 진행 갱신을 분리한다.
- 기기 사진 삭제는 서버의 영속 접수 확인과 보관 정책을 따른다. 상대 기기가 전송 중이라는 이유만으로 삭제하지 않는다.

원본은 기기에 영속 저장하지 않는다. 오프라인에서는 **기기 내 마스킹 완료본만** 영속 저장한다. 제출 성공 RECEIVED와 NO_MEDIA 품질 실패 처리를 구분하고, 미접수/기술 장애 사진은 재전송을 위해 보관한다. 다른 조원이 채택되면 해당 구도의 중복 대기 사진을 정리할 수 있다. PWA는 순회 시작 때 저장소 유지(`navigator.storage.persist()`)를 요청하고, 영속 접수 전에는 "전송 대기"로 표시한다. 브라우저가 저장소를 지울 수 있으므로 대기 사진을 전송 완료로 간주하지 않는다.

이벤트 중복은 actor + clientEventId + payload 해시로 검사한다. 늦은 skip/return 이벤트는 이력으로 기록하되 채택 사진·완료 활동을 되돌리지 않는다. 순회/장소 잠금 아래 상태를 검증한다. ABORT 후 미방문으로 기록한 장소의 늦은 사진은 촬영 당시 활동 여부를 확인해 별도 검토하며 자동 방문 성공으로 집계하지 않는다.

## 8. 활동 관리

| API | 요청·기능 |
|---|---|
| GET `/admin/patrols?date=&activityStatus=&organizationId=` | 활동 목록 |
| GET `/admin/patrols/{id}` | 개인 출결·진행·복귀 지연·이력 |
| PUT `/admin/patrols/{id}/attendance/{participantId}` | attendance=PRESENT/ABSENT, version |
| POST `/admin/patrols/{id}/departure-confirmations` | version; 두 명 출석 검증 후 출발 |
| POST `/admin/patrols/{id}/return-confirmations` | participantId, returnedAt, version |
| POST `/admin/patrols/{id}/follow-up-records` | 연락·지연 확인·대응 기록 |

활동 상태: ASSIGNED → IN_PROGRESS → RETURNING → COMPLETED. 중도 종료도 먼저 RETURNING(returnReason=ABORT), 두 명 복귀 확인 후 ABORTED. 출발 전 취소는 CANCELLED.

장소 상태: PENDING/CAPTURED/SKIPPED/REVIEW_REQUESTED/NOT_VISITED. 전송·품질·분석 상태는 별도다. 모든 장소가 촬영·건너뛰기·확인 요청으로 처리되면 정상 복귀한다. 오프라인 미동기화가 있더라도 실제 사람의 복귀 확인은 막지 않는다.

활동 변경 응답은 patrolId, version, activityStatus, patrolRevision이다. skip-events는 patrolId, patrolRevision, nextAction, nextStopId, return-requests는 patrolId, activityStatus, patrolRevision을 반환한다.

## 9. 사진 검토와 확인 요청

| API | 기능 |
|---|---|
| GET `/admin/review-tasks?type=&status=&facilityType=&from=&to=` | 사진·반복 촬영 실패·수리 확인 공통 검토 목록 |
| GET `/admin/review-tasks/{id}` | 근거 사진·AI 결과·시설 이력·처리 대상 |
| POST `/admin/review-tasks/{id}/decisions` | decision, followUpType, reason, version |
| GET `/admin/inspections/{id}` | 권한 범위 내 마스킹 사진·촬영자·분석 상세 |
| GET `/media/{id}/content` | 권한·현재 privacy_status 확인 후 승인 사진 스트리밍. Cache-Control: no-store |
| POST `/admin/media` | multipart file, purpose=REFERENCE/REPAIR_EVIDENCE, deviceMasked, deviceMaskReport, maskingVersion, organizationId 또는 issueId. 소유 범위 확인. AI `POST /privacy/mask`(purpose `reference`/`repair_evidence`)로 개인정보 단독 검사 후 검사 완료본만 저장하며, 이 사진은 판독 실적에 넣지 않는다. AI 검사에 연결할 수 없으면 503 PRIVACY_SERVICE_NOT_READY |
| POST `/admin/media/{id}/privacy-reviews` | ok, reason, version. 실패 판정은 즉시 조회 차단·재검토·AI `POST /audit/masking/{id}` 전달 |

사진 검토 decision: ISSUE_CONFIRMED/NO_ISSUE/FOLLOW_UP_REQUIRED. 추가 확인은 followUpType=REPHOTO/SITE_VISIT. 응답은 reviewId, issueId(nullable), reinspectionRequestId(nullable), version.

사진 없는 실패 요청도 ReviewTask로 관리한다. INSPECTION은 ISSUE_CONFIRMED/NO_ISSUE/FOLLOW_UP_REQUIRED, CAPTURE_FAILURE는 FOLLOW_UP_REQUIRED/RESOLVE_CAPTURE_PROBLEM만 허용한다. FOLLOW_UP_REQUIRED는 followUpType=REPHOTO/SITE_VISIT 필수다. RESOLVE_CAPTURE_PROBLEM은 촬영 장애 처리 종료이며 시설 정상 판정이 아니다. REPAIR_VERIFICATION은 아래 수리 확인 준비·판정 API만 사용하여 서로 다른 경로에서 중복 종결하지 않는다.

관리자 표시 예시:

```json
{
  "id":"review-01",
  "type":"INSPECTION",
  "status":"OPEN",
  "version":1,
  "facilityId":"fac-01",
  "inspectionIds":["insp-01"],
  "findings":[{"facilityType":"SIDEWALK","code":"lifted","description":"보도블록 들뜸 의심","reason":"일부 블록이 주변보다 돌출되어 보임"}]
}
```

AI 상태 PENDING/PROCESSING/SUCCEEDED/FAILED와 사람 검토 OPEN/IN_REVIEW/RESOLVED를 분리한다. 확인 필요·반복 실패·수리 확인은 전부 검토하고 사진상 이상 미발견은 표본 검토한다. 활동일마다 모아서 검토하며 표본 비율·긴급 대응은 운영기관과 협의한다.

관리자 AI 결과에는 status, suggestedAction, grade, gradeReasons, findings, facilityMatch, retry, modelVersion, promptVersion, resultSnapshotId를 정규화해 반환한다. 결과 스냅샷은 추가 저장하고 사람 판단이 참조한 버전을 고정한다. AI action=close_case는 사건 상태를 변경하지 않는다. 같은 검토 작업·입력 결과에 대한 재시도는 작업을 중복 생성하지 않는다. 사진 없는 기술 실패·AI gave_up도 검토에서 누락하지 않는다.

AI가 응답 이후 사진을 다시 가리거나(image_remasked) 가림 점검에서 보류(image_hidden)하거나 재판독 결과를 바꾸면(result_updated) AI `GET /events`로 알린다. 백엔드는 마지막으로 처리한 순번을 저장해 두고 이어서 가져온다. 다시 가린 사진은 새 media 행·새 객체로 저장하고(이전 버전 조회 차단), 보류 알림은 privacy_status를 QUARANTINED로 바꾸고 재검토로 넘긴다. 재판독 결과는 새 결과 스냅샷으로 추가하며 사람 판단이 참조한 스냅샷을 바꾸지 않는다.

## 10. 시설 문제·보수·재점검

| API | 요청·기능 |
|---|---|
| GET `/admin/issues?status=&departmentId=&facilityId=` | 사건 목록 |
| GET `/admin/issues/{id}` | 문제·사진·검토·보수 이력 |
| POST `/admin/issues/{id}/repair-requests` | assignedDepartmentId(권한 검증), memo, version |
| POST `/admin/issues/{id}/repair-starts` | version |
| POST `/admin/issues/{id}/repair-completions` | description, completedAt, evidenceMediaIds, version |
| GET `/admin/reinspection-requests?status=&organizationId=` | 다음 정기 순회 반영 요청 |
| POST `/admin/reinspection-requests/{id}/assignments` | captureSlotId, version(요청), patrolVersion. 구도에서 장소·순회를 역조회해 시설·출발 전 상태 검증 |
| POST `/admin/reinspection-assignments/{id}/cancellations` | reason, version(요청), patrolVersion. 출발 전 취소 후 재배정 |
| GET `/admin/issues/{id}/verifications` | 전후 사진·AI 비교·사람 결정 |
| POST `/admin/issues/{id}/verification-preparations` | requestId, repairEventId, verificationMethod=PHOTO/SITE_VISIT, beforeInspectionIds, afterInspectionIds, fieldNote, version(사건), requestVersion → 201 verificationId, verificationVersion, comparisonStatus |
| POST `/admin/issues/{id}/verification-decisions` | verificationId, decision=CLOSE/REPAIR_AGAIN/FOLLOW_UP_REQUIRED, followUpType(추가 확인 시), reason, version(사건), requestVersion, verificationVersion |
| POST `/admin/issues/{id}/inspection-links` | 점검 연결, reason, version |
| DELETE `/admin/issues/{id}/inspection-links/{inspectionId}` | 연결 해제. reason·version 전달 형식은 상세 계약에서 결정, 감사 기록 유지 |

사건 상태: OPEN → REPAIR_REQUESTED → IN_REPAIR → AWAITING_VERIFICATION → CLOSED. 재요청은 REPAIR_REQUESTED로 돌아가고 이력을 보존한다.

처리 완료와 재점검 요청 생성은 하나의 트랜잭션으로 기록한다. AI 비교는 판단 지원이며 비교 불가를 수리됨으로 처리하지 않는다. 시설 담당자가 최종 종결한다. 보통 다음 정기 순회에 촬영하고, 기다리기 어려운 위험 의심은 시설 담당자가 별도 현장 확인한다.

보수 완료 응답은 repairEventId, reinspectionRequestId, issueVersion을 반환하며 사건의 currentReinspectionRequestId를 새 요청으로 고정한다. 최종 판정은 사건·요청·verification을 같은 트랜잭션에서 잠그고 버전·현재 요청·보수 이벤트를 검증한다. 과거 회차/이미 종결된 요청은 409 STALE_VERIFICATION이다. 준비 이후 증거 변경은 새 준비 버전으로 만들고 이전 비교 결과를 재사용하지 않는다.

PHOTO 준비에는 현재 요청에 유효 배정된 구도의 채택 AFTER와 같은 사건/시설의 BEFORE가 필요하다. SITE_VISIT는 현장 확인 근거 fieldNote가 필수다. AI 비교 사용 여부는 별도 기능 설정이며 준비·사람 판정은 AI 성공을 필수 조건으로 하지 않는다. 사람이 CLOSE하려면 해당 보수 회차에 유효한 근거를 확인해야 한다. AI 비교 불가를 자동 CLOSE로 바꾸지 않는다.

REPAIR_AGAIN은 현재 요청 종료 + 사건 REPAIR_REQUESTED + 보수 재요청 이력을 원자적으로 기록한다. FOLLOW_UP_REQUIRED는 같은 보수 회차의 요청을 열린 상태로 두고 확인 방법·배정을 갱신하며 판단 이력을 추가한다. 보수 완료별 UNIQUE 요청을 중복 생성하지 않는다. 새 배정은 같은 시설의 미출발 순회, purpose=REINSPECTION 구도만 허용한다. 한 요청의 복수 구도는 허용하되 모든 활성 구도는 한 순회 안에 있어야 하며 재배정 시 기존 활성 배정을 취소한다.

출발한 배정의 이력은 취소로 지우지 않는다. 활동 종료/후속 판단 시 해당 배정에 completed_at을 기록해 활성 배정에서 제외하고, 추가 확인이 필요하면 새 미출발 순회에 배정한다. 이미 완료된 유효 배정의 사진은 원래 회차의 증거로 계속 참조할 수 있다.

같은 시설의 같은 미해결 문제는 기존 사건에 연결한다. 다른 문제·종결 후 재발은 새 건에 이전 이력을 연결한다. 여러 이상 코드와 중복 연결 규칙은 AI 계약과 함께 구체화한다.

## 11. 집계·감사·알림

| API | 기능 |
|---|---|
| GET `/admin/reports/weekly?weekStart=&organizationId=` | DB 집계·보고 문장·생성 상태 |
| POST `/admin/reports/weekly-generation-requests` | 기간·범위, 202 + jobId |
| GET `/admin/jobs/{id}` | 비동기 관리 작업 상태 |
| GET `/admin/audit-events?resourceType=&resourceId=` | 허용 관리자만 감사 조회·페이지네이션 |
| GET `/admin/notifications?read=` | 검토·처리 알림 |
| PUT `/admin/notifications/{id}/read` | 읽음 처리 |

개인 출결·활동시간은 담당자 확인으로 집계하며 사진 수로 대체하지 않는다. 사진 수·고유 시설 수·사건 수·방문·건너뛰기·미방문은 구분한다. 주 시작은 한국 시간 월요일 제안. AI 보고 문장은 확정된 집계값만 사용한다.

보고 문장은 DB 집계값을 항목 목록(`key`, `label`, `value`, `unit`)으로 만들어 AI `POST /report/narrative`에 보낸다. AI는 입력에 없는 숫자나 빠진 숫자가 있으면 고정 문장을 돌려주며, AI 장애 시에도 DB 수치 + 고정 문구로 보고를 만든다. "수리 확인 제안"처럼 확정 전 값은 label에 그렇게 적는다. AI 내부 `/report/weekly`, `/participants` 실적은 공식 수치로 쓰지 않는다.

## 12. 데이터 구조와 남은 상세 설계

주요 자원: Organization/User/Participant/Team/Facility/Route/Patrol/PatrolMember/PatrolStop/CaptureSlot/Inspection/Analysis/ReviewTask/Issue/RepairEvent/ReinspectionRequest/Verification/Media/AuditEvent.

기관·관할에 따라 필요한 사진만 접근하게 하고 서버 재검사 전 이미지는 메모리에서만 처리한다. 사진·전화번호·감사 이력 보관 기간, 기기 마스킹본 정리, 담당자 변경, 사고·기상 중단과 긴급 대응은 운영기관과 확정한다.

OpenAPI 작성 전에 구체화할 내용:

- 자원별 필수·nullable·enum·최대 길이·오류 예시.
- JWT/Refresh Token 만료·회전·CSRF·관리자 MFA·기기별 세션·계정 복구.
- 구도별 총 3회 정책의 현장 검증, 오프라인 허용 동기화 기간·초과 처리.
- AI 연동: 예시 응답(`ai/contract/fixtures/`), 사진 버전·보류 전파(`image_revision`, `GET /events`), 개인정보 단독 검사, 보고 문장 스키마는 AI 계약서 v2.0에 정의됐다. 남은 것은 실제 AI 서버와의 연동 검증, 전후 비교 결과의 업무 분류 매핑 확정, 재판독 확인 기한. [AI 연동 선결 사항](AI_연동_계약.md#8-ai-측-확인수정이-필요한-항목) 참조.
- 최대 해상도·삭제·인증 이미지 조회·오프라인 폐기 전파.
- 출발 후 예외 배정 변경.
- 알림 채널·개인 활동시간 정정·감사 권한.

구현 순서: 인증 → 순회/출발·복귀 → 촬영/품질/예외 → 관리자 검토 → 보수/재점검 → 집계·기준 데이터 편집. 실서비스 전체 계약과 10월 15일 시연 구현 범위는 구분한다.

