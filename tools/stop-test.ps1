# tools\start-test.ps1로 띄운 창과 서버·터널을 모두 끈다. Ollama는 그대로 둔다.
$StateFile = Join-Path $env:TEMP 'senior-test\pids.txt'

if (Test-Path $StateFile) {
    foreach ($id in Get-Content $StateFile) {
        if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
            taskkill /T /F /PID $id | Out-Null   # 창 안에서 돈 python·cloudflared·node까지 함께
            Write-Host "PID $id 끔"
        }
    }
    Remove-Item $StateFile
}

# 창을 직접 닫아 남은 서버가 있으면 포트로 찾아 끈다
foreach ($port in 8000, 8100, 8081) {
    try {
        Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop | ForEach-Object {
            Stop-Process -Id $_.OwningProcess -Force
            Write-Host "포트 $port (PID $($_.OwningProcess)) 끔"
        }
    } catch { }
}
$Tailscale = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
$tsExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (-not $Tailscale -and (Test-Path $tsExe)) { $Tailscale = $tsExe }  # 설치 전에 연 창은 PATH에 없음
if ($Tailscale) {
    & $Tailscale funnel --https=443 off 2>$null | Out-Null
    & $Tailscale funnel --https=8443 off 2>$null | Out-Null
    Write-Host 'Tailscale Funnel 닫음'
}
Write-Host '모두 껐습니다.'
