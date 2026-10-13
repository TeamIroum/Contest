# 문서 목록·개발 기준

설계 문서는 서로 같은 폴더에 있다고 가정하고 링크합니다. 파일 이름을 바꾸거나 하위 폴더로 옮기지 마세요. 링크가 깨집니다.

## 어떤 문서가 기준인가

문서끼리 내용이 다르면 아래 순서로 판단합니다.

1. **서비스 방향**: [서비스_설계서.md](서비스_설계서.md). 다른 설계 문서와 충돌하면 이 문서가 우선합니다.
2. **업무 API**: [API_명세서.md](API_명세서.md). 필드·오류·경로의 기준입니다.
3. **저장 구조**: [DB_설계.md](DB_설계.md). 저장 제약과 트랜잭션의 기준입니다.
4. **AI 판독 모듈 호출**: [../ai/contract/API_CONTRACT.md](../ai/contract/API_CONTRACT.md). AI 응답 값과 그 뜻의 기준입니다. 이 폴더에 복사본을 두지 않습니다.

[기능정의서.md](기능정의서.md)의 기능 ID(`SEN-03`, `FAC-09` 등)는 이슈, 브랜치, PR, 검수 항목을 잇는 공통 식별자로 씁니다.

## 문서

| 문서 | 내용 | 담당 |
|---|---|---|
| [서비스_설계서.md](서비스_설계서.md) | 목적, 운영 주체, 순회 방식, 시니어 앱 흐름, 촬영 기준, 보수·수리 확인 | 기획 |
| [기능정의서.md](기능정의서.md) | 역할별 기능, 우선순위(P0~P2), 상태와 완료 조건, 수용 기준 | 기획·전체 |
| [API_명세서.md](API_명세서.md) | 업무 REST API (`/api/v1`), 인증, 사진 제출, 오프라인·동기화 | 백엔드 |
| [DB_설계.md](DB_설계.md) | PostgreSQL 테이블, 상태 값, 잠금 순서, 인덱스 | 백엔드 |
| [AI_기능_설계.md](AI_기능_설계.md) | AI가 맡는 네 기능의 개발 기준과 역할 분담 | AI·백엔드 |
| [AI_실행_가이드.md](AI_실행_가이드.md) | AI 판독 서버·검증 콘솔 실행, 판독 모델(mock·Ollama) 바꾸기, 휴대폰 시험용 앱(PWA·RN) 실행, 문제 해결 | AI·전체 |
| [presentation/시니어_동네_점검단_기획안.html](presentation/시니어_동네_점검단_기획안.html) | 팀 이로움 기획안 (발표·공유용 웹 문서) | 기획·발표 |
| [presentation/이로움_발표자료_초안.pptx](presentation/이로움_발표자료_초안.pptx) | 본선 발표 자료 (본문 13장 + 부록 5장, 발표자 노트 포함) | 발표 |
| [presentation/시스템_아키텍처.svg](presentation/시스템_아키텍처.svg) | 시스템 구성도 (PNG: [시스템_아키텍처.png](presentation/시스템_아키텍처.png)) | 전체 |
| [../ai/contract/API_CONTRACT.md](../ai/contract/API_CONTRACT.md) | AI 판독 모듈 호출 계약 v2.0 | AI·백엔드 |
| [../ai/contract/APP_MASKING_GUIDE.md](../ai/contract/APP_MASKING_GUIDE.md) | 앱 기기 마스킹 구현 안내 | 앱 |
| [../ai/MASKING_AND_SERVER.md](../ai/MASKING_AND_SERVER.md) | 마스킹과 AI 서버 처리 설계 근거 | AI |

## 아직 리포지토리에 없는 문서

설계 문서가 링크하지만 아직 올라오지 않은 파일입니다. 받는 대로 이 폴더(또는 표시한 위치)에 같은 이름으로 넣어 주세요.

| 파일 | 링크하는 문서 | 넣을 위치 |
|---|---|---|
| `AI_연동_계약.md` | API 명세서, DB 설계, 기능정의서, 서비스 설계서, AI 기능 설계 | `docs/` |
| `프론트_전달용_API.md` | API 명세서, 기능정의서 | `docs/` |
| `DB_트랜잭션_검증결과.md` | 기능정의서 | `docs/` |
| `core-schema-draft.sql` | DB 설계 | `docs/database/` |
| `transaction-tests/README.md`, `transaction-tests/results.json` | DB 설계 | `docs/database/transaction-tests/` |

`AI_연동_계약.md`는 AI 계약서 v2.0이 나오기 전에 쓰였습니다. 넣은 뒤 "미지원"으로 적힌 항목(개인정보 단독 검사, 보고 문장 스키마, 사진 버전·보류 전파)을 v2.0 기준으로 고쳐야 합니다.
