@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ============================================================
rem  AI Gateway WebView2 edition - release packaging
rem  Output: build\release-web\
rem    AI Gateway.exe   launcher (WebView2 shell)
rem    WebView2*.dll    WebView2 managed libs + loader (must sit beside exe)
rem    runtime\         trimmed JRE (java.desktop needed by Swing Timer)
rem    app\             backend jars (core/desktop/server + deps)
rem    web\             frontend static files
rem  NOTE: keep this file pure ASCII. cmd.exe reads .bat as OEM codepage;
rem        UTF-8 Chinese comments get split into bogus commands.
rem ============================================================

set "OUT=build\release-web"
set "APPDIR=%OUT%\app"
set "WEBDIR=%OUT%\web"
set "SHELLDIR=%OUT%\shell"

echo [1/6] clean
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [2/6] backend jars
call gradlew.bat :server:installDist --console=plain --no-daemon -q
if errorlevel 1 goto :fail
mkdir "%APPDIR%"
xcopy /y /q "server\build\install\aigw-server\lib\*.jar" "%APPDIR%\" >nul
if exist "%APPDIR%\server.jar" ren "%APPDIR%\server.jar" "aigw-server.jar"

echo [3/6] frontend
xcopy /e /i /y /q "web\dist" "%WEBDIR%" >nul

echo [4/6] webview2 shell
pushd shell
call build-shell.bat release
if errorlevel 1 goto :fail
popd
xcopy /e /i /y /q "shell\out\release\*" "%SHELLDIR%" >nul

echo [5/6] jre runtime
if not exist "build\release\dist\AI Gateway\runtime" goto :noRuntime
xcopy /e /i /y /q "build\release\dist\AI Gateway\runtime" "%OUT%\runtime" >nul

echo [6/6] assemble launcher
copy /y /q "%SHELLDIR%\AiGatewayWeb.exe" "%OUT%\AI Gateway.exe" >nul
copy /y /q "%SHELLDIR%\Microsoft.Web.WebView2.Core.dll" "%OUT%\" >nul
copy /y /q "%SHELLDIR%\Microsoft.Web.WebView2.WinForms.dll" "%OUT%\" >nul
copy /y /q "%SHELLDIR%\WebView2Loader.dll" "%OUT%\" >nul
if exist "WINDOWS-VERSION.txt" copy /y /q "WINDOWS-VERSION.txt" "%OUT%\README.txt" >nul

echo.
echo done. output folder: %OUT%
dir /b "%OUT%"
goto :eof

:noRuntime
echo [error] build\release\dist\AI Gateway\runtime not found.
echo         Run build-release.bat once to produce the jpackage image first.
exit /b 1

:fail
echo [error] build step failed
exit /b 1
