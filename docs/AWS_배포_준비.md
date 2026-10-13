# AWS 배포 준비

업무 백엔드 쪽 AWS 배포 설계와 설정 견본이다(DEPLOY-01, 2026-10-13. DEPLOY-02에서 프론트 빌드·AI 서버 배치·S3 구현 상태를 고침). **AWS 자원은 만들지 않았고 비용이 드는 작업도 하지 않았다.** 금액은 적지 않고 산정에 필요한 항목만 둔다. 확정 정책이 아닌 제안은 "제안"으로, 정할 것은 9절에 모은다.

확정된 것과 아닌 것: WEB EC2(공개) → WAS EC2(사설) → RDS PostgreSQL(사설) 3계층과 프론트 React + Vite 빌드는 이 문서의 기준이다. **AI 판독 서버를 WAS와 분리하는 것은 확정이지만 AWS 안에 둘지는 정하지 않았다**(3절 "AI 서버 배치 선택지").

설정 견본: [infra/aws/](../infra/aws/) — `was/Dockerfile`, `was/docker-compose.yml`, `was/.env.example`, `web/nginx.conf`, `ai/README.md`(AI 서버 배치 조건). 비밀 값은 들어 있지 않다.

## 0. 확인한 것과 하지 않은 것

| 구분 | 내용 |
|---|---|
| 로컬에서 확인 | `bootJar` → `was/Dockerfile` 이미지 빌드 성공. `was/docker-compose.yml`을 임시 PostgreSQL 16.15와 함께 로컬 Docker에서 기동: Redis 비밀번호 healthcheck 통과, AOF `appendonly yes`, Flyway 전체 적용, `/actuator/health` UP. `web/nginx.conf`는 자리표시자를 바꾸고 임시 인증서로 `nginx -t` 통과(nginx 1.27, DEPLOY-02 캐시 규칙 수정 뒤 다시 통과). 확인 뒤 임시 컨테이너·볼륨은 지웠다. S3 저장소는 S3 호환 컨테이너(LocalStack)로 테스트(4절) |
| 확인 못 함 | 실제 AWS 네트워크·보안 그룹·RDS SSL·S3·IAM(S3 저장소는 S3 호환 컨테이너로만 확인, 4절), 프록시 뒤 실제 클라이언트 IP 인식(`SERVER_FORWARD_HEADERS_STRATEGY`), HTTPS 인증서 발급, AI 서버 배치 위치별 연결·지연(AWS GPU 인스턴스 또는 외부 GPU 서버), 프론트 빌드 결과를 nginx에 올린 동작, 복원 연습 |
| 하지 않음 | AWS 자원 생성, 실제 S3 연결, SMS 공급자 연동, 프론트 빌드 실행(프론트는 담당자 코드를 읽기만 함) |
| 문서로만 확인 | 프론트 빌드 방식: origin/main(919af64)의 `frontend/senior`·`frontend/admin`은 React 19 + Vite 8, `npm run build`(= `tsc --noEmit && vite build`) → 각 앱 `dist/`. 두 앱 모두 `vite.config.ts`에 `base`가 없어 `/` 기준이다(1절) |

## 1. 구성

```
인터넷 ──443──▶ [WEB EC2 · 공개 서브넷]  nginx: Vite 빌드 결과(dist) 정적 파일 + /api 역방향 프록시(같은 출처)
                         │ 8080 (사설)
                         ▼
               [WAS EC2 · 사설 앱 서브넷]  docker compose: backend(Spring Boot) + redis(별도 컨테이너, 외부 포트 없음)
                 │ 5432                        │ HTTPS (S3 게이트웨이 엔드포인트)     ┊ AI API (API 키, WAS에서만)
                 ▼                             ▼                                     ┊
   [RDS PostgreSQL 16 ·              [S3 사설 버킷: 업무 사진]                       ┊
    사설 DB 서브넷 2개 AZ]           (MEDIA_STORE=s3일 때)                           ▼
                                                                    [AI 판독 서버 · WAS와 분리 · 위치 미정]
                                                                     선택지 A: AWS GPU EC2(사설 AI 서브넷)
                                                                     선택지 B: AWS 밖 GPU 서버(VPN 또는 허용 목록+TLS)
                 WAS의 외부 통신(이미지 받기·SMS·OS 갱신) ─▶ NAT 게이트웨이(공개 서브넷) 또는 VPC 엔드포인트(3절)
```

