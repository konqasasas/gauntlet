@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0enable-friends-firewall.ps1"
if errorlevel 1 echo Firewall setup failed. Check the message above.
pause
