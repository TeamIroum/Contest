# 시험용 시니어 PWA (기기 마스킹 → 업무 API → AI)

시니어 앱의 **앱 안 카메라 촬영 → 브라우저에서 얼굴·번호판·차량 가림 → 가린 사진만 업무 백엔드로 전송** 흐름을 AI 판독 모듈까지 이어서 시험하는 PWA입니다. 기준은 [앱 마스킹 구현 안내서](../../../ai/contract/APP_MASKING_GUIDE.md)와 [API 명세서 6·7절](../../../docs/API_명세서.md)입니다.

```
휴대폰 PWA (/pwa/) ──multipart──▶ 시험용 업무 API (/api/v1, 검증 콘솔 :8100) ──▶ AI 판독 모듈 (:8000)
 · 앱 안 카메라(getUserMedia)          · API 명세 6절 사진 제출 계약            · 서버 가림 확인(server_added)
 · YuNet + 번호판 t-384 + 차량           · deviceMaskReport → snake_case          · 판독
 · 가린 JPEG만 IndexedDB 대기함           · 위치는 location_tags (lat/lon 안 보냄)
                                         · 결과는 검증 콘솔 기록에 쌓임
```

- 앱은 AI를 부르지 않고 업무 API만 부릅니다. 실제 업무 백엔드가 생기면 시험 설정의 "업무 API 주소"만 바꿉니다.
- 시험용 업무 API는 실제 업무 백엔드가 아닙니다. 인증·DB·조 공동 진행이 없고, 순회는 고정된 시험 코스 하나(장소 5곳)입니다.
- 어르신 화면에는 판독 결과(이상 여부·등급)가 나오지 않습니다. 결과는 검증 콘솔 기록(`/#run-<번호>`)에서 봅니다.

## 실행

```powershell
# 0) 번호판·차량 모델 받기 (처음 한 번, 약 15MB. Git에 넣지 않음)
python tools/ai-verify/pwa/fetch_models.py

# 1) AI 판독 모듈: 실제 모델(Ollama qwen3-vl)로 띄움. mock은 쓰지 않음
#    ai/.env 에 AI_PROVIDER=ollama. Ollama가 켜져 있고 모델(ollama pull qwen3-vl:8b-instruct)이 있어야 함
cd ai; python -m uvicorn server:app --port 8000 --env-file .env

# 2) 검증 콘솔 (다른 창). PWA와 시험용 업무 API가 함께 뜸
#    ai/.env 에 API_KEYS를 두었으면 그중 하나를 AI_API_KEY로 넘김
cd tools/ai-verify/backend; $env:AI_API_KEY="<ai/.env의 API_KEYS 중 하나>"; python -m uvicorn app:app --port 8100
```

PC 브라우저에서 http://localhost:8100/pwa/ 를 엽니다. 카메라가 없는 PC는 크롬을 `--use-fake-device-for-media-stream --use-file-for-fake-video-capture=<사진>.mjpeg`로 띄우면 그 사진이 카메라 화면으로 나옵니다.

### 휴대폰에서 열기

카메라와 서비스 워커는 HTTPS(또는 `localhost`)에서만 동작합니다.

