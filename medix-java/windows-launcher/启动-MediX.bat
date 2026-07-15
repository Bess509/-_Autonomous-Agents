@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-medix.ps1" %*
set "MEDIX_EXIT_CODE=%ERRORLEVEL%"
if not "%MEDIX_EXIT_CODE%"=="0" (
  echo.
  echo MediX launcher failed. Exit code: %MEDIX_EXIT_CODE%
  if /I not "%~1"=="-NoPause" pause
)
exit /b %MEDIX_EXIT_CODE%
