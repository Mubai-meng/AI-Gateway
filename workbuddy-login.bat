@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "CP=%~dp0desktop\build\install\aigw-desktop\lib\*"
set "JAVA=%~dp0build\release\runtime\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
echo WorkBuddy / CodeBuddy device-code login
echo Data dir: %~dp0.data
echo.
"%JAVA%" -Dfile.encoding=UTF-8 -cp "%CP%" dev.aigw.desktop.DeviceCodeLoginKt --data "%~dp0.data" %*
echo.
pause
