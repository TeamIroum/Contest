# 업무 백엔드 (Java / Spring Boot)

기준 문서: [API 명세서](../docs/API_명세서.md), [DB 설계](../docs/DB_설계.md), [AI 판독 계약](../ai/contract/API_CONTRACT.md)

DB는 PostgreSQL, 인증은 Spring Security + Access JWT + 회전하는 Refresh Token + Redis입니다. Java·Spring Boot 버전, 빌드 도구(Gradle/Maven), 마이그레이션 도구는 아직 정하지 않았습니다(서비스 설계서 "남은 결정" 5번). 정해지면 빌드 파일을 이 폴더에 추가합니다.

## 패키지 구조

기본 패키지 `kr.iroum.inspection`은 임시 이름입니다. 바꿀 때는 폴더째 옮깁니다.

| 패키지 | 맡는 것 | 주요 테이블 / API |
|---|---|---|
| `common.config` | 설정, 시간대(Asia/Seoul), JSON camelCase | — |
| `common.security` | JWT 필터, 역할·관할 검사 | — |
| `common.error` | 오류 코드와 응답 형식 (`SUBMISSION_CONFLICT`, `STALE_VERIFICATION` 등) | — |
| `common.idempotency` | Idempotency-Key, 제출 ID 중복 처리 | — |
| `common.web` | 공통 응답, 페이지, version 충돌 처리 | — |
| `auth` | 시연 로그인, 관리자 로그인, 토큰 갱신·로그아웃 | `/auth/*` |
| `organization` | 사업, 수행기관, 부서, 거점 | programs, organizations |
| `participant` | 계정, 참여자, 2인 조 편성 | users, participants, teams |
| `facility` | 시설 대장 (관리 부서 기준) | facilities |
| `route` | 코스와 코스 버전, 장소, 구도 템플릿 | routes, route_versions, route_stops |
| `patrol` | 날짜별 순회, 순회 장소, 촬영 구도, 출발·복귀, 공동 진행·오프라인 동기화 | patrols, patrol_members, patrol_stops, capture_slots |
| `inspection` | 사진 제출 접수, 시도 관리, 품질 실패 횟수 | inspections |
| `media` | 승인 사진 저장(S3), 참고·보수 사진, 개인정보 상태 | media |
| `analysis.client` | AI 판독 모듈 호출과 형식 변환 (camelCase ↔ snake_case, location_tags) | — |
| `analysis.job` | 분석 작업, 리스, 재시도, 결과 스냅샷 | analysis_jobs, analysis_results |
| `analysis.event` | AI `GET /events` 수신 (다시 가림·보류·재판독 결과 반영) | — |
| `review` | 검토 작업과 판정 | review_tasks, review_decisions |
| `issue` | 사건, 보수 이력, 재점검 요청, 수리 확인 | issues, repair_events, reinspection_requests, verifications |
| `report` | 주간 집계, AI 보고 문장 요청 | report_jobs |
| `notification` | outbox, 알림 | — |
| `audit` | 감사 이력 | audit_events |

AI 판독 모듈을 부르는 코드는 `analysis.client` 한 곳에만 둡니다. AI 계약이 바뀌면 이 패키지만 고치게 하기 위해서입니다. 연동 시 꼭 맞출 점은 AI 계약서 마지막 절 "백엔드 연동 시 꼭 맞출 것"에 있습니다.

## 사진 저장소 (MEDIA-S3-01)

승인 사진은 `MediaStorage` 인터페이스로 저장합니다. 설정 `inspection.media.store`(환경 변수 `MEDIA_STORE`)로 파일 시스템과 S3 중 하나를 고르며, 기본값은 파일 시스템입니다. 저장 키 규칙과 DB 값은 두 방식이 같습니다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `MEDIA_STORE` | `filesystem` | `filesystem` 또는 `s3` |
| `MEDIA_STORAGE_ROOT` | 임시 디렉터리 | 파일 시스템 저장소 루트. 운영에서는 저장소 밖 별도 볼륨 |
| `MEDIA_S3_BUCKET` / `MEDIA_S3_PREFIX` / `MEDIA_S3_REGION` | 없음 / 없음 / ap-northeast-2 | S3 버킷·키 접두사·리전. 자격 증명은 AWS SDK 기본 체인(인스턴스 역할)만 쓰고 접근 키는 설정하지 않음 |
| `MEDIA_S3_SSE` / `MEDIA_S3_KMS_KEY_ID` | AES256 / 없음 | 서버 측 암호화(AES256 또는 aws:kms)와 KMS 키 |
| `MEDIA_S3_ENDPOINT` | 없음 | 로컬 S3 호환 시험 컨테이너 전용. 운영에서는 비움 |

- 라이브러리: AWS SDK for Java v2 BOM 2.55.7(`s3`, 동기 Apache 클라이언트). 서명 URL과 ACL은 쓰지 않습니다.
- 테스트: 두 구현에 같은 계약 테스트를 돌리고, S3 모드의 사진 접수·조회·보류·재가림·저장 실패·정리를 S3 호환 컨테이너(LocalStack 4.4.0)로 확인합니다. Docker가 필요합니다.
- 실제 AWS S3·IAM 연결은 확인하지 않았습니다. 배포 설계는 [AWS 배포 준비](../docs/AWS_배포_준비.md)에 있습니다.
