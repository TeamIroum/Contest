# 시연 데이터를 처음 상태로 되돌린다 (10/15 순회, 출석 전, 사진·검토·사건 0건, 관리자 비밀번호 준비).
# 공개 주소(Funnel)와 Caddy는 그대로 두고 업무 백엔드만 다시 켠다. 초기화 중 1~2분은 화면이 열려도 로그인·조회가 안 된다.
#
#   powershell -ExecutionPolicy Bypass -File service\reset-demo.ps1      (또는 service\reset-demo.bat 더블클릭)
#
# 하는 일
#   1. AI 동기화 위치(ai_event_cursors)를 읽어 둔다. 지우면 0이 되어 이미 처리한 AI 알림을 처음부터 다시 읽게 된다
#   2. 업무 백엔드만 끈다
#   3. DB(public 스키마)·Redis(로그인 세션)·사진 저장소를 비운다
#   4. WSL 안에서 AI 동기화 위치 복원을 기다리게 걸어 두고, start.ps1로 백엔드를 다시 켠다 (시작할 때 시연 데이터를 새로 넣음)
#   5. 시연 관리자 3개 계정의 첫 로그인 비밀번호 변경을 마쳐 둔다 (ADMIN_PASSWORD)
#   6. 결과를 확인해 보여 준다
$ErrorActionPreference = 'Stop'
$Service = $PSScriptRoot
$Root = Split-Path -Parent $Service
$Distro = 'Ubuntu-24.04'

function Read-EnvFile([string]$Path) {
    $vars = [ordered]@{}
    foreach ($line in Get-Content $Path -Encoding UTF8) {
        if ($line -match '^\s*(?:export\s+)?([A-Z0-9_]+)\s*=(.*)$') {
            $k = $Matches[1]; $v = $Matches[2].Trim()
            if ($v -match "^'(.*)'$" -or $v -match '^"(.*)"$') { $v = $Matches[1] } else { $v = ($v -replace '\s+#.*$', '').Trim() }
            $vars[$k] = $v
        }
    }
    $vars
}
function Test-Port([int]$Port) {
    try { [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop) } catch { $false }
}
# WSL 안 psql. 비밀번호는 WSLENV로만 넘겨 명령줄에 남기지 않는다
function Invoke-Psql([string]$Sql) {
    # NOTICE(예: drop cascades) 같은 안내는 끄고, stderr를 PowerShell 오류로 바꾸지 않는다(Windows PowerShell 5.1)
    $ErrorActionPreference = 'Continue'
    $out = & wsl -d $Distro -- psql -h localhost -U inspection -d inspection -At -q -v ON_ERROR_STOP=1 -c "SET client_min_messages TO warning; $Sql" 2>&1
    if ($LASTEXITCODE) { throw "DB 명령 실패: $out" }
    (($out | ForEach-Object { "$_" }) | Where-Object { $_ -and $_ -notmatch '^(NOTICE|DETAIL|SET$)' }) -join "`n"
}

$envFile = Join-Path $Service '.env'
if (-not (Test-Path $envFile)) { throw 'service\.env가 없습니다.' }
$cfg = Read-EnvFile $envFile
foreach ($k in 'DB_PASSWORD', 'DEMO_ADMIN_TEMP_PASSWORD', 'ADMIN_PASSWORD') { if (-not $cfg[$k]) { throw "service\.env의 $k 값이 비어 있습니다." } }
$env:PGPASSWORD = $cfg['DB_PASSWORD']
$env:WSLENV = 'PGPASSWORD/u:CUR/u'

Write-Host '시연 데이터를 초기화합니다.'

# --- 1. AI 동기화 위치 ---
if (-not (Test-Port 5432)) { throw 'PostgreSQL(:5432)에 닿지 않습니다. 먼저 service\start.ps1로 서비스를 켜 주세요.' }
# 읽은 값은 파일에도 남긴다. 앞선 초기화가 DB를 비운 뒤 멈췄다면 DB에는 값이 없으므로 파일 값을 쓴다 (둘 중 큰 값)
$curFile = Join-Path $env:LOCALAPPDATA 'iroum-service\ai-cursor.txt'
New-Item -ItemType Directory -Force (Split-Path $curFile) | Out-Null
$dbCur = 0
try { $dbCur = [int](Invoke-Psql "select coalesce(max(last_applied_seq),0) from ai_event_cursors where provider='ai-v2' and generation='g1'") } catch { $dbCur = 0 }
$fileCur = 0
if (Test-Path $curFile) { $fileCur = [int](Get-Content $curFile -Raw) }
$cur = [Math]::Max($dbCur, $fileCur)
Set-Content -Path $curFile -Value $cur -Encoding ascii
Write-Host "  1 AI 동기화 위치: $cur"
$env:CUR = "$cur"

