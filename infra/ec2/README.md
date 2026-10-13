# EC2 배포

배포 순서는 로컬 Docker → EC2 첫 배포 → GitHub Actions 자동화입니다(서비스 설계서). 이 폴더에는 EC2 첫 배포에 쓰는 설정과 절차를 둡니다.

## 정할 것

- EC2 사양과 리전. 백엔드·AI는 다른 서버로 나누기로 했고, AI 서버를 AWS에 둘지(GPU EC2) AWS 밖에 둘지는 미정
- 사진 저장소: 백엔드는 파일 시스템·S3 둘 다 지원(`MEDIA_STORE`). 실제 버킷·역할은 미생성. DB는 RDS 제안(AWS 배포 준비 5절)
- 프론트: 시니어 앱·관리자 웹은 React + Vite, `npm run build` → `dist/`. 두 앱을 호스트로 나눌지 정한다
- 도메인·HTTPS(서비스 설계서의 Cloudflare 사용 범위)
- 비밀 값 보관 방식 (GitHub Actions Secrets, EC2 환경 변수 등). 키를 저장소에 올리지 않습니다

## 시니어 PWA 배포 설정

- HTTPS 필수 (카메라·서비스 워커가 HTTPS에서만 동작)
- 마스킹 모델 파일(`/models/`)은 버전이 붙은 이름으로 두고 오래 캐시. 앱 화면 파일과 서비스 워커는 짧게 캐시
- 브라우저 마스킹 속도를 위해 WASM 여러 스레드를 쓰기로 하면 COOP/COEP 헤더 추가 (실기기 측정 후 결정)

## AI 판독 모듈 운영 설정

- `API_KEYS` 설정, `REQUIRE_DEVICE_MASKING=1`
- 작업자 1개 (`--workers 1`). 재판독 대기열과 변경 알림이 프로세스 안에 있기 때문입니다
- AI 포트(8000)는 외부에 열지 않고 백엔드만 접근하게 합니다. 앱은 AI를 직접 부르지 않습니다
- `data/images`, `data/images_revisions`, `data/results`는 볼륨으로 보존하고 보관 기간을 정합니다

## AWS 배포 설계

WEB(공개)·WAS(사설)·RDS(사설) 3계층과 S3 사진 저장소, WAS와 분리한 AI 판독 서버(AWS GPU EC2 또는 AWS 밖 GPU 서버, 위치 미정)의 서브넷·보안 그룹·비밀 값·배포·롤백·백업·비용 항목은 [AWS 배포 준비](../../docs/AWS_배포_준비.md), 설정 견본은 [infra/aws](../aws/)에 있다(AWS 자원은 만들지 않음).
