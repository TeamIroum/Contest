@echo off
rem 휴대폰 시험용 서버 켜기 (PWA만, 창 1~3)
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-test.ps1" -PwaOnly
pause
