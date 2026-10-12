# 휴대폰 시험용 서버를 한 번에 띄운다 (docs/AI_실행_가이드.md 8.2절의 창 1~5).
#
#   powershell -ExecutionPolicy Bypass -File tools\start-test.ps1            # PWA + Expo
#   powershell -ExecutionPolicy Bypass -File tools\start-test.ps1 -PwaOnly   # PWA만 (창 1~3)
#   powershell -ExecutionPolicy Bypass -File tools\stop-test.ps1             # 모두 끄기
#
# 창마다 PowerShell이 하나씩 열린다. API 키는 ai/.env에서 읽어 창 2에 환경 변수로만 넘긴다.
param([switch]$PwaOnly)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $env:TEMP 'senior-test'
$StateFile = Join-Path $RunDir 'pids.txt'

function Test-Port([int]$Port) {
    try { [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop) } catch { $false }
}

# 창 안을 클릭하면 '선택' 모드가 되어 출력이 멈추고 서버(특히 Expo)까지 멈춘다. 새 창에서는 그 모드를 끈다.
$NoQuickEdit = @'
Add-Type -Name Con -Namespace NoQuickEdit -MemberDefinition '[DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int h); [DllImport("kernel32.dll")] public static extern bool GetConsoleMode(IntPtr h, out uint m); [DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr h, uint m);'
$h = [NoQuickEdit.Con]::GetStdHandle(-10); $m = 0
if ([NoQuickEdit.Con]::GetConsoleMode($h, [ref]$m)) { [void][NoQuickEdit.Con]::SetConsoleMode($h, ($m -band -bnot 0x40) -bor 0x80) }
'@

function Start-Window([string]$Title, [string]$Dir, [string]$Command) {
    $script = "$NoQuickEdit`n`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$Dir'; $Command"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    $p = Start-Process powershell -ArgumentList '-NoExit', '-EncodedCommand', $encoded -PassThru
    Add-Content -Path $StateFile -Value $p.Id
    Write-Host "  $Title (PID $($p.Id))"
}

function Wait-TunnelUrl([string]$LogFile) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Path $LogFile) {
            $m = Select-String -Path $LogFile -Pattern 'https://(?!api\.)[a-z0-9-]+\.trycloudflare\.com' |
                Select-Object -First 1
            if ($m) { return $m.Matches[0].Value }
        }
        Start-Sleep -Seconds 1
    }
    throw "터널 주소를 60초 안에 받지 못했습니다. $LogFile 를 확인하세요."
}

# --- 시작 전 점검 ---
# AI 서버·검증 콘솔이 이미 떠 있으면 그대로 쓴다 (PWA로 띄운 뒤 Expo를 더 띄울 때)
$aiRunning = Test-Port 8000
$consoleRunning = Test-Port 8100
if (-not $PwaOnly -and (Test-Port 8081)) {
    throw '포트 8081(Expo)을 이미 쓰고 있습니다. tools\stop-test.bat으로 끄고 다시 실행하세요.'
}

$envLine = (Select-String -Path (Join-Path $Root 'ai\.env') -Pattern '^API_KEYS=' | Select-Object -First 1).Line
$apiKey = if ($envLine) { ($envLine -replace '^API_KEYS=', '' -replace '\s*#.*$', '').Split(',')[0].Trim() } else { '' }

try {
    Invoke-WebRequest 'http://127.0.0.1:11434/api/version' -UseBasicParsing -TimeoutSec 2 | Out-Null
} catch {
    $ollamaApp = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
    if (-not (Test-Path $ollamaApp)) { throw 'Ollama가 꺼져 있습니다. 시작 메뉴에서 Ollama를 실행하세요.' }
    Write-Host 'Ollama를 켭니다...'
    Start-Process $ollamaApp
    Start-Sleep -Seconds 5
}

New-Item -ItemType Directory -Force $RunDir | Out-Null
Remove-Item "$RunDir\*.log" -Force -ErrorAction SilentlyContinue   # pids.txt는 남겨 stop-test가 앞서 띄운 창도 끄게 한다

# --- 창 1~4 ---
Write-Host '창을 엽니다:'
if ($aiRunning) {
    Write-Host '  1 AI 판독 서버 :8000 (이미 떠 있음)'
} else {
    Start-Window '1 AI 판독 서버 :8000' "$Root\ai" 'python -m uvicorn server:app --port 8000 --env-file .env'
}

if ($consoleRunning) {
    Write-Host '  2 검증 콘솔 :8100 (이미 떠 있음)'
} else {
    if ($apiKey) { $env:AI_API_KEY = $apiKey }   # 새 창이 물려받는다. 명령줄에 키를 남기지 않는다. 비었으면 인증 없는 개발 모드
    Start-Window '2 검증 콘솔 :8100' "$Root\tools\ai-verify\backend" 'python -m uvicorn app:app --port 8100'
    Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
}

