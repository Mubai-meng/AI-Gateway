@echo off
chcp 65001 >nul
set "BIN=%~dp0cli\build\install\aigw\bin\aigw.bat"
set "DATA=%~dp0.data"
echo [1/4] accounts
call "%BIN%" accounts --provider trae --data "%DATA%"
echo.
echo [2/4] credits
call "%BIN%" credits --provider trae --data "%DATA%"
echo.
echo [3/4] models
call "%BIN%" models --data "%DATA%"
echo.
echo [4/4] chat completions (stream)
for /f "delims=" %%m in ('powershell -NoProfile -Command "(Invoke-RestMethod -Uri http://127.0.0.1:8790/v1/models).data[0].id"') do set "MODEL=%%m"
if "%MODEL%"=="" (
  echo No model available. Check login, then restart serve.bat so AccountPool reloads from disk.
  pause
  exit /b 1
)
echo model=%MODEL%
> "%TEMP%\aigw-req.json" echo {"model":"%MODEL%","stream":true,"messages":[{"role":"user","content":"hi"}]}
curl -N -s -m 60 http://127.0.0.1:8790/v1/chat/completions -H "Content-Type: application/json" --data-binary "@%TEMP%\aigw-req.json"
echo.
pause
