@echo off
setlocal
set "LOGDIR=E:\gying-tools\xunlei-auth-helper"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
echo %date% %time% start>>"%LOGDIR%\scheduled-task.log"
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0sync-xunlei-edge-token.ps1" -RepoRoot "%~dp0.." -NodePath "D:\nvm\nodejs\node.exe" -DockerPath "C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe" >>"%LOGDIR%\scheduled-task.log" 2>&1
set "RC=%ERRORLEVEL%"
echo %date% %time% exit=%RC%>>"%LOGDIR%\scheduled-task.log"
exit /b %RC%