- 실선은 확정한 3계층, 점선(┊)은 위치를 정하지 않은 AI 서버 연결이다. 어느 선택지든 AI는 WAS만 부르고 앱·관리자 웹·인터넷에서 직접 닿지 않는다.
- 프론트 빌드(문서로 확인): 시니어 앱 `frontend/senior`와 관리자 웹 `frontend/admin`이 각각 `npm ci && npm run build`로 `dist/`를 만든다. `dist/index.html`, 해시 붙은 `dist/assets/*`, `public/`을 그대로 복사한 파일(시니어 앱은 `manifest.webmanifest`·아이콘·`models/model-manifest.json`·`models/face_yunet_s_dynamic@1.onnx`)이 들어 있다. 두 앱 모두 `base`가 `/`라 **같은 호스트의 다른 경로(`/admin/` 등)에 함께 올릴 수 없다.** 호스트를 나누거나(예: `app.<도메인>`, `admin.<도메인>`, 각 server 블록이 같은 WAS로 `/api` 프록시), 프론트 담당이 한 앱의 `base`를 바꿔야 한다(9절 결정).
- 같은 출처 빌드: 두 앱의 `.env`에서 `VITE_API_ORIGIN`을 비워 두면 화면과 같은 주소의 `/api/v1`을 부른다. `VITE_*` 값은 빌드 때 파일에 박히므로 비밀 값을 넣지 않는다.
- 시니어 앱 서비스 워커(`sw.js`)는 origin/main 기준 아직 없다(프론트 README의 남은 일). 생기면 nginx 캐시 규칙(`/sw.js` no-cache)이 그대로 쓰인다.
- 앱과 관리자 웹은 업무 API만 부른다. AI는 WAS만 부른다(`infra/ec2/README.md`).
- 같은 출처 배포를 제안한다. `__Host-itda-refresh`·`__Host-itda-csrf` 쿠키(SameSite, Domain 없음)와 CSRF가 추가 설정 없이 동작하고 `CORS_ALLOWED_ORIGINS`를 비워 둘 수 있다. 시니어 앱과 관리자 웹을 다른 호스트로 나눠도 각 호스트가 자기 `/api`를 프록시하면 호스트마다 같은 출처라 CORS가 필요 없다(쿠키는 호스트별로 따로 생김). API를 별도 호스트로 두고 `VITE_API_ORIGIN`을 채우는 방식은 CORS·쿠키 설계를 다시 본다.
- 처음에는 WAS·AI 모두 1대다. AI는 재판독 대기열·변경 알림이 프로세스 안에 있어 1대·작업자 1개가 전제다. WAS의 주기 작업(이벤트 동기화·리스 복구·보류 해제 전송·검토 outbox·기한 건너뛰기)은 DB 잠금·선점으로 중복 실행에 대비하고 이벤트 동기화 등 일부는 동시 실행을 테스트로 확인했지만, 여러 대 운영은 검증하지 않았으므로 1대로 시작한다.

## 2. 네트워크와 접근 규칙(제안)

리전은 서울(ap-northeast-2)을 가정한다. RDS 서브넷 그룹은 AZ 2개가 필요하다.

| 서브넷 | CIDR 예 | AZ | 둘 것 |
|---|---|---|---|
| public-a / public-c | 10.0.1.0/24, 10.0.2.0/24 | a, c | WEB EC2(탄력적 IP), NAT 게이트웨이 |
| app-a | 10.0.11.0/24 | a | WAS EC2 |
| ai-a(선택지 A일 때만) | 10.0.21.0/24 | a | AWS GPU EC2(해당 AZ에 GPU 인스턴스 여유가 있는지 확인). 선택지 B면 만들지 않는다 |
| db-a / db-c | 10.0.31.0/24, 10.0.32.0/24 | a, c | RDS 서브넷 그룹 |

라우팅: 공개 서브넷만 인터넷 게이트웨이. 사설 서브넷의 0.0.0.0/0은 NAT(선택 시), S3는 게이트웨이 엔드포인트 경로.

보안 그룹(출발지는 IP 대신 보안 그룹 참조):

| 그룹 | 들어오는 연결 | 나가는 연결 |
|---|---|---|
| sg-web | 443, 80(인증서 갱신·HTTPS 전환용) ← 0.0.0.0/0 | 8080 → sg-was, 443 → 0.0.0.0/0(OS 갱신·인증서) |
| sg-was | 8080 ← sg-web | 5432 → sg-rds, 443 → S3 엔드포인트 접두사 목록·NAT 경유 외부(SMS 공급자·레지스트리). AI: 선택지 A면 8000(또는 내부 TLS 포트) → sg-ai, 선택지 B면 VPN 경로의 AI 사설 주소 또는 AI 공인 주소 443만 |
| sg-ai(선택지 A일 때만) | 8000 ← sg-was | 443 → 필요한 곳만(모델을 미리 넣으면 운영 중 불필요. 외부 판독 공급자를 쓰면 그 주소) |
| sg-rds | 5432 ← sg-was | 없음 |

