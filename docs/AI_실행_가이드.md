# AI 판독 모듈 실행 가이드

AI 판독 서버와 검증 콘솔을 내 컴퓨터에서 띄우고, 판독 모델(mock, Ollama)을 바꾸는 방법입니다. 휴대폰 시험용 앱(PWA·React Native)으로 촬영부터 판독까지 시험하는 방법은 [8장](#8-휴대폰-시험용-앱-실행하기)에 있습니다. Windows PowerShell 기준입니다.

여기서 "백엔드 서버"는 **검증 콘솔의 백엔드**(`tools/ai-verify/backend`)입니다. 업무 백엔드(Spring Boot, `backend/`)는 아직 만들어지지 않았습니다.

## 1. 구성

```
브라우저 ──▶ 검증 콘솔 :8100 ──▶ AI 판독 서버 :8000 ──▶ 판독 모델
            (tools/ai-verify)     (ai/)                  ├─ mock (가짜 결과, 내 컴퓨터)
                                                          └─ Ollama :11434 (자체 서버·내 컴퓨터)
```

| 창 | 하는 일 | 주소 |
|---|---|---|
| AI 판독 서버 | 마스킹, 판독, 결과 저장 | http://localhost:8000/docs |
| 검증 콘솔 | 사진 올리기, 계약 검사, 정확도 집계 화면 | http://localhost:8100 |
| Ollama | 내 컴퓨터에서 도는 비전 모델 (Ollama를 쓸 때만) | 설치하면 자동 실행 |

AI 서버와 검증 콘솔은 **PowerShell 창을 따로 하나씩** 열어 실행합니다. 창을 닫으면 서버도 꺼집니다.

## 2. 처음 한 번만 할 일

1. 프로젝트를 **한글이 없는 경로**에 둡니다 (예: `C:\Users\hwang\Downloads\senior-inspection`). 경로에 한글이 있으면 얼굴 검출 모델을 읽지 못해 AI 서버가 시작되지 않습니다.
2. 필요한 패키지를 설치합니다.
   ```powershell
   cd C:\Users\hwang\Downloads\senior-inspection
   pip install -r ai\requirements.txt
   pip install -r tools\ai-verify\backend\requirements.txt
   ```
3. 설정 파일을 만듭니다. `.env`는 `.gitignore`에 들어 있어 GitHub에 올라가지 않습니다. **API 키는 `.env`에만 넣고, 코드나 채팅에 붙이지 않습니다.**
   ```powershell
   cd ai
   copy .env.example .env
   notepad .env
   ```

## 3. 실행

**창 1: AI 판독 서버**
```powershell
cd C:\Users\hwang\Downloads\senior-inspection\ai
python -m uvicorn server:app --port 8000 --env-file .env
```
시작 로그 마지막 부분에 어떤 모델을 쓰는지 나옵니다.
```
검출기: 얼굴=YuNetFaces 번호판=PretrainedPlates / AI=ollama(qwen3-vl:8b-instruct)
```

**창 2: 검증 콘솔**
```powershell
cd C:\Users\hwang\Downloads\senior-inspection\tools\ai-verify\backend
python -m uvicorn app:app --port 8100
```

`ai/.env`에 `API_KEYS`를 넣었으면 콘솔에도 그중 하나를 넘깁니다. 넘기지 않으면 콘솔이 AI 서버에서 401을 받습니다.
```powershell
$env:AI_API_KEY="<ai/.env의 API_KEYS 중 하나>"; python -m uvicorn app:app --port 8100
```

브라우저에서 http://localhost:8100 을 엽니다. 오른쪽 위에 `ollama · qwen3-vl:8b-instruct · 계약 2.0`처럼 연결된 모델이 보이면 준비된 것입니다. 콘솔 사용법은 [검증 콘솔 README](../tools/ai-verify/README.md)에 있습니다.

**끄기**: 각 창에서 `Ctrl+C`. 한 번에 안 꺼지면 한 번 더 누르거나 창을 닫습니다.

## 4. AI 모델 바꾸기

`ai/.env`의 `AI_PROVIDER`와 모델별 설정을 바꾸고 **AI 서버만 다시 띄웁니다**. 검증 콘솔은 그대로 둡니다.

**판독은 자체 서버의 공개 모델(Ollama)로만 합니다** (2026-10-11 결정). 사진을 외부 AI API로 보내지 않으며, Gemini·Claude 연결 코드는 없앴습니다. 공개 모델끼리 비교할 때는 `OLLAMA_MODEL`만 바꿉니다.

| 모델 | 비용 | 속도 (장당) | 특징 | 쓰임 |
|---|---|---|---|---|
| `mock` | 무료 | 즉시 | 사진을 보지 않고 고른 시나리오의 가짜 결과. 마스킹만 실제로 동작 | 화면·계약 시험 |
| `ollama` | 무료(GPU 서버 비용만) | 2~10초 | 자체 서버 GPU로 판독. 사진이 밖으로 안 나감. 호출 한도 없음 | 운영·시험 |

### 4.1 mock

```
AI_PROVIDER=mock
```
판독 시험 화면에서 "mock 시나리오"를 고르면 그 결과가 나옵니다(예: 바닥 파손 = `sidewalk_danger`). 시나리오를 고르지 않으면 사진과 관계없이 아무 시나리오나 나옵니다.

### 4.2 Ollama (자체 서버·내 컴퓨터)

1. https://ollama.com/download 에서 설치합니다. PowerShell에서 `winget install Ollama.Ollama`로도 됩니다.
2. **새 PowerShell 창**에서 모델을 받습니다 (약 6GB).
   ```powershell
   ollama pull qwen3-vl:8b-instruct
   ```
3. `ai/.env`
   ```
   AI_PROVIDER=ollama
   OLLAMA_MODEL=qwen3-vl:8b-instruct
   OLLAMA_NUM_CTX=16384
   OLLAMA_KEEP_ALIVE=3h
   AI_TIMEOUT_S=12
   OLLAMA_WARMUP_TIMEOUT_S=300
   ```
   - `OLLAMA_NUM_CTX`: 4096(Ollama 기본값)이면 큰 사진이 들어가지 않아 400 오류가 납니다.
   - `OLLAMA_KEEP_ALIVE`: Ollama는 기본으로 5분 동안 요청이 없으면 모델을 GPU에서 내립니다. 장소를 옮기느라 5분 넘게 사진이 없으면 다음 판독이 1분 가까이 걸리므로, 활동 시간 길이만큼 유지합니다.
   - `AI_TIMEOUT_S`: 운영 값은 12초입니다. 어르신이 사진을 올리고 17초 안에 다음 안내를 받게 하려는 설계입니다([마스킹·서버 설계](../ai/MASKING_AND_SERVER.md) 5.3절). 모델이 GPU에 올라가 있으면 장당 2~10초라 이 안에 들어오지만, 모델을 올리는 첫 판독은 1분 가까이 걸립니다. 그래서 활동 전에 모델 준비를 확인하고 시작합니다(4.6절). 내 컴퓨터에서 모델 준비 전에 바로 시험하려면 잠시 120으로 올려도 됩니다.
   - AI 서버는 켜질 때 모델을 미리 올립니다. 콘솔 개요의 **모델 준비**가 "준비됨"이면 첫 판독도 바로 됩니다.
   - `OLLAMA_WARMUP_TIMEOUT_S`: 모델을 미리 올릴 때 기다리는 최대 시간입니다. 판독 제한(`AI_TIMEOUT_S`)과 따로 둬서 12초 운영에서도 모델 올리기가 끊기지 않습니다.
   - 시험이 끝나 GPU 메모리를 바로 비우려면 `ollama stop qwen3-vl:8b-instruct`.

**모델 고르기** (GPU 메모리 16GB 기준, 2026-10-11 인터넷 사진 48장으로 확인)

| 모델 | 속도 | 결과 |
|---|---|---|
| `qwen3-vl:8b-instruct` (운영·기본) | 2~10초 | 등급·위치는 쓸 만함. 파손/꺼짐 구분, 정상 시설 판정이 약한 편 |
| `qwen3-vl:8b` (생각 버전) | 35~45초 | 문제 유형 구분이 더 정확함. 12초 제한을 넘어 운영에는 쓰지 않음 |
| `qwen2.5vl:7b` | 3~5초 | 근거 문장을 예시에서 베끼고 유형을 자주 틀림. 권하지 않음 |

모델마다 위치 좌표 형식이 다릅니다(qwen2.5는 픽셀, qwen3는 0~1000 비율). 코드가 모델 이름을 보고 자동으로 맞춥니다. 다른 계열 모델을 쓸 때 빨간 네모 위치가 엉뚱하면 `.env`에 `OLLAMA_BBOX=pixel` 또는 `OLLAMA_BBOX=norm1000`을 넣어 봅니다.

**모델 지우기**
```powershell
ollama list                       # 설치된 모델 보기
ollama rm qwen2.5vl:7b            # 지우기
```
지워도 디스크가 안 늘면 Ollama가 만든 실행용 변환본(`C:\Users\<사용자>\.ollama\models\manifests\registry.ollama.ai\library\llamacpp\`)이 남은 것입니다. 지금 쓰는 모델의 변환본을 지우지 않도록 확인한 뒤 정리합니다. 디스크가 부족하면 Windows 환경 변수 `OLLAMA_MODELS=D:\ollama\models`로 저장 위치를 다른 드라이브로 옮길 수 있습니다.

### 4.3·4.4 (삭제)

Gemini·Claude 연결은 2026-10-11에 없앴습니다. 판독은 자체 서버의 Ollama로만 합니다. 예전 코드는 `archive/ai-external-providers/`에 있습니다.

### 4.5 바꾼 뒤 확인

1. AI 서버 시작 로그의 `AI=...(...)`가 원하는 모델인지 봅니다.
2. 콘솔을 새로고침하고 오른쪽 위 표시를 봅니다.
3. **시나리오 자동 점검** 탭에서 "점검 시작"을 누릅니다. mock이면 통과 31·확인 2·건너뜀 1, 다른 모델이면 시나리오 비교는 건너뛰고 오류 처리 점검만 합니다.

### 4.6 운영: 활동 시간에만 GPU 켜기

설계는 **바로 판독**입니다. 사진이 올라오면 품질 확인, 가림, AI 판독을 한 번에 하고 결과를 바로 저장합니다. 판독 결과를 바로 쓰는 곳은 현장 재촬영 안내(대상이 틀 밖, 시설 안 보임)뿐이지만, 어르신이 그 자리에 있을 때 안내해야 하므로 판독을 모아서 하지 않습니다. 흐림·어두움은 AI 없이 코드가 1초 안에 판단합니다.

GPU 비용은 활동 시간(예: 오전 9시~12시)에만 서버를 켜서 줄입니다.

| 시점 | 할 일 |
|---|---|
| 활동 15~30분 전 | GPU 서버와 AI 서버를 켬. AI 서버가 켜지면서 모델을 미리 올림. `GET /health`의 `warmup.state`가 `ready`인지 확인 |
| 활동 중 | 바로 판독. `OLLAMA_KEEP_ALIVE`를 활동 시간보다 길게 두어 중간에 모델이 내려가지 않게 함 |
| 활동 후 | 서버를 끔 |

활동 시간 밖에 도착한 사진(오프라인으로 늦게 전송 등)은 AI 재판독 대기열이 아니라 **앱·업무 백엔드**가 맡습니다. AI 대기열은 30초·2분·10분 뒤 세 번 시도하고 끝나며, 서버가 꺼져 있으면 사진을 받을 수도 없습니다. 업무 API 명세대로 백엔드가 AI 연결 실패 시 "전송 대기"(503)를 돌려주면, 가린 사진은 휴대폰에 남아 있다가 다음 활동 시간에 같은 제출 ID로 다시 전송됩니다.

## 5. 마스킹 설정 (`ai/.env`)

| 설정 | 지금 값 | 뜻 |
|---|---|---|
| `PLATE_HEURISTIC` | 0 (시험 중) | 규칙 기반 번호판 검출. 보도블록 줄눈·간판을 번호판으로 잘못 잡는 일이 많아 시험 중에는 끔 |
| `VEHICLE_FALLBACK` | 켜짐 | 번호판 모델이 번호판을 못 찾은 차는 크기·방향과 관계없이 차 전체를 가림 (기획안·앱 안내와 같은 규칙). 옆모습 차, 먼 차도 가려짐. 끄려면 값을 비움. 켰는데 모델을 불러오지 못하면 사진을 받지 않음(`/health`의 `ok: false`) |

운영 전에는 실제 한국 거리 사진으로 마스킹 평가(`python eval/privacy/run_mask_eval.py`)를 하고 위 값을 정합니다. 자세한 설계는 [마스킹·서버 설계](../ai/MASKING_AND_SERVER.md)에 있습니다.

## 6. 여러 장을 한 번에 평가하기

정답 라벨 표(`labels.csv`)가 있는 사진 묶음을 한 번에 판독하고 정확도를 계산합니다. AI 서버를 띄우지 않아도 됩니다.

```powershell
cd C:\Users\hwang\Downloads\senior-inspection\ai
python eval/run_eval.py analyze --labels eval/photos/internet/labels.csv --provider ollama
python eval/run_eval.py analyze --labels eval/photos/internet/labels.csv --provider ollama --sample 15   # 일부만 빠르게
```

- 결과: `ai/eval/results/<시각>_<모델>_analyze/summary.md` (긴급 재현율, 등급 일치율, 등급 혼동표)
- `eval/photos/internet/`: 인터넷 자유 라이선스 사진 48장과 라벨 초안. 출처·라이선스는 같은 폴더의 `SOURCES.md`. 발표에 쓸 때는 저작자 표시가 필요합니다. `ai/eval/photos/`는 Git에서 제외되므로(CONTRIBUTING) 사진과 라벨은 팀 공유 드라이브에서 받아 이 폴더에 둡니다.
- 라벨 작성 기준: [LABELING_GUIDE.md](../ai/eval/LABELING_GUIDE.md)

## 7. 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| `[Errno 10048] … 각 소켓 주소는 하나만 사용할 수 있습니다` | 그 포트에 서버가 이미 떠 있음 | 떠 있는 창에서 `Ctrl+C`. 창이 없으면 `Get-NetTCPConnection -LocalPort 8100 -State Listen`으로 프로세스 번호를 찾아 `Stop-Process -Id <번호>` |
| `.env`를 바꿨는데 다른 모델로 뜸 (예: `AI=mock`) | 같은 창에서 예전에 `$env:AI_PROVIDER=...`를 실행해 창의 값이 `.env`보다 우선함 | `Remove-Item Env:AI_PROVIDER` 후 다시 실행. 또는 새 창에서 실행 |
| 메모에 `ReadTimeout` | 모델 응답이 `AI_TIMEOUT_S`보다 늦음 | 콘솔의 모델 준비가 "준비됨"인지 확인(준비 전 시험이면 잠시 120). 모델 준비 자체가 실패하면 `OLLAMA_WARMUP_TIMEOUT_S`(기본 300)를 늘림 |
| 메모에 `ConnectError` (Ollama) | Ollama가 꺼져 있음 | 시작 메뉴에서 Ollama 실행 |
| 콘솔에 "판독 요청 실패" | 콘솔이 AI 서버를 기다리다 포기함 (기본 300초) | 기록 탭에서 그 기록을 열고 "GET /results로 최신 결과 확인" |
| 빨간 네모 위치가 엉뚱함 | 모델의 좌표 형식이 다름 | Ollama면 `OLLAMA_BBOX` 설정 (4.2절) |
| 번호판이 없는 곳이 회색으로 가려짐 | 규칙 기반 번호판 오탐 | `PLATE_HEURISTIC=0` |
| C 드라이브 용량이 줄어듦 | Ollama 모델 (모델 하나에 원본+변환본 약 12GB) | 4.2절 "모델 지우기" |
| 휴대폰 시험용 앱 관련 | | [8.5절](#85-문제-해결) |

## 8. 휴대폰 시험용 앱 실행하기

휴대폰 카메라로 찍고 **휴대폰에서 얼굴·번호판을 가린 뒤** 가린 사진만 보내, AI 판독까지 이어 보는 시험용 앱이 두 가지 있습니다. 둘 다 업무 API 형식(`/api/v1`, [API 명세서](API_명세서.md) 6절)으로 보내며, 받는 쪽은 검증 콘솔 안의 **시험용 업무 API**입니다(인증·DB 없음, 고정된 시험 코스 5곳).

| 앱 | 어디서 | 특징 | 문서 |
|---|---|---|---|
| PWA | 휴대폰 브라우저 | 설치 없이 주소로 열림. 아이폰 무음 모드에서도 촬영음, 볼륨을 낮추면 소리가 안 나서 화면 번쩍임으로 보완 | [pwa/README](../tools/ai-verify/pwa/README.md) |
| React Native (Expo) | **Expo Go** 앱 (iOS·Android) | 시스템 촬영음 사용. 마스킹은 앱 안 보이지 않는 WebView에서 PWA와 같은 코드로 처리 | [ai-test-app/README](../tools/ai-test-app/README.md) |

```
휴대폰 ──https──▶ cloudflared 터널 ──▶ 검증 콘솔 :8100 (/pwa, /api/v1) ──▶ AI 판독 서버 :8000 ──▶ Ollama
(RN만) Expo Go ──▶ cloudflared 터널 ──▶ Expo 개발 서버 :8081 (앱 코드)
```

판독 결과(등급·문제)는 어르신 화면에 나오지 않습니다. 결과는 검증 콘솔 기록(http://localhost:8100/#runs)에서 봅니다.

### 8.1 처음 한 번만 할 일

1. 번호판·차량 마스킹 모델을 받습니다 (약 15MB, Git에 올리지 않음).
   ```powershell
   python tools\ai-verify\pwa\fetch_models.py
   ```
2. 휴대폰이 PC에 접속할 터널 도구를 설치합니다.
   ```powershell
   winget install --id Cloudflare.cloudflared -e
   ```
3. (RN만) Node.js 20 이상, 앱 패키지, Expo 계정 로그인.
   ```powershell
   cd tools\ai-test-app
   npm install
   npx expo login          # 아이디·비밀번호 입력. 브라우저 로그인이 막히면 이 방식으로
   npx expo whoami         # 아이디가 나오면 성공
   ```
   Expo Go는 **서명된 앱 정보만** 받으므로 프로젝트가 Expo 계정에 등록돼 있어야 합니다. 이미 등록돼 있으면(`app.json`의 `extra.eas.projectId`) 다시 할 필요가 없습니다. 다른 계정으로 바꿀 때만 `npx eas-cli@latest init`을 실행합니다.
4. (RN만) 휴대폰에 **Expo Go**를 설치하고(아이폰은 App Store, 안드로이드는 Play 스토어) 같은 Expo 계정으로 로그인합니다.

### 8.2 실행 (PowerShell 창 5개)

아래 창 1~5를 스크립트 하나로 띄울 수 있습니다. API 키·터널 주소·`.env.local`을 자동으로 채우고, 끝나면 PWA 주소와 Expo Go 주소를 보여 줍니다.
```powershell
powershell -ExecutionPolicy Bypass -File tools\start-test.ps1            # PWA + Expo
powershell -ExecutionPolicy Bypass -File tools\start-test.ps1 -PwaOnly   # PWA만 (창 1~3)
powershell -ExecutionPolicy Bypass -File tools\stop-test.ps1             # 모두 끄기
```
Tailscale이 설치되어 있으면 cloudflared 대신 **Tailscale Funnel**로 열어 주소가 바뀌지 않습니다(PWA `https://<PC 이름>.<tailnet>.ts.net:8443/pwa/`, Expo Go `exp://<PC 이름>.<tailnet>.ts.net`). 앱 코드를 443에 두는 이유: Expo QR의 `exp://` 주소는 443일 때만 https로 접속하고 다른 포트면 http로 접속해 연결이 끊깁니다. 처음 한 번만 준비합니다.
1. `winget install --id Tailscale.Tailscale -e`로 설치하고 작업 표시줄의 Tailscale에서 로그인
2. https://login.tailscale.com/admin/dns 에서 MagicDNS와 HTTPS Certificates 켜기
3. `tailscale funnel --bg --https=8443 http://localhost:8100` 실행. Funnel 허용 링크가 나오면 열어서 허용. 이후로는 스크립트가 알아서 엶

**PWA는 GitHub Pages 주소로 여는 것을 권합니다**: https://teamiroum.github.io/service/test/ . 터널은 느려서 화면·모델(약 15MB)은 GitHub Pages(CDN)에서 받고, 사진 전송·업무 API만 터널로 이 PC의 검증 콘솔에 보냅니다. PWA를 고쳤거나 서버 주소가 바뀌면 `python tools/ai-verify/pwa/deploy_pages.py`로 다시 올립니다(서버 주소는 `tools/ai-test-app/.env.local`에서 읽음, `--api`로 지정 가능). 검증 콘솔은 `https://teamiroum.github.io`의 요청만 허용합니다(`PWA_ORIGINS`).

탐색기에서 `tools\start-pwa.bat`(PWA, 창 1~3), `tools\start-expo.bat`(Expo, 창 1~5. PWA도 함께 열림), `tools\stop-test.bat`(끄기)을 더블클릭해도 같습니다.

손으로 띄울 때는 다음과 같습니다.

각 창은 저장소 폴더(이 컴퓨터에서는 `C:\Contest`. 2·3장 예시의 `senior-inspection` 경로 대신)에서 시작합니다. Ollama가 켜져 있어야 합니다.

| 창 | 명령 | 비고 |
|---|---|---|
| 1. AI 판독 서버 | `cd ai; python -m uvicorn server:app --port 8000 --env-file .env` | 3장과 같음 |
| 2. 검증 콘솔 | `cd tools\ai-verify\backend; $env:AI_API_KEY="<키>"; python -m uvicorn app:app --port 8100` | PWA와 시험용 업무 API가 함께 뜸 |
| 3. 서버 터널 | `cloudflared tunnel --url http://localhost:8100` | 출력의 `https://<이름>.trycloudflare.com`이 **서버 주소** |
| 4. (RN) 앱 코드 터널 | `cloudflared tunnel --url http://localhost:8081` | 출력 주소가 **앱 주소** |
| 5. (RN) Expo 개발 서버 | 아래 | |

5번 창:
```powershell
cd tools\ai-test-app
"EXPO_PUBLIC_SERVER_URL=https://<서버 주소>" | Out-File -Encoding utf8 .env.local   # Git에 올라가지 않음
$env:EXPO_PACKAGER_PROXY_URL="https://<앱 주소>"
npx expo start --port 8081
```
`EXPO_PACKAGER_PROXY_URL`을 주지 않으면 휴대폰이 앱 코드를 `http://`로 받으려다 iOS가 막아 열리지 않습니다. Expo가 제공하는 `npx expo start --tunnel`(ngrok)도 되지만, 시험 중 연결이 자주 끊겨 cloudflared를 씁니다.

**휴대폰에서 열기**
- PWA: 휴대폰 브라우저에서 `https://<서버 주소>/pwa/`
- RN: Expo Go에서 `exp://<앱 주소에서 https:// 뺀 것>` 입력, 또는 5번 창의 QR

### 8.3 시험 전 점검

- [ ] PC 절전·화면 잠금 끄기 (잠들면 서버·터널이 모두 멈춤)
- [ ] 검증 콘솔 오른쪽 위에 `ollama · qwen3-vl:8b-instruct · 계약 2.0`
- [ ] 휴대폰에서 앱을 한 번 열어 "사진 가림 준비"가 얼굴·번호판·차량 모두 "준비" (처음에는 모델 약 15MB를 받음)
- [ ] 한 장 찍어 보내고 검증 콘솔 기록에 들어오는지 확인
- [ ] 시험 기록 정리: 앱 "시험 설정 → 구도 상태 초기화"

터널 주소는 cloudflared를 다시 띄울 때마다 바뀝니다. 바뀌면 PWA는 새 주소로 열고, RN은 `.env.local`과 `EXPO_PACKAGER_PROXY_URL`을 고쳐 5번 창을 다시 띄웁니다. **터널 주소는 인터넷 어디서나 열리고 시험용 업무 API에는 인증이 없으니** 시험하는 사람에게만 알립니다.

### 8.4 결과와 기록 보는 곳

| 무엇 | 위치 |
|---|---|
| 판독 결과·가린 사진 | http://localhost:8100/#runs (각 기록을 열면 사진과 AI 응답) |
| 가린 사진 파일 | 콘솔 사본 `tools\ai-verify\data\images\`, AI 서버 `ai\data\images\` |
| 휴대폰에서 사진이 서버에 오기 전에 난 오류 | http://localhost:8100/api/v1/test-client-logs (이 PC에서만 열림) |
| 휴대폰 대기 사진 | PWA: 브라우저 IndexedDB, RN: 앱 문서 폴더 `outbox/`. 둘 다 가린 사진만. 서버 접수 후 지워짐 |

### 8.5 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| Expo Go: "There was a problem running the requested app" | 앱 코드 주소가 `http://`라 iOS가 막음 | 8.2의 `EXPO_PACKAGER_PROXY_URL`을 `https://` 앱 주소로 주고 5번 창 다시 실행 |
| Expo Go: "you need to be signed in … npx expo login" | PC의 Expo CLI가 로그인 안 됨 (폰 로그인과 별개) | 8.1의 `npx expo login`. 브라우저 로그인(`--browser`)이 "Failed to fetch"로 막히면 아이디·비밀번호 방식 |
| `npx expo start --tunnel`이 `Cannot read properties of undefined (reading 'body')` | ngrok 연결 실패 | cloudflared 방식(8.2)으로 |
| 앱에 "전송 대기"가 계속 남음 | 업로드가 서버에 닿지 못함 | 8.4의 오류 기록 확인. 서버·터널은 `curl https://<서버 주소>/api/v1/me/patrols`로 확인 |
| "저장하지 못했어요" (PWA) | 브라우저 저장소 오류 | 오류 기록의 `outbox-save` 확인. 사파리 개인정보 보호 브라우징이면 일반 창으로 |
| 촬영음이 안 남 (PWA) | 휴대폰 볼륨이 낮음. 웹은 볼륨을 올릴 수 없음 | 볼륨 버튼으로 올림. 화면 번쩍임은 항상 나옴 |
| 사진 전체가 회색으로 가려짐 | 작은 번호판 모델 오탐 | 앱은 사진 25% 이상·정사각형에 가까운 번호판 상자를 버림([앱 마스킹 안내서](../ai/contract/APP_MASKING_GUIDE.md) 3장). 그래도 생기면 그 사진으로 알림 |
| 휴대폰 코드가 안 바뀜 | PWA 서비스 워커·Expo Go 캐시 | PWA는 새로고침 두 번(홈 화면 앱은 완전히 닫았다 열기), Expo Go는 완전히 종료 후 다시 열기 |
