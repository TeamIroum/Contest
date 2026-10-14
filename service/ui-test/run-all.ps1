# 화면 시험 전체: 시연 데이터를 초기화하고 순회를 오늘로 옮겨 가며 시니어 앱·관리자 웹 시험을 차례로 돌린 뒤, 끝나면 다시 초기화한다.
# 실제 데이터를 바꾸므로 리허설·시연 중에는 돌리지 않는다. 서비스(start.ps1)가 켜져 있어야 한다.
#
#   powershell -ExecutionPolicy Bypass -File service\ui-test\run-all.ps1            # 공개 주소로
#   powershell -ExecutionPolicy Bypass -File service\ui-test\run-all.ps1 -Local     # 이 PC 안(Caddy :8180·:8181)으로
param([switch]$Local)

$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot
$Service = Split-Path -Parent $Here
$Root = Split-Path -Parent $Service

$senior = if ($Local) { 'http://127.0.0.1:8180' } else { 'https://desktop-iv9p5jl.tail3f4b5a.ts.net' }
$admin = if ($Local) { 'http://127.0.0.1:8181' } else { 'https://desktop-iv9p5jl.tail3f4b5a.ts.net:10000' }

# service\.env의 DB 비밀번호 (WSLENV로만 넘김)
$pw = ((Get-Content (Join-Path $Service '.env') -Encoding UTF8 | Where-Object { $_ -match '^\s*DB_PASSWORD\s*=' }) -replace '^\s*DB_PASSWORD\s*=\s*', '').Trim().Trim("'").Trim('"')
$env:PGPASSWORD = $pw
$env:WSLENV = 'PGPASSWORD/u'
$sql = '/mnt/' + $Root.Substring(0, 1).ToLower() + ($Root.Substring(2) -replace '\\', '/') + '/tools/e2e/move_demo_date.sql'
$today = [DateTime]::UtcNow.AddHours(9).ToString('yyyy-MM-dd')

# 촬영 시험에 비출 실제 사진(깨진 보도블록, 밝고 선명해 휴대폰 품질 검사를 통과). 없으면 Chromium 기본 시험 화면(단색이라 '흐려요')
$photo = Join-Path $Root 'ai\eval\photos\internet\sidewalk_011.jpg'
$video = Join-Path $env:TEMP 'iroum-ui-test-sidewalk_011-even.mjpeg'
if ((Test-Path $photo) -and -not (Test-Path $video)) {
    # Chromium 가짜 카메라는 가로·세로가 짝수인 JPEG 프레임만 제대로 읽는다(홀수면 검은 화면 → '어두워요'). 짝수로 맞춰 다시 저장해 60장 이어 붙인다
    & python -c "import io,sys; from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB'); s=min(1280/im.width,1280/im.height,1); im=im.resize((int(im.width*s)//2*2,int(im.height*s)//2*2)); b=io.BytesIO(); im.save(b,'JPEG',quality=90); open(sys.argv[2],'wb').write(b.getvalue()*60)" $photo $video
}

function Reset-Today {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $Service 'reset-demo.ps1') | Out-Null
    $ErrorActionPreference = 'Continue'
    & wsl -d Ubuntu-24.04 -- psql -h localhost -U inspection -d inspection -q -v "day=$today" -f $sql 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
}
$results = [ordered]@{}
function Run([string]$name, [string[]]$cmd) {
    Write-Host "▶ $name"
    Push-Location $Here
    try { $out = & node @cmd 2>&1 | ForEach-Object { "$_" } } finally { Pop-Location }
    $out | ForEach-Object { Write-Host "    $_" }
    $results[$name] = if (($out -join "`n") -match '문제 없음') { '통과' } else { '확인 필요' }
}

Write-Host "화면 시험 전체 ($senior · $admin)"
Write-Host '― 1. 건너뛰기·그만하기·복귀 (출발 확인 전 시작 막힘, 출석 1명 출발 거절 포함)'
Reset-Today
Run 'skip-return' @('skip-return.mjs', $senior, $admin)

Write-Host '― 1-2. 장소를 모두 건너뛰고 정상 종료 → 통계에 순회 1회 · 중도 종료 0회'
Reset-Today
Run 'senior-normal-end' @('senior-normal-end.mjs', $senior, $admin)

Write-Host '― 2. 촬영 · 화면 둘러보기 · 쉬기 · 관리자 화면 · 판정 · 개인정보 검토 · 참여자 계정 · 조 편성 · 코스·순회 배정 · 재점검 배정 · 통계 · 운영(알림·기관·감사) · 공무원 계정'
Reset-Today
if (Test-Path $video) { $env:VIDEO = $video }
Run 'senior (촬영)' @('senior.mjs', $senior, '--shoot')
Run 'senior-tour' @('senior-tour.mjs', $senior)
Run 'senior-reload' @('senior-reload.mjs', $senior)
Remove-Item env:VIDEO -ErrorAction SilentlyContinue
Run 'pause-check' @('pause-check.mjs', $senior, $admin)
Run 'admin-accounts' @('admin-accounts.mjs', $admin)
Run 'admin-reload' @('admin-reload.mjs', $admin)
Run 'admin' @('admin.mjs', $admin)
$env:LOGIN = 'demo-road'
Run 'admin-decide' @('admin-decide.mjs', $admin)
Run 'admin-privacy' @('admin-privacy.mjs', $admin)
Run 'admin-reinspect' @('admin-reinspect.mjs', $admin)
$env:EXPECT_DATA = '1'
Run 'admin-stats' @('admin-stats.mjs', $admin)
Run 'admin-ops' @('admin-ops.mjs', $admin)
Remove-Item env:EXPECT_DATA -ErrorAction SilentlyContinue
Remove-Item env:LOGIN -ErrorAction SilentlyContinue
Run 'admin-participants' @('admin-participants.mjs', $admin, $senior)
Run 'admin-teams' @('admin-teams.mjs', $admin)
Run 'admin-assign' @('admin-assign.mjs', $admin)
Run 'admin-officers' @('admin-officers.mjs', $admin)

Write-Host '― 3. 시연 데이터 초기화'
& powershell -ExecutionPolicy Bypass -File (Join-Path $Service 'reset-demo.ps1') | Out-Null
Remove-Item env:PGPASSWORD -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '결과 (캡처: service\ui-test\shots\):'
foreach ($k in $results.Keys) { Write-Host ("  {0,-18} {1}" -f $k, $results[$k]) }
