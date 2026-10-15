# AI 판독 서버 배치 메모

AI 코드·설정은 `ai/` 담당자가 관리한다. 여기에는 업무 백엔드 쪽에서 필요한 배치 조건만 적는다(실행 방법은 `ai/README.md`, `docs/AI_실행_가이드.md`).

**AI 판독 서버는 AWS WAS와 분리된 외부 GPU 서버로 둔다.** 서버 주소와 인증키는 저장소에 쓰지 않고 WAS 비밀값 파일의 `AI_BASE_URL`, `AI_API_KEY`로 주입한다. 네트워크 연결 방식(VPN 또는 허용 목록 HTTPS)은 실제 AI 서버 주소가 정해지면 검증한다.

## 어느 위치든 같은 조건

- 들어오는 연결은 WAS에서 오는 AI API 요청만 허용. 앱·관리자 웹·인터넷에서 직접 닿지 않게 한다
- `API_KEYS` 설정(WAS의 `AI_API_KEY`와 맞춤), `REQUIRE_DEVICE_MASKING=1`, 작업자 1개(`--workers 1`)
- 업무 서버 기한(연결 5초, 요청 30초)은 바꾸지 않는다. 판독 시간 + 왕복 지연이 30초 안인지 그 위치에서 잰다
- `data/images`, `data/images_revisions`, `data/results`, 이벤트 로그는 보존 볼륨에 두고 백업한다. 이벤트 로그가 초기화되면 업무 서버 커서 세대(G4) 복구가 필요하다
- 모델 파일(Ollama 모델, 얼굴·번호판 모델)은 미리 넣어 운영 중 외부 다운로드가 없게 한다
- 실제 검증은 로컬 RTX 2080 8GB + qwen3-vl:8b-instruct에서만 했다. AWS GPU 인스턴스·외부 GPU 서버 성능은 측정하지 않았다

## AWS 밖 GPU 서버(외부·자체 보유)

- B-1 Site-to-Site VPN: WAS가 사설 주소로 호출. AWS 쪽 가상 사설 게이트웨이·라우팅, AI 쪽 VPN 장비 필요. 터널 안에서도 TLS 권장
- B-2 허용 목록 HTTPS: WAS는 NAT 게이트웨이로 나가고 AI 쪽 방화벽은 NAT 탄력적 IP만 허용. `AI_BASE_URL=https://<AI 주소>`, 신뢰할 수 있는 인증서 필수(AI 앞 nginx 등에서 종단)
- 사진(가린 사진 포함)이 AWS 밖으로 오가므로 전송 보호·보관 위치를 사진 보관 정책과 함께 확인한다
- 장비·회선 장애 연락 체계와 백업은 AI 담당이 정한다
