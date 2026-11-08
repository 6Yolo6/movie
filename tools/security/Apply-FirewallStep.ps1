[CmdletBinding()]
param([switch]$Apply,[switch]$CheckOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'firewall_health.ps1')
if($Apply -and $CheckOnly) { throw 'Use either -Apply or -CheckOnly, not both' }
$base='G:\gying-tools\security-20260925'
$attempts=Join-Path $base 'firewall-attempts'
$name='GYing-Hardening-20260925-PhysicalSensitiveTCP'
$ports=@(3306,33060,5005,8880,9000,9001)
$physical=@(Get-NetAdapter -Physical | Select-Object -ExpandProperty Name)
if(-not $physical.Count) { throw 'No physical adapters found' }
Write-Output ('Scope: enable Public profile; block sensitive TCP inbound only on physical interfaces: '+($physical -join ', '))
Write-Output ('Ports: '+($ports -join ', ')+'. No outbound policy or Docker/database changes. TLS checks remain enabled; probes use IPv4.')
if(-not $Apply -and -not $CheckOnly) { Write-Output 'DRY RUN. Use -CheckOnly for read-only health checks, or -Apply from administrator PowerShell.'; return }
if($Apply) {
    $principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator token required' }
    if(Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue) { throw 'Target rule already exists; review instead of overwriting' }
    if(Test-Path -LiteralPath (Join-Path $base 'firewall-step-active.json')) { throw 'Legacy firewall operation is active; review before retrying' }
    if(Test-Path -LiteralPath $attempts) {
        foreach($dir in (Get-ChildItem -LiteralPath $attempts -Directory)) {
            if(Test-Path -LiteralPath (Join-Path $dir.FullName 'active.json')) { throw 'Another firewall attempt is active; review its result before retrying' }
        }
    }
}
$null=New-Item -ItemType Directory -Path $attempts -Force
$id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$attempt=(New-Item -ItemType Directory -Path (Join-Path $attempts $id)).FullName
$resultPath=Join-Path $attempt 'result.json'
$signal=Join-Path $attempt 'active.json'
$outcome=[ordered]@{started_at=(Get-Date).ToString('o');mode=if($Apply){'apply'}else{'check_only'};status='preflight';attempt_directory=$attempt;rule_name=$name;physical_interfaces=$physical;ports=$ports;before=@();after=@();firewall_changed=$false;production_containers_recreated=$false;external_reachability_verified=$false;ipv6_health_verified=$false}
$watcher=$null; $mutex=$null; $locked=$false; $applyMutex=$null; $applyLocked=$false
try {
    if($Apply) {
        # Serialize the complete apply flow, including the preflight/active-marker gap.
        $applyMutex=[Threading.Mutex]::new($false,'Global\GYingFirewall-PhysicalSensitiveTCP-Apply')
        try { $applyLocked=$applyMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $applyLocked=$true }
        if(-not $applyLocked) { throw 'Another firewall apply process is running' }
        if(Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue) { throw 'Target rule already exists; review instead of overwriting' }
        if(Test-Path -LiteralPath (Join-Path $base 'firewall-step-active.json')) { throw 'Legacy firewall operation is active' }
        foreach($dir in (Get-ChildItem -LiteralPath $attempts -Directory)) {
            if(Test-Path -LiteralPath (Join-Path $dir.FullName 'active.json')) { throw 'Another firewall attempt is active' }
        }
        $backup=Get-Content -LiteralPath (Join-Path $base 'full-backup-result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if($backup.state -ne 'backup_complete' -or $backup.failures.Count -ne 0 -or $backup.resumed_ids.Count -ne 6) { throw 'Backup/resume gate not satisfied' }
        $db=Get-Content -LiteralPath (Join-Path $backup.backup_directory 'database-restore-verification.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $media=Get-Content -LiteralPath (Join-Path $backup.backup_directory 'minio-restore-verification.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $art=Get-Content -LiteralPath (Join-Path $backup.backup_directory 'artifact-verification.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if(-not $db.database_restore_passed -or -not $db.all_table_checksums_match -or -not $db.schema_object_coverage_matches -or -not $db.isolated_server_stopped) { throw 'Database restore gate failed' }
        if(-not $media.public_image_sha256_match -or -not $media.private_metadata_rejected -or -not $media.test_container_removed) { throw 'MinIO restore gate failed' }
        if(-not $art.backup_artifacts_verified -or $art.artifacts.Count -ne $backup.encrypted_artifacts) { throw 'Artifact verification gate failed' }
        $null=& 'E:\python3.10.6\python.exe' -X utf8 'D:\gying-movie\movie\tools\security\verify_backup.py' $backup.backup_directory
        if($LASTEXITCODE -ne 0) { throw 'Backup integrity recheck failed' }
    }
    $outcome.before=@(Test-GyingHealthEndpoints)
    Write-GyingJson -Path (Join-Path $attempt 'baseline.json') -Value $outcome.before
    $failed=@($outcome.before | Where-Object { -not $_.passed })
    if($failed.Count) {
        $outcome.status='baseline_failed'
        foreach($c in $failed) { Write-Output ('FAIL '+$c.url+' expected='+$c.expected+' observed='+$c.observed+' curl_exit='+$c.exit+' error='+$c.error) }
        throw ('Baseline health failed; no firewall changes made. Details: '+(Join-Path $attempt 'baseline.json'))
    }
    Write-Output ('Baseline health PASS: '+$outcome.before.Count+' checks')
    if($CheckOnly) { $outcome.status='healthy'; return }
    # A failed baseline creates no before snapshot and no active marker; older evidence is never overwritten.
    $public=Get-NetFirewallProfile -PolicyStore PersistentStore -Name Public
    $export=Join-Path $attempt 'firewall-before.wfw'
    $null=& netsh.exe advfirewall export $export
    if($LASTEXITCODE -ne 0) { throw 'Fresh firewall backup export failed' }
    $before=[ordered]@{captured_at=(Get-Date).ToString('o');rule_name=$name;rule_preexisted=$false;public_enabled=[bool]$public.Enabled;default_inbound=[string]$public.DefaultInboundAction;default_outbound=[string]$public.DefaultOutboundAction;physical_interfaces=$physical;ports=$ports;export=$export;backup_directory=$backup.backup_directory}
    Write-GyingJson -Path (Join-Path $attempt 'before.json') -Value $before
    $mutex=[Threading.Mutex]::new($false,('Local\GYingFirewall-'+$id))
    Write-GyingJson -Path $signal -Value @{started_at=(Get-Date).ToString('o')}
    $watchArgs=@('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'Rollback-FirewallStep.ps1')+'"'),'-AttemptDirectory',('"'+$attempt+'"'),'-Watch')
    $watcher=Start-Process -FilePath 'powershell.exe' -ArgumentList $watchArgs -WindowStyle Hidden -PassThru
    if($watcher.HasExited) { throw 'Rollback watchdog failed to start' }
    # Never allow a delayed writer to reapply policy after the watchdog has rolled back.
    try { $locked=$mutex.WaitOne(10000) } catch [Threading.AbandonedMutexException] { $locked=$true }
    if(-not $locked -or -not (Test-Path -LiteralPath $signal) -or (Test-Path -LiteralPath (Join-Path $attempt 'rollback.json')) -or (Test-Path -LiteralPath (Join-Path $attempt 'rollback-error.json'))) { throw 'Watchdog rollback or lock conflict before changes' }
    $outcome.status='changing'
    $null=New-NetFirewallRule -PolicyStore PersistentStore -Name $name -DisplayName 'GYing sensitive TCP - physical inbound deny' -Group 'GYing Security Hardening' -Direction Inbound -Action Block -Enabled True -Profile Any -InterfaceAlias $physical -Protocol TCP -LocalPort $ports
    $outcome.firewall_changed=$true
    Set-NetFirewallProfile -PolicyStore PersistentStore -Name Public -Enabled True -DefaultInboundAction Block
    $mutex.ReleaseMutex(); $locked=$false
    Start-Sleep -Seconds 3
    $outcome.after=@(Test-GyingHealthEndpoints)
    Write-GyingJson -Path (Join-Path $attempt 'after.json') -Value $outcome.after
    if(@($outcome.after | Where-Object { -not $_.passed }).Count) { throw 'Post-change health failed' }
    $current=Get-NetFirewallProfile -PolicyStore ActiveStore -Name Public
    $rule=Get-NetFirewallRule -PolicyStore ActiveStore -Name $name
    $filter=$rule | Get-NetFirewallPortFilter
    $interfaces=$rule | Get-NetFirewallInterfaceFilter
    $expectedPorts=($ports | ForEach-Object { [string]$_ } | Sort-Object) -join ','
    $actualPorts=(@($filter.LocalPort) | Sort-Object) -join ','
    if(-not [bool]$current.Enabled -or [string]$current.DefaultInboundAction -ne 'Block' -or [string]$rule.Enabled -ne 'True' -or [string]$rule.Action -ne 'Block' -or [string]$rule.Direction -ne 'Inbound' -or $actualPorts -ne $expectedPorts) { throw 'Effective policy verification failed' }
    $actualInterfaces=(@($interfaces.InterfaceAlias) | Sort-Object) -join ','
    $expectedInterfaces=($physical | Sort-Object) -join ','
    if($actualInterfaces -ne $expectedInterfaces -or [string]$filter.Protocol -notin @('TCP','6') -or [string]$rule.Profile -ne 'Any') { throw 'Effective interface/protocol/profile scope mismatch' }
    $persistAfter=Get-NetFirewallProfile -PolicyStore PersistentStore -Name Public
    if([string]$persistAfter.DefaultOutboundAction -ne $before.default_outbound) { throw 'Outbound policy changed unexpectedly' }
    try { $locked=$mutex.WaitOne(10000) } catch [Threading.AbandonedMutexException] { $locked=$true }
    if(-not $locked -or (Test-Path -LiteralPath (Join-Path $attempt 'rollback.json')) -or (Test-Path -LiteralPath (Join-Path $attempt 'rollback-error.json'))) { throw 'Watchdog rollback or lock conflict' }
    $outcome.status='applied'; $outcome.public_enabled=$true; $outcome.default_inbound='Block'; $outcome.outbound_unchanged=$true
    Write-GyingJson -Path $resultPath -Value $outcome
    Remove-Item -LiteralPath $signal
    $mutex.ReleaseMutex(); $locked=$false
} catch {
    $outcome.error_class=$_.Exception.GetType().Name
    if($locked) { $mutex.ReleaseMutex(); $locked=$false }
    if(Test-Path -LiteralPath $signal) {
        try {
            & (Join-Path $PSScriptRoot 'Rollback-FirewallStep.ps1') -AttemptDirectory $attempt
            $outcome.status='rolled_back'; $outcome.firewall_changed=$false
        } catch { $outcome.status='rollback_failed' }
    } elseif(Test-Path -LiteralPath (Join-Path $attempt 'rollback.json')) {
        $outcome.status='rolled_back'; $outcome.firewall_changed=$false
    } elseif($outcome.status -eq 'preflight') { $outcome.status='preflight_failed' }
    throw
} finally {
    $outcome.completed_at=(Get-Date).ToString('o')
    Write-GyingJson -Path $resultPath -Value $outcome
    Write-Output ('Result: '+$outcome.status+'; report: '+$resultPath)
    if($watcher -and -not (Test-Path -LiteralPath $signal)) { $watcher.WaitForExit(5000) | Out-Null }
    if($mutex) { $mutex.Dispose() }
    if($applyLocked) { $applyMutex.ReleaseMutex() }
    if($applyMutex) { $applyMutex.Dispose() }
}
