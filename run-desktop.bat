@echo off
cd /d "%~dp0"
set "CP=%~dp0desktop\build\install\aigw-desktop\lib\*"
start "AI Gateway" javaw -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=on -cp "%CP%" dev.aigw.desktop.MainKt --data "%~dp0.data"
