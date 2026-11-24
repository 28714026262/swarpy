param([string]$RuntimeHome)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
& mvn -f (Join-Path $taskRoot 'backend/pom.xml') verify
if($LASTEXITCODE -ne 0){throw '构建或测试失败，未打包。'}
$taskDist=Join-Path $taskRoot ('dist/swapy-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path (Join-Path $taskDist 'scripts') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'backend/target/swapy-0.1.0.jar') -Destination (Join-Path $taskDist 'swapy.jar')
Copy-Item -LiteralPath (Join-Path $taskRoot 'scripts/start.ps1'),(Join-Path $taskRoot 'scripts/stop.ps1') -Destination (Join-Path $taskDist 'scripts')
Copy-Item -LiteralPath (Join-Path $taskRoot 'backend/README.md') -Destination (Join-Path $taskDist 'README.md')
if($RuntimeHome){
    $taskJlink=Join-Path $RuntimeHome 'bin/jlink.exe'
    if(-not(Test-Path -LiteralPath $taskJlink)){throw 'RuntimeHome必须是包含jlink的、允许分发的JDK目录。'}
    & $taskJlink --add-modules java.se,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --output (Join-Path $taskDist 'runtime')
    if($LASTEXITCODE -ne 0){throw '运行时打包失败。'}
    if(Test-Path -LiteralPath (Join-Path $RuntimeHome 'legal')){Copy-Item -LiteralPath (Join-Path $RuntimeHome 'legal') -Destination (Join-Path $taskDist 'jdk-legal') -Recurse}
}
@{version='0.1.0';builtAt=(Get-Date).ToUniversalTime().ToString('o');sha256=(Get-FileHash -LiteralPath (Join-Path $taskDist 'swapy.jar')).Hash;runtimeBundled=[bool]$RuntimeHome} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDist 'build.json') -Encoding UTF8
Write-Output $taskDist
