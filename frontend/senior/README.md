# 시니어 앱 (PWA)

시니어 앱은 PWA로 확정했습니다. 어르신은 링크로 열고 홈 화면에 추가해 씁니다. 스토어 설치는 없습니다.

기준 문서: [서비스 설계서 "시니어 앱"](../../docs/서비스_설계서.md), [기능정의서 4·5절](../../docs/기능정의서.md), [API 명세서 6·7절](../../docs/API_명세서.md), [앱 마스킹 구현 안내](../../ai/contract/APP_MASKING_GUIDE.md)

참고 구현: 촬영 → 기기 마스킹 → 대기함 → 업무 API 전송까지 동작하는 시험용 앱이 있습니다. [시험용 PWA](../../tools/ai-verify/pwa/README.md)의 `masking.js`(가림)·`outbox.js`(대기함)는 프레임워크와 무관해 이 폴더의 `src/masking/`, `src/offline/`으로 옮겨 쓸 수 있습니다. 아이폰 실기기에서 확인한 주의점은 [앱 마스킹 안내 8·9장](../../ai/contract/APP_MASKING_GUIDE.md)에 있습니다.

## 화면 흐름

오늘 순회 → 담당자 출발 확인 대기 → 장소·구도 안내 → 촬영·기기 마스킹·제출 → 다음 구도/장소 자동 안내 → 센터 복귀 → 담당자 복귀 확인 → 활동 완료

## 꼭 지킬 것

- 앱 안 카메라(`getUserMedia`)로만 찍고, 얼굴·번호판을 **브라우저에서 가린 뒤** 가린 사진만 올립니다. 가리지 못하면 원본을 올리지 않고 다시 찍게 합니다. (`SEN-03`)
- 원본은 IndexedDB·캐시·서비스 워커 어디에도 저장하지 않습니다. 오프라인일 때 보관하는 것은 가린 사진뿐입니다. (`SYN-04`)
- 위험도·이상 여부·판독 근거를 어르신 화면에 띄우지 않습니다. 재촬영·진행 안내만 표시합니다.
- 품질 재촬영은 구도별 최초 + 2회까지입니다. 통신·서버 문제는 횟수에 넣지 않습니다. (`SEN-05`)
- 저장·전송 실패를 성공으로 안내하지 않습니다. 서버 접수 전에는 "전송 대기"로 보여 줍니다.
- 재점검 촬영에서는 이전 가린 사진을 카메라 화면 위에 반투명하게 겹쳐 보여 줍니다. (`SEN-11`)
- 촬영할 때마다 앱이 촬영음을 재생합니다. 브라우저 카메라는 스스로 소리를 내지 않습니다.

## PWA로 만들 때 챙길 것

| 항목 | 내용 |
|---|---|
| HTTPS | 카메라와 서비스 워커는 HTTPS에서만 동작합니다. 개발 중에는 `localhost`만 예외 |
| 서비스 워커 | 앱 화면, 마스킹 모델 파일, 순회 오프라인 패키지(코스·참고 사진)를 캐시합니다. 사전 다운로드(`SYN-03`)가 끝나야 "준비 완료" |
| 저장소 | 가린 사진과 촬영·건너뛰기·복귀 이벤트는 IndexedDB에 저장한 뒤 진행합니다. 순회 시작 때 `navigator.storage.persist()` 요청 |
| 백그라운드 | 앱을 닫으면 전송이 멈출 수 있습니다. 앱을 다시 열 때, 복귀 전에 대기 사진을 보냅니다 |
| 마스킹 모델 | ONNX Runtime Web(얼굴 YuNet, 번호판), MediaPipe Tasks Vision(차량). 모델 파일은 AI 담당이 버전과 함께 넘기고, 같은 도메인의 `/models/` 아래에서 제공. 얼굴 검출 코드와 확인 자료는 [ai/contract/yunet_web/](../../ai/contract/yunet_web/README.md) |
| 성능 | WebGPU가 있으면 사용, 없으면 WASM. WASM 여러 스레드를 쓰려면 배포 시 COOP/COEP 헤더가 필요하므로 실기기 측정 후 결정 |
| 인증 | 웹/PWA는 갱신 토큰을 Secure/HttpOnly 쿠키로 받습니다(API 명세서 4절) |
| 실기기 확인 | 안드로이드 크롬, 아이폰 사파리(홈 화면 실행 포함)에서 카메라 권한, 저장소 유지, 처리 시간 |

## 폴더 구성 제안 (프레임워크가 정해지면 맞춰 조정)

