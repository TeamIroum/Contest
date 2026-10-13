# 업무 백엔드 연결 시험 (시니어 앱 → 업무 백엔드 → AI 판독 모듈)

로컬에서 실제 업무 백엔드(`backend/`)와 AI 판독 모듈(`ai/`)을 띄우고, 시니어 앱 흐름(로그인 → 출석 → 활동 시작 → 사진 → AI 판독 → 검토 목록)이 이어지는지 확인한다.
발표 시연은 지금처럼 시험용 업무 API(`tools/ai-verify`, :8100)로 하고, 이 경로는 연결 확인용이다.

## 준비

1. PostgreSQL 16·Redis (WSL 등). 백엔드 기본값: `localhost:5432` DB·사용자 `inspection`, Redis `localhost:6379`
2. AI 판독 모듈 :8000 (`ai/README.md`). 키를 쓰지 않으면 백엔드 `AI_API_KEY`도 비워 둔다
3. 업무 백엔드 :8080, local 프로필 (`backend/README.md`)

   ```bash
   cd backend
   # 필요한 환경 변수: DB_PASSWORD, JWT_SECRET, PHONE_ENC_KEY, PHONE_HMAC_KEY, AI_BASE_URL=http://localhost:8000, AI_API_KEY,
   #                  DEMO_OTP_CODE(시연 인증번호 6자리), DEMO_ADMIN_TEMP_PASSWORD(시연 관리자 임시 비밀번호)
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```

   Git Bash에서 `/`로 시작하는 base64 키가 경로로 바뀌면 `MSYS2_ENV_CONV_EXCL='*' MSYS_NO_PATHCONV=1`을 함께 준다.

4. 시연 순회를 오늘로 옮기기. 시드는 2026-10-15 하루로 고정이라 다른 날에는 출석·사진이 날짜 오류로 막힌다 (로컬 DB에서만)

   ```bash
   psql -h localhost -U inspection -d inspection -v day=$(TZ=Asia/Seoul date +%F) -f tools/e2e/move_demo_date.sql
   ```

## 1) 서버끼리 시험 (`smoke`)

```bash
export DEMO_OTP_CODE=… DEMO_ADMIN_TEMP_PASSWORD=… ADMIN_PASSWORD=…   # ADMIN_PASSWORD: 첫 로그인 때 바꿀 새 비밀번호
python tools/e2e/backend_e2e.py smoke
```

시니어(010-0000-0001) 로그인 → 출석 요청 → 활동 담당자(`demo-activity`)가 출석 2명·출발 확인 → 활동 시작 → 사진 1장 제출(AI 판독) → 시설 담당자(`demo-road`) 검토 목록까지 차례로 부른다.
관리자 계정은 첫 로그인 때 임시 비밀번호를 `ADMIN_PASSWORD`로 바꾼다. 출석 요청은 한 번만 기록되므로 다시 돌려도 된다.

## 2) 시니어 앱으로 시험

```bash
cd frontend/senior
VITE_BACKEND=real VITE_API_PROXY=http://localhost:8080 VITE_API_ORIGIN= npx vite --port 5174
```

`http://localhost:5174`에서 시연 번호(010-0000-0001 또는 0002)와 `DEMO_OTP_CODE`로 들어간다. 출석 요청을 누른 뒤 담당자 확인은 다른 창에서

```bash
python tools/e2e/backend_e2e.py confirm
```

으로 대신한다. 앱은 4초마다 순회 상태를 다시 받아 출석·출발 확인을 보여 준다.

- 로그인·CSRF 쿠키가 `__Host-`·Secure라 `http://localhost`에서만 된다. 휴대폰(LAN 주소)으로 보려면 HTTPS가 필요하다
- 인증번호 요청은 번호마다 30초, 10분에 5번(주소마다 20번)까지다. 넘으면 "잠시 후 다시 시도해 주세요."
