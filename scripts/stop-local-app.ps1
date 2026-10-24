$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $projectRoot '.local/app.pid'
if (!(Test-Path $pidFile)) { throw '没有本目录启动的 PID 记录。' }
$appPid = [int](Get-Content $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$appPid"
if (!$process) { Write-Host '应用已经停止。'; exit }
$jar = Join-Path $projectRoot 'target/library-seat-0.1.0.jar'
if (!$process.CommandLine.Contains($jar)) { throw 'PID 已被其他进程复用，停止操作已取消。' }
Stop-Process -Id $appPid
Write-Host '应用已停止；数据库与历史数据保留。'