- SSH 포트는 열지 않는다. 접속·명령 실행은 SSM Session Manager/Run Command를 쓴다(인스턴스 역할에 `AmazonSSMManagedInstanceCore`, 사설 서브넷은 NAT 또는 ssm·ssmmessages·ec2messages 엔드포인트 필요).
- Redis는 WAS compose 네트워크 안에서만 열고 호스트 포트를 열지 않는다(`was/docker-compose.yml`).
- `/actuator/health`는 nginx에서 VPC 대역만 허용한다. 상태 세부 정보는 이미 `show-details: never`.
- WEB→WAS 구간은 VPC 안 HTTP다. WAS→AI는 선택지 A면 VPC 안 HTTP, 선택지 B면 AWS 밖으로 나가므로 **TLS(또는 VPN 터널) 필수**다. 사진·토큰·API 키가 지나가므로 선택지 A에서도 내부 TLS를 추가할 수 있다(결정 9절).

## 3. 사설 WAS·AI의 외부 통신 경로

| 필요한 통신 | 누가 | 경로 |
|---|---|---|
| 컨테이너 이미지 받기 | WAS(백엔드·redis 이미지), AI(선택지 A) | ECR이면 ecr.api·ecr.dkr 인터페이스 엔드포인트 + S3 게이트웨이(이미지 계층이 S3에 있음). Docker Hub 이미지(redis, 기본 JRE)는 ECR로 복사해 두거나 NAT 필요 |
| 비밀 값 읽기 | WAS, AI(선택지 A) | SSM(ssm) 인터페이스 엔드포인트 또는 NAT |
| 사진 저장 | WAS | S3 게이트웨이 엔드포인트(무료, NAT 처리 비용 없음). 버킷 정책으로 이 엔드포인트만 허용 |
| SMS 인증번호 발송 | WAS | **외부 공급자 HTTPS → NAT 필요**(AWS 안의 발송 서비스를 쓰면 그 엔드포인트). 공급자 미정 |
| OS 패키지 갱신 | 전부 | NAT, 또는 미리 갱신한 AMI로 교체 |
| AI 모델 받기 | AI(선택지 A) | 준비 단계에서만. 모델을 AMI/EBS에 넣어 두면 운영 중 불필요 |
| 외부 판독 공급자(Gemini 등) | AI(선택지 A) | 그 공급자를 쓸 때만 NAT. 로컬 GPU 모델(Ollama)이면 불필요 |
| AI 서버 호출 | WAS | 선택지 A: VPC 안 사설 IP. 선택지 B: Site-to-Site VPN(가상 사설 게이트웨이) 또는 NAT 경유 HTTPS(AI 쪽에서 NAT 탄력적 IP만 허용) |

제안: SMS 발송이 외부 공급자인 한 NAT 게이트웨이 1개(AZ a)가 필요하다. S3는 게이트웨이 엔드포인트로 따로 빼 사진 트래픽이 NAT를 지나지 않게 한다. ECR·SSM 인터페이스 엔드포인트는 시간당 비용이 있으므로 NAT가 있으면 우선 생략할 수 있다. NAT를 두지 않으려면 SMS 경로가 정해진 뒤 다시 본다.

### AI 서버 배치 선택지(미정)

AI 판독 서버를 WAS와 다른 서버로 두는 것은 확정이다. AWS 안에 둘지는 정하지 않았다. 어느 쪽이든 공통 조건은 같다.

공통 조건:
- WAS `AI_BASE_URL`, `AI_API_KEY`(AI `API_KEYS` 중 하나)로 부른다. 앱·관리자 웹은 AI를 부르지 않는다. AI에는 WAS 말고 들어오는 경로를 두지 않는다.
- 연결·요청 기한은 업무 서버 설정 그대로다(`connect-timeout 5s`, `request-timeout 30s`, 보류 해제 전송 30초). **운영 기한을 임의로 바꾸지 않는다.** nginx `proxy_read_timeout`은 사진 접수가 AI를 기다리는 시간보다 길게 90초로 둔다. 판독 시간 + 왕복 지연이 30초 안이어야 한다.
- 판독 시간은 로컬 RTX 2080 8GB·qwen3-vl:8b-instruct 실측만 있다(04단계 기록). 정한 위치·장비에서 같은 모델로 재고 확인한다.
- AI는 작업자 1개·1대가 전제다(재판독 대기열·변경 알림이 프로세스 안). AI 데이터(`data/images`, `data/images_revisions`, `data/results`, 이벤트 로그)는 보존 볼륨에 두고 백업한다. 이벤트 로그가 초기화되면 업무 서버 커서 세대 복구(G4, 미정)가 필요하다.
- AI를 운영 시간 밖에 끄는 경우: 사진 접수는 AI 미확보(재촬영·재시도 경로), 이벤트 동기화·보류 해제 전송은 다음 주기에 다시 시도한다. 꺼진 동안 공개 상태 불일치가 남을 수 있으므로 활동 시간 전후 여유를 두고 켠다.
- AI 서버에는 가린 사진과 판독 결과가 남는다. 어느 위치든 사진 보관·접근 통제 정책(사진 보관과 인증 정책)을 같이 적용한다.

