#Requires -Version 7.3
<#
The operator's decision on account requests.

Two kinds of sign-up wait here:
  - FM: a facility manager can authorize work and close tickets, so the account stays pending
    until approved.
  - TECHNICIAN, with an email that already names a technician on the dispatch list: the account
    would take over that technician's row - their assigned jobs and history - and a password
    sign-up proves nothing about owning the address. Check with the technician that it is them,
    then approve: the account is linked to that row, skills kept. (A new address is a new
    technician and never waits.)
Users, and technicians with a new address, join freely.

  .\Approve-CbmFm.ps1                                     list pending requests
  .\Approve-CbmFm.ps1 -Email fm@example.com -Approve      approve
  .\Approve-CbmFm.ps1 -Email x@example.com -Reject -Reason "Not the facility manager of this site"

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
    # For a technician, the dispatch row the account would take over: check its name with them.
    Invoke-Sql @"
SELECT u.email, m.role, s.name AS site, m.requested_at::timestamp(0) AS requested,
       CASE WHEN m.role='TECHNICIAN' THEN (SELECT '#'||t.id||' '||t.full_name FROM public.technicians t
                                           WHERE lower(t.email)=u.email ORDER BY t.id LIMIT 1) END AS takes_over
FROM cbm_app.memberships m JOIN cbm_app.users u ON u.id=m.user_id JOIN cbm_app.sites s ON s.id=m.site_id
WHERE m.role IN ('FM','TECHNICIAN') AND m.status='PENDING' ORDER BY m.requested_at;
"@
    return
}

$decision = if ($Approve) { 'APPROVE' } else { 'REJECT' }
Invoke-Sql @"
SELECT cbm_app.decide_membership(jsonb_build_object(
  'membership_id',(SELECT m.id FROM cbm_app.memberships m JOIN cbm_app.users u ON u.id=m.user_id
                   WHERE u.email=lower(:'email') AND m.role IN ('FM','TECHNICIAN') AND m.status='PENDING'
                   ORDER BY m.requested_at LIMIT 1),
  'decision',:'decision','reason',nullif(:'reason',''),'operator',true))->>'status' AS result;
"@ @{ email = $Email.Trim(); decision = $decision; reason = "$Reason" }
