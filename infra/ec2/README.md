# EC2 배포

배포 순서는 로컬 Docker → EC2 첫 배포 → GitHub Actions 자동화입니다(서비스 설계서). 이 폴더에는 EC2 첫 배포에 쓰는 설정과 절차를 둡니다.

## 정할 것

- EC2 사양과 리전, 백엔드·AI를 한 대에 둘지 나눌지
- 사진 저장소(S3 버킷), DB를 EC2 안 컨테이너로 둘지 RDS로 둘지
- 도메인·HTTPS(서비스 설계서의 Cloudflare 사용 범위)
- 비밀 값 보관 방식 (GitHub Actions Secrets, EC2 환경 변수 등). 키를 저장소에 올리지 않습니다

## AI 판독 모듈 운영 설정

- `API_KEYS` 설정, `REQUIRE_DEVICE_MASKING=1`
- 작업자 1개 (`--workers 1`). 재판독 대기열과 변경 알림이 프로세스 안에 있기 때문입니다
- AI 포트(8000)는 외부에 열지 않고 백엔드만 접근하게 합니다. 앱은 AI를 직접 부르지 않습니다
- `data/images`, `data/images_revisions`, `data/results`는 볼륨으로 보존하고 보관 기간을 정합니다