$Tailscale = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
$tsExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (-not $Tailscale -and (Test-Path $tsExe)) { $Tailscale = $tsExe }  # 설치 전에 연 창은 PATH에 없음
if ($Tailscale) {
    # Tailscale Funnel: 주소가 고정된다 (Funnel이 여는 포트는 443·8443·10000뿐).
    # 앱 코드는 443: Expo QR의 exp:// 주소는 443일 때만 https로 접속하고, 다른 포트면 http로 접속해 끊긴다.
    # 서버(PWA·업무 API)는 8443. 브라우저와 앱은 포트가 붙어도 https로 잘 접속한다.
    # 윈도우 Tailscale은 작업 표시줄 앱(tailscale-ipn)이 붙어야 로그인 정보를 불러온다.
    # 앱이 꺼져 있으면 서비스가 'Tailscale is starting'(NoState)에 머물러 로그인 안 된 것처럼 보인다
    $tsState = (& $Tailscale status --json | ConvertFrom-Json).BackendState
    $tsIpn = Join-Path (Split-Path $Tailscale) 'tailscale-ipn.exe'
    if ($tsState -ne 'Running' -and -not (Get-Process tailscale-ipn -ErrorAction SilentlyContinue) -and (Test-Path $tsIpn)) {
        Write-Host 'Tailscale 작업 표시줄 앱을 켭니다'
        Start-Process $tsIpn
    }
    for ($i = 0; $i -lt 30 -and $tsState -ne 'Running'; $i++) {
        Start-Sleep 1
        $tsState = (& $Tailscale status --json | ConvertFrom-Json).BackendState
    }
    $tsHost = (& $Tailscale status --json | ConvertFrom-Json).Self.DNSName.TrimEnd('.')
    if ($tsState -ne 'Running' -or -not $tsHost) { throw "Tailscale에 연결되지 않았습니다 (상태: $tsState). 작업 표시줄의 Tailscale에서 로그인하세요." }
    Write-Host "Tailscale Funnel ($tsHost)"
    & $Tailscale funnel --bg --https=8443 http://127.0.0.1:8100 | Out-Null
    $serverUrl = "https://${tsHost}:8443"
    if (-not $PwaOnly) {
        & $Tailscale funnel --bg --https=443 http://localhost:8081 | Out-Null
        $appUrl = "https://$tsHost"
    }
} else {
    # cloudflared 빠른 터널: 실행할 때마다 주소가 바뀐다
    $serverLog = Join-Path $RunDir 'tunnel-8100.log'
    Start-Window '3 서버 터널' $Root "cloudflared tunnel --logfile '$serverLog' --url http://127.0.0.1:8100"
    if (-not $PwaOnly) {
        $appLog = Join-Path $RunDir 'tunnel-8081.log'
        Start-Window '4 앱 코드 터널' $Root "cloudflared tunnel --logfile '$appLog' --url http://localhost:8081"
    }
    Write-Host '터널 주소를 기다립니다...'
    $serverUrl = Wait-TunnelUrl $serverLog
    if (-not $PwaOnly) { $appUrl = Wait-TunnelUrl $appLog }
}

# --- 창 5 ---
if (-not $PwaOnly) {
    $appDir = Join-Path $Root 'tools\ai-test-app'
    [IO.File]::WriteAllText((Join-Path $appDir '.env.local'), "EXPO_PUBLIC_SERVER_URL=$serverUrl`n")
    $env:EXPO_PACKAGER_PROXY_URL = $appUrl
    Start-Window '5 Expo 개발 서버 :8081' $appDir 'npx expo start --port 8081'
    Remove-Item Env:EXPO_PACKAGER_PROXY_URL
}

# --- AI 모델 준비 기다리기 ---
Write-Host 'AI 모델을 GPU에 올리는 중입니다 (처음엔 1분쯤)...'
$state = '?'
for ($i = 0; $i -lt 180; $i++) {
    try {
        $state = (Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 2).warmup.state
        if ($state -in 'ready', 'failed', 'skipped') { break }
    } catch { }
    Start-Sleep -Seconds 2
}

Write-Host ''
Write-Host "모델 준비: $state" -ForegroundColor $(if ($state -eq 'ready') { 'Green' } else { 'Yellow' })
Write-Host "검증 콘솔: http://localhost:8100   (판독 결과: http://localhost:8100/#runs)"
Write-Host "PWA:       $serverUrl/pwa/" -ForegroundColor Cyan
if (-not $PwaOnly) {
    Write-Host "Expo Go:   exp://$($appUrl -replace '^https://', '')   (창 5의 QR로도 열림)" -ForegroundColor Cyan
}
Write-Host '터널 주소는 인터넷 어디서나 열립니다. 시험하는 사람에게만 알리세요.'
Write-Host '끄기: powershell -ExecutionPolicy Bypass -File tools\stop-test.ps1'