| 항목 | 선택지 A: AWS GPU EC2(사설 AI 서브넷) | 선택지 B: AWS 밖 GPU 서버(외부·자체 보유) |
|---|---|---|
| 네트워크 경로 | 같은 VPC. `AI_BASE_URL=http://<AI 사설 IP>:8000`. ai-a 서브넷·sg-ai 추가 | (B-1) Site-to-Site VPN: AWS 가상 사설 게이트웨이 ↔ AI 쪽 VPN 장비, WAS는 사설 주소로 호출. (B-2) 공인 HTTPS: WAS는 NAT 경유로 나가고 AI 쪽 방화벽은 NAT 탄력적 IP만 허용 |
| TLS·인증 | API 키. 내부 TLS는 선택 | API 키 + **TLS 필수**(B-2는 신뢰할 수 있는 인증서, AI 앞 nginx 등에서 종단). B-1도 터널 안 TLS 권장. 키 교체 순서는 6절 |
| 지연 | VPC 안, 무시할 수준 | 인터넷·VPN 왕복이 매 요청(사진 업로드 최대 10MiB, 가린 사진 받기)에 더해진다. 30초 기한 안인지 실측 필요 |
| 가용성 | AWS 인스턴스 장애·GPU 용량 부족(서울 리전 AZ별 확인) | 외부 서버·회선·VPN 장애. 담당·연락 체계 필요 |
| 비용 항목 | GPU 인스턴스 시간(가장 큰 항목), EBS, 스냅숏 | VPN 연결 시간·전송(B-1) 또는 NAT 처리 GB·인터넷 전송(B-2), AWS 밖 서버·전기·회선 비용 |
| 운영 | AMI·EBS에 모델, SSM으로 관리 | AI 담당 장비 관리, AWS 쪽은 경로·허용 목록만 |
| AWS 쪽 준비 | ai-a 서브넷, sg-ai, (NAT 또는 엔드포인트) | B-1: 가상 사설 게이트웨이·고객 게이트웨이·라우팅. B-2: NAT 게이트웨이(어차피 SMS로 필요할 수 있음) |

## 4. 사진 저장소: 파일 시스템과 S3

사진 저장소는 설정으로 고른다(MEDIA-S3-01, 04단계 공백 G1). `media/MediaStorage` 인터페이스 뒤에 `FileSystemMediaStorage`(기본값)와 `S3MediaStorage`가 있다. 쓰기는 `inspection-media/<프로그램>/<점검 또는 remask>/…/r<버전>-<UUID>.jpg|png` 새 키에만 한다(덮어쓰기 없음). 저장 호출(`put`)은 사진 접수·리스 복구·재가림 이벤트·보류 해제 뒤 새 버전 받기에서 모두 **DB 트랜잭션 밖**에서 일어나고, 트랜잭션이 실패하면 `deleteQuietly`로 고아 사본을 지운다. 읽기는 사진 조회 API(`MediaContentService`, 권한 확인 뒤 WAS가 바이트를 응답)와 재판독에서 한다. DB `media.bucket`에는 논리 이름 `inspection-media`가 저장되고, 저장소를 바꿔도 DB 값은 그대로다.

| 항목 | 파일 시스템(기본) | S3 |
|---|---|---|
| 내구성·확장 | WAS EBS 한 볼륨. 인스턴스·볼륨 장애 시 스냅숏 시점까지 손실 | 여러 AZ 보관. 용량 관리 불필요 |
| 원자적 쓰기 | 임시 파일+이동으로 구현 | PutObject는 끝난 뒤에만 보임. 임시 이름 불필요 |
| 지연 | 로컬 디스크 | 요청마다 네트워크 왕복. 저장이 잠금 밖이라 잠금 시간에는 영향 없음 |
| 삭제 | 즉시 삭제 | 버전 관리를 켜면 이전 버전이 남는다. 보류·고아 사본이 남지 않게 이전 버전 만료 규칙 필요 |
| WAS 여러 대 | 공유 불가 | 공유 가능 |
| 접근 통제 | 호스트 권한 | IAM 역할·버킷 정책·공개 차단·암호화 |

### 백엔드 구현(MEDIA-S3-01)

설정(`application.yml`, 환경 변수):

