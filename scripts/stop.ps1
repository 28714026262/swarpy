$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskData=Join-Path $taskRoot 'data'
if($env:SWAPY_DATA){$taskData=[IO.Path]::GetFullPath($env:SWAPY_DATA)}
$taskState=Get-Content -LiteralPath (Join-Path $taskData 'service.json') -Raw | ConvertFrom-Json
$taskCandidates=Get-CimInstance Win32_Process
$taskProcess=$taskCandidates | Where-Object {$_.ProcessId -eq $taskState.pid} | Select-Object -First 1
if($null -eq $taskProcess){Write-Output '服务已停止';exit 0}
$taskCommand=$taskProcess.CommandLine.Replace('\\','\')
if($taskCommand.IndexOf($taskState.jar,[StringComparison]::OrdinalIgnoreCase) -lt 0 -or $taskCommand.IndexOf($taskState.data,[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'PID与当前Swapy实例不符，未停止任何进程。'}
$taskDescendants=[Collections.Generic.List[int]]::new()
$taskParents=@([int]$taskState.pid)
while($taskParents.Count -gt 0){$taskChildren=@($taskCandidates | Where-Object {$taskParents -contains [int]$_.ParentProcessId});$taskParents=@($taskChildren | ForEach-Object {[int]$_.ProcessId});foreach($taskChild in $taskParents){$taskDescendants.Add($taskChild)}}
for($taskIndex=$taskDescendants.Count-1;$taskIndex -ge 0;$taskIndex--){Stop-Process -Id $taskDescendants[$taskIndex] -ErrorAction SilentlyContinue}
Stop-Process -Id $taskState.pid -ErrorAction SilentlyContinue
Wait-Process -Id $taskState.pid -Timeout 15 -ErrorAction SilentlyContinue
Write-Output '服务已停止；扫描断点将在下次启动时读取。'