| 방법 | 하는 일 |
|---|---|
| 안드로이드 + USB (가장 간단) | `adb reverse tcp:8100 tcp:8100` 후 휴대폰 크롬에서 `http://localhost:8100/pwa/` |
| 같은 와이파이 + HTTPS | [mkcert](https://github.com/FiloSottile/mkcert)로 PC IP 인증서를 만들고 `python -m uvicorn app:app --host 0.0.0.0 --port 8100 --ssl-keyfile key.pem --ssl-certfile cert.pem`. 휴대폰에 mkcert 루트 인증서를 설치해야 함 |
| 아이폰 | 위 HTTPS 방식. 홈 화면에 추가해 실행할 때 카메라 권한이 반복해서 뜨는지 함께 확인 |
| 밖에서 (권장) | GitHub Pages 배포본 https://teamiroum.github.io/pwa-test/ (`deploy_pages.py`). 화면·모델은 CDN에서, 사진·업무 API만 터널로 PC에 |

**처음 한 번은 Wi-Fi에서 여세요.** 처음 열 때 가림 모델과 실행기를 약 20MB(실제 전송량) 받습니다. 1Mbps로 제한된 데이터에서는 3분 안팎 걸립니다. 다 받은 파일은 휴대폰에 남아 다음부터는 거의 받지 않습니다. 운영에서는 복지관 교육 때 Wi-Fi로 한 번 열어 두게 안내합니다.

받는 방식 (`download.js`): 얼굴 → 번호판 → 차량 순서로 하나씩 받고(동시에 받으면 셋 다 느려져 끊기기 쉬움), 끊기면 2·5·10·20·30초… 뒤 최대 8번 다시 받습니다. 모델은 받은 곳부터 이어 받고(Range) 다 받은 뒤 주소의 내용 해시(`?v=`)로 확인합니다. 받은 파일은 바로 캐시에 두므로 앱을 닫았다 열어도 남은 것만 받습니다. 셋이 모두 준비되기 전에는 촬영 버튼이 꺼져 있습니다(차량을 못 가린 사진이 생기지 않게, 원본은 붙잡아 두지 않음).

## 화면 흐름

순회 시작 → 장소 안내 → 사진 찍기(촬영음) → 가리는 중 → (시험 설정) 가린 사진 확인 → 대기함 저장 → 전송 → 진행 안내(다음 장소 / 다시 찍기 / 전송 대기)

**시험 설정 (처음 화면 아래, 담당자용)**

| 항목 | 내용 |
|---|---|
| 업무 API 주소 | 기본 `/api/v1` |
| 보내기 전에 가린 사진 확인 | 끄면 실제 앱처럼 찍자마자 보냄 |
| 가린 위치 표시 | 확인 화면에만 점선 상자를 그림. 전송 사진에는 없음 |
| 촬영 위치 함께 보내기 | 촬영 순간 한 번만 위치를 받아 `latitude`/`longitude`로 보냄. 시험용 업무 API는 받기만 하고 AI에는 넘기지 않음 |
| 저장된 사진 고르기 (장소 안내 화면, 사진 찍기 아래) | 휴대폰에 저장된 JPEG·PNG를 골라 **카메라 사진과 같은 경로로 가린 뒤** 확인 화면을 거쳐 보냄. 앱 마스킹 성능을 준비한 사진으로 확인할 때 씀. 사진 방향은 사진 정보대로 바로잡고 위치 정보(EXIF)는 빠짐. 서버 기록에서는 `appVersion`이 `…+file`로 구분됨. 안내서 2장에 따라 실제 시니어 앱에는 두지 않음 |
| 얼굴 검출 점검 | `ai/contract/yunet_web/`의 시험 사진 5장을 이 브라우저에서 돌려 서버(OpenCV)와 같은 얼굴을 찾는지 확인 (`verify.mjs`와 같은 기준) |
| 구도 상태 초기화 | 시험 코스의 재촬영 횟수·채택 상태를 처음으로 되돌림 |

## 파일 구성

화면 코드(`app.js`)와 나머지를 나눠 두었습니다. `app.js`를 뺀 파일은 화면과 상관없어 시니어 앱에서 그대로 가져다 씁니다([적용 요청](../../../docs/시니어앱_촬영기능_적용요청.md)).

| 파일 | 맡는 것 |
|---|---|
| `app.js` | 시험용 화면 전환, 확인 화면, 시험 기록 |
| `camera.js` | 카메라 열기·닫기, 촬영음, 연속 장면 중 가장 선명한 것 고르기, 흐림·어두움 확인, 가리기 호출, 보낼 JPEG 재확인 |
| `quality.js` | 선명도·밝기 계산 (서버 `ai/quality.py`와 같은 계산) |
| `masking.js`, `download.js` | 얼굴·번호판·차량 검출과 가리기, 모델 받기. 모델 주소는 `loadModels`의 옵션으로 바꿀 수 있음 (`make_model_manifest.py`로 목록 생성) |
| `outbox.js` | 대기함(IndexedDB)과 전송·자동 재전송 |

`camera.js`·`masking.js`·`outbox.js`·`quality.js` 옆의 `.d.ts`는 TypeScript 타입 선언입니다. 시험용 PWA는 쓰지 않고, 시니어 앱(React + TypeScript)이 가져갈 때 씁니다. `.js`의 함수나 반환값을 바꾸면 같이 고쳐 주세요.

## 마스킹 구현

`masking.js`가 안내서 3·4·5장을 구현합니다. 숫자는 서버(`ai/privacy.py`)와 같습니다.

| 단계 | 내용 |
|---|---|
| 얼굴 | `ai/contract/yunet_web/yunet.mjs`와 가변 YuNet 모델을 그대로 씀(콘솔이 계약 폴더 파일을 내려줌). 찍은 해상도 그대로, 긴 변 2,400px 초과면 줄여서 찾음 |
| 번호판 | `yolo-v9-t-384-license-plate-end2end`. open-image-models와 같은 전처리(비율 유지 + 회색 114 여백, RGB, 0~1), 점수 기준 0.6 (서버는 큰 모델로 0.15). 거짓 검출은 0.53점 이하, 한국 차 사진 속 진짜 번호판은 0.72점 이상이었음 (`ai/eval/privacy/device_plate_threshold.py`) |
| 차량 | MediaPipe EfficientDet-Lite0, 자동차·버스·트럭·오토바이, 점수 0.4. 안에 번호판 중심이 없으면 차량 전체를 뭉갬 |
| 가림 | 얼굴·차량: 가로 6칸 모자이크 + 가우시안 흐림(커널은 서버와 같은 식). 흐림 미지원 브라우저는 4칸 모자이크. 번호판: `#3C3C3C`로 덮음. 상자 확장 25% / 20% / 5% |
| 출력 | 긴 변 1,280px, JPEG 품질 0.88 (`toBlob`, EXIF 없음). 1568에서 줄임: 판독 결과는 같고 용량은 약 39% 감소 |
| 원본 | 촬영 캔버스는 가린 뒤 크기를 0으로 만듦. IndexedDB·캐시·localStorage에 넣지 않음. 실패하면 사진을 버리고 "한 번 더 찍어 주세요" |
| 버전 | `maskingVersion = pwa-test-2+<모델 해시>`, `detector = yunet+plate-t384+effdet0`. 규칙 버전(`pwa-test-2`)은 입력 크기·가리는 방법을 바꿀 때 올리고, 모델 파일이 바뀌면 뒤의 해시가 저절로 바뀜 |

검출 라이브러리(ONNX Runtime Web 1.20.1, MediaPipe Tasks Vision 0.10.14)는 jsDelivr에서 받고, 모델 파일은 같은 도메인에서 받습니다. 모델 주소는 `/pwa/model-manifest.json`이 내려주며 내용 해시(`?v=`)가 붙어 있어, `ai/contract/yunet_web/`의 얼굴 모델이나 받은 번호판·차량 모델이 바뀌면 설치된 앱도 새 모델을 받습니다. 라이브러리 코드는 서비스 워커가, 모델과 실행기(`.wasm`)는 `download.js`가 캐시하고 같은 모델의 예전 해시 캐시는 지웁니다. 차량 모델은 원본(Google)에서 바로 받습니다. 사진과 업무 API 응답은 캐시하지 않습니다.

## 확인한 것 (2026-10-12, PC 크롬 헤드리스)

- 얼굴 검출 점검: 5장 모두 서버와 같은 얼굴 (`t02` 1920×1080 약 170ms)
- 번호판: 합성 번호판 6장에서 Python(open-image-models) t-384와 같은 상자(IoU 0.96~1.00). 축소 보간을 `high`로 두면 점수가 낮은 번호판 1장을 놓쳐 `low`(선형)로 맞춤
- 1920×1080 촬영 한 장: 검출·가림·JPEG 합계 약 0.6초 (WASM 1스레드)
- 전송 → 다음 장소 안내, 흐림·어두움 재촬영 안내와 남은 횟수, 같은 제출 ID 재전송 시 같은 결과, 오프라인 저장 후 연결되면 자동 재전송

## 아직 못 한 것

- **실기기 측정**: 안내서 7장의 저사양 휴대폰 3초 목표, 아이폰 사파리(홈 화면 실행) 카메라 권한, 무음 모드 촬영음, 배터리
- **실제 차량 사진**: 차량 안전망은 실제 차량이 찍힌 사진으로 시험하지 않았습니다
- **재점검 겹쳐 보기**(이전 가린 사진 반투명 표시), 인증·조 공동 진행: 시험용 업무 API에 없으므로 넣지 않았습니다
- **`server_added` 해석 주의**: AI 서버는 이미 가린 영역 안에서도 얼굴·번호판을 다시 찾아 셉니다. 서버가 자기가 가린 사진을 한 번 더 돌려도 6개 중 5개 얼굴을 다시 찾고, 합성 벽돌 무늬에서는 규칙 기반 번호판 검출기가 오탐을 냅니다. 그래서 지금 값은 "앱이 놓친 수"보다 큽니다. AI 담당과 기준을 맞춰야 합니다.
