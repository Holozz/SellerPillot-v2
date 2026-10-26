param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Stage,
    [int]$Rate = 200,
    [string]$Duration = '5m',
    [int]$PreallocatedVUs = 300
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $projectRoot '.data/java-bench'
$reports = Join-Path $projectRoot 'docs/verification/java'
# Only these synthetic fixture files are exposed to k6, never .env.java or source root.
docker run --rm `
    --mount "type=bind,source=$PSScriptRoot/java-query-load.js,target=/test.js,readonly" `
    --mount "type=bind,source=$fixture/credentials.json,target=/credentials.json,readonly" `
    --mount "type=bind,source=$fixture/query.json,target=/query.json,readonly" `
    --mount "type=bind,source=$reports,target=/reports" `
    -e API_BASE=http://host.docker.internal:8081 `
    -e CREDENTIALS_FILE=/credentials.json -e QUERY_FILE=/query.json `
    -e "RATE=$Rate" -e "DURATION=$Duration" `
    -e "PREALLOCATED_VUS=$PreallocatedVUs" -e "MAX_VUS=$PreallocatedVUs" `
    grafana/k6:1.3.0 run --quiet "--summary-export=/reports/$Stage-load.json" /test.js
exit $LASTEXITCODE
