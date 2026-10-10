param([switch]$DatabaseOnly, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$localDir = Join-Path $root '.local'
$dbRuntimeDir = Join-Path $env:LOCALAPPDATA 'library-seat-dev-13307'
if (Test-Path -LiteralPath 'D:/Code') { $dbRuntimeDir = 'D:/Code/library-seat-dev-13307' }
if ($env:LIBRARY_DB_RUNTIME) { $dbRuntimeDir = $env:LIBRARY_DB_RUNTIME }
$dataDir = Join-Path $dbRuntimeDir 'mysql-data'
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
if ($env:MYSQL_BIN) { $mysqlBin = $env:MYSQL_BIN }
if (!(Test-Path -LiteralPath (Join-Path $mysqlBin 'mysqld.exe'))) { throw '请设置 MYSQL_BIN 为 MySQL bin 目录。' }
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
New-Item -ItemType Directory -Force -Path $dbRuntimeDir | Out-Null
$dbRuntimeDir | Set-Content (Join-Path $localDir 'db-location.txt')
$listen = Get-NetTCPConnection -LocalPort 13307 -State Listen -ErrorAction SilentlyContinue
if ($listen) {
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$($listen[0].OwningProcess)"
    if (!$existing -or !$existing.CommandLine -or !$existing.CommandLine.Replace('\','/').Contains($dataDir.Replace('\','/'))) {
        throw 'Port 13307 is occupied by another database. Stop it or choose a different project port.'
    }
    $existing.ProcessId | Set-Content (Join-Path $localDir 'mysql.pid')
}
if (!$listen) {
    if (!(Test-Path -LiteralPath (Join-Path $dataDir 'mysql'))) {
        & (Join-Path $mysqlBin 'mysqld.exe') --no-defaults --initialize-insecure --skip-log-bin "--basedir=$(Split-Path $mysqlBin -Parent)" "--datadir=$dataDir"
        if ($LASTEXITCODE -ne 0) { throw '本地数据库初始化失败。' }
    }
    $mysqlArgs = @('--no-defaults', "--basedir=`"$(Split-Path $mysqlBin -Parent)`"", "--datadir=`"$dataDir`"", '--port=13307', '--bind-address=127.0.0.1', '--mysqlx-port=13308', '--mysqlx-bind-address=127.0.0.1', '--skip-log-bin', '--default-time-zone=+08:00', "--log-error=`"$(Join-Path $dbRuntimeDir 'mysql.log')`"")
    $process = Start-Process -FilePath (Join-Path $mysqlBin 'mysqld.exe') -ArgumentList $mysqlArgs -WorkingDirectory $dbRuntimeDir -WindowStyle Hidden -PassThru
    $process.Id | Set-Content (Join-Path $localDir 'mysql.pid')
}
$ready = $false
for ($i=0; $i -lt 40; $i++) {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $mysqlBin 'mysqladmin.exe') --no-defaults --host=127.0.0.1 --port=13307 --user=root ping 2>$null | Out-Null
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 500
}
if (!$ready) { throw "本地数据库未就绪，检查 $dbRuntimeDir/mysql.log。" }
# This dedicated instance has no access to the installed MySQL80 service or its data.
$sql = "CREATE DATABASE IF NOT EXISTS library_seat CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS library_seat_test CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'library'@'localhost' IDENTIFIED BY 'local-library-demo'; GRANT ALL ON library_seat.* TO 'library'@'localhost'; GRANT ALL ON library_seat_test.* TO 'library'@'localhost';"
& (Join-Path $mysqlBin 'mysql.exe') --no-defaults --host=127.0.0.1 --port=13307 --user=root --execute=$sql
if ($LASTEXITCODE -ne 0) { throw '无法配置专用数据库，请确认 13307 为本项目实例。' }
if ($DatabaseOnly) { Write-Host '项目专用 MySQL 已就绪：127.0.0.1:13307'; exit }
$appListen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($appListen) {
    $appProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($appListen[0].OwningProcess)"
    if ($appProcess -and $appProcess.CommandLine -and $appProcess.CommandLine.Replace('\','/').Contains('target/library-seat-0.1.0.jar')) {
        Write-Host 'Application is already running: http://127.0.0.1:8080'
        exit
    }
    throw 'Port 8080 is occupied. Stop the existing application before starting.'
}
$jdk = $env:JAVA_HOME
if (!$jdk -or !(Test-Path (Join-Path $jdk 'bin/javac.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '^jdk-' | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (!$jdk) { throw '需要 JDK 21 或更新版本。设置 JAVA_HOME 后重试。' }
$env:JAVA_HOME = $jdk
if (!$SkipBuild) {
    & mvn -B -ntp -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw '构建失败。' }
}
Write-Host '启动图书馆座位系统：http://127.0.0.1:8080'
& (Join-Path $jdk 'bin/java.exe') -jar (Join-Path $root 'target/library-seat-0.1.0.jar') --spring.profiles.active=local
