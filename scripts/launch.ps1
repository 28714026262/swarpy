param([ValidateRange(1,65535)][int]$Port=4175,[switch]$NoBrowser,[switch]$Stop,[switch]$ForUpgrade)
$ErrorActionPreference='Stop'
function Get-SwapyJson {
    param([string]$Uri,[int]$TimeoutSec=10)
    $taskHttp=Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSec -UseBasicParsing
    [Text.Encoding]::UTF8.GetString($taskHttp.RawContentStream.ToArray()) | ConvertFrom-Json
}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskData=Join-Path $taskRoot 'data'
if(Test-Path -LiteralPath (Join-Path $taskRoot 'installed.flag')){$taskData=Join-Path $env:LOCALAPPDATA 'Swapy/data'}
if($env:SWAPY_DATA){$taskData=[IO.Path]::GetFullPath($env:SWAPY_DATA)}
$taskData=[IO.Path]::GetFullPath($taskData)
$env:SWAPY_DATA=$taskData
if($Stop){
    $taskStatePath=Join-Path $taskData 'service.json'
    if(-not(Test-Path -LiteralPath $taskStatePath)){Write-Output 'Swapy is not running.';exit 0}
    if($ForUpgrade){
        $taskState=Get-Content -LiteralPath $taskStatePath -Raw | ConvertFrom-Json
        $taskLive=$null
        try{$taskLive=Get-SwapyJson -Uri ("http://127.0.0.1:"+$taskState.port+'/api/v1/session') -TimeoutSec 3}catch{
            if(Get-Process -Id ([int]$taskState.pid) -ErrorAction SilentlyContinue){throw '无法确认运行中服务的任务状态，未停止服务；请稍后重试。'}
        }
        if($taskLive){
            if(-not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($taskLive.dataDirectory),$taskData)){throw '该端口属于其他Swapy实例，未执行停止。'}
            $taskJobs=Get-SwapyJson -Uri ("http://127.0.0.1:"+$taskState.port+'/api/v1/tasks') -TimeoutSec 10
            if(@($taskJobs | Where-Object {$_.status -notin @('SUCCEEDED','PARTIAL','FAILED','INTERRUPTED','PAUSED')}).Count -gt 0){throw '有任务正在执行，请先暂停扫描或等待任务完成后再安装。'}
        }
    }
    & (Join-Path $PSScriptRoot 'stop.ps1')
    exit 0
}
if(-not(Test-Path -LiteralPath (Join-Path $taskRoot 'runtime/bin/java.exe'))){throw '随包Java运行时缺失，请重新解压完整发行包。'}
& (Join-Path $PSScriptRoot 'start.ps1') -Port $Port -NoBrowser | Out-Null
$taskUrl="http://127.0.0.1:$Port/"
$taskSession=Get-SwapyJson -Uri ($taskUrl+'api/v1/session') -TimeoutSec 10
if(-not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($taskSession.dataDirectory),$taskData)){throw '该端口属于其他Swapy数据目录，未更新词库。'}
$taskCatalog=Get-SwapyJson -Uri ($taskUrl+'api/v1/catalog/summary') -TimeoutSec 30
if($taskCatalog.version -eq 0 -and $taskCatalog.poolCount -eq 0 -and $taskCatalog.sensitiveCount -eq 0){
    $taskLibrary=Join-Path $taskRoot 'library/swapy-basic.csv'
    if(-not(Test-Path -LiteralPath $taskLibrary)){throw '随包词库缺失。'}
    Add-Type -AssemblyName System.Net.Http
    $taskHandler=[Net.Http.HttpClientHandler]::new();$taskHandler.UseProxy=$false
    $taskClient=[Net.Http.HttpClient]::new($taskHandler);$taskClient.Timeout=[TimeSpan]::FromMinutes(3)
    $taskForm=[Net.Http.MultipartFormDataContent]::new()
    $taskStream=[IO.File]::OpenRead($taskLibrary)
    $taskContent=[Net.Http.StreamContent]::new($taskStream)
    $taskForm.Add($taskContent,'file','swapy-basic.csv')
    $taskClient.DefaultRequestHeaders.Add('X-Swapy-Token',[string]$taskSession.token)
    try{
        Write-Output '正在初始化随包词库，请稍候...'
        $taskResponse=$taskClient.PostAsync(($taskUrl+'api/v1/catalog/imports'),$taskForm).GetAwaiter().GetResult()
        try{$taskResponse.EnsureSuccessStatusCode() | Out-Null}finally{$taskResponse.Dispose()}
    }finally{$taskForm.Dispose();$taskClient.Dispose();$taskStream.Dispose()}
}
Write-Output $taskUrl
if(-not $NoBrowser){Start-Process $taskUrl}
