@echo off
setlocal

where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
)

set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo Build failed with exit code %EXIT_CODE%.
  pause
)

exit /b %EXIT_CODE%
