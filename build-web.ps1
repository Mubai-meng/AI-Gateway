# AI Gateway WebView2 edition - release packaging
# Output: build\release-web\
#   AI Gateway.exe     launcher (WebView2 shell, renamed from AiGatewayWeb.exe)
#   Microsoft.Web.WebView2.*.dll / WebView2Loader.dll
#                      MUST sit beside the exe: assembly resolution only looks
#                      at the app folder, and the managed wrapper loads the
#                      native loader by relative path.
#   runtime\           trimmed JRE (java.desktop needed by the Swing Timer used
#                      for the daily checkin scheduler)
#   app\               backend jars (core/desktop/server + deps)
#   web\               frontend static files
#
# Why PowerShell instead of .bat: the install path contains a space
# ("AI Gateway"), and cmd's xcopy/copy quoting with %OUT%\AI Gateway.exe
# silently split into two arguments. PS handles spaces natively.

$ErrorActionPreference = 'Stop'
Set-Location -Path (Split-Path -Parent $MyInvocation.MyCommand.Path)

$OUT     = Join-Path $PWD 'build\release-web'
$APPDIR  = Join-Path $OUT 'app'
$WEBDIR  = Join-Path $OUT 'web'
$SHELLSRC = Join-Path $PWD 'shell\out\release'
$RUNTIMESRC = Join-Path $PWD 'build\release\dist\AI Gateway\runtime'

Write-Host '[1/6] clean'
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT | Out-Null

Write-Host '[2/6] backend jars'
# Gradle 需要 JDK。Windows 上 JAVA_HOME 常常没设（构建能跑是因为之前某次 shell 里设过），
# 所以这里显式找一个：优先本机 jdk-21，其次任意已有 runtime。
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidates = @(
        (Join-Path $env:USERPROFILE '.workbuddy\binaries\jdk-21'),
        (Join-Path $env:USERPROFILE '.workbuddy\binaries\jdk-17'),
        (Join-Path $PWD 'build\release\dist\AI Gateway\runtime')
    )
    foreach ($c in $candidates) {
        if (-not (Test-Path $c)) { continue }
        # jdk-21 目录下还有一层 jdk-21.0.3+9，需要再下一层
        $hit = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
               Select-Object -First 1
        if ($hit) { $env:JAVA_HOME = $hit.FullName; break }
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}
if (-not $env:JAVA_HOME) { throw 'JDK not found. Set JAVA_HOME before running this script.' }
Write-Host "      JAVA_HOME = $env:JAVA_HOME"
& (Join-Path $PWD 'gradlew.bat') ':server:installDist' '--console=plain' '--no-daemon' '-q'
if ($LASTEXITCODE -ne 0) { throw 'gradle :server:installDist failed' }
New-Item -ItemType Directory -Path $APPDIR | Out-Null
$libs = Join-Path $PWD 'server\build\install\aigw-server\lib'
Copy-Item (Join-Path $libs '*.jar') $APPDIR -Force
$serverJar = Join-Path $APPDIR 'server.jar'
if (Test-Path $serverJar) { Move-Item $serverJar (Join-Path $APPDIR 'aigw-server.jar') -Force }

Write-Host '[3/6] frontend'
# 必须先建目录：Copy-Item 'src\*' -Destination <不存在路径> 会把**第一个文件**当成目标
# 文件名写出去，结果 web 变成 13 KB 的 kd-design-system.css 文件而不是目录。
# （这就是「前端资源缺失 web\dist/index.html」的真实原因，不是查找逻辑的问题。）
New-Item -ItemType Directory -Path $WEBDIR -Force | Out-Null
Copy-Item (Join-Path $PWD 'web\dist\*') $WEBDIR -Recurse -Force
$indexOk = Test-Path (Join-Path $WEBDIR 'index.html')
Write-Host "      index.html present: $indexOk"
if (-not $indexOk) { throw "frontend copy failed: $WEBDIR\index.html missing" }

Write-Host '[4/6] webview2 shell'
Push-Location (Join-Path $PWD 'shell')
try { & .\build-shell.bat release } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'shell build failed' }

Write-Host '[5/6] jre runtime'
if (-not (Test-Path $RUNTIMESRC)) {
    throw "runtime not found: $RUNTIMESRC`nRun build-release.bat once to produce the jpackage image."
}
Copy-Item $RUNTIMESRC (Join-Path $OUT 'runtime') -Recurse -Force

Write-Host '[6/6] assemble launcher'
Copy-Item (Join-Path $SHELLSRC 'AiGatewayWeb.exe') (Join-Path $OUT 'AI Gateway.exe') -Force
foreach ($dll in @('Microsoft.Web.WebView2.Core.dll',
                   'Microsoft.Web.WebView2.WinForms.dll',
                   'WebView2Loader.dll')) {
    Copy-Item (Join-Path $SHELLSRC $dll) (Join-Path $OUT $dll) -Force
}
# keep the shell libs too: the exe also probes .\shell\ when launched from there
Copy-Item $SHELLSRC (Join-Path $OUT 'shell') -Recurse -Force
$verFile = Join-Path $PWD 'WINDOWS-VERSION.txt'
if (Test-Path $verFile) { Copy-Item $verFile (Join-Path $OUT 'README.txt') -Force }

$total = (Get-ChildItem $OUT -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ''
Write-Host ("done. {0}  ({1:N1} MB)" -f $OUT, $total)
Get-ChildItem $OUT | Select-Object Name, @{n='MB';e={ '{0:N1}' -f ($(if ($_.PSIsContainer) { (Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum } else { $_.Length }) / 1MB) }} | Format-Table -AutoSize
