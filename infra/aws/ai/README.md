# AI 판독 서버 배치 메모

AI 코드·설정은 `ai/` 담당자가 관리한다. 여기에는 업무 백엔드 쪽에서 필요한 배치 조건만 적는다(실행 방법은 `ai/README.md`, `docs/AI_실행_가이드.md`).

**WAS와 다른 서버로 두는 것은 확정이다. AWS 안에 둘지는 정하지 않았다.** 선택지와 비교는 [AWS 배포 준비](../../../docs/AWS_배포_준비.md) 3절 "AI 서버 배치 선택지"에 있다. 위치가 정해지기 전에는 AI용 서브넷·보안 그룹·VPN·GPU 인스턴스를 만들지 않는다.

## 어느 위치든 같은 조건

- 들어오는 연결은 WAS에서 오는 AI API 요청만 허용. 앱·관리자 웹·인터넷에서 직접 닿지 않게 한다
- `API_KEYS` 설정(WAS의 `AI_API_KEY`와 맞춤), `REQUIRE_DEVICE_MASKING=1`, 작업자 1개(`--workers 1`)
- 업무 서버 기한(연결 5초, 요청 30초)은 바꾸지 않는다. 판독 시간 + 왕복 지연이 30초 안인지 그 위치에서 잰다
- `data/images`, `data/images_revisions`, `data/results`, 이벤트 로그는 보존 볼륨에 두고 백업한다. 이벤트 로그가 초기화되면 업무 서버 커서 세대(G4) 복구가 필요하다
- 모델 파일(Ollama 모델, 얼굴·번호판 모델)은 미리 넣어 운영 중 외부 다운로드가 없게 한다
- 실제 검증은 로컬 RTX 2080 8GB + qwen3-vl:8b-instruct에서만 했다. AWS GPU 인스턴스·외부 GPU 서버 성능은 측정하지 않았다

## 선택지 A: AWS GPU EC2

- 사설 AI 서브넷(ai-a), 공인 IP 없음. sg-ai는 sg-was의 8000/tcp만 허용
- `AI_BASE_URL=http://<AI 사설 IP>:8000`. 내부 TLS는 선택
- 모델·데이터는 AMI·EBS, EBS 스냅숏으로 백업. 접속은 SSM

## 선택지 B: AWS 밖 GPU 서버(외부·자체 보유)

- B-1 Site-to-Site VPN: WAS가 사설 주소로 호출. AWS 쪽 가상 사설 게이트웨이·라우팅, AI 쪽 VPN 장비 필요. 터널 안에서도 TLS 권장
- B-2 허용 목록 HTTPS: WAS는 NAT 게이트웨이로 나가고 AI 쪽 방화벽은 NAT 탄력적 IP만 허용. `AI_BASE_URL=https://<AI 주소>`, 신뢰할 수 있는 인증서 필수(AI 앞 nginx 등에서 종단)
- 사진(가린 사진 포함)이 AWS 밖으로 오가므로 전송 보호·보관 위치를 사진 보관 정책과 함께 확인한다
- 장비·회선 장애 연락 체계와 백업은 AI 담당이 정한다