# --- 2. 업무 백엔드만 끄기 (service\backend의 java와 그 창) ---
$javas = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*inspection-backend*' }
foreach ($j in $javas) {
    $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($j.ParentProcessId)" -ErrorAction SilentlyContinue
    Stop-Process -Id $j.ProcessId -Force -ErrorAction SilentlyContinue
    if ($parent -and $parent.Name -eq 'powershell.exe') { Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue }
}
for ($i = 0; $i -lt 30 -and (Test-Port 8080); $i++) { Start-Sleep -Seconds 1 }
if (Test-Port 8080) { throw '업무 백엔드(:8080)가 꺼지지 않았습니다.' }
Write-Host '  2 업무 백엔드 끔 (공개 주소·Caddy는 그대로)'

# --- 3. 비우기 ---
Invoke-Psql 'DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO inspection;' | Out-Null
& wsl -d $Distro -- redis-cli FLUSHDB | Out-Null
$media = Join-Path $env:LOCALAPPDATA 'inspection-media'
if (Test-Path $media) { Remove-Item -Recurse -Force $media }
Write-Host '  3 DB · Redis · 사진 저장소 비움'

# --- 4. 백엔드 다시 켜기 (+ AI 동기화 위치 복원 대기) ---
$sh = '/mnt/' + $Service.Substring(0, 1).ToLower() + ($Service.Substring(2) -replace '\\', '/') + '/scripts/restore-ai-cursor.sh'
$watch = Start-Process wsl -ArgumentList '-d', $Distro, '--', 'bash', $sh -WindowStyle Hidden -PassThru
& powershell -ExecutionPolicy Bypass -File (Join-Path $Service 'start.ps1') | Out-Null
for ($i = 0; $i -lt 60 -and -not $watch.HasExited; $i++) { Start-Sleep -Seconds 1 }
Write-Host '  4 업무 백엔드 다시 켬 (시연 데이터 새로 넣음)'

# --- 5. 관리자 비밀번호 ---
$env:BACKEND_URL = 'http://127.0.0.1:8181'
$env:DEMO_ADMIN_TEMP_PASSWORD = $cfg['DEMO_ADMIN_TEMP_PASSWORD']
$env:ADMIN_PASSWORD = $cfg['ADMIN_PASSWORD']
$env:PYTHONIOENCODING = 'utf-8'
& python (Join-Path $Service 'scripts\prepare_admins.py') | Out-Null
if ($LASTEXITCODE) { throw '관리자 비밀번호 준비 실패 (scripts\prepare_admins.py)' }
Remove-Item env:DEMO_ADMIN_TEMP_PASSWORD, env:ADMIN_PASSWORD, env:BACKEND_URL -ErrorAction SilentlyContinue
Write-Host '  5 관리자 계정 3개 비밀번호 준비 (service\.env의 ADMIN_PASSWORD)'

# --- 6. 확인 ---
$check = Invoke-Psql @"
select 'AI 동기화 위치 ' || coalesce((select max(last_applied_seq)::text from ai_event_cursors), '없음')
union all select '순회 ' || activity_date || ' ' || activity_status from patrols
union all select '조원 ' || string_agg(name_snapshot || ' ' || attendance, ', ') from patrol_members
union all select '사진 ' || count(*) from inspections
union all select '사건 ' || count(*) from issues
"@
Remove-Item env:PGPASSWORD, env:CUR -ErrorAction SilentlyContinue
Write-Host ''
Write-Host '초기화 끝:'
$check -split "`n" | ForEach-Object { Write-Host "  $_" }
if ([int]$cur -gt 0 -and $check -notmatch "AI 동기화 위치 $cur") { Write-Host "  주의: AI 동기화 위치가 $cur 로 맞춰지지 않았습니다." -ForegroundColor Yellow }
