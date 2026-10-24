param([switch]$Demo, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if (Test-Path .env) {
    foreach ($line in Get-Content .env) {
        if ($line -match '^([A-Z_]+)=(.*)$' -and ![Environment]::GetEnvironmentVariable($Matches[1])) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}
if (Get-NetTCPConnection -LocalPort 18082 -State Listen -ErrorAction SilentlyContinue) { throw '18082 已被占用，请先停止现有应用。' }
if (!$SkipBuild) {
    npm ci --prefix web
    if ($LASTEXITCODE) { throw '前端依赖安装失败。' }
    npm run build --prefix web
    if ($LASTEXITCODE) { throw '前端构建失败。' }
    mvn -B -ntp -DskipTests package
    if ($LASTEXITCODE) { throw '后端构建失败。' }
}
if (!$env:JAVA_HOME) { throw '请设置 JAVA_HOME 为 JDK21 目录。' }
$jar = Join-Path $projectRoot 'target/library-seat-0.1.0.jar'
if (!(Test-Path $jar)) { throw '缺少打包结果，请去掉 -SkipBuild 重新运行。' }
New-Item -ItemType Directory -Force .local | Out-Null
$arguments = @('-jar', "`"$jar`"")
if ($Demo) { $arguments += '--spring.profiles.active=demo' }
$process = Start-Process (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput .local/app.log -RedirectStandardError .local/app-error.log
$process.Id | Set-Content .local/app.pid
Write-Host "应用已启动，PID=$($process.Id)。访问 http://127.0.0.1:18082，日志 .local/app.log"
Write-Host '默认不生成演示账号；仅对专用演示库使用 -Demo。'
