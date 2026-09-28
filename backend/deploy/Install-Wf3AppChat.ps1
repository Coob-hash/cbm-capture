#Requires -Version 7.3
<#
Gives the running WF3 its app entry, so facility managers can ask the building assistant from the app
(backend\n8n\wf3_app_chat.py). Run it AFTER Install-CbmApp.ps1, which makes the key in app.env.

  1. exports the running WF3 into n8n_deploy\backups\wf3-app-chat-<stamp>\ (the backup, and the base)
  2. adds the entry (wf3_app_chat.py) and checks the result (test_wf3_app_chat.mjs)
  3. gives n8n the key: the 'CBM App Assistant Key' credential (header X-CBM-App-Key), from app.env
  4. imports WF3 in place (n8n import:workflow). That changes WF3's draft only: the published WF3 keeps
     running as it was until it is published.

Then publish WF3 in the n8n editor, which applies it at once. Or pass -PublishAndRestart: the CLI
publishes it, and n8n is restarted, since a CLI publish takes effect only on a restart (about 30 s in
which no workflow runs; the workflows' sweeps pick up what arrived meanwhile).

No workflow is executed by this script. Safe to re-run once WF3 already has the entry: it only
refreshes the credential.
#>
param(
    [string]$Deployment = 'C:\Users\USER\Desktop\n8n_deploy',
    [switch]$PublishAndRestart
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$wf3 = '658IWGwRtDMsPri7'; $n8n = 'n8n_v1'
$here = Split-Path -Parent $PSScriptRoot
$patch = Join-Path $here 'n8n\wf3_app_chat.py'
$test = Join-Path $here 'n8n\test_wf3_app_chat.mjs'
$node = (Get-Command node -ErrorAction SilentlyContinue)?.Source ?? 'C:\Users\USER\toolchains\node\node-v22.20.0-win-x64\node.exe'

$envPath = Join-Path $Deployment 'app\app.env'
$keyLine = if (Test-Path -LiteralPath $envPath) {
    Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^CBM_APP_ASSISTANT_KEY=[0-9a-f]{64}$' } | Select-Object -First 1
}
if (-not $keyLine) { throw "No CBM_APP_ASSISTANT_KEY in $envPath. Run Install-CbmApp.ps1 first; nothing was changed." }
$key = $keyLine.Split('=', 2)[1]

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $Deployment "backups\wf3-app-chat-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$before = Join-Path $backup 'wf3-before.json'
$after = Join-Path $backup 'wf3-with-app-chat.json'

# 1. Export the running WF3.
docker exec $n8n n8n export:workflow --id=$wf3 --output=/tmp/cbm-wf3-live.json | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Export failed; nothing was changed.' }
docker cp "${n8n}:/tmp/cbm-wf3-live.json" $before | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not copy the export out; nothing was changed.' }
$hasEntry = (Get-Content -LiteralPath $before -Raw) -match '"name":\s*"App Chat"'

# 2. Add the entry and check it (skipped when WF3 already has it).
if (-not $hasEntry) {
    py $patch $before $after
    if ($LASTEXITCODE -ne 0) { throw 'The entry could not be added; nothing was changed.' }
    & $node $test $before $after | Tee-Object -FilePath (Join-Path $backup 'check.log')
    if ($LASTEXITCODE -ne 0) { throw 'WF3 with the entry failed its checks; nothing was changed.' }
}

# 3. The key, as an n8n credential. Written to a temporary file only for the import, then deleted;
#    n8n stores it encrypted with its own key.
$credFile = Join-Path ([IO.Path]::GetTempPath()) "cbm-assistant-key-$stamp.json"
try {
    $credential = @(@{ id = 'cbmAppAssistKey1'; name = 'CBM App Assistant Key'; type = 'httpHeaderAuth'
                       data = @{ name = 'X-CBM-App-Key'; value = $key } })
    [IO.File]::WriteAllText($credFile, (ConvertTo-Json -InputObject $credential -Depth 5), [Text.UTF8Encoding]::new($false))
    docker cp $credFile "${n8n}:/tmp/cbm-assistant-key.json" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the credential in; WF3 was not changed.' }
    docker exec $n8n n8n import:credentials --input=/tmp/cbm-assistant-key.json | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The credential import failed; WF3 was not changed.' }
} finally {
    Remove-Item -LiteralPath $credFile -Force -ErrorAction SilentlyContinue
    docker exec -u 0 $n8n rm -f /tmp/cbm-assistant-key.json 2>$null | Out-Null
}
Write-Output "n8n has the 'CBM App Assistant Key' credential."

if ($hasEntry) {
    docker exec -u 0 $n8n rm -f /tmp/cbm-wf3-live.json | Out-Null
    Write-Output "WF3 already has the app entry; only the key was refreshed. Backup: $before"
    return
}

# 4. Import in place: WF3's draft.
docker cp $after "${n8n}:/tmp/cbm-wf3-app-chat.json" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not copy the workflow in; WF3 was not changed.' }
docker exec $n8n n8n import:workflow --input=/tmp/cbm-wf3-app-chat.json
if ($LASTEXITCODE -ne 0) { throw "Import failed. The workflow as it was: $before" }
docker exec -u 0 $n8n rm -f /tmp/cbm-wf3-live.json /tmp/cbm-wf3-app-chat.json | Out-Null

if (-not $PublishAndRestart) {
    Write-Output "Imported WF3 with the app entry as its draft. Publish WF3 in the n8n editor to apply it. The workflow as it was: $before"
    return
}
docker exec $n8n n8n publish:workflow --id=$wf3
if ($LASTEXITCODE -ne 0) { throw "Publishing failed; the published WF3 is unchanged. Publish it in the editor. Backup: $before" }
docker restart $n8n | Out-Null
$up = $false
for ($i = 0; $i -lt 60 -and -not $up; $i++) {
    Start-Sleep -Seconds 2
    docker exec cbm_app-api-1 python -c "import urllib.request; urllib.request.urlopen('http://n8n:5678/healthz', timeout=3)" 2>$null
    $up = $LASTEXITCODE -eq 0
}
if (-not $up) { throw "n8n did not come back within two minutes. Inspect: docker logs $n8n" }
Write-Output "WF3 is published with the app entry and n8n is running again. The workflow as it was: $before"
