@echo off
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  py -3 "%~dp0..\scripts\bdr.py" %*
  exit /b %ERRORLEVEL%
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  python "%~dp0..\scripts\bdr.py" %*
  exit /b %ERRORLEVEL%
)

echo bdr: Python 3.10 or newer is required 1>&2
exit /b 127
