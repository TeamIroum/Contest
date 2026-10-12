# 시험용 시니어 앱 (React Native + Expo, iOS·Android)

시니어 앱의 **앱 안 카메라 촬영 → 기기에서 얼굴·번호판·차량 가림 → 가린 사진만 업무 API로 전송** 흐름을 휴대폰(iOS·Android)에서 AI 판독 모듈까지 이어 시험하는 앱입니다. 화면·전송·마스킹 코드는 두 플랫폼이 같습니다. 같은 흐름의 PWA판은 [../ai-verify/pwa/](../ai-verify/pwa/README.md)입니다.

```
휴대폰 Expo Go 앱 ──multipart──▶ 시험용 업무 API (/api/v1, 검증 콘솔) ──▶ AI 판독 모듈 (Ollama qwen3-vl)
 · expo-camera로 앱 안 촬영 (촬영음)
 · 보이지 않는 WebView에서 가림 (/pwa/mask-host.html, PWA와 같은 masking.js)
 · 가린 JPEG만 앱 문서 폴더 대기함에 저장 → 전송, 연결되면 자동 재전송
```

## 왜 WebView로 가리나

얼굴·번호판·차량 모델을 기기에서 돌리려면 ONNX Runtime·TFLite 네이티브 모듈이 필요합니다. 이것들은 Expo Go에 없어서 앱을 직접 빌드해야 하고, 아이폰에 설치하려면 유료 Apple 개발자 계정과 기기 등록이 필요합니다. 시험을 가볍게 하려고 **Expo Go로 바로 열리는 방식**을 택했습니다.

- 마스킹은 앱 안 보이지 않는 WebView가 검증 콘솔의 `/pwa/mask-host.html`을 열어 처리합니다. 코드가 PWA와 같은 `masking.js`라서, 서버와 맞춰 둔 검출 결과(YuNet 5장 일치, 번호판 t-384 일치)가 그대로 유지됩니다.
- 실서비스용으로 네이티브 추론(onnxruntime-react-native 등)이 필요해지면 `src/MaskEngine.js`만 바꾸면 됩니다. 나머지 화면·전송 코드는 그대로 씁니다.

## 실행 (PC)

