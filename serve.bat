@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Gateway on http://127.0.0.1:8790/v1  (Ctrl+C to stop)
call "%~dp0cli\build\install\aigw\bin\aigw.bat" serve --port 8790 --data "%~dp0.data"