| 위치 | 내용 |
|---|---|
| `src/pages/` | 오늘 순회, 장소 안내, 촬영, 복귀, 완료 |
| `src/camera/` | `getUserMedia` 카메라 화면, 촬영, 이전 사진 겹쳐 보기, 촬영음 |
| `src/masking/` | 모델 불러오기, 얼굴·번호판·차량 검출과 가림 (APP_MASKING_GUIDE 기준) |
| `src/offline/` | IndexedDB 저장, 재연결 시 재전송, 공동 진행 동기화 |
| `src/api/` | 업무 API 호출 (`../shared` 사용) |
| `public/models/` | 마스킹 모델 파일 (버전이 붙은 이름). 용량이 커서 Git LFS나 배포 단계 내려받기를 검토 |
| `public/sw.js` 또는 프레임워크의 PWA 설정 | 서비스 워커, 웹 앱 매니페스트(앱 이름·아이콘·홈 화면 표시) |

## 지금 들어 있는 것 (시연용 프로토타입)

실행 방법은 [frontend/README.md](../README.md)에 있습니다. 시연 로그인은 "휴대폰 번호로 시작하기" → "인증번호 받기" → 아무 숫자 6개입니다.

| 파일 | 내용 |
|---|---|
| `src/App.tsx` | 모든 화면: 시작·휴대폰 인증 → 오늘 순회 → 출석 → 장소 안내 → 촬영 → 전송 → 다시 찍기 → 복귀 → 완료, 도움·건너뛰기·그만하기 창 |
| `src/data.ts` | 시연용 예시 데이터 (조, 장소 6곳, 건너뛰기·그만하기 사유) |
| `src/Scenery.tsx`, `src/sky.ts` | 화면 위쪽 풍경. 휴대폰 시계에 맞춰 새벽·오전·한낮·오후·해 질 녘·밤 6단계로 하늘이 바뀜 |
| `src/Photo.tsx`, `src/media.ts` | 사진 자리. 사진이 없으면 시설 그림을 그림 |
| `src/sounds.ts` | 효과음. 촬영음("찰칵")은 늘 재생, 나머지(전송 완료·다시 찍기·알림)는 위쪽 막대에서 끌 수 있음 |
| `src/camera/` | 앱 안 후면 카메라(`LiveCamera.tsx`), 흐림·어두움 계산(`quality.js`) |
| `src/masking/` | 얼굴·번호판·차량 가리기(`masking.js`, `yunet.mjs`), 모델 받기(`download.js`) |
| `src/offline/` | 전송 대기함(`outbox.js`, IndexedDB) |
| `src/api/` | 업무 API 주소·토큰, 오늘 순회 조회(`client.ts`) |
| `public/` | 앱 아이콘, 웹 앱 매니페스트(홈 화면 이름 "동네 점검단"), 가림 모델(`public/models/`) |

`camera/`, `masking/`, `offline/`의 `.js`·`.d.ts` 파일은 [시험용 PWA](../../tools/ai-verify/pwa/README.md) 원본을 **고치지 않고 그대로** 복사했습니다 ([촬영 기능 적용 요청](../../docs/시니어앱_촬영기능_적용요청.md) 0·3장). AI 쪽이 기준을 고치면 파일만 다시 복사하면 됩니다. 원본과 다른 곳은 `offline/outbox.d.ts`의 import 경로(`../masking/masking.js`) 한 줄뿐이고, 시니어 앱에 맞출 설정은 `App.tsx`에서 옵션으로 넘깁니다.

```ts
outbox.configure({ dbName: 'senior-outbox' })
masking.loadModels(show, { manifestUrl: `${import.meta.env.BASE_URL}models/model-manifest.json` })
```

`public/models/model-manifest.json`은 `npm run models`가 모델을 받은 뒤 내용 해시(`?v=`)와 함께 만듭니다 (`tools/ai-verify/pwa/make_model_manifest.py`와 같은 결과). 모델 파일을 바꾸면 다시 실행합니다.
## 실제 촬영

| 주소 | 동작 |
|---|---|
| `https` (개발 중에는 `localhost`) | **실제 촬영.** 가림 모델 셋(얼굴·번호판·차량)이 준비되고 카메라 화면이 나오기 전에는 찍기 버튼이 꺼지고 준비 상태를 보여 줍니다. 모델을 못 받았거나 카메라를 못 열면 "다시 준비하기" |
| `http` | **그림 시연.** 카메라가 `https`에서만 열리므로 지금까지의 시연 화면으로 동작 |

찍기 순서 (요청서 1장)

1. 셔터를 누르면 촬영음과 함께 화면이 한 번 번쩍입니다.
2. 약 0.3초 동안 5장면을 떼어 가장 선명한 것을 고릅니다.
3. 흐리거나 어두우면(선명도 60, 밝기 45 미만, 서버와 같은 계산) 보내지 않고 바로 다시 찍기 화면으로 갑니다.
4. 얼굴·번호판·차량을 기기에서 가리고, 압축한 사진으로 흐림을 한 번 더 확인합니다. 가리지 못하면 원본을 버리고 "한 번 더 찍어 주세요".
5. 가린 사진을 대기함에 먼저 저장한 뒤 `POST /patrols/{patrolId}/stops/{stopId}/inspections`로 보냅니다. 진행 문구: 가리는 중 → 보내는 중 → 결과 확인 중.

