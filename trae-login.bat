@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "BIN=%~dp0cli\build\install\aigw\bin\aigw.bat"
set "DATA=%~dp0.data"
echo Trae browser login - callback http://127.0.0.1:51120/authorize
echo Open the URL printed below in your browser and finish authorization.
echo Waiting up to 5 minutes.
echo.
call "%BIN%" browser-login --provider trae --data "%DATA%"
echo.
echo --- accounts ---
call "%BIN%" accounts --provider trae --data "%DATA%"
echo.
pause
