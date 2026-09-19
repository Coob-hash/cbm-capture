#Requires -Version 7.3
<#
Installs or updates the CBM App backend into the existing n8n_deploy deployment. App parts only:

  n8n_deploy\app\backend\              API source and migrations   (replaced on every install)
  n8n_deploy\app\docker-compose.app.yml                              (replaced on every install)
  n8n_deploy\app\app.env               secrets and options           (created once, then kept)
  schema cbm_app in cbm_demo          migrations 0*.sql (then oneoff\*.sql if any), all repeatable

Nothing of the workflow stack is written: no workflow export, no workflow SQL, no n8n_deploy root
file. The database is dumped before any migration runs.
#>
param([string]$DeploymentDirectory = 'C:\Users\USER\Desktop\n8n_deploy')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$backendSource = Split-Path -Parent $PSScriptRoot
$target = [IO.Path]::GetFullPath($DeploymentDirectory).TrimEnd('\')
$pg = 'n8n_deploy-cbm-postgres-1'

if (-not (Test-Path -LiteralPath (Join-Path $target 'docker-compose.yml'))) { throw "No workflow deployment at $target." }
& docker exec $pg pg_isready -U cbm_app -d cbm_demo | Out-Null
if ($LASTEXITCODE -ne 0) { throw "$pg is not running; start the workflow stack first." }

$appDir = Join-Path $target 'app'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $target "backups\app-install-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null

# 1. Database backup before any change.
$dumpPath = Join-Path $backup 'cbm_demo.sql'
& docker exec $pg pg_dump -U cbm_app -d cbm_demo --no-owner --no-privileges --file=/tmp/cbm-app-install.sql
if ($LASTEXITCODE -ne 0) { throw 'Database backup failed; nothing was changed.' }
& docker cp "${pg}:/tmp/cbm-app-install.sql" $dumpPath | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath $dumpPath -Pattern 'PostgreSQL database dump complete' -Quiet)) {
    throw 'Database backup is incomplete; nothing was changed.'
}
& docker exec $pg rm -f /tmp/cbm-app-install.sql | Out-Null

# 2. App files: replace app\backend and the compose file; keep app.env. Previous copy goes to the backup.
if (Test-Path -LiteralPath $appDir) { Copy-Item -LiteralPath $appDir -Destination (Join-Path $backup 'app') -Recurse }
$backendTarget = Join-Path $appDir 'backend'
if (Test-Path -LiteralPath $backendTarget) { Remove-Item -LiteralPath $backendTarget -Recurse -Force }
New-Item -ItemType Directory -Force -Path $backendTarget | Out-Null
foreach ($item in @('Dockerfile', 'requirements.lock', 'cbm_api', 'migrations')) {
    Copy-Item -LiteralPath (Join-Path $backendSource $item) -Destination $backendTarget -Recurse
}
Get-ChildItem -LiteralPath $backendTarget -Directory -Recurse -Filter '__pycache__' | Remove-Item -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'docker-compose.app.yml') -Destination $appDir -Force

$envPath = Join-Path $appDir 'app.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $password = [Convert]::ToHexString($bytes).ToLowerInvariant()
    $lines = @(
        '# CBM App API. Created by Install-CbmApp.ps1; kept across installs. Do not commit.'
        "CBM_APP_API_DB_PASSWORD=$password"
        '# Comma-separated Google OAuth client IDs; empty disables Google sign-in.'
        'CBM_APP_GOOGLE_CLIENT_IDS='
        '# 127.0.0.1 = this PC only. 0.0.0.0 = reachable from the LAN (plain HTTP).'
        'CBM_APP_BIND=127.0.0.1'
        'CBM_APP_TRUST_PROXY=0'
    )
    [IO.File]::WriteAllText($envPath, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
$passwordLine = Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^CBM_APP_API_DB_PASSWORD=[0-9a-f]{64}$' } | Select-Object -First 1
if (-not $passwordLine) { throw "$envPath has no valid CBM_APP_API_DB_PASSWORD." }
$password = $passwordLine.Split('=', 2)[1]

# 3. Migrations, in order, each in its own transaction (the files BEGIN/COMMIT themselves).
# oneoff\ holds guarded one-time data moves, when there are any; each is a no-op once done.
$oneoff = Join-Path $backendTarget 'migrations\oneoff'
$migrations = @(Get-ChildItem -LiteralPath (Join-Path $backendTarget 'migrations') -Filter '0*.sql' -File | Sort-Object Name)
if (Test-Path -LiteralPath $oneoff) { $migrations += @(Get-ChildItem -LiteralPath $oneoff -Filter '*.sql' -File | Sort-Object Name) }
foreach ($m in $migrations) {
    & docker cp $m.FullName "${pg}:/tmp/cbm-app-migration.sql" | Out-Null
    & docker exec -e PGOPTIONS='-c client_min_messages=warning' $pg psql -X -q -U cbm_app -d cbm_demo -v ON_ERROR_STOP=1 -f /tmp/cbm-app-migration.sql
    if ($LASTEXITCODE -ne 0) { throw "Migration failed: $($m.Name). Database backup: $dumpPath" }
    Write-Output "Applied $($m.Name)"
}
& docker exec $pg rm -f /tmp/cbm-app-migration.sql | Out-Null

# 4. The API login's password, from app.env. Sent on stdin so it is not in a process listing.
"ALTER ROLE cbm_app_api LOGIN PASSWORD '$password';" |
    & docker exec -i $pg psql -X -q -U cbm_app -d cbm_demo -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw 'Could not set the API login password.' }

# 5. Build and (re)start the API, then wait for it to answer.
& docker compose --project-directory $appDir -f (Join-Path $appDir 'docker-compose.app.yml') --env-file $envPath up -d --build
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed for the app.' }
$health = $null
for ($i = 0; $i -lt 30; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/healthz' -TimeoutSec 3
        if ($health.ok) { break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $health -or -not $health.ok) { throw 'The API did not become healthy. Inspect: docker logs cbm_app-api-1' }
# The internal image service has no published port; ask Docker for its health instead.
$internal = ''
for ($i = 0; $i -lt 30; $i++) {
    $internal = (& docker inspect --format '{{.State.Health.Status}}' cbm_app-api-internal-1 2>$null)
    if ($internal -eq 'healthy') { break }
    Start-Sleep -Seconds 2
}
if ($internal -ne 'healthy') { throw 'The internal image service did not become healthy. Inspect: docker logs cbm_app-api-internal-1' }
Write-Output "CBM App API is running on port 8080. Database backup: $dumpPath"
