@echo off
rem start-test로 띄운 서버와 터널 모두 끄기
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-test.ps1"
pause
