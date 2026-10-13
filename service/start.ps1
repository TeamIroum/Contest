# 이 PC 한 대로 서비스를 띄운다: AI 판독(:8000) → 업무 백엔드(:8080) → 두 앱 빌드 → Caddy(:8180·:8181) → Tailscale Funnel(HTTPS 공개 주소)
#
#   powershell -ExecutionPolicy Bypass -File service\start.ps1            # 이미 떠 있는 서버는 그대로 쓴다
#   powershell -ExecutionPolicy Bypass -File service\start.ps1 -Build     # 백엔드·두 앱을 다시 빌드
#   powershell -ExecutionPolicy Bypass -File service\stop.ps1             # 끄기
#
# 값은 service\.env(.env.example 참고)에서 읽어 이 스크립트가 띄우는 창에만 환경 변수로 넘긴다.
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$Service = $PSScriptRoot
$Root = Split-Path -Parent $Service
$RunDir = Join-Path $env:TEMP 'iroum-service'
$StateFile = Join-Path $RunDir 'pids.txt'
New-Item -ItemType Directory -Force $RunDir | Out-Null

function Test-Port([int]$Port) {
    try { [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop) } catch { $false }
}
function Wait-Port([int]$Port, [int]$Seconds, [string]$Name) {
    for ($i = 0; $i -lt $Seconds; $i++) { if (Test-Port $Port) { return }; Start-Sleep -Seconds 1 }
    throw "$Name(:$Port)이 ${Seconds}초 안에 뜨지 않았습니다. 해당 창이나 $RunDir 의 로그를 확인하세요."
}
function Read-EnvFile([string]$Path) {
    $vars = [ordered]@{}
    foreach ($line in Get-Content $Path -Encoding UTF8) {
        if ($line -match '^\s*(?:export\s+)?([A-Z0-9_]+)\s*=(.*)$') {
            $k = $Matches[1]; $v = $Matches[2].Trim()
            # '값' 또는 "값"이면 따옴표만 벗기고(값 안의 #도 그대로), 아니면 뒤 주석을 지운다
            if ($v -match "^'(.*)'$" -or $v -match '^"(.*)"$') { $v = $Matches[1] } else { $v = ($v -replace '\s+#.*$', '').Trim() }
            $vars[$k] = $v
        }
    }
    $vars
}
function Start-Window([string]$Title, [string]$Dir, [string]$Command) {
    $script = "`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$Dir'; $Command"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    $p = Start-Process powershell -ArgumentList '-NoExit', '-EncodedCommand', $encoded -PassThru
    Add-Content -Path $StateFile -Value $p.Id
    Write-Host "  $Title (PID $($p.Id))"
}

# --- 값 읽기 ---
$envFile = Join-Path $Service '.env'
if (-not (Test-Path $envFile)) { throw 'service\.env가 없습니다. service\.env.example을 복사해 값을 채우세요.' }
$cfg = Read-EnvFile $envFile
foreach ($k in 'DB_PASSWORD', 'JWT_SECRET', 'PHONE_ENC_KEY', 'PHONE_HMAC_KEY', 'DEMO_OTP_CODE', 'DEMO_ADMIN_TEMP_PASSWORD') {
    if (-not $cfg[$k]) { throw "service\.env의 $k 값이 비어 있습니다." }
}
if (-not $cfg['AI_API_KEY']) {
    $line = (Select-String -Path (Join-Path $Root 'ai\.env') -Pattern '^API_KEYS=' | Select-Object -First 1).Line
    if ($line) { $cfg['AI_API_KEY'] = ($line -replace '^API_KEYS=', '' -replace '\s*#.*$', '').Split(',')[0].Trim() }
}
if (-not $cfg['AI_BASE_URL']) { $cfg['AI_BASE_URL'] = 'http://localhost:8000' }
if (-not $cfg['MEDIA_STORAGE_ROOT']) { $cfg['MEDIA_STORAGE_ROOT'] = Join-Path $env:LOCALAPPDATA 'inspection-media' }

Write-Host '창을 엽니다:'

# --- 0. WSL(PostgreSQL·Redis)을 켜 두기. 열린 WSL 창이 없으면 WSL이 곧 꺼져 DB·Redis가 끊긴다
$wslAlive = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" | Where-Object { $_.CommandLine -like '*Ubuntu-24.04*sleep infinity*' }
if ($wslAlive) { Write-Host '  0 WSL 유지 (이미 떠 있음)' } else {
    $k = Start-Process wsl -ArgumentList '-d', 'Ubuntu-24.04', '--', 'sleep', 'infinity' -WindowStyle Hidden -PassThru
    Add-Content -Path $StateFile -Value $k.Id
    Write-Host "  0 WSL 유지 (PID $($k.Id), 숨김)"
}
foreach ($port in 5432, 6379) { Wait-Port $port 60 "WSL 안의 $(if ($port -eq 5432) { 'PostgreSQL' } else { 'Redis' })" }