| 설정 | 환경 변수 | 기본값 | 설명 |
|---|---|---|---|
| `inspection.media.store` | `MEDIA_STORE` | `filesystem` | `filesystem` 또는 `s3`. 로컬·기존 테스트는 파일 시스템 그대로 |
| `inspection.media.storage-root` | `MEDIA_STORAGE_ROOT` | 임시 디렉터리 | 파일 시스템일 때 루트 |
| `inspection.media.s3.bucket` | `MEDIA_S3_BUCKET` | 없음 | s3일 때 필수. 비어 있으면 기동 실패 |
| `inspection.media.s3.prefix` | `MEDIA_S3_PREFIX` | 비어 있음 | 객체 키 = `<prefix>/inspection-media/<키>`. 비면 `inspection-media/<키>` |
| `inspection.media.s3.region` | `MEDIA_S3_REGION` | `ap-northeast-2` | 버킷 리전 |
| `inspection.media.s3.server-side-encryption` | `MEDIA_S3_SSE` | `AES256` | `AES256`(SSE-S3) 또는 `aws:kms`(SSE-KMS). 그 밖의 값은 기동 실패 |
| `inspection.media.s3.kms-key-id` | `MEDIA_S3_KMS_KEY_ID` | 비어 있음 | SSE-KMS 키. 비면 S3 관리 키(aws/s3) |
| `inspection.media.s3.endpoint` | `MEDIA_S3_ENDPOINT` | 비어 있음 | **로컬 S3 호환 시험 컨테이너 전용.** 운영에서는 비운다 |

동작:

1. 자격 증명은 AWS SDK v2 기본 체인만 쓴다. AWS에서는 EC2 인스턴스 역할이다. 접근 키는 코드·설정·`.env`에 두지 않는다. 의존성은 AWS SDK v2 BOM 2.55.7(2026-09-28 배포)의 `s3` 모듈, 동기 Apache 클라이언트만 쓴다.
2. `put`: PutObject + 서버 측 암호화(기본 SSE-S3) + 확장자 기준 `Content-Type`(jpg/png). ACL은 보내지 않는다. 같은 키를 다시 쓰지 않으므로 덮어쓰기 방지 조건(If-None-Match)은 넣지 않았다.
3. `read`: GetObject → 바이트. 없음(404)·거부(403, `ListBucket` 권한이 없으면 없는 객체도 403)·연결 실패는 파일 시스템과 같은 `UncheckedIOException`이다. 사진 조회는 503 `SERVICE_UNAVAILABLE`, 재판독은 기술 실패로 남는다. 사진이 10MiB 이하라 바이트 배열 방식을 유지했다.
4. 저장 실패도 같은 예외라 사진 접수는 지금처럼 `STORAGE_FAILED` → `RETRY_REQUIRED`(사진은 기기에 남고 같은 ID 재전송으로 복구), 보류 해제 뒤 새 버전 받기는 일시 실패로 다시 시도한다.
5. `deleteQuietly`: DeleteObject. 실패는 무시하고 정리 작업에 맡긴다(정리 작업·보관 기간은 미정).
6. 키 검사는 두 구현이 같다. `..`·`.`·빈 단계, 절대 경로, 역슬래시, `:`, 제어 문자가 든 키와 잘못된 논리 버킷 이름은 `IllegalArgumentException`으로 거부한다.
7. **미리 서명한 URL·공개 객체·ACL은 쓰지 않는다.** 사진 조회는 요청마다 현재 관할·역할·공개 상태(D-40, 보류)를 확인하고 `no-store`로 응답한다. 서명 URL은 유효 시간 동안 보류·관할 변경을 우회한다.

로컬 확인(S3 호환 컨테이너 LocalStack `localstack/localstack:4.4.0`, Testcontainers):

- 저장소 계약 테스트를 두 구현에 같이 돌렸다(쓰기·읽기·없는 키·삭제·경로 이탈 거부). S3 쪽은 접두사 대응, SSE-S3·SSE-KMS 헤더, Content-Type, 객체 ACL에 공개 권한 없음, 설정 오류 기동 실패, 없는 버킷·닿지 않는 주소에서 쓰기·읽기 실패와 삭제 실패 무시도 확인했다.
- `inspection.media.store=s3`로 통합 흐름을 다시 돌렸다: 사진 접수 → 버킷에 암호화된 객체(파일 시스템에는 쓰지 않음) → 관할 담당자 GET `/media/{id}/content` 200·`no-store`, 범위 밖 404, 시설 담당자 보류 뒤 404, 재가림 이벤트 새 객체, 객체가 없으면 503, 저장 실패(버킷 없음) 시 사진 행 없이 `RETRY_REQUIRED` → 같은 ID 재전송 복구, 저장 뒤 트랜잭션 실패 시 객체 삭제.
- 테스트는 컨테이너의 시험용 자격 증명을 테스트 코드(JVM 시스템 속성)로만 넣는다.

**확인하지 않은 것**: 실제 AWS S3·IAM 인스턴스 역할·버킷 정책(`aws:SecureTransport`·`aws:sourceVpce`)·퍼블릭 액세스 차단·VPC 게이트웨이 엔드포인트 경로·KMS 키 정책·실제 지연. LocalStack은 IAM·버킷 정책을 기본으로 검사하지 않으므로 권한 거부 동작은 실제 AWS에서 확인해야 한다. AWS 자원은 만들지 않았다.

