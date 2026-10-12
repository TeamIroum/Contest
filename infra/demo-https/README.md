# 시연용 HTTPS (Caddy)

시니어 앱의 카메라는 `https` 주소에서만 켜집니다. 공인 IP가 있는 PC 한 대에서 두 앱을 도메인과 HTTPS로 열기 위한 설정입니다. 인증서는 Caddy가 Let's Encrypt에서 자동으로 받고 갱신합니다.

| 주소 | 연결 |
|---|---|
| `https://동네-점검단.메인.한국` (www 포함) | 시니어 앱 → `127.0.0.1:4173` |
| `https://관리자-점검단.메인.한국` (www 포함) | 관리자 웹 → `127.0.0.1:4174` |

`Caddyfile`의 도메인은 한글 도메인을 퓨니코드로 적은 것입니다.

## 준비 (처음 한 번)

1. **DNS:** 도메인 관리 화면에서 웹포워딩을 끄고, `@`(비움)와 `www`에 A 레코드로 PC의 공인 IP를 넣습니다.
2. **방화벽 (관리자 PowerShell):**
   ```powershell
   New-NetFirewallRule -DisplayName "Caddy HTTP (80)" -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow -Profile Any
   New-NetFirewallRule -DisplayName "Caddy HTTPS (443)" -Direction Inbound -Protocol TCP -LocalPort 443 -Action Allow -Profile Any
   ```
3. **Caddy:** [공식 배포본](https://github.com/caddyserver/caddy/releases)에서 `caddy_2.10.0_windows_amd64.zip`을 받아 `caddy.exe`를 이 폴더에 둡니다. 실행 파일은 Git에 넣지 않습니다.

## 실행

```powershell
# 1) 두 앱을 빌드하고 이 PC 안에서만 열기 (각각 다른 창)
cd frontend/senior; npm run build; npx vite preview --port 4173 --strictPort --host 127.0.0.1
cd frontend/admin;  npm run build; npx vite preview --port 4174 --strictPort --host 127.0.0.1

# 2) HTTPS 앞단 (이 폴더에서)
.\caddy.exe run --config Caddyfile
```

처음 실행하면 인증서를 받느라 1~2분 걸립니다. 미리보기 서버는 `127.0.0.1`에만 열어 두고, 밖에서는 Caddy를 거친 `https` 주소로만 들어오게 합니다.

## 주의

- PC가 켜져 있고 위 세 프로그램이 실행 중이어야 열립니다. 80·443 포트가 인터넷에 열리므로 시연·시험 기간에만 켭니다.
- 끝나면 Caddy를 끄고 방화벽 규칙을 지웁니다: `Remove-NetFirewallRule -DisplayName "Caddy HTTP (80)", "Caddy HTTPS (443)"`
- 실제 운영 배포(EC2 등)는 [infra/ec2](../ec2/README.md)에서 정합니다. 이 폴더는 시연용입니다.
