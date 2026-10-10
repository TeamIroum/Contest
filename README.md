# 시니어 동네 점검단

2인 1조 시니어가 지정 코스의 시설(보도블록·빗물받이·벤치·안전펜스·운동기구) 외관을 촬영하고, 담당자가 AI 판독 결과를 참고해 보수와 수리 확인까지 관리하는 서비스입니다. 팀 이로움.

## 구조

```
.
├─ docs/                설계 문서 (서비스 설계서, 기능정의서, API 명세서, DB 설계, AI 기능 설계)
│  ├─ database/         DB 초안 DDL, 트랜잭션 검증 자료
│  └─ presentation/     발표·공유용 자료
├─ backend/             업무 백엔드 (Java / Spring Boot, PostgreSQL, Redis)
├─ frontend/
│  ├─ senior/           시니어 앱 (모바일 웹 / PWA, 기기 마스킹)
│  ├─ admin/            관리자 웹 (활동 담당자, 시설 담당자, 사업 총괄)
│  └─ shared/           두 앱이 함께 쓰는 API 클라이언트·타입
├─ ai/                  AI 판독 모듈 (Python / FastAPI)
│  └─ contract/         AI 호출 계약 v2.0, 앱 마스킹 안내, 예시 응답
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
| 시니어 앱 | [frontend/senior/README.md](frontend/senior/README.md), [앱 마스킹 안내](ai/contract/APP_MASKING_GUIDE.md) |
| 관리자 웹 | [frontend/admin/README.md](frontend/admin/README.md) |
| AI | [ai/README.md](ai/README.md), [마스킹·서버 설계](ai/MASKING_AND_SERVER.md) |

## 로컬 실행

```bash
cd infra/local
cp .env.example .env
docker compose up -d     # PostgreSQL :5432, Redis :6379, AI 판독 모듈(mock) :8000
```

AI 모듈 API 문서는 http://localhost:8000/docs 에서 볼 수 있습니다. `.env`의 `AI_API_KEYS` 값을 `X-API-Key` 헤더로 보냅니다. 백엔드·프론트 실행 방법은 빌드 도구와 프레임워크가 정해지면 각 폴더 README에 추가합니다.

## 아직 정하지 않은 것

- Java·Spring Boot 버전, 빌드 도구, 마이그레이션 도구
- 프론트엔드 프레임워크
- 실행 서버 구성(EC2·S3·RDS), Cloudflare 사용 범위
- 10월 15일 시연에서 실제 구현할 기능과 모의 시연 기능

정해지면 '결정 필요' 이슈에 기록하고 관련 README를 고칩니다.