S3 버킷 설정(제안): 퍼블릭 액세스 차단 4개 모두 켬, 객체 소유권 "버킷 소유자 강제"(ACL 끔), SSE-S3 또는 SSE-KMS(기본 암호화도 같은 값), 버킷 정책으로 TLS 아닌 요청 거부(`aws:SecureTransport=false`)와 WAS VPC 엔드포인트 밖 접근 거부(`aws:sourceVpce`). WAS 역할은 `s3:PutObject`·`s3:GetObject`·`s3:DeleteObject`를 `arn:aws:s3:::<버킷>/<prefix>/inspection-media/*`(접두사가 없으면 `arn:aws:s3:::<버킷>/inspection-media/*`)에만. SSE-KMS면 그 키의 `kms:GenerateDataKey`·`kms:Decrypt`도 필요하다. `s3:ListBucket`은 주지 않는다(없는 객체가 403으로 오지만 같은 읽기 실패로 처리). 버전 관리·보관 기간·수명 주기는 사진 보관 기간 결정 뒤 정한다.

전환·이전: 파일 시스템으로 운영을 시작했다면 같은 키로 S3에 복사(`<루트>/inspection-media/…` → `s3://<버킷>/<prefix>/inspection-media/…`) → `MEDIA_STORE=s3` 전환·재기동 → 사진 조회 확인 → 파일 정리. 복사 도구는 만들지 않았다. S3 버킷이 준비되기 전에는 `MEDIA_STORE=filesystem`과 `MEDIA_STORAGE_ROOT`를 WAS의 별도 EBS 볼륨(compose `media` 볼륨)에 두고 그 볼륨을 스냅숏한다.

## 5. RDS·Redis

RDS(제안): PostgreSQL 16, 사설 DB 서브넷 그룹, 공개 접근 끔, 저장 암호화, 삭제 방지, 자동 백업(보관 일수 결정)·특정 시점 복구, 삭제 시 최종 스냅숏. 연결은 SSL(`sslmode=require`, CA 묶음을 컨테이너에 넣으면 `verify-full`). Multi-AZ는 비용과 함께 결정. Flyway가 앱 기동 때 마이그레이션하므로 앱 계정에 스키마 변경 권한이 필요하다. 마스터 계정은 앱에서 쓰지 않는다(마이그레이션 계정 분리 여부는 결정 9절).

Redis: 세션·Refresh Token·인증번호 시도 정보가 있다. 컨테이너를 다시 만들면 모두 다시 로그인해야 하므로 AOF를 켜고 볼륨에 둔다(견본 반영, 로컬 확인). 백업은 하지 않는다(잃어도 업무 데이터 손실은 없고 재로그인이 필요). 비밀번호를 걸고 호스트 포트를 열지 않는다. WAS를 여러 대로 늘리면 ElastiCache 등 공유 Redis로 옮긴다.

## 6. 비밀 값 주입

보관: SSM Parameter Store SecureString(예: `/itda/prod/was/JWT_SECRET`). 저장소·AMI·사용자 데이터·로그에 넣지 않는다.

주입: 배포 명령(SSM Run Command)이 인스턴스 역할로 `/itda/prod/was/` 아래 값을 읽어 `.env`(권한 600, 소유자 배포 계정)를 만들고 `docker compose up -d`. 역할 권한은 그 경로의 `ssm:GetParametersByPath`와 해당 KMS 키 `kms:Decrypt`만.

| 값 | 쓰는 곳 | 바꿀 때 영향 |
|---|---|---|
| `DB_PASSWORD` | WAS | RDS와 함께 바꾸고 재기동 |
| `REDIS_PASSWORD` | WAS backend·redis | 함께 재기동 |
| `JWT_SECRET` | WAS | 발급된 Access Token 무효(최대 15분 영향) |
| `PHONE_ENC_KEY`, `PHONE_HMAC_KEY` | WAS | **바꾸면 저장된 전화번호를 풀거나 찾을 수 없다.** 재암호화 절차 없이는 바꾸지 않는다. 별도 백업 필수 |
| `AI_API_KEY` ↔ AI `API_KEYS` | WAS, AI | AI에 새 키 추가 → WAS 교체 → 옛 키 제거 순서 |
| SMS 공급자 키 | WAS | 공급자 결정 후 |

`.env.example`에는 이름과 형식만 둔다.

## 7. 배포·롤백 절차(제안)

