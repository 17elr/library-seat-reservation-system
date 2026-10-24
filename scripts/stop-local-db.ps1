Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
docker compose stop mysql redis
if ($LASTEXITCODE) { throw '停止 Compose 服务失败。原生安装的数据库不由本脚本管理。' }
