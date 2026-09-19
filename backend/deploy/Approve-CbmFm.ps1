#Requires -Version 7.3
<#
The operator's decision on facility-manager requests. Users and technicians join freely; an FM can
authorize work and close tickets, so an FM account stays pending until approved here.

  .\Approve-CbmFm.ps1                                   list pending FM requests
  .\Approve-CbmFm.ps1 -Email fm@example.com -Approve    approve
  .\Approve-CbmFm.ps1 -Email fm@example.com -Reject -Reason "Not the facility manager of this site"

Runs cbm_app.decide_membership() as the database owner (the operator path, which the API's own
login can never use). Values are passed as psql variables, never pasted into the SQL text.
#>
[CmdletBinding(DefaultParameterSetName = 'List')]
param(
    [Parameter(ParameterSetName = 'Approve', Mandatory)][Parameter(ParameterSetName = 'Reject', Mandatory)][string]$Email,
    [Parameter(ParameterSetName = 'Approve', Mandatory)][switch]$Approve,
    [Parameter(ParameterSetName = 'Reject', Mandatory)][switch]$Reject,
    [Parameter(ParameterSetName = 'Reject', Mandatory)][string]$Reason
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pg = 'n8n_deploy-cbm-postgres-1'

function Invoke-Sql([string]$sql, [hashtable]$vars = @{}) {
    $a = @('exec', '-i', $pg, 'psql', '-X', '-q', '-U', 'cbm_app', '-d', 'cbm_demo', '-v', 'ON_ERROR_STOP=1', '-P', 'footer=off')
    foreach ($k in $vars.Keys) { $a += @('-v', "$k=$($vars[$k])") }
    $out = $sql | & docker @a
    if ($LASTEXITCODE -ne 0) { throw 'The database call failed.' }
    $out
}

if ($PSCmdlet.ParameterSetName -eq 'List') {
    Invoke-Sql @"
SELECT u.email, s.name AS site, m.requested_at::timestamp(0) AS requested
FROM cbm_app.memberships m JOIN cbm_app.users u ON u.id=m.user_id JOIN cbm_app.sites s ON s.id=m.site_id
WHERE m.role='FM' AND m.status='PENDING' ORDER BY m.requested_at;
"@
    return
}

$decision = if ($Approve) { 'APPROVE' } else { 'REJECT' }
Invoke-Sql @"
SELECT cbm_app.decide_membership(jsonb_build_object(
  'membership_id',(SELECT m.id FROM cbm_app.memberships m JOIN cbm_app.users u ON u.id=m.user_id
                   WHERE u.email=lower(:'email') AND m.role='FM' AND m.status='PENDING'),
  'decision',:'decision','reason',nullif(:'reason',''),'operator',true))->>'status' AS result;
"@ @{ email = $Email.Trim(); decision = $decision; reason = "$Reason" }