# --- 1. AI 판독 (Ollama + :8000) ---
try { Invoke-WebRequest 'http://127.0.0.1:11434/api/version' -UseBasicParsing -TimeoutSec 2 | Out-Null } catch {
    $ollamaApp = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
    if (-not (Test-Path $ollamaApp)) { throw 'Ollama가 꺼져 있습니다. 시작 메뉴에서 Ollama를 실행하세요.' }
    Start-Process $ollamaApp; Start-Sleep -Seconds 5
}
if (Test-Port 8000) { Write-Host '  1 AI 판독 :8000 (이미 떠 있음)' } else {
    Start-Window '1 AI 판독 :8000' "$Root\ai" 'python -m uvicorn server:app --port 8000 --host 127.0.0.1 --workers 1 --env-file .env'
}

# --- 2. 업무 백엔드 :8080 (serviceackend, local 프로필) ---
if (Test-Port 8080) { Write-Host '  2 업무 백엔드 :8080 (이미 떠 있음. service\.env 값으로 다시 띄우려면 stop 뒤 실행)' } else {
    # JAVA_HOME이 없으면 Gradle이 받아 둔 JDK 21을 쓴다
    $java = (Get-ChildItem "$env:USERPROFILE\.gradle\jdks\*21*\bin\java.exe" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    if (-not $env:JAVA_HOME -and $java) { $env:JAVA_HOME = Split-Path (Split-Path $java) }
    if (-not $java) { $java = 'java' }
    $jar = Get-ChildItem "$Service\backend\build\libs\*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if ($Build -or -not $jar) {
        Write-Host '  백엔드 실행 파일을 만듭니다 (gradlew bootJar)...'
        Push-Location "$Service\backend"; try { & .\gradlew.bat bootJar -q; if ($LASTEXITCODE) { throw 'bootJar 실패' } } finally { Pop-Location }
        $jar = Get-ChildItem "$Service\backend\build\libs\*.jar" | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    }
    # 이 창에만 값을 넘긴다. Caddy·Funnel 뒤 실제 접속 주소를 X-Forwarded-For로 받는다(127.0.0.1만 믿음, Tomcat 기본값)
    foreach ($k in $cfg.Keys) { Set-Item "env:$k" $cfg[$k] }
    $env:SERVER_FORWARD_HEADERS_STRATEGY = 'native'
    $env:TZ = 'Asia/Seoul'
    Start-Window '2 업무 백엔드 :8080' "$Service\backend" "& '$java' '-Duser.timezone=Asia/Seoul' -jar '$($jar.FullName)' --spring.profiles.active=local --server.address=127.0.0.1 2>&1 | Tee-Object -FilePath '$RunDir\backend.log'"
    foreach ($k in @($cfg.Keys) + 'SERVER_FORWARD_HEADERS_STRATEGY') { Remove-Item "env:$k" -ErrorAction SilentlyContinue }
    Wait-Port 8080 120 '업무 백엔드'
}

# --- 3. 두 앱 빌드 (실제 백엔드, 같은 출처 /api) ---
foreach ($app in 'senior', 'admin') {
    $dir = Join-Path $Service $app
    if ($Build -or -not (Test-Path "$dir\dist\index.html")) {
        Write-Host "  $app 빌드..."
        Push-Location $dir
        try {
            if (-not (Test-Path node_modules)) { npm ci --no-audit --no-fund | Out-Null }
            # 시니어 앱 사진 가리기 모델(번호판·차량)은 Git에 없으므로 처음 한 번 받는다
            if ($app -eq 'senior' -and -not (Test-Path 'public\models\plate-t384@1.onnx')) { npm run models | Out-Null; if ($LASTEXITCODE) { throw '가림 모델 받기 실패 (npm run models)' } }
            $env:VITE_BACKEND = 'real'; $env:VITE_API_ORIGIN = ''; $env:VITE_API_PROXY = ''
            npm run build | Out-Null
            if ($LASTEXITCODE) { throw "$app 빌드 실패 (npm run build)" }
        } finally { Remove-Item env:VITE_BACKEND, env:VITE_API_ORIGIN, env:VITE_API_PROXY -ErrorAction SilentlyContinue; Pop-Location }
    }
}

# --- 4. Caddy :8180(시니어 앱) :8181(관리자 웹) ---
if ((Test-Port 8180) -or (Test-Port 8181)) { Write-Host '  3 Caddy :8180·:8181 (이미 떠 있음)' } else {
    Start-Window '3 Caddy :8180 :8181' "$Service\web" '.\bin\caddy.exe run --config Caddyfile'
    Wait-Port 8181 20 'Caddy'
}

# --- 5. Tailscale Funnel (공개 HTTPS. 기존 :8443은 건드리지 않음) ---
tailscale funnel --bg --https=443 http://127.0.0.1:8180 | Out-Null
tailscale funnel --bg --https=10000 http://127.0.0.1:8181 | Out-Null
$host_ = ((tailscale status --json | ConvertFrom-Json).Self.DNSName).TrimEnd('.')

Write-Host ''
Write-Host "시니어 앱 : https://$host_"
Write-Host "관리자 웹 : https://${host_}:10000"
Write-Host '끄기      : powershell -ExecutionPolicy Bypass -File service\stop.ps1'
