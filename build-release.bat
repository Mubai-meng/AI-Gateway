@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "ROOT=%CD%"
set "VER=0.7.0"
set "MODULES=java.base,java.compiler,java.desktop,java.sql,java.logging,jdk.crypto.ec,jdk.unsupported"
set "OUT=%ROOT%\build\release"

echo [1/6] gradle installDist
call gradlew.bat :desktop:installDist --console=plain --no-daemon
if errorlevel 1 goto fail

echo [2/6] jlink runtime
if exist "%OUT%\runtime" rmdir /s /q "%OUT%\runtime"
jlink --add-modules %MODULES% --output "%OUT%\runtime" --strip-debug --compress zip-9 --no-header-files --no-man-pages
if errorlevel 1 goto fail

echo [3/6] jpackage app-image
if exist "%OUT%\dist" rmdir /s /q "%OUT%\dist"
mkdir "%OUT%\dist"
jpackage --type app-image --name "AI Gateway" --app-version %VER% --vendor "AIGateway" --input "%ROOT%\desktop\build\install\aigw-desktop\lib" --main-jar desktop.jar --main-class dev.aigw.desktop.MainKt --runtime-image "%OUT%\runtime" --dest "%OUT%\dist" --java-options "-Dfile.encoding=UTF-8" --java-options "-Dawt.useSystemAAFontSettings=on"
if errorlevel 1 goto fail

echo [4/6] MSI (requires WiX 3.14 in build\tools\wix314)
if exist "%ROOT%\build\tools\wix314\candle.exe" (
  set "PATH=%ROOT%\build\tools\wix314;!PATH!"
  if not exist "%OUT%\msi" mkdir "%OUT%\msi"
  jpackage --type msi --app-image "%OUT%\dist\AI Gateway" --name "AI Gateway" --app-version %VER% --vendor "AIGateway" --dest "%OUT%\msi" --license-file "%ROOT%\licenses\LICENSE.txt" --win-menu --win-shortcut --win-per-user-install
  if errorlevel 1 echo MSI step failed, portable image is still usable
) else (
  echo WiX not found - skipped MSI. Extract wix314-binaries.zip into build\tools\wix314 to enable it.
)

echo [5/6] EXE installer (requires Inno Setup 6 - auto-detected via registry/PATH)
if not exist "%OUT%\exe" mkdir "%OUT%\exe"
jpackage --type exe --app-image "%OUT%\dist\AI Gateway" --name "AI Gateway" --app-version %VER% --vendor "AIGateway" --dest "%OUT%\exe" --license-file "%ROOT%\licenses\LICENSE.txt" --win-menu --win-shortcut --win-per-user-install
if errorlevel 1 echo EXE step failed - check Inno Setup installation

echo [6/6] bundle licenses + portable zip
xcopy /E /I /Y "%ROOT%\licenses" "%OUT%\dist\AI Gateway\licenses" >nul
powershell -NoProfile -Command "Compress-Archive -Path '%OUT%\dist\AI Gateway' -DestinationPath '%OUT%\AI-Gateway-%VER%-win-x64.zip' -Force"

echo.
echo DONE. Output: %OUT%
dir /b "%OUT%"
exit /b 0

:fail
echo BUILD FAILED
exit /b 1
