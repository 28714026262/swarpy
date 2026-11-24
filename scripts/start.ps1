param([int]$Port = 4175, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJar = Join-Path $taskRoot 'swapy.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { $taskJar = Join-Path $taskRoot 'backend/target/swapy-0.1.0.jar' }
if (-not (Test-Path -LiteralPath $taskJar)) { throw '请先执行 mvn -f backend/pom.xml verify，或使用已打包发行目录。' }
$taskJava = Join-Path $taskRoot 'runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava)) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { $taskJava = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $taskJava = (Get-Command java -ErrorAction Stop).Source }
}
$taskData = Join-Path $taskRoot 'data'
if ($env:SWAPY_DATA) { $taskData = [IO.Path]::GetFullPath($env:SWAPY_DATA) }
New-Item -ItemType Directory -Path $taskData -Force | Out-Null
$taskUrl = "http://127.0.0.1:$Port/"
$taskReady = $false
try { $taskSession = Invoke-RestMethod -Uri ($taskUrl+'api/v1/session') -TimeoutSec 2; $taskReady = $taskSession.backend -eq 'java-sqlite' } catch {}
if($taskReady -and $taskSession.dataDirectory -and -not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($taskSession.dataDirectory),[IO.Path]::GetFullPath($taskData))){throw '该端口正在运行另一个Swapy数据实例，请更换Port。'}
if (-not $taskReady) {
    $taskArguments = @('-Xmx1536m','-XX:ActiveProcessorCount=4','-Dfile.encoding=UTF-8','-jar',('"'+$taskJar+'"'),"--server.port=$Port",('"--swapy.data='+$taskData+'"'))
    $taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArguments -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskData 'service.log') -RedirectStandardError (Join-Path $taskData 'service-error.log') -PassThru
    @{pid=$taskProcess.Id;jar=$taskJar;data=$taskData;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskData 'service.json') -Encoding UTF8
    for ($taskAttempt=0; $taskAttempt -lt 60; $taskAttempt++) {
        if ($taskProcess.HasExited) { throw "服务启动失败，请查看 $taskData/service-error.log" }
        try { $taskSession=Invoke-RestMethod -Uri ($taskUrl+'api/v1/session') -TimeoutSec 1; if ($taskSession.backend -eq 'java-sqlite') { $taskReady=$true;break } } catch {}
        Start-Sleep -Milliseconds 500
    }
}
if (-not $taskReady) { throw "服务尚未就绪，请查看 $taskData/service.log" }
if($taskSession.pid){@{pid=[int]$taskSession.pid;jar=$taskJar;data=$taskData;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskData 'service.json') -Encoding UTF8}
Write-Output $taskUrl
if (-not $NoBrowser) { Start-Process $taskUrl }
