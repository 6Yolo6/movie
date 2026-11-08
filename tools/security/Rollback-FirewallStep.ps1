[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AttemptDirectory,[switch]$Watch)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'firewall_health.ps1')
$root=[IO.Path]::GetFullPath('G:\gying-tools\security-20260925\firewall-attempts')
$attempt=(Resolve-Path -LiteralPath $AttemptDirectory).Path
if(-not $attempt.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Rollback directory scope guard failed' }
$signal=Join-Path $attempt 'active.json'
if($Watch) {
    $deadline=(Get-Date).AddSeconds(180)
    while((Test-Path -LiteralPath $signal) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    if(-not (Test-Path -LiteralPath $signal)) { return }
}
$mutex=[Threading.Mutex]::new($false,('Local\GYingFirewall-'+(Split-Path -Leaf $attempt)))
$locked=$false
try {
    try { $locked=$mutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $locked=$true }
    if(-not $locked) { throw 'Rollback lock timeout' }
    if($Watch -and -not (Test-Path -LiteralPath $signal)) { return }
    $before=Get-Content -LiteralPath (Join-Path $attempt 'before.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $name='GYing-Hardening-20260925-PhysicalSensitiveTCP'
    if($before.rule_name -ne $name -or $before.rule_preexisted) { throw 'Rollback rule scope guard failed' }
    if(Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -PolicyStore PersistentStore -Name $name }
    if(Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue) { throw 'Rollback rule removal verification failed' }
    Set-NetFirewallProfile -PolicyStore PersistentStore -Name Public -Enabled ([bool]$before.public_enabled) -DefaultInboundAction $before.default_inbound
    $current=Get-NetFirewallProfile -PolicyStore PersistentStore -Name Public
    if([bool]$current.Enabled -ne [bool]$before.public_enabled -or [string]$current.DefaultInboundAction -ne $before.default_inbound) { throw 'Rollback profile verification failed' }
    Write-GyingJson -Path (Join-Path $attempt 'rollback.json') -Value ([ordered]@{completed_at=(Get-Date).ToString('o');status='rolled_back';watchdog=[bool]$Watch;rule_name=$name;public_enabled=[bool]$current.Enabled})
    if(Test-Path -LiteralPath $signal) { Remove-Item -LiteralPath $signal }
} catch {
    Write-GyingJson -Path (Join-Path $attempt 'rollback-error.json') -Value @{completed_at=(Get-Date).ToString('o');status='rollback_failed';error_class=$_.Exception.GetType().Name}
    throw
} finally { if($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
