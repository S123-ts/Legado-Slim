@echo off
chcp 65001 >nul
setlocal
where pwsh >nul 2>nul
if "%errorlevel%"=="0" (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve-logs.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve-logs.ps1" %*
)
if errorlevel 1 pause
endlocal
