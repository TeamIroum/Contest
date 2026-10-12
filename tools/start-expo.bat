@echo off
rem 휴대폰 시험용 서버 켜기 (Expo, 창 1~5. PWA도 함께 열림)
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-test.ps1"
pause
