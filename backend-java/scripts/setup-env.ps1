$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$envFile = Join-Path $projectRoot '.env.java'
if (Test-Path -LiteralPath $envFile) { Write-Host '.env.java 已存在，保持原值。'; exit 0 }
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
@("JAVA_DB_USER=sellerpilot", "JAVA_DB_PASSWORD=$(New-Secret)", "JAVA_MYSQL_ROOT_PASSWORD=$(New-Secret)", "JAVA_JWT_SECRET=$(New-Secret)", 'JAVA_CACHE_ENABLED=true') | Set-Content -LiteralPath $envFile -Encoding utf8
Write-Host '已生成 .env.java（不输出密钥，不覆盖已有配置）。'
