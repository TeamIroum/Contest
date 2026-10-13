# 서비스 (실제 운영 마무리 작업 공간)

팀원이 만든 원본(`frontend/`, `backend/`, `infra/`)은 그대로 두고, 이어받아 실제 서비스로 마무리하는 작업은 이 폴더에서 한다. 담당: 황상진.

| 폴더 | 이어받은 원본 | 내용 |
|---|---|---|
| `senior/` | `frontend/senior` | 시니어 앱. `VITE_BACKEND=real`이면 실제 업무 백엔드에 연결 |
| `admin/` | `frontend/admin` | 관리자 웹. `VITE_BACKEND=real`이면 로그인·오늘 활동 현황·검토 목록·사건·보수를 실제 API로 |
| `web/` | `infra/demo-https` | 앞단 Caddy. 정적 화면 + `/api` → 업무 백엔드(:8080) |
| `backend/` | `backend` | 업무 백엔드. 원본 그대로에 CSRF 쿠키 버그 수정만 더함(아래) |
| `docker/` | `infra/aws` | 업무 백엔드 + Redis 컨테이너 실행 파일(Dockerfile, compose). 서버에 올릴 때 쓴다 |
| `ui-test/` | — | 두 앱을 실제 브라우저(Playwright Chromium)로 눌러 보고 화면을 캡처하는 시험 |
| `start.ps1`, `stop.ps1` | — | 이 PC 한 대로 서비스 켜기·끄기 |

업무 백엔드는 `service/backend`를 local 프로필(시연 데이터 + 시연 로그인)로 실행한다. 원본과 다른 점:

- CSRF 쿠키 버그 수정(`common/security/SecurityConfig.java`): Bearer 인증이 될 때마다 Spring Security가 `__Host-itda-csrf` 쿠키를 지워, 로그인 뒤 조회 한 번이면 다음 변경 요청(출석 요청·판정 등)이 403이 되던 문제. 회귀 시험 `auth/CsrfCookieKeptRegressionTest.java`

## 이 PC로 서비스하기

구성도·요청 흐름·장애 대처는 [ARCHITECTURE.md](ARCHITECTURE.md)에 있다.

```
휴대폰·브라우저 ─HTTPS─▶ Tailscale Funnel ─▶ Caddy :8180(시니어 앱) / :8181(관리자 웹)
                                              └ /api ─▶ 업무 백엔드 :8080 ─▶ PostgreSQL·Redis(WSL), AI 판독 :8000 ─▶ Ollama
```

1. `service\.env.example`을 `service\.env`로 복사해 값을 채운다 (Git에 올라가지 않음)
2. Caddy: [공식 배포본](https://github.com/caddyserver/caddy/releases/tag/v2.10.0) `caddy_2.10.0_windows_amd64.zip`의 `caddy.exe`를 `web\bin\`에 둔다
3. 켜기·끄기

   ```powershell
   powershell -ExecutionPolicy Bypass -File service\start.ps1          # -Build: 두 앱 다시 빌드
   powershell -ExecutionPolicy Bypass -File service\stop.ps1
   ```

   마지막에 시니어 앱(`https://<PC>.ts.net`)과 관리자 웹(`https://<PC>.ts.net:10000`) 주소가 나온다.

- `start.ps1`은 WSL(PostgreSQL·Redis)이 꺼지지 않게 숨은 WSL 프로세스를 하나 띄운다. 열린 WSL 창이 없으면 WSL이 곧 꺼져 DB·Redis가 끊긴다
- 깃페이지 입구: https://teamiroum.github.io/service/ (바로가기 /service/senior, /service/admin). 올리기는 `web/pages/deploy_pages.py`
- PC가 켜져 있고 인터넷이 연결되어 있어야 열린다. 다 쓰면 `stop.ps1`로 공개 주소를 닫는다
- 시연 데이터의 순회는 2026-10-15 10:00~12:00(한국 시간) 하나다. 다른 날 시험하려면 `tools/e2e/move_demo_date.sql`

## 화면 시험 (`ui-test/`)

서비스를 켠 뒤 실제 브라우저로 두 앱을 눌러 보고 `ui-test/shots/`에 캡처를 남긴다. 화면 오류·실패한 API 응답을 모아 끝에 보여 준다. 값은 `service\.env`에서 읽는다.

```bash
cd service/ui-test
npm install && npm run install-browser          # 처음 한 번
node admin.mjs https://<PC>.ts.net:10000         # 관리자 웹: 로그인·검토 목록·사건·오늘 활동 현황
node admin-decide.mjs https://<PC>.ts.net:10000  # 관리자 웹에서 판정 저장 → 사건 생성 (LOGIN=demo-road 등으로 계정 선택)
node senior.mjs https://<PC>.ts.net --shoot      # 시니어 앱: 로그인 → 출석 → 전체 화면 촬영 → 보내기
node senior-tour.mjs https://<PC>.ts.net        # 시니어 앱 화면 둘러보기 (shots/tour/index.html, 데이터 안 바꿈)
node pause-check.mjs <시니어 주소> <관리자 주소>   # 잠깐 쉬기 ↔ 관리자 화면 '쉬는 중'
node gh-entry.mjs                                # 깃페이지 입구(teamiroum.github.io/service) → 두 앱 로그인
```

- 시니어 앱 카메라는 Chromium 가짜 장치다. `VIDEO=<JPEG를 이어 붙인 .mjpeg>`를 주면 그 사진을 카메라에 비춘다(기본 시험 화면은 단색이라 흐림으로 나온다)
- `--shoot`과 `admin-decide.mjs`는 실제 데이터를 만든다(사진 접수, 판정, 사건)