배포:
1. 빌드 서버(로컬 또는 이후 GitHub Actions): `./gradlew test`(건너뜀 0개 확인)·`check_openapi` → `bootJar` → 이미지 태그는 Git 커밋 → ECR push. 이미지는 커밋 태그를 덮어쓰지 않는다.
2. 새 Flyway 마이그레이션이 있으면 RDS 수동 스냅숏을 먼저 만든다.
3. WAS: SSM Run Command로 `.env` 생성(6절) → `BACKEND_IMAGE` 변경 → `docker compose pull backend && docker compose up -d backend`. `server.shutdown: graceful`로 진행 중 요청을 마친다. 1대 구성이라 재기동 중 짧은 중단이 있으므로 **활동 시간 밖**에 한다(사진 전송 기한·활동일 기준 Asia/Seoul).
4. `/actuator/health` UP 확인(VPC 안에서), 로그인·순회 조회·사진 조회 점검.
5. WEB: 빌드 서버에서 각 앱 `npm ci && npm run build`(`VITE_API_ORIGIN` 비움) → `dist/` 내용을 `/var/www/app-<버전>`(관리자 웹은 `/var/www/admin-<버전>`)에 올리고 링크를 바꾼 뒤 `nginx -s reload`. `index.html`·`sw.js`(생기면)·`manifest.webmanifest`·`models/model-manifest.json`은 짧게, 해시 붙은 `assets/*`와 버전 붙은 모델 파일은 길게 캐시한다(`web/nginx.conf`).

롤백:
- 앱만 바뀐 경우: 이전 커밋 태그로 `BACKEND_IMAGE`를 되돌려 재기동.
- 마이그레이션이 있던 경우: Flyway는 앞으로만 간다. 이전 앱은 자기보다 새 마이그레이션 기록을 무시하고 기동하므로(기본값) **마이그레이션은 이전 앱과 호환되게(추가 위주)** 만든다. 호환되지 않는 변경은 이전 앱으로 못 돌아가며, 마지막 수단은 배포 전 스냅숏 복원(스냅숏 뒤 데이터 손실)이다.
- 프론트: 링크를 이전 폴더로 되돌린다. 이미 설치된 PWA는 서비스 워커 갱신 주기를 거친다.
- AI 롤백은 AI 담당자 절차를 따른다(배치 위치와 관계없음). 이벤트 로그를 지우거나 새로 만들면 업무 서버 커서 세대를 맞춰야 한다(G4).

## 8. 백업

| 대상 | 방법 | 비고 |
|---|---|---|
| RDS | 자동 백업 + 배포 전 수동 스냅숏 | 보관 일수 결정 |
| 사진(S3) | 버킷 내구성. 버전 관리·복제는 보관 기간 결정 뒤 | 개인정보: 보류·삭제한 사진의 이전 버전이 남는지 같이 본다 |
| 사진(파일 시스템 임시 운영) | WAS media 볼륨 EBS 스냅숏(Data Lifecycle Manager) | S3 전환 뒤 제거 |
| AI 데이터·이벤트 로그 | 선택지 A: AI EBS 스냅숏. 선택지 B: AI 담당 장비의 백업 | 이벤트 로그 초기화 시 G4 |
| Redis | 하지 않음 | 잃으면 재로그인 |
| 비밀 값 | SSM. `PHONE_*` 키는 별도 안전한 곳에 사본 | 잃으면 전화번호 복구 불가 |
| 설정 | 저장소(infra/aws) | 비밀 값 제외 |

복원 연습은 하지 않았다. 배포 전에 스냅숏 → 새 RDS 복원 → 앱 연결을 한 번 해 본다.

## 9. 배포 전 차단 요소와 결정할 것

차단 요소(코드·연동):
1. **SMS 공급자 미구현**: `SmsSender` 구현체가 없어 운영에서 `/auth/sms-*`가 503이다. 시연 경로(`inspection.demo.enabled`)는 운영에서 꺼 두는 설정이다. 공급자 선정과 구현, 또는 시연용 배포에서 시연 경로를 켤지 결정이 필요하다.
2. **S3 저장소는 구현했지만 실제 AWS에서 확인하지 않았다**(4절). 버킷·역할을 만든 뒤 사진 접수·조회를 한 번 확인한다. 그 전에는 EBS 파일 시스템으로 운영 가능.
3. **프록시 뒤 클라이언트 IP**: 인증번호 IP당 발송 제한이 `getRemoteAddr()`를 쓴다. 프록시 뒤에서 설정이 없으면 모든 요청이 WEB 서버 IP 하나로 보여 제한이 전원에게 걸린다. 견본은 `SERVER_FORWARD_HEADERS_STRATEGY=native`와 WEB 사설 IP 신뢰 범위를 넣었고 nginx는 `X-Forwarded-For`를 덮어쓴다. **실제 프록시 환경 검증은 하지 않았다.**
4. 초기 데이터: 기관·부서·관리자 계정·시설 대장을 만드는 업무 API가 없다. 투입 방법(SQL 스크립트 등)과 담당을 정한다.
5. 시니어 앱·관리자 웹을 한 호스트에 둘지: 두 앱 모두 Vite `base`가 `/`라 한 호스트의 다른 경로로는 못 올린다. 호스트 분리(nginx server 블록 둘) 또는 프론트 쪽 `base` 변경 중 하나가 필요하다. 견본 `web/nginx.conf`는 시니어 앱 한 개 기준이다.
6. **AI 서버 위치 미정**(3절). 정해지기 전에는 AI 서브넷·보안 그룹·VPN·GPU 인스턴스를 만들지 않는다.

