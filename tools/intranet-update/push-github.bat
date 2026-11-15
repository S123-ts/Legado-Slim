@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 发布到 GitHub Release（外网更新通道）
echo.
if "%GITHUB_TOKEN%"=="" (
    echo 没有检测到 GITHUB_TOKEN。
    echo 到 https://github.com/settings/tokens 生成一个 classic token（勾 repo），粘贴到下面。
    set /p GITHUB_TOKEN=Token: 
    echo.
)
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-github.ps1" %*
) else (
    powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-github.ps1" %*
)
echo.
pause
