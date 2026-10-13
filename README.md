# 시니어 동네 점검단

2인 1조(인원이 홀수면 한 조만 3인) 시니어가 지정 코스의 시설(보도블록·빗물받이·벤치·안전펜스·운동기구) 외관을 촬영하고, 담당자가 AI 판독 결과를 참고해 보수와 수리 확인까지 관리하는 서비스입니다. 팀 이로움.

## 구조

```
.
├─ docs/                설계 문서 (서비스 설계서, 기능정의서, API 명세서, DB 설계, AI 기능 설계, AI 실행 가이드)
│  ├─ database/         DB 초안 DDL, 트랜잭션 검증 자료
│  └─ presentation/     발표 자료, 기획안 웹 문서, 시스템 구성도
├─ backend/             업무 백엔드 (Java 21 / Spring Boot 4.1 / Gradle, PostgreSQL, Redis, Flyway)
├─ frontend/
│  ├─ senior/           시니어 앱 (PWA, 브라우저 기기 마스킹)
│  ├─ admin/            관리자 웹 (활동 담당자, 시설 담당자, 사업 총괄)
│  └─ shared/           두 앱이 함께 쓰는 API 클라이언트·타입
├─ ai/                  AI 판독 모듈 (Python / FastAPI)
│  └─ contract/         AI 호출 계약 v2.0, 앱 마스킹 안내, 예시 응답
├─ tools/
│  ├─ ai-verify/        AI 판독 모듈 검증 콘솔 (검증용 백엔드 + 웹 화면), 시험용 업무 API·시험용 PWA(pwa/)
│  └─ ai-test-app/      시험용 앱 (React Native / Expo Go, iOS·Android, 기기 마스킹 → 업무 API → AI)
├─ infra/
│  ├─ local/            로컬 개발 환경 (docker compose)
│  └─ ec2/              EC2 배포
└─ .github/             CI, 이슈·PR 양식, CODEOWNERS
```

호출 흐름은 **앱 → 업무 백엔드 → AI 판독 모듈**입니다. 앱은 AI를 직접 부르지 않습니다.

## 담당별 시작 파일

| 담당 | 먼저 볼 것 |
|---|---|
| 전체 | [docs/README.md](docs/README.md) (문서 목록과 기준 순서), [CONTRIBUTING.md](CONTRIBUTING.md) |
| 기획·발표 | [서비스 설계서](docs/서비스_설계서.md), [기능정의서](docs/기능정의서.md) |
| 백엔드 | [backend/README.md](backend/README.md), [API 명세서](docs/API_명세서.md), [DB 설계](docs/DB_설계.md), [AI 호출 계약](ai/contract/API_CONTRACT.md) |
| 시니어 앱 | [frontend/senior/README.md](frontend/senior/README.md), [앱 마스킹 안내](ai/contract/APP_MASKING_GUIDE.md), 참고 구현: [시험용 PWA](tools/ai-verify/pwa/README.md)·[시험용 RN 앱](tools/ai-test-app/README.md) |
| 관리자 웹 | [frontend/admin/README.md](frontend/admin/README.md) |
| AI | [ai/README.md](ai/README.md), [마스킹·서버 설계](ai/MASKING_AND_SERVER.md), [AI 검증 콘솔](tools/ai-verify/README.md), [AI 실행 가이드](docs/AI_실행_가이드.md) (8장: 휴대폰 시험용 앱 실행) |

## 로컬 실행

```bash
cd infra/local
cp .env.example .env
docker compose up -d     # PostgreSQL :5432, Redis :6379, AI 판독 모듈 :8000 (PC의 Ollama로 판독)
```

AI 모듈 API 문서는 http://localhost:8000/docs 에서 볼 수 있습니다. `.env`의 `AI_API_KEYS` 값을 `X-API-Key` 헤더로 보냅니다. Ollama 준비와 판독 모델은 [AI 실행 가이드](docs/AI_실행_가이드.md)를 봅니다.

업무 백엔드(포트 8080)는 위 PostgreSQL·Redis·AI 모듈을 띄운 뒤 실행합니다.

```bash
cd backend
# 필수: DB_PASSWORD(= infra/local/.env의 POSTGRES_PASSWORD), JWT_SECRET(Base64 32바이트 이상)
# AI 연결: AI_BASE_URL(기본 http://localhost:8000), AI_API_KEY(= infra/local/.env의 AI_API_KEYS 중 하나)
# 시연 로그인: DEMO_OTP_CODE (local 프로필에서만)
./gradlew bootRun --args='--spring.profiles.active=local'
```

DB 이름·사용자의 기본값은 `inspection`이며 `DB_URL`·`DB_USERNAME`으로 바꿉니다. 설정 전체는 `backend/src/main/resources/application.yml`에 있습니다. 프론트 실행 방법은 각 폴더 README를 봅니다.

## 아직 정하지 않은 것

- 프론트엔드 프레임워크 (시니어 앱은 PWA로 확정)
- 실행 서버 구성(EC2·S3·RDS), Cloudflare 사용 범위
- AI 판독용 GPU 서버의 사양과 위치. 공개 모델 자체 운영(Ollama)과 활동 시간에만 가동은 확정 ([AI 실행 가이드](docs/AI_실행_가이드.md) 4.6절)
- 10월 15일 시연에서 실제 구현할 기능과 모의 시연 기능

정해지면 '결정 필요' 이슈에 기록하고 관련 README를 고칩니다.
