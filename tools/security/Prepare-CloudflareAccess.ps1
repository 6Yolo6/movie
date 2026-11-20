[CmdletBinding()]
param([switch]$StoreToken)
$ErrorActionPreference='Stop'
$base='G:\gying-tools\security-20260926'
$planPath=Join-Path $base 'cloudflare-access-plan.json'
$tokenPath='G:\gying-secrets\cloudflare-access-token.dpapi'
$python='E:\python3.10.6\python.exe'
$checker=Join-Path $base 'cloudflare_access.py'
if(-not (Test-Path -LiteralPath $planPath)) { throw 'Prepared Access plan is missing' }
Write-Output 'Scope: local token storage and read-only Cloudflare account checks only. No application, policy, DNS, Tunnel or service changes.'
if(-not $StoreToken) {
    & $python -X utf8 $checker --plan $planPath
    if($LASTEXITCODE -ne 0) { throw 'Local plan validation failed' }
    Write-Output 'DRY RUN. After creating the scoped API token, use -StoreToken and enter it in the hidden local prompt. Never paste it into chat.'
    return
}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
$folder=Split-Path -Parent $tokenPath
$acl=Get-Acl -LiteralPath $folder
if(-not $acl.AreAccessRulesProtected) { throw 'The secret directory must have a protected ACL' }
foreach($rule in $acl.Access) {
    $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
    if($rule.AccessControlType -eq 'Allow' -and $sid -notin $allowed) { throw 'Unexpected access on the private secret directory' }
}
if(Test-Path -LiteralPath $tokenPath) {
    Write-Output 'An existing DPAPI token file was found. It will not be overwritten; running read-only preflight with that token.'
} else {
    $secure=Read-Host 'Enter the scoped Cloudflare API token locally (hidden)' -AsSecureString
    try {
        if($secure.Length -lt 20) { throw 'Token was empty or too short; nothing saved' }
        $encrypted=ConvertFrom-SecureString -SecureString $secure
        $bytes=[Text.UTF8Encoding]::new($false).GetBytes($encrypted)
        $stream=[IO.File]::Open($tokenPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
        Write-Output ('Stored with Windows DPAPI CurrentUser protection: '+$tokenPath)
    } finally { if($secure) { $secure.Dispose() } }
}
$report=Join-Path $base ('access-preflight-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)+'.json')
& $python -X utf8 $checker --plan $planPath --preflight --report $report
$code=$LASTEXITCODE
Write-Output ('Read-only report: '+$report)
if($code -ne 0) { throw 'Account preflight needs attention. Send only the sanitized result, never the token.' }
Write-Output 'Account preflight passed. Nothing has been applied; return to the maintenance task for the reviewed change.'
