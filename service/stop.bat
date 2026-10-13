@echo off
REM Double-click to run service\stop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause
