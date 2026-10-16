@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Repair-Claude.ps1"
if errorlevel 1 exit /b 1
call claude %*
exit /b %errorlevel%
