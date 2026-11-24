param([string]$Cache,[string]$Proxy=$env:HTTPS_PROXY,[switch]$Fresh)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
if(-not $Cache){$Cache=Join-Path $taskRoot 'backend/target/release-tools'}
$Cache=[IO.Path]::GetFullPath($Cache)
New-Item -ItemType Directory -Path $Cache -Force | Out-Null
$taskPrepareRoot=$Cache
if($Fresh){$taskPrepareRoot=Join-Path $Cache ('prepared-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $taskPrepareRoot | Out-Null}
$taskTools=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-tools.json') -Raw | ConvertFrom-Json
[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
foreach($taskKind in @('runtime','nsis')){
    $taskTool=$taskTools.$taskKind
    $taskArchive=Join-Path $Cache $taskTool.archive
    if(-not(Test-Path -LiteralPath $taskArchive) -or (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskTool.sha256){
        Write-Host ('Downloading '+$taskKind+' '+$taskTool.version)
        $taskDownload=@{Uri=$taskTool.url;OutFile=$taskArchive;UseBasicParsing=$true}
        if($Proxy){$taskDownload.Proxy=$Proxy}
        Invoke-WebRequest @taskDownload
    }
    if((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskTool.sha256){throw ('Checksum mismatch: '+$taskKind)}
    $taskMarker=Join-Path $taskPrepareRoot ($taskKind+'-verified.txt')
    $taskVerified=(Test-Path -LiteralPath $taskMarker) -and (Get-Content -LiteralPath $taskMarker -Raw).Trim() -eq $taskTool.sha256
    if(-not $taskVerified){
        $taskOutput=Join-Path $taskPrepareRoot $(if($taskKind -eq 'runtime'){'jre'}else{'nsis'})
        Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskOutput -Force
        [IO.File]::WriteAllText($taskMarker,$taskTool.sha256,[Text.UTF8Encoding]::new($false))
    }
}
$taskResult=@{runtimeHome=(Join-Path $taskPrepareRoot $taskTools.runtime.folder);nsisHome=(Join-Path $taskPrepareRoot $taskTools.nsis.folder)}
foreach($taskCheck in @((Join-Path $taskResult.runtimeHome 'bin/java.exe'),(Join-Path $taskResult.nsisHome 'makensis.exe'))){if(-not(Test-Path -LiteralPath $taskCheck)){throw ('Prepared tool is missing: '+$taskCheck)}}
$taskResult | ConvertTo-Json -Compress
