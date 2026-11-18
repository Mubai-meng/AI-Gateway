param(
    [string]$Ref = "",
    [switch]$Check
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$refFile = Join-Path $root "upstream.ref"
$current = (Get-Content $refFile -Raw).Trim()
$headers = @{ "User-Agent" = "aigw-windows" }

if ($Check) {
    # 走 releases.atom 而不是 GitHub REST API：API 有未认证速率限制，atom feed 没有
    $feed = Invoke-WebRequest -Uri "https://github.com/xibai6668/Android-AI-gateway/releases.atom" -UseBasicParsing -Headers $headers
    $match = [regex]::Match($feed.Content, '/releases/tag/([^"<]+)')
    if (-not $match.Success) { throw "cannot parse latest tag from releases.atom" }
    $latest = $match.Groups[1].Value
    Write-Host "pinned : $current"
    Write-Host "latest : $latest"
    if ($current -eq "main") {
        Write-Host "tracking main branch - run: sync-core.ps1 -Ref main"
    } elseif ($latest -ne $current) {
        Write-Host "UPDATE AVAILABLE - run: sync-core.ps1 -Ref $latest"
    } else {
        Write-Host "up to date"
    }
    exit 0
}

if (-not $Ref) { $Ref = $current }
$refPath = if ($Ref -like "v*") { "refs/tags/$Ref" } else { "refs/heads/$Ref" }
$url = "https://github.com/xibai6668/Android-AI-gateway/archive/$refPath.zip"
Write-Host "syncing core to $Ref ..."

$zip = Join-Path $env:TEMP "aigw-upstream-$($Ref -replace '[^\w.-]','_').zip"
Invoke-WebRequest -Uri $url -OutFile $zip -Headers $headers
$extract = Join-Path $env:TEMP "aigw-upstream-extract"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $extract -Force

$found = Get-ChildItem -Path $extract -Directory |
    Where-Object { $_.Name -like "Android-AI-gateway-*" } |
    Select-Object -First 1
if (-not $found) { throw "unexpected zip layout under $extract" }

$srcCore = Join-Path $found.FullName "core"
$dstCore = Join-Path $root "core"
if (-not (Test-Path (Join-Path $srcCore "src"))) { throw "upstream core/src not found" }

$backup = Join-Path $root ("core-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
Copy-Item -Path $dstCore -Destination $backup -Recurse
Remove-Item -Path (Join-Path $dstCore "src") -Recurse -Force
Copy-Item -Path (Join-Path $srcCore "src") -Destination $dstCore -Recurse
Copy-Item -Path (Join-Path $srcCore "build.gradle.kts") -Destination $dstCore -Force
Set-Content -Path $refFile -Value $Ref -NoNewline

Write-Host "core synced. backup: $backup"
Write-Host "next: build-release.bat   (then run SelfCheckKt to verify)"
