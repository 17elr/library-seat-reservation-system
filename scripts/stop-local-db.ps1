$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $root '.local/mysql.pid'
if (Test-Path -LiteralPath $pidFile) {
    $dbPid = [int](Get-Content -LiteralPath $pidFile)
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$dbPid"
    $runtime = (Get-Content -LiteralPath (Join-Path $root '.local/db-location.txt')).Trim()
    $expected = Join-Path $runtime 'mysql-data'
    if ($proc -and $proc.Name -eq 'mysqld.exe' -and $proc.CommandLine.Replace('\','/').Contains($expected.Replace('\','/'))) {
        $mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
        if ($env:MYSQL_BIN) { $mysqlBin = $env:MYSQL_BIN }
        & (Join-Path $mysqlBin 'mysqladmin.exe') --no-defaults --host=127.0.0.1 --port=13307 --user=root shutdown
    }
}
