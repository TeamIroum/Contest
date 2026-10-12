# 프론트엔드

시니어 앱은 **PWA로 확정**했습니다. 시니어 앱(PWA)과 관리자 웹이 같은 업무 API(`/api/v1`)를 씁니다. 두 앱 모두 **React 19 + Vite + Tailwind CSS v4 + TypeScript**로 만들었습니다.

| 폴더 | 사용자 | 기능정의서 ID |
|---|---|---|
| `senior/` | 참여자(시니어) | `SEN-*`, `SYN-*`, `COM-03`, `COM-05` |
| `admin/` | 활동 담당자, 시설 담당자, 사업 총괄 | `ACT-*`, `FAC-*`, `OPS-*`, `COM-04` |
| `shared/` | 두 앱이 함께 쓰는 API 클라이언트, 타입, 상태 값 | — |

**두 앱 모두 AI 판독 모듈을 직접 부르지 않습니다.** 업무 백엔드 API만 씁니다.

## 실행

Node.js 20.11 이상이 필요합니다. 각 앱 폴더에서 따로 실행합니다.

```bash
cd frontend/senior      # 관리자 웹은 frontend/admin
npm install
npm run dev             # 개발 화면 (주소는 터미널에 표시)
npm run build           # 타입 검사 후 dist/ 에 결과물
npm run preview         # 만든 결과물 미리보기
```

API 서버 주소는 `.env.example`을 `.env`로 복사해 `VITE_API_ORIGIN`에 적습니다. 화면과 같은 주소에서 서비스하면 비워 둡니다.

## 지금 상태: 시연용 프로토타입 (모의)

두 앱은 아직 업무 API에 연결하지 않았습니다. 화면과 흐름은 프론트엔드 전달 자료(OpenAPI v0.2, 프론트엔드 API 명세서 v1.4, 응답 JSON 명세 v0.3)에 맞춰 만들었고, 데이터는 각 앱의 `src/data.ts`에 있는 시연용 예시입니다. 실제 인물·시설 정보가 아닙니다.

| 시연용으로 꾸민 것 | 위치 | 실제 연결할 때 |
|---|---|---|
| 예시 데이터 | 각 앱 `src/data.ts` | 업무 API 응답으로 바꿈 |
| 사진 자리 그림 | 각 앱 `src/Photo.tsx` | `media`(서버 사진) 또는 `src`(사진 파일)를 넘기면 실제 사진을 보여 줌. 지금도 연결해 둠 |
| 사진 불러오기 | 각 앱 `src/media.ts` | 로그인 뒤 `setMediaToken(토큰)`을 한 번 부르면 `contentPath`를 토큰과 함께 받아 옴 |
| 출석·시작·복귀 자동 진행 | 각 앱 `src/App.tsx` | 서버 상태 조회로 바꿈 |
| 시연 시계 (10/12 오전 10:40부터) | 관리자 `src/util.ts` | 실제 시각으로 바꿈 |
| "시연용" 버튼 (예시 비밀번호, 흔들린 사진, 하늘 시간) | 화면 곳곳 | 실제 서비스에서는 뺌 |

AI 판독 위치(`bbox`)는 0~1 비율 `[왼쪽, 위, 오른쪽, 아래]`로 그립니다 (AI API_CONTRACT `issues[].bbox` 기준).

## 공통 코드(`shared/`)

`Photo.tsx`, `media.ts`는 지금 두 앱에 같은 내용으로 들어 있습니다. 업무 API 클라이언트를 만들 때 `shared/`로 함께 옮깁니다.
