$ErrorActionPreference = 'Stop'
$listen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listen) {
    $appProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listen[0].OwningProcess)"
    if ($appProcess -and $appProcess.Name -eq 'java.exe' -and $appProcess.CommandLine -and $appProcess.CommandLine.Replace('\','/').Contains('target/library-seat-0.1.0.jar')) {
        Stop-Process -Id $appProcess.ProcessId
        Write-Host 'Library seat application stopped.'
    } else {
        throw 'The listener on 8080 is not this application. No process was stopped.'
    }
}
