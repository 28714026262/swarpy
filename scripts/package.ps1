[CmdletBinding()]
param([string]$LibraryPath,[string]$Version='0.1.0',[switch]$UseVerifiedJar)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
if($Version -notmatch '^\d+\.\d+\.\d+([.-][A-Za-z0-9.-]+)?$'){throw 'Invalid version'}
$taskTools=(& (Join-Path $PSScriptRoot 'prepare-release-tools.ps1') -Fresh) | ConvertFrom-Json
$RuntimeHome=$taskTools.runtimeHome;$NsisHome=$taskTools.nsisHome
$taskToolsMetadata=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-tools.json') -Raw | ConvertFrom-Json
if(-not $LibraryPath){
    $LibraryPath=Join-Path (Split-Path -Parent $taskRoot) 'SwarpyDB/releases/swapy-basic.csv'
    if(-not(Test-Path -LiteralPath $LibraryPath)){
        $LibraryPath=Join-Path $taskRoot 'backend/target/release-tools/swapy-basic.csv'
        New-Item -ItemType Directory -Path (Split-Path -Parent $LibraryPath) -Force | Out-Null
        $taskLibraryDownload=@{Uri=$taskToolsMetadata.library.url;OutFile=$LibraryPath;UseBasicParsing=$true}
        if($env:HTTPS_PROXY){$taskLibraryDownload.Proxy=$env:HTTPS_PROXY}
        Invoke-WebRequest @taskLibraryDownload
    }
}
if((Get-FileHash -LiteralPath $LibraryPath).Hash.ToLowerInvariant() -ne $taskToolsMetadata.library.sha256){throw 'Release library must match the pinned public corpus.'}
foreach($taskInput in @((Join-Path $RuntimeHome 'bin/java.exe'),(Join-Path $NsisHome 'makensis.exe'),$LibraryPath)){if(-not(Test-Path -LiteralPath $taskInput)){throw ('Missing release input: '+$taskInput)}}
$taskBuild=Join-Path $taskRoot 'backend/target/release-build'
$taskJar=Join-Path $taskBuild 'swapy-0.1.0.jar'
$taskStamp=Join-Path $taskBuild 'verified.json'
$taskSourcePaths=@((Join-Path $taskRoot 'backend/pom.xml'))+@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend/src'),(Join-Path $taskRoot 'prototype') -File -Recurse | Sort-Object FullName | ForEach-Object {$_.FullName})
$taskSourceText=($taskSourcePaths | ForEach-Object {($_.Substring($taskRoot.Length))+':'+(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}) -join "`n"
$taskSourceHash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($taskSourceText))).Replace('-','').ToLowerInvariant()
if($UseVerifiedJar){
    if(-not(Test-Path -LiteralPath $taskStamp)){throw 'No verified build; run package.ps1 without UseVerifiedJar first.'}
    $taskVerified=Get-Content -LiteralPath $taskStamp -Raw | ConvertFrom-Json
    if($taskVerified.sourceHash -ne $taskSourceHash -or $taskVerified.jarHash -ne (Get-FileHash -LiteralPath $taskJar).Hash){throw 'Application changed after verification; run a full package build.'}
}else{
    & mvn -f (Join-Path $taskRoot 'backend/pom.xml') ("-Dswapy.build.directory="+$taskBuild) verify
    if($LASTEXITCODE -ne 0){throw 'Build or tests failed; no release created.'}
    @{sourceHash=$taskSourceHash;jarHash=(Get-FileHash -LiteralPath $taskJar).Hash} | ConvertTo-Json | Set-Content -LiteralPath $taskStamp -Encoding UTF8
}
$taskDist=Join-Path $taskRoot ('dist/build-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$taskStage=Join-Path $taskDist ('Swapy-'+$Version)
foreach($taskDir in @('scripts','runtime','library','licenses/frontend','licenses/library','licenses/dependencies')){New-Item -ItemType Directory -Path (Join-Path $taskStage $taskDir) -Force | Out-Null}
Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskStage 'swapy.jar')
foreach($taskScript in @('start.ps1','stop.ps1','launch.ps1')){
    $taskScriptText=[IO.File]::ReadAllText((Join-Path $PSScriptRoot $taskScript),[Text.Encoding]::UTF8)
    [IO.File]::WriteAllText((Join-Path $taskStage ('scripts/'+$taskScript)),$taskScriptText,[Text.UTF8Encoding]::new($true))
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start Swapy.cmd'),(Join-Path $PSScriptRoot 'Stop Swapy.cmd') -Destination $taskStage
function Copy-ReleaseTree([string]$Source,[string]$Destination){
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /MT:8 /R:1 /W:1 /XJ /XF '*.msi' /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw ('Copy failed: '+$Source)}
}
Copy-ReleaseTree $RuntimeHome (Join-Path $taskStage 'runtime')
Copy-Item -LiteralPath $LibraryPath -Destination (Join-Path $taskStage 'library/swapy-basic.csv')
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'prototype/vendor') -Filter 'LICENSE*' | Copy-Item -Destination (Join-Path $taskStage 'licenses/frontend')
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources') -Filter '*-LICENSE.txt' | Copy-Item -Destination (Join-Path $taskStage 'licenses/library')
Copy-Item -LiteralPath (Join-Path $NsisHome 'COPYING') -Destination (Join-Path $taskStage 'licenses/NSIS-COPYING.txt')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive=[IO.Compression.ZipFile]::OpenRead($taskJar)
try{
    foreach($taskDependency in @($taskArchive.Entries | Where-Object {$_.FullName -like 'BOOT-INF/lib/*.jar'})){
        $taskBuffer=[IO.MemoryStream]::new();$taskEntryStream=$taskDependency.Open();try{$taskEntryStream.CopyTo($taskBuffer)}finally{$taskEntryStream.Dispose()};$taskBuffer.Position=0
        $taskNested=[IO.Compression.ZipArchive]::new($taskBuffer,[IO.Compression.ZipArchiveMode]::Read)
        try{
            $taskLicenseDir=Join-Path $taskStage ('licenses/dependencies/'+$taskDependency.Name)
            New-Item -ItemType Directory -Path $taskLicenseDir -Force | Out-Null
            foreach($taskEntry in @($taskNested.Entries | Where-Object {$_.FullName -match '^META-INF/(LICENSE|NOTICE|COPYING|DEPENDENCIES)([./_-]|$)' -and $_.Name})){
                $taskLicenseFile=Join-Path $taskLicenseDir ($taskEntry.FullName.Replace('/','_'))
                $taskInput=$taskEntry.Open();$taskOutput=[IO.File]::Create($taskLicenseFile);try{$taskInput.CopyTo($taskOutput)}finally{$taskInput.Dispose();$taskOutput.Dispose()}
            }
        }finally{$taskNested.Dispose();$taskBuffer.Dispose()}
    }
}finally{$taskArchive.Dispose()}
$taskToolsMetadata=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-tools.json') -Raw | ConvertFrom-Json
$taskSourceCommit=(& git -C $taskRoot rev-parse HEAD).Trim();$taskDirty=[bool](& git -C $taskRoot status --porcelain)
$taskLibraryHeader=[IO.File]::ReadLines($LibraryPath) | Select-Object -First 1
@{version=$Version;builtAt=(Get-Date).ToUniversalTime().ToString('o');sourceCommit=$taskSourceCommit;sourceDirty=$taskDirty;jarSha256=(Get-FileHash -LiteralPath $taskJar).Hash.ToLowerInvariant();runtimeBundled=$true;officeBundled=$false;librarySha256=(Get-FileHash -LiteralPath $LibraryPath).Hash.ToLowerInvariant();libraryHeader=$taskLibraryHeader;tools=$taskToolsMetadata} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskStage 'build.json') -Encoding UTF8
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/windows-release-readme.txt') -Destination (Join-Path $taskStage 'README.txt')
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/third-party-sources.md') -Destination (Join-Path $taskStage 'THIRD-PARTY-SOURCES.md')
if(-not ('Swapy.ReleaseHashes' -as [type])){Add-Type -Path (Join-Path $PSScriptRoot 'release-hashes.cs')}
$taskManifest=[Swapy.ReleaseHashes]::Create($taskStage)
$taskManifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $taskStage 'files.json') -Encoding UTF8
$taskDelete=[Collections.Generic.List[string]]::new()
foreach($taskFile in @(Get-ChildItem -LiteralPath $taskStage -Recurse -File)){
    $taskRelative=$taskFile.FullName.Substring($taskStage.Length+1).Replace('$','$$').Replace('"','$\"')
    $taskDelete.Add(('SetFileAttributes "$INSTDIR\'+$taskRelative+'" NORMAL'))
    $taskDelete.Add(('Delete "$INSTDIR\'+$taskRelative+'"'))
}
foreach($taskDirectory in @(Get-ChildItem -LiteralPath $taskStage -Recurse -Directory | Sort-Object {$_.FullName.Length} -Descending)){$taskDelete.Add(('RMDir "$INSTDIR\'+$taskDirectory.FullName.Substring($taskStage.Length+1).Replace('$','$$')+'"'))}
$taskUninstallList=Join-Path $taskDist 'uninstall-files.nsh';[IO.File]::WriteAllLines($taskUninstallList,$taskDelete,[Text.UTF8Encoding]::new($true))
$taskSetup=Join-Path $taskDist ('Swapy-'+$Version+'-windows-x64-setup.exe')
& (Join-Path $NsisHome 'makensis.exe') /V2 ("/DVERSION="+$Version) ("/DSTAGE_PATH="+$taskStage) ("/DOUTPUT_FILE="+$taskSetup) ("/DUNINSTALL_LIST="+$taskUninstallList) (Join-Path $PSScriptRoot 'installer.nsi')
if($LASTEXITCODE -ne 0){throw 'Installer compilation failed.'}
$taskZip=Join-Path $taskDist ('Swapy-'+$Version+'-windows-x64-portable.zip')
[IO.Compression.ZipFile]::CreateFromDirectory($taskStage,$taskZip,[IO.Compression.CompressionLevel]::Optimal,$true)
$taskArtifacts=@($taskSetup,$taskZip)
$taskSums=($taskArtifacts | ForEach-Object {(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()+'  '+[IO.Path]::GetFileName($_)}) -join "`n"
[IO.File]::WriteAllText((Join-Path $taskDist 'SHA256SUMS.txt'),$taskSums+"`n",[Text.UTF8Encoding]::new($false))
@{directory=$taskDist;stage=$taskStage;setup=$taskSetup;zip=$taskZip;checksums=(Join-Path $taskDist 'SHA256SUMS.txt');version=$Version;sourceCommit=$taskSourceCommit} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDist 'release.json') -Encoding UTF8
Write-Output $taskDist
