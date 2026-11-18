@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem Build script for the WebView2 shell.
rem Uses csc.exe shipped with .NET Framework 4.8 (no dotnet SDK needed).
rem NOTE: keep pure ASCII. cmd.exe reads .bat as OEM codepage and UTF-8
rem       Chinese comments would be split into bogus commands.
rem Usage: build-shell.bat [release]

set "CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" set "CSC=C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not exist "%CSC%" goto :noCsc

set "OUTDIR=out"
if /i "%~1"=="release" set "OUTDIR=out\release"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo [1/3] compiling AiGatewayWeb.exe
"%CSC%" /nologo /target:winexe /platform:anycpu /optimize+ /debug- ^
  /out:"%OUTDIR%\AiGatewayWeb.exe" ^
  /win32manifest:app.manifest ^
  /win32icon:app.ico ^
  /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Net.Http.dll ^
  "/r:lib\Microsoft.Web.WebView2.Core.dll" ^
  "/r:lib\Microsoft.Web.WebView2.WinForms.dll" ^
  Program.cs ShellArgs.cs ServerLauncher.cs WindowNative.cs ShellWindow.cs TitleBar.cs
if errorlevel 1 goto :fail

echo [2/3] copying runtime dependencies
copy /y "lib\Microsoft.Web.WebView2.Core.dll" "%OUTDIR%\" >nul
copy /y "lib\Microsoft.Web.WebView2.WinForms.dll" "%OUTDIR%\" >nul
rem Loader dll must live beside the exe: the managed wrapper loads it by relative path
copy /y "lib\WebView2Loader.dll" "%OUTDIR%\" >nul
if not exist "%OUTDIR%\runtimes\win-x64\native" mkdir "%OUTDIR%\runtimes\win-x64\native"
copy /y "lib\WebView2Loader.dll" "%OUTDIR%\runtimes\win-x64\native\" >nul
rem icon beside the exe: tray icon is read from the exe itself, this copy is for
rem the few paths (shortcut creation / preview) that take a file name
copy /y "app.ico" "%OUTDIR%\" >nul

echo.
echo done: %OUTDIR%\AiGatewayWeb.exe
echo backend must be built first: gradlew :server:installDist
goto :eof

:noCsc
echo [error] csc.exe not found
exit /b 1

:fail
echo [error] compile failed
exit /b 1