서버 답에 따른 화면 (요청서 2장)

| 서버 답 | 화면 |
|---|---|
| `nextAction: RETAKE` | 다시 찍기 (서버 `message`), 남은 횟수는 `retakesRemaining` |
| `NEXT_STOP` / `NEXT_CAPTURE` | 보냈어요 → `nextStopId` 장소 또는 다음 구도 |
| `RETURN_TO_CENTER` | 보냈어요 → 센터 복귀 |
| `captureStatus: REVIEW_REQUESTED` | 선생님께 확인을 부탁했어요 |
| `code: SLOT_RESOLVED` (409) 또는 `duplicate: true` (200) | 이미 끝난 곳이에요 |
| 연결 안 됨 · 5xx · `SUBMISSION_PROCESSING` | 전송 대기 중이에요 (대기함에 남김, 앱을 다시 볼 때·연결이 돌아올 때 자동으로 다시 보냄). 홈에 "전송 대기 N장" |
| 그 밖의 4xx | 보내지 못했어요 (대기함에 남기되 자동 재전송 안 함) |

오늘 순회: 로그인 뒤 `GET /me/patrols` → `GET /patrols/{id}`로 받아 장소·구도 목록을 서버 순회로 바꿉니다. 이미 끝난 구도(`ACCEPTED`, `REVIEW_REQUESTED`)는 찍기 버튼을 끄고 "이미 끝난 곳이에요", 모두 끝났으면 "오늘 순회를 모두 마쳤어요". API에 연결하지 못하면 `src/data.ts` 예시 데이터로 시연합니다. 연결은 `.env`의 `VITE_API_ORIGIN`(예: 시험용 업무 API `http://127.0.0.1:8100`).

**모델 받기** (처음 한 번)

```bash
npm run models   # 번호판(7.8MB)·차량(7.3MB) 모델을 public/models/에 받음. Git에는 넣지 않음(.gitignore)
```

얼굴 모델(`face_yunet_s_dynamic@1.onnx`, 230KB)은 `ai/contract/yunet_web/` 복사본으로 저장소에 들어 있습니다. 검출 라이브러리(ONNX Runtime Web 1.20.1, MediaPipe Tasks Vision 0.10.14)는 jsDelivr에서 받습니다.

**확인한 것** (PC 엣지, 가짜 카메라에 `ai/contract/yunet_web/images/t02_street_1920x1080.jpg`, 서버 답은 시험 스크립트가 흉내 냄)

- 서버 순회를 받아 이미 끝난 첫 장소를 건너뛰고 둘째 장소부터 시작, 서버 구도 번호로 전송
- 얼굴 6개를 가린 사진과 `deviceMaskReport`가 전송됨
- 접수 → `nextStopId` 장소로, RETAKE → 서버 문구, REVIEW_REQUESTED → 선생님 확인, SLOT_RESOLVED → 이미 끝난 곳, 연결 끊김 → 전송 대기 + 대기함 1건
- 어두운 사진 → 보내지 않고 "사진이 어두워요"
- 카메라가 없는 PC(localhost) → 찍기 버튼 꺼짐 + "카메라를 열지 못했어요" + 다시 준비하기
- 휴대폰 실기기는 아직 확인하지 않았습니다

화면 원칙

- 기본 글씨가 크고, 위쪽 "가가" 버튼으로 두 단계 더 키울 수 있습니다. 글씨를 키워도 화면이 옆으로 넘치지 않게 맞췄습니다.
- 위험도·판독 결과는 보여 주지 않고, 접수·다시 찍기·진행 안내만 보여 줍니다.
- 주요 버튼은 화면 아래에 고정해 목록이 길어도 늘 보입니다.

아직 없는 것 (다음 단계)

- 서비스 워커(오프라인에서 앱 열기, 모델 캐시는 `download.js`가 Cache Storage에 함)
- 업무 API 연결: 로그인, 출석·시작·복귀·건너뛰기. 지금은 순회 조회와 사진 전송만 연결
- 휴대폰 실기기 확인

이 문서와 다르게 만든 것 (결정 필요 이슈로 확인 중)

- 화면 흐름: 출석을 요청하면 담당자 출발 확인을 기다리지 않고 바로 시작할 수 있습니다 (프론트엔드 전달 자료의 "출발 확인과 개인 시작은 별개" 기준).
- 시연용 "흔들린 사진으로 찍기" 버튼: 요청서는 삭제이지만 시연용으로 남겼습니다. `http` 그림 시연에서만 보이고 실제 촬영(`https`)에서는 나오지 않습니다.