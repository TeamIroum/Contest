# AWS WEB·WAS·DB 구성

시니어 동네 점검단의 서울 리전(`ap-northeast-2`) 배포 구성이다. 이 저장소에는 재현 가능한 설정과 연결 관계만 둔다. 주소, 계정 번호, 비밀번호, 토큰, 서버에서 사용한 실제 환경 파일은 포함하지 않는다.

```text
이용자 → WEB (정적 프론트엔드·HTTPS)
             └─ /api → WEB Nginx → 사설 WAS:8080 → 사설 RDS PostgreSQL:5432
                                      ├─ 내부 Redis
                                      ├─ 비공개 S3 사진 버킷 (VPC 게이트웨이 엔드포인트)
                                      └─ NAT 경유 HTTPS → AWS 외부의 별도 GPU AI 서버
```

| 계층 | 구성 | 접근 범위 |
| --- | --- | --- |
| 네트워크 | 전용 VPC, WEB 공개 서브넷·WAS/DB 사설 서브넷(각 2개 AZ) | SSH를 인터넷에 공개하지 않음 |
| WEB | 정적 시니어·관리자 웹, HTTPS, Nginx 역방향 프록시 | 인터넷 TCP 80·443; API만 WAS로 전달 |
| WAS | EC2, Java 21 Spring Boot·Redis를 Docker Compose로 실행 | WEB 보안 그룹에서 TCP 8080만 허용 |
| DB | RDS PostgreSQL 16, 앱 전용 계정 | WAS 보안 그룹에서 TCP 5432만 허용 |
| 사진 | 비공개 S3, 퍼블릭 접근 차단·서버 측 암호화 | WAS 역할에 해당 버킷의 필요한 객체 권한만 부여 |
| 외부 통신 | NAT EC2 | WAS의 AI API 등 HTTPS 발신 |
| AI | AWS 밖의 별도 GPU 서버 | WAS의 인증된 요청만 허용; `AI_BASE_URL`·`AI_API_KEY`는 서버 비밀값 |

시연에는 정적 웹을 CloudFront로 제공한 구성도 사용했다. `web/nginx.conf`는 WEB EC2의 HTTPS·API 프록시 설정 견본이며, CloudFront 배포 설정이나 인증서를 담지 않는다. 실제 도메인·인증서·CloudFront 원본 설정은 배포 환경에서 별도로 지정한다.

## 저장소 파일

- `web/nginx.conf`: WEB EC2에서 사용할 Nginx 설정 견본. `<도메인>`, `<was-private-ip>`, `<VPC_CIDR>`를 배포 시 치환한다.
- `was/Dockerfile`, `was/compose.yaml`, `was/deploy.sh`: 빌드된 백엔드 JAR를 WAS에서 컨테이너로 실행하는 설정.
- `was/.env.example`: 필요한 환경 변수 이름만 보여 준다. 실제 값은 서버의 권한 제한된 `/opt/itda/secrets/app.env`에 둔다.
- `ai/README.md`: 외부 GPU AI 서버와 WAS의 연결 조건.

## 처음부터 구축하는 순서

1. 서울 리전에 VPC와 WEB 공개·WAS 사설·DB 사설 서브넷을 만든다. 보안 그룹은 위 표의 경로만 허용한다.
2. WEB·WAS 인스턴스에 SSM 관리 역할을 붙인다. WAS 역할에는 프로젝트 전용 S3 버킷 권한만 추가한다. RDS와 S3는 공개 접근을 차단한다.
3. RDS에 앱 전용 계정을 만들고 비밀번호는 서버의 비밀값 파일에 주입한다. 이 파일은 Git에 추가하지 않는다.
4. 백엔드 JAR를 빌드해 WAS로 전달한다. `was/` 파일을 `/opt/itda/current/`에 두고 `.env.example`의 키를 실제 비밀값 파일에 채운 뒤 `deploy.sh <JAR 경로>`를 실행한다.
5. WEB의 도메인·TLS 인증서와 WAS 사설 주소를 지정하고 Nginx 설정을 검사한 후 정적 프론트엔드를 배포한다.
6. 외부 GPU AI 서버의 HTTPS 주소·키, SMS 공급자 설정을 연결한다. `/actuator/health`와 로그인·사진 접수·검토 흐름을 실제 브라우저 및 기기에서 확인한다.

## 공개 저장소 주의

실제 `.env`, PEM 키, AWS 액세스 키, DB 주소·암호, 계정 번호, 공인·사설 IP, 사진 원본, 사용자 개인정보는 커밋하지 않는다. `CHANGE_ME`는 배포 전에 서버 내부에서만 실제 값으로 교체한다. 이 파일은 배포 절차를 설명하며 현재 AWS 자원의 가동 여부를 증명하지 않는다.