자세한 절차와 문제 해결은 [AI 실행 가이드 8장](../../docs/AI_실행_가이드.md#8-휴대폰-시험용-앱-실행하기)에 있습니다. PowerShell 창을 5개 열고, **각 창은 저장소 폴더(예: `C:\Contest`)에서 시작**합니다. Ollama가 켜져 있어야 합니다.

```powershell
# 처음 한 번: 마스킹 모델 받기, 앱 패키지 설치, Expo 로그인
python tools\ai-verify\pwa\fetch_models.py
cd tools\ai-test-app; npm install; npx expo login

# 창 1) AI 판독 모듈 (실제 모델. mock 쓰지 않음)
cd ai; python -m uvicorn server:app --port 8000 --env-file .env

# 창 2) 검증 콘솔 + 시험용 업무 API (AI_API_KEY는 ai/.env의 API_KEYS 중 하나)
cd tools\ai-verify\backend; $env:AI_API_KEY="<키>"; python -m uvicorn app:app --port 8100

# 창 3) 서버 터널: 출력의 https://<이름>.trycloudflare.com 이 "서버 주소" (다시 띄우면 바뀜)
cloudflared tunnel --url http://localhost:8100

# 창 4) 앱 코드 터널: 출력 주소가 "앱 주소"
cloudflared tunnel --url http://localhost:8081

# 창 5) 앱 개발 서버. 서버 주소는 .env.local에 (Git에 올라가지 않음)
cd tools\ai-test-app
"EXPO_PUBLIC_SERVER_URL=https://<서버 주소>" | Out-File -Encoding utf8 .env.local
$env:EXPO_PACKAGER_PROXY_URL="https://<앱 주소>"
npx expo start --port 8081
```

- `EXPO_PACKAGER_PROXY_URL`을 주지 않으면 앱 코드 주소가 `http://`가 되어 아이폰 Expo Go가 열지 못합니다.
- Expo Go는 서명된 앱 정보만 받으므로 PC가 Expo 계정에 로그인돼 있어야 하고, 프로젝트가 그 계정에 등록돼 있어야 합니다(`app.json`의 `extra.eas.projectId`, 지금은 `@hwangsangjin/ai-test-app`).
- Expo의 `npx expo start --tunnel`(ngrok)도 되지만 시험 중 연결 오류가 잦아 cloudflared를 씁니다.

## 휴대폰에서 열기 (iOS·Android)

1. **Expo Go**를 설치합니다 (SDK 57 지원 버전). 아이폰은 App Store, 안드로이드는 Play 스토어. PC의 Expo 계정과 같은 계정으로 로그인해 둡니다.
2. 창 5의 QR을 찍어 엽니다(아이폰은 기본 **카메라 앱**, 안드로이드는 **Expo Go 안의 QR 스캔**). 또는 Expo Go에서 `exp://<앱 주소에서 https:// 뺀 것>`을 직접 입력합니다.
3. 처음 화면 "시험 설정"의 **서버 주소**가 창 3의 서버 주소인지 확인합니다. 기본값은 `.env.local`의 `EXPO_PUBLIC_SERVER_URL`이고, 비어 있으면 입력하라는 안내가 나옵니다.
4. "사진 가림 준비"가 모두 "준비"가 되면 **순회 시작** → **사진 찍기** 또는 **저장된 사진 고르기**.

## 화면

| 화면 | 내용 |
|---|---|
| 처음 | 가림 모델 준비 상태, 순회 시작, 전송 대기 목록, 시험 설정(서버 주소·확인 화면·가린 위치 표시·구도 초기화·보낸 기록) |
| 장소 안내 | 장소 이름·안내, **사진 찍기**, **저장된 사진 고르기**(시험용), 다음 장소, 처음으로 |
| 카메라 | 앱 안 카메라. 찍을 때 촬영음 |
| 가린 사진 확인 | 가린 사진과 가린 위치(노랑 얼굴, 하늘 번호판, 분홍 차량), 개수, 처리 시간 |
| 결과 | 업무 API가 내려준 진행 안내(다음 장소 / 다시 찍기 / 전송 대기). 판독 결과는 보이지 않음. "시험 정보"에 응답과 검증 콘솔 기록 번호 |

## 원본 다루기

- 카메라·사진 고르기·크기 조정이 앱 캐시 폴더에 만드는 원본 임시 파일은 가린 직후(성공·실패 모두) 지웁니다. 갤러리 원본은 건드리지 않습니다.
- 대기함(`문서 폴더/outbox/`)에는 가린 JPEG과 제출 정보만 들어갑니다.
- 가리지 못하면 보내지 않고 "한 번 더 찍어 주세요"를 띄웁니다. 실패 내용(사진 제외)은 서버 `/api/v1/test-client-logs`에 남습니다.

## 알아 둘 점

- **PC를 켜 두어야 합니다**: AI 서버, 콘솔, 터널, Expo 개발 서버가 모두 PC에서 돌아갑니다. 잠자기에 들어가면 시험이 끊깁니다.
- **cloudflared 주소는 다시 띄울 때마다 바뀝니다**: 바뀌면 `.env.local`을 고치고 Expo Go에서 다시 불러오거나, 앱의 서버 주소를 고칩니다. 시험 전에 주소를 확인하세요.
- **터널 주소는 공개 주소입니다**: 시험용 업무 API에는 인증이 없습니다. 주소는 시험하는 사람에게만 알려 주세요. 앱 실패 기록 조회(`/api/v1/test-client-logs`)는 이 PC에서만 열립니다.
- **전송 대기 사진**: 자동 재전송한 사진이 나중에 "다시 찍기"로 판정되면 처음 화면이나 장소 화면에서 "다시 찍어야 할 곳이 있어요"로 알립니다. 형식 오류로 거절된 사진은 처음 화면 전송 대기 목록에서 지울 수 있습니다.
- **처음 실행은 시간이 걸립니다**: 가림 모델과 라이브러리 약 15MB를 받습니다. 시험 전에 한 번 열어 두면 WebView 캐시로 빨라집니다.
- **저장된 사진 고르기**는 시험용 기능입니다. 안내서 2장에 따라 실제 시니어 앱에는 두지 않습니다.
- **확인한 범위**: iOS 번들 빌드, `expo-doctor` 21개 점검, 마스킹 엔진 페이지(PC 크롬에서 앱과 같은 메시지로 시험), 아이폰(iOS 26.6, Expo Go 57)에서 앱 열기·촬영·가림까지 확인했습니다. 아이폰 업로드는 SDK 57 방식으로 고친 뒤 다시 확인해야 하고, **안드로이드 실기기는 아직 확인하지 않았습니다.** 코드는 같지만 WebView 엔진(크롬 기반), 기종별 카메라 회전·처리 속도, 무음 모드 촬영음이 다를 수 있습니다.
