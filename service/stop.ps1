# service\start.ps1로 띄운 것을 끈다. Funnel은 443·10000만 끄고 기존 :8443(시험 콘솔)은 그대로 둔다.
#   powershell -ExecutionPolicy Bypass -File service\stop.ps1
$RunDir = Join-Path $env:TEMP 'iroum-service'
$StateFile = Join-Path $RunDir 'pids.txt'

tailscale funnel --https=443 off 2>$null | Out-Null
tailscale funnel --https=10000 off 2>$null | Out-Null

if (Test-Path $StateFile) {
    foreach ($id in Get-Content $StateFile) {
        # 창(PowerShell)과 그 안의 서버(java·caddy·python)를 함께 끈다
        & taskkill /PID $id /T /F 2>$null | Out-Null
    }
    Remove-Item $StateFile -Force
}
Write-Host '껐습니다. (start.ps1 실행 전부터 떠 있던 서버는 그대로 둡니다)'