결정할 것(정책·운영):
- 도메인과 인증서 발급 방식(WEB에서 Let's Encrypt / ALB+ACM / Cloudflare 사용 범위, `infra/ec2/README.md`)
- NAT 게이트웨이 vs VPC 엔드포인트 조합(SMS 경로에 따라)
- AI 서버 위치: 선택지 A(AWS GPU EC2) / B-1(외부 GPU + VPN) / B-2(외부 GPU + 허용 목록 HTTPS). 정할 입력: 판독 시간·왕복 지연 실측, 월 가동 시간, 비용, 장비 관리 담당
- 선택지에 따른 AI 연결 보호: B는 TLS 필수(인증서 발급·종단 위치), A는 내부 TLS 여부. WEB→WAS 내부 TLS 여부
- 시니어 앱·관리자 웹 호스트 구성(두 호스트 / 한 호스트 + 프론트 `base` 변경)
- RDS 인스턴스 등급·Multi-AZ·백업 보관 일수, 마이그레이션 계정 분리
- (선택지 A일 때) GPU 인스턴스 종류와 가동 시간(상시/활동 시간만). 어느 선택지든 판독 모델(로컬 GPU/외부 공급자)
- S3 전환 시점(`MEDIA_STORE=s3`): 버킷·역할을 만든 뒤 실제 AWS에서 사진 접수·조회 확인(4절)
- 사진 보관 기간·S3 버전 관리·수명 주기(기존 미정 항목)
- 로그 보관 위치(CloudWatch Logs 여부)와 기간
- WAS 인스턴스 크기, 이후 여러 대 운영 시점(공유 Redis·S3 선행)

## 10. 비용 산정 항목

금액은 AWS 요금 계산기와 서울 리전 요금표로 산정한다(요금은 바뀌므로 적지 않음).

| 항목 | 산정 단위 | 정해야 할 입력 |
|---|---|---|
| WEB EC2 | 인스턴스 시간 + EBS GB·월 | 인스턴스 종류(소형) |
| WAS EC2 | 인스턴스 시간 + EBS GB·월 | 메모리(JVM+Redis), 사진 임시 볼륨 크기 |
| (조건부: 선택지 A) GPU AI EC2 | 인스턴스 시간(가장 큰 항목) + EBS GB·월 + 스냅숏 | 종류(GPU 메모리 ≥ 모델 요구), 월 가동 시간, 서울 리전 가용성 |
| (조건부: 선택지 B-1) Site-to-Site VPN | 연결 시간 + 전송 GB | 연결 수, 사진 전송량 |
| (조건부: 선택지 B-2) AI 호출 전송 | NAT 처리 GB + 인터넷 전송 GB | 사진 수×(업로드+가린 사진 받기) 크기 |
| RDS | 인스턴스 시간(Multi-AZ면 약 2배) + 스토리지 GB·월 + 백업 초과분 | 등급, 스토리지, 보관 일수 |
| NAT 게이트웨이 | 시간 + 처리 GB | 개수, 외부 통신량(이미지 받기·SMS·갱신) |
| VPC 인터페이스 엔드포인트 | 엔드포인트×AZ 시간 + 처리 GB | 사용하는 엔드포인트 수(S3 게이트웨이는 무료) |
| 공인 IPv4 | 주소 시간 | WEB 탄력적 IP, NAT |
| S3 | 저장 GB·월 + 요청 수 + 이전 버전 | 사진 수×평균 크기×보관 기간 |
| 인터넷 전송(나가는 쪽) | GB | 관리자 사진 조회, 앱·마스킹 모델 파일 다운로드 |
| EBS 스냅숏 | GB·월 | 대상·보관 기간 |
| ECR | 저장 GB·월 | 보관할 이미지 태그 수 |
| CloudWatch Logs | 수집 GB + 저장 GB·월 | 사용 여부 |
| Route 53 | 호스팅 영역 + 질의 | 도메인 사용 방식 |
| AWS 밖 | 건당·월 | SMS 발송, 외부 판독 공급자(사용 시), 도메인 등록, (선택지 B) AI 서버 장비·회선 |

## 11. 관련 문서

[infra/ec2/README.md](../infra/ec2/README.md), [AI 실행 가이드](AI_실행_가이드.md).
