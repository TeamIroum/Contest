# 서비스 (실제 운영 마무리 작업 공간)

팀원이 만든 원본(`frontend/`, `backend/`, `infra/`)은 그대로 두고, 이어받아 실제 서비스로 마무리하는 작업은 이 폴더에서 한다. 담당: 황상진.

| 폴더 | 이어받은 원본 | 내용 |
|---|---|---|
| `senior/` | `frontend/senior` | 시니어 앱. `VITE_BACKEND=real`이면 실제 업무 백엔드에 연결 |
| `admin/` | `frontend/admin` | 관리자 웹. `VITE_BACKEND=real`이면 로그인·오늘 활동 현황·검토 목록·사건·보수를 실제 API로 |
| `web/` | `infra/demo-https` | 앞단 Caddy. 정적 화면 + `/api` → 업무 백엔드(:8080) |
| `backend/` | `backend` | 업무 백엔드. 원본에서 고친 점은 아래 |
| `docker/` | `infra/aws` | 업무 백엔드 + Redis 컨테이너 실행 파일(Dockerfile, compose). 서버에 올릴 때 쓴다 |
| `ui-test/` | — | 두 앱을 실제 브라우저(Playwright Chromium)로 눌러 보고 화면을 캡처하는 시험 |
| `start.ps1`, `stop.ps1`, `reset-demo.ps1` | — | 이 PC 한 대로 서비스 켜기·끄기, 시연 데이터 초기화 (같은 이름의 `.bat`는 더블클릭용) |
| `scripts/` | — | 초기화 보조 (AI 동기화 위치 복원, 관리자 비밀번호 준비) |

업무 백엔드는 `service/backend`를 local 프로필(시연 데이터 + 시연 로그인)로 실행한다. 원본과 다른 점:

