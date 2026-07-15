@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-medix.ps1" %*
set "MEDIX_EXIT_CODE=%ERRORLEVEL%"
if not "%MEDIX_EXIT_CODE%"=="0" (
  echo.
  echo MediX stop request failed. Exit code: %MEDIX_EXIT_CODE%
  if /I not "%~1"=="-NoPause" pause
)
exit /b %MEDIX_EXIT_CODE%
