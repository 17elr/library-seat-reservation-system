param([string]$Database = 'library_seat', [string]$BackupFile)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
if (!$BackupFile) { $BackupFile = ".local/backups/pre-rollback-$(Get-Date -Format yyyyMMdd-HHmmss).sql" }
# Export the latest history before switching code. Added columns remain compatible with the old mapper.
python scripts/db-tool.py backup --database $Database --file $BackupFile
if ($LASTEXITCODE -ne 0) { throw '备份失败，停止回退。' }
Write-Host '最新数据已备份。停止新版应用，然后从旧版 tag 的独立工作目录构建运行。'
Write-Host '不要恢复旧备份覆盖现有库；保留新增列和 flyway 历史即可兼容旧代码。'
Write-Host '旧版本地 profile 默认连接旧端口；必须显式指定本次 DB_URL、DB_USER、DB_PASSWORD。'