- CSRF 쿠키 버그 수정(`common/security/SecurityConfig.java`): Bearer 인증이 될 때마다 Spring Security가 `__Host-itda-csrf` 쿠키를 지워, 로그인 뒤 조회 한 번이면 다음 변경 요청(출석 요청·판정 등)이 403이 되던 문제. 회귀 시험 `auth/CsrfCookieKeptRegressionTest.java`
- 검토 응답의 사진(`review/ReviewViews.Media`)에 기록 `version` 추가: 개인정보 검토 API(`/admin/media/{id}/privacy-reviews`)가 요구하는 값인데 응답에 없어 화면이 판정을 보낼 수 없었다. 계약 명세(`src/test/resources/contract/openapi.yaml`)에 선택 칸으로 추가
- 출발 확인·활동 시작 규칙을 기능정의서(SEN-13·ACT-07·ACC-02)대로 (`patrol/command/StartDepartureService.java`): 출발 확인은 출석 확정(PRESENT) 2명 이상일 때만, 활동 시작은 출발 확인 뒤에만, 조원 누구든 누르면 출석한 조원 전원이 같은 시각으로 시작. 원본은 개인 시작(D-02: 출발 확인을 기다리지 않고 누른 사람만 시작)이었다. 원본의 `StartDepartureIntegrationTest`는 개인 시작을 전제로 해서 이 규칙과 맞지 않는다(Docker가 없어 고치지 않음)
- 시연(local 프로필, `application-local.yml`): AI가 이상 없다고 본 사진도 모두 검토 목록에 올린다(`inspection.review.no-issue-sample-rate: 1`, 운영 기본값 0). 시연에서 담당자 판정을 늘 보여 주기 위함
- 참여자 계정 API 추가(기능정의서 ACT-01, `participant/`): `GET·POST /admin/participants`, `GET·PATCH /admin/participants/{id}`, `POST /admin/participants/{id}/status-changes`, `GET /admin/participants/organizations`. 활동 담당자 관할 기관만, 번호는 암호문·검색용 HMAC으로만 저장하고 응답에는 가린 번호(`010-****-1234`), 같은 번호는 거절, 일시 중지·종료하면 인증 버전을 올리고 로그인 세션을 모두 끊는다. 번호 변경은 새 번호 확인 절차가 필요해 받지 않는다
- 평소 조 편성 API 추가(기능정의서 ACT-02, `team/`): `GET·POST /admin/teams`, `PATCH /admin/teams/{id}`. 한 조 2~3명(자리 1~3), 조원은 적용 시작일(`effectiveFrom`, 오늘 이후)부터 바뀌고 이전 구성은 이력(`team_memberships`)으로 남는다. 이미 배정된 순회는 조원을 따로 복사해 두므로 바뀌지 않는다. 다른 조에서 데려오면 그 조에서 빠지며, 그 조가 1명이 되면 거절, 0명이면 그 조를 닫는다
- 코스·버전 API 추가(기능정의서 ACT-03, `route/`): `GET·POST /admin/routes`, `GET /admin/routes/options`(거점·시설), `PATCH /admin/routes/{id}`(이름·닫기), `POST /admin/routes/{id}/versions`. 출발·복귀 거점, 순서 있는 장소(같은 시설 재방문 허용), 장소별 구도 1~5개. 공개 버전은 고치지 않고 새 버전으로 바로 공개한다
- 날짜별 순회 배정 API 추가(기능정의서 ACT-04, `schedule/`): `POST /admin/patrols`(activityDate, teamId, routeVersionId, scheduledStartAt, expectedReturnAt), `GET /admin/patrol-schedule?from=&to=`(기본 오늘부터 2주). 그날의 실제 조원(평소 조·활동 중)과 코스 버전의 장소·구도를 순회에 복사하고, 같은 참여자의 시간 겹침·지난 날짜·닫은 코스·조원 2명 미만은 거절한다
- 재점검 구도·배정 후보 API 추가(기능정의서 ACT-10, `schedule/ReinspectionSlotService`): `POST /admin/patrols/{id}/stops/{stopId}/capture-slots`(출발 전 순회 장소에 재점검 구도 추가, 순회 version·revision 올림), `GET /admin/reinspection-requests/{id}/candidates`(같은 시설이 든 출발 전 담당 순회 장소, 다시 쓸 빈 재점검 구도). 원본에는 구도 추가 API가 없어 재점검 요청을 배정할 구도를 만들 수 없었다. 배정·취소는 원본 API(업무 API 10절)를 그대로 쓴다
- 공무원 계정 API 추가(기능정의서 OPS-07·08, `officer/`, 시스템 관리자만): `GET·POST /admin/accounts`, `GET /admin/accounts/organizations`, `PUT /admin/accounts/{id}/roles`, `POST /admin/accounts/{id}/password-resets`·`unlocks`·`status-changes`. 발급·초기화 때 임시 비밀번호(12자)를 응답에 한 번만 담고 해시로만 저장한다(멱등 결과·감사 기록에도 남기지 않으므로 이 API들은 멱등 키를 받지 않는다). 초기화·사용 중지는 인증 버전을 올리고 로그인 세션을 끊는다. 본인 계정의 초기화·사용 중지·시스템 관리자 역할 빼기는 거절한다. 잠금 기준은 로그인 쪽 그대로(5회 실패 10분)
- 기간 집계 API 추가(기능정의서 OPS-03, `stats/`, 사업 총괄만): `GET /admin/stats?from=&to=`(기본 오늘까지 14일, 한국 시간). 끝난 순회·출석 확인 연인원·사진·점검 장소·건너뜀·미방문·검토·사건·보수·종결·재보수를 따로 세고, 사진 수로 활동 시간을 계산하지 않는다. 부서별·시설별 사건과 엑셀용 활동·사건 목록도 함께 준다
- 시연 관리자 계정 3개 추가(시드 `R__dev_seed_demo_scenario.sql`, `application-local.yml`): `demo-park`(공원녹지과 시설 담당자, 벤치 검토), `demo-program`(사업 총괄, 통계·보고), `demo-admin`(시스템 관리자, 공무원 계정). 원본 시드에는 벤치를 관리하는 공원녹지과 담당자가 없어 벤치 사진을 볼 사람이 없었다. 초기화 때 6개 모두 비밀번호를 준비한다
- 시연(local 프로필): 관리자 웹에서 새로 등록한 어르신도 고정 시연 인증번호로 로그인한다(`inspection.demo.registered-seniors: true`, 기본값 false). 문자 발송 공급자가 없어서

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

