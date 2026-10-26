$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$envFile = Join-Path $projectRoot '.env.java'
if (!(Test-Path -LiteralPath $envFile)) { throw '先运行 backend-java/scripts/setup-env.ps1' }
Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { Set-Item ('Env:' + $matches[1]) $matches[2] }
}
$env:MAVEN_SKIP_RC = 'true'
$env:TEMP = Join-Path $projectRoot '.tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
Set-Location -LiteralPath $projectRoot