4. 시연 데이터 초기화 (리허설 뒤 등)

   ```powershell
   powershell -ExecutionPolicy Bypass -File service\reset-demo.ps1     # 또는 service\reset-demo.bat 더블클릭
   ```

   공개 주소·Caddy는 그대로 두고 업무 백엔드만 다시 켠다(약 15초). 10/15 순회 출석 전 상태, 사진·검토·사건 0건, 시연 관리자 비밀번호(`ADMIN_PASSWORD`) 준비까지 한다.
   AI 동기화 위치는 보존한다(`%LOCALAPPDATA%\iroum-servicei-cursor.txt`에도 남김). 지우면 이미 처리한 AI 알림을 처음부터 다시 읽는다.

- `start.ps1`은 WSL(PostgreSQL·Redis)이 꺼지지 않게 숨은 WSL 프로세스를 하나 띄운다. 열린 WSL 창이 없으면 WSL이 곧 꺼져 DB·Redis가 끊긴다
- 깃페이지 입구: https://teamiroum.github.io/service/ (바로가기 /service/senior, /service/admin). 올리기는 `web/pages/deploy_pages.py`
- PC가 켜져 있고 인터넷이 연결되어 있어야 열린다. 다 쓰면 `stop.ps1`로 공개 주소를 닫는다
- 시연 데이터의 순회는 2026-10-15 10:00~12:00(한국 시간) 하나다. 다른 날 시험하려면 `tools/e2e/move_demo_date.sql`

## 화면 시험 (`ui-test/`)

서비스를 켠 뒤 실제 브라우저로 두 앱을 눌러 보고 `ui-test/shots/`에 캡처를 남긴다. 화면 오류·실패한 API 응답을 모아 끝에 보여 준다. 값은 `service\.env`에서 읽는다.

```bash
cd service/ui-test
npm install && npm run install-browser          # 처음 한 번
powershell -ExecutionPolicy Bypass -File run-all.ps1   # 전체: 초기화 → 순회를 오늘로 → 시험 14개 → 다시 초기화 (약 3분, 실제 데이터를 바꿈)
node admin.mjs https://<PC>.ts.net:10000         # 관리자 웹: 로그인·검토 목록·사건·오늘 활동 현황
node admin-accounts.mjs <관리자 주소>              # 시연 관리자 6개 계정 로그인·역할별 메뉴 (데이터 안 바꿈)
node admin-decide.mjs https://<PC>.ts.net:10000  # 관리자 웹에서 판정 저장 → 사건 생성 (LOGIN=demo-road 등으로 계정 선택)
node senior.mjs https://<PC>.ts.net --shoot      # 시니어 앱: 로그인 → 출석 → 전체 화면 촬영 → 보내기
node senior-tour.mjs https://<PC>.ts.net        # 시니어 앱 화면 둘러보기 (shots/tour/index.html, 데이터 안 바꿈)
node pause-check.mjs <시니어 주소> <관리자 주소>   # 잠깐 쉬기 ↔ 관리자 화면 '쉬는 중'
node admin-participants.mjs <관리자 주소> <시니어 주소>  # 참여자 등록 → 같은 번호 거절 → 일시 중지 → 그 번호로 로그인 안 됨
node admin-teams.mjs <관리자 주소>                 # 조 만들기 → 1명 남는 편성 거절 → 배정된 순회 조원은 그대로
node admin-assign.mjs <관리자 주소>                # 코스 만들기 → 순회 배정 → 시간 겹침 거절 → 코스 v2 공개, 배정된 순회는 v1
node admin-reinspect.mjs <관리자 주소>             # 보수 완료 → 재점검 요청을 내일 순회에 배정 → 취소 → 다시 배정 (admin-decide 뒤)
node admin-officers.mjs <관리자 주소>              # 계정 발급 → 첫 로그인 변경 → 잠김·해제 → 비밀번호 초기화 → 사용 중지
node admin-stats.mjs <관리자 주소>                 # 통계·보고: 보고 문장·기간 전환·엑셀 (데이터 안 바꿈)
node gh-entry.mjs                                # 깃페이지 입구(teamiroum.github.io/service) → 두 앱 로그인
```

- 시니어 앱 카메라는 Chromium 가짜 장치다. `VIDEO=<JPEG를 이어 붙인 .mjpeg>`를 주면 그 사진을 카메라에 비춘다(기본 시험 화면은 단색이라 흐림으로 나온다)
- `--shoot`과 `admin-decide.mjs`는 실제 데이터를 만든다(사진 접수, 판정, 사건)
