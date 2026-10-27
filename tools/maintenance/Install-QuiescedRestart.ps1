[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ConfigFile,
    [Parameter(Mandatory=$true)][string]$CheckpointFile,
    [ValidateRange(10,30)][int]$DelayMinutes=12,
    [string]$StartAt,
    [switch]$Apply
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding=[Text.UTF8Encoding]::new()
. (Join-Path $PSScriptRoot 'TaskVerification.ps1')
function Read-QuiescenceJson([string]$Path) {
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Write-QuiescenceJson([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
}
function Invoke-QuiescencePython([string]$Python,[string[]]$Arguments,[string]$Receipt) {
    $raw=@(& $Python @Arguments)
    $code=$LASTEXITCODE
    if ($Receipt) { Write-QuiescenceJson $Receipt ([ordered]@{exitCode=$code;output=($raw -join [Environment]::NewLine)}) }
    if ($code -ne 0) { throw ('Maintenance helper failed with exit code '+$code+'.') }
    try { ($raw -join [Environment]::NewLine) | ConvertFrom-Json }
    catch { throw 'Maintenance helper returned an invalid JSON receipt.' }
}
function Confirm-MaintenanceTasks($Config,[string]$ConfigPath,[string]$Runtime) {
    $owner=$Config.host+'\ASUS'
    $sid=[Security.Principal.NTAccount]::new($owner).Translate([Security.Principal.SecurityIdentifier]).Value
    $directory=Split-Path -Parent $Runtime
    foreach ($name in @('GYing Weekly Graceful Restart','GYing Maintenance Recovery')) {
        $t=Get-ScheduledTask -TaskName $name -TaskPath '\'
        if ($t.State -eq 'Running' -or -not $t.Settings.Enabled -or $t.Principal.UserId -notin @($owner,$sid,'ASUS') -or
            $t.Principal.RunLevel -ne 'Highest' -or $t.Principal.LogonType -ne 'Interactive' -or
            $t.Actions.Count -ne 1 -or $t.Actions[0].Execute -ne $Config.python -or
            $t.Actions[0].WorkingDirectory -ne $directory -or $t.Settings.AllowHardTerminate -or
            [Xml.XmlConvert]::ToTimeSpan($t.Settings.ExecutionTimeLimit) -ne [TimeSpan]::Zero -or
            [string]$t.Settings.MultipleInstances -ne 'IgnoreNew') { throw 'Existing maintenance task binding/settings changed.' }
        $mode=if ($name -eq 'GYing Maintenance Recovery') {'--recover'} else {'--execute'}
        $expected='-X utf8 "'+$Runtime+'" --config "'+$ConfigPath+'" '+$mode
        if ($t.Actions[0].Arguments -cne $expected) { throw 'Existing maintenance action changed.' }
        [xml]$xml=Export-ScheduledTask -TaskName $name -TaskPath '\'
        $triggers=@($xml.Task.Triggers.ChildNodes | Where-Object {$_.NodeType -eq 'Element'})
        if ($triggers.Count -ne 1) { throw 'Unexpected maintenance trigger count.' }
        if ($mode -eq '--recover') {
            if ($triggers[0].LocalName -ne 'LogonTrigger' -or
                $triggers[0].UserId -notin @($owner,$sid,'ASUS') -or
                [Xml.XmlConvert]::ToTimeSpan($triggers[0].Delay) -ne [TimeSpan]::FromSeconds(90) -or
                -not $t.Settings.StartWhenAvailable -or $t.Settings.RestartCount -ne 10 -or
                [Xml.XmlConvert]::ToTimeSpan($t.Settings.RestartInterval) -ne [TimeSpan]::FromMinutes(2)) {
                throw 'Existing ASUS login recovery trigger/settings changed.'
            }
        } else {
            if ($triggers[0].LocalName -ne 'CalendarTrigger' -or
                [DateTimeOffset]::Parse($triggers[0].StartBoundary) -ne [DateTimeOffset]::Parse($Config.firstRun) -or
                $triggers[0].ScheduleByWeek.WeeksInterval -ne '1' -or
                @($triggers[0].ScheduleByWeek.DaysOfWeek.ChildNodes | Where-Object {$_.NodeType -eq 'Element'}).Count -ne 1 -or
                $triggers[0].ScheduleByWeek.DaysOfWeek.FirstChild.LocalName -ne 'Sunday' -or
                $t.Settings.StartWhenAvailable) { throw 'Existing weekly schedule changed.' }
        }
    }
}
function Confirm-QuiescenceReadiness($Report) {
    $property=$Report.PSObject.Properties['pendingAttempts']
    if ($Report.mode -ne 'read-only' -or -not $property -or
        ($property.Value -isnot [int] -and $property.Value -isnot [long]) -or $property.Value -ne 0) {
        throw 'Zero pending maintenance attempts required before replacing helper.'
    }
}
function Get-BoundRestartPermit($Config,[string]$ConfigPath,[string]$Runtime,[string]$TaskName,[DateTimeOffset]$Start) {
    $t=Get-ScheduledTask -TaskName $TaskName -TaskPath '\' -ErrorAction SilentlyContinue
    if (-not $t) { return $null }
    $owner=$Config.host+'\ASUS'
    $sid=[Security.Principal.NTAccount]::new($owner).Translate([Security.Principal.SecurityIdentifier]).Value
    if ($t.Actions.Count -ne 1 -or $t.Actions[0].Execute -ne $Config.python -or
        $t.Actions[0].WorkingDirectory -ne (Split-Path -Parent $Runtime) -or
        $t.Principal.UserId -notin @($owner,$sid,'ASUS') -or $t.Principal.RunLevel -ne 'Highest' -or
        $t.Principal.LogonType -ne 'Interactive' -or $t.Triggers.Count -ne 1 -or
        [DateTimeOffset]::Parse($t.Triggers[0].StartBoundary) -ne $Start) { throw 'Refuse cancellation of an unbound restart task.' }
    $prefix='-X utf8 "'+$Runtime+'" --config "'+$ConfigPath+'" --execute --once-window "'
    $arguments=$t.Actions[0].Arguments
    if (-not $arguments.StartsWith($prefix,[StringComparison]::Ordinal) -or -not $arguments.EndsWith('"')) {
        throw 'Refuse cancellation of an unbound restart action.'
    }
    $permitPath=$arguments.Substring($prefix.Length,$arguments.Length-$prefix.Length-1)
    $permitPath=(Resolve-Path -LiteralPath $permitPath).Path
    if ((Split-Path -Parent $permitPath) -ne (Join-Path $Config.stateRoot 'one-off')) { throw 'Unexpected restart permit directory.' }
    $permit=Read-QuiescenceJson $permitPath
    if ($permit.version -ne 1 -or $permit.host -ne $Config.host -or
        $permit.configSha256 -ne (Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash.ToLowerInvariant() -or
        $permit.runtimeSha256 -ne (Get-FileHash -LiteralPath $Runtime -Algorithm SHA256).Hash.ToLowerInvariant() -or
        [DateTimeOffset]::Parse($permit.startAt) -ne $Start -or
        [DateTimeOffset]::Parse($permit.latestStartAt) -ne $Start.AddMinutes(10) -or
        [DateTimeOffset]::Parse($permit.finishBefore) -ne $Start.AddMinutes(60)) { throw 'Restart permit binding changed.' }
    return $permitPath
}
function Disable-BoundRestart($Config,[string]$ConfigPath,[string]$Runtime,[string]$TaskName,[DateTimeOffset]$Start) {
    $permit=Get-BoundRestartPermit $Config $ConfigPath $Runtime $TaskName $Start
    if ($permit) {
        Disable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
        if ((Get-ScheduledTask -TaskName $TaskName -TaskPath '\').Settings.Enabled) { throw 'Restart cancellation readback failed.' }
    }
}
function Register-WorkerGuard($Config,[string]$ConfigPath,[string]$Helper,[string]$Operation,[string]$GuardName) {
    $arguments='-X utf8 "'+$Helper+'" --config "'+$ConfigPath+'" --watch --operation "'+$Operation+'"'
    $existing=Get-ScheduledTask -TaskName $GuardName -TaskPath '\' -ErrorAction SilentlyContinue
    if (-not $existing) {
        $action=New-ScheduledTaskAction -Execute $Config.python -Argument $arguments -WorkingDirectory (Split-Path -Parent $Helper)
        $trigger=New-ScheduledTaskTrigger -AtLogOn -User ($Config.host+'\ASUS')
        $trigger.Delay='PT2M'
        $settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 2)
        $settings.AllowHardTerminate=$false
        $principal=(Get-ScheduledTask -TaskName 'GYing Weekly Graceful Restart' -TaskPath '\').Principal
        Register-ScheduledTask -TaskName $GuardName -TaskPath '\' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Restore only this recorded worker flag after bounded maintenance or cancellation and original ASUS login recovery.' | Out-Null
    }
    $actual=Get-ScheduledTask -TaskName $GuardName -TaskPath '\'
    $proof=[ordered]@{name=$actual.TaskName;path=$actual.TaskPath;expectedSid=[Security.Principal.NTAccount]::new($Config.host+'\ASUS').Translate([Security.Principal.SecurityIdentifier]).Value;xml=(Export-ScheduledTask -TaskName $GuardName -TaskPath '\')}
    $code="import json,pathlib,runpy,sys; p=pathlib.Path(sys.argv[1]); c=pathlib.Path(sys.argv[2]); m=runpy.run_path(str(p)); cfg=json.loads(c.read_text(encoding='utf-8-sig')); m['validate_guard_task'](json.loads(sys.stdin.read()),cfg,p,c,pathlib.Path(sys.argv[3]),sys.argv[4]); print('guard-verified')"
    $raw=$proof | ConvertTo-Json -Depth 10 | & $Config.python -X utf8 -c $code $Helper $ConfigPath $Operation $GuardName
    if ($LASTEXITCODE -ne 0 -or ($raw -join [Environment]::NewLine) -ne 'guard-verified') { throw 'Exact worker recovery task validation failed.' }
}
function Invoke-QuiescenceCancellation([scriptblock]$Cancel,[scriptblock]$Restore) {
    $errors=[Collections.Generic.List[string]]::new()
    try { & $Cancel | Out-Null } catch { $errors.Add('cancel: '+$_.Exception.Message) }
    try { & $Restore | Out-Null } catch { $errors.Add('restore: '+$_.Exception.Message) }
    return ,$errors.ToArray()
}
$configPath=(Resolve-Path -LiteralPath $ConfigFile).Path
$checkpointPath=(Resolve-Path -LiteralPath $CheckpointFile).Path
$config=Read-QuiescenceJson $configPath
$admin=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($Apply -and -not $admin) { throw 'Administrator token required before runtime changes, worker pause or restart registration.' }
if ($env:COMPUTERNAME -ne $config.host -or [Security.Principal.WindowsIdentity]::GetCurrent().Name -ne ($config.host+'\ASUS') -or (Get-TimeZone).Id -ne 'China Standard Time') { throw 'Unexpected host, owner or timezone.' }
$root=Split-Path -Parent $configPath
$runtime=Join-Path $root 'runtime\weekly_restart.py'
$helper=Join-Path $root 'runtime\resource_hub_pause.py'
$source=Join-Path $PSScriptRoot 'resource_hub_pause.py'
$operationRoot=Join-Path $root 'resource-hub-quiesce'
Confirm-MaintenanceTasks $config $configPath $runtime
$readiness=Invoke-QuiescencePython $config.python @('-X','utf8',(Join-Path $PSScriptRoot 'weekly_restart.py'),'--config',$configPath,'--readiness') ''
Confirm-QuiescenceReadiness $readiness
$oldOperations=@();$oldOperationPaths=@()
if (Test-Path -LiteralPath $operationRoot) {
    $oldOperations=@(Get-ChildItem -LiteralPath $operationRoot -Filter '*.json');$oldOperationPaths=@($oldOperations | ForEach-Object {$_.FullName})
    foreach ($file in $oldOperations) { if ((Read-QuiescenceJson $file.FullName).phase -ne 'restored') { throw 'Earlier worker pause still requires restoration.' } }
}
$verify="import json,pathlib,runpy,subprocess,sys; m=runpy.run_path(sys.argv[1]); c=json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8-sig')); p=json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8-sig')); m['verify_checkpoint'](p); db=m['ApplicationDatabase'](c,require_running=True); db.verify(); a=pathlib.Path(p['directory'])/'mysql.sql.age'; r=subprocess.run([c['age'],'-d','-i',c['ageIdentity'],str(a)],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=300); sys.exit('Checkpoint authentication failed' if r.returncode else 0)"
& $config.python -X utf8 -c $verify $source $configPath $checkpointPath
if ($LASTEXITCODE -ne 0) { throw 'Fresh authenticated checkpoint and application database permissions required.' }
if (-not $Apply) {
    [ordered]@{mode='dry-run';pendingAttempts=0;existingTasksVerified=$true;checkpointVerified=$true;delayMinutes=$DelayMinutes;helper=$helper;noWorkerChanges=$true} | ConvertTo-Json
    return
}
$evidence=Join-Path $root ('quiesced-installation-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $evidence | Out-Null
Write-QuiescenceJson (Join-Path $evidence 'readiness-before.json') $readiness
Write-QuiescenceJson (Join-Path $evidence 'checkpoint.json') (Read-QuiescenceJson $checkpointPath)
foreach ($name in @('GYing Weekly Graceful Restart','GYing Maintenance Recovery')) {
    [IO.File]::WriteAllText((Join-Path $evidence ($name+'.xml')),(Export-ScheduledTask -TaskName $name -TaskPath '\'),[Text.UTF8Encoding]::new($false))
}
$hadHelper=Test-Path -LiteralPath $helper
if ($hadHelper) { Copy-Item -LiteralPath $helper -Destination (Join-Path $evidence 'resource_hub_pause.before.py') }
Copy-Item -LiteralPath $runtime -Destination (Join-Path $evidence 'weekly_restart.before.py')
$metadata=[ordered]@{sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant();runtimeBeforeSha256=(Get-FileHash -LiteralPath $runtime -Algorithm SHA256).Hash.ToLowerInvariant();hadHelper=$hadHelper;at=[DateTimeOffset]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz')}
Write-QuiescenceJson (Join-Path $evidence 'baseline.json') $metadata
$operation=$null;$guardName=$null;$taskName=$null;$start=$null;$replaced=$false;$ownsTask=$false
try {
    $staged=Join-Path (Split-Path -Parent $helper) ([guid]::NewGuid().ToString('N')+'.tmp')
    Copy-Item -LiteralPath $source -Destination $staged
    if ($hadHelper) { [IO.File]::Replace($staged,$helper,(Join-Path $evidence 'resource_hub_pause.atomic-before.py')) }
    else { [IO.File]::Move($staged,$helper) }
    $replaced=$true
    $start=if ($StartAt) { [DateTimeOffset]::Parse($StartAt) } else { [DateTimeOffset]::Parse([DateTimeOffset]::Now.AddMinutes($DelayMinutes).ToString('yyyy-MM-ddTHH:mm:00zzz')) }
    if ($start.Offset -ne [TimeSpan]::FromHours(8) -or $start -le [DateTimeOffset]::Now.AddMinutes(5)) { throw 'Require a future explicit +08:00 restart window at least five minutes ahead.' }
    $taskName='GYing One-off Graceful Restart '+$start.ToString('yyyyMMdd-HHmm')
    if (Get-ScheduledTask -TaskName $taskName -TaskPath '\' -ErrorAction SilentlyContinue) { throw 'Refuse an existing one-off task name.' }
    $ownsTask=$true
    $raw=@(& (Join-Path $PSScriptRoot 'Install-OneOffRestart.ps1') -ConfigFile $configPath -StartAt $start.ToString('yyyy-MM-ddTHH:mm:sszzz') -Apply)
    Write-QuiescenceJson (Join-Path $evidence 'installer-receipt.json') ([ordered]@{output=($raw -join [Environment]::NewLine)})
    $registration=($raw -join [Environment]::NewLine) | ConvertFrom-Json
    if ($registration.mode -ne 'registered' -or $registration.task -ne $taskName) { throw 'Invalid registration receipt.' }
    $permit=Get-BoundRestartPermit $config $configPath $runtime $taskName $start
    if (-not $permit -or $permit -ne $registration.permit -or
        -not (Test-WeeklyTaskTime $start (Get-ScheduledTask -TaskName $taskName -TaskPath '\').Triggers[0].StartBoundary (Get-ScheduledTaskInfo -TaskName $taskName -TaskPath '\').NextRunTime)) { throw 'Registered restart task readback failed.' }
    $registrationFile=Join-Path $evidence 'registration.json'
    Write-QuiescenceJson $registrationFile $registration
    $prepared=Invoke-QuiescencePython $config.python @('-X','utf8',$helper,'--config',$configPath,'--prepare','--registration',$registrationFile,'--checkpoint',$checkpointPath) (Join-Path $evidence 'prepare-receipt.json')
    $operation=(Resolve-Path -LiteralPath $prepared.operation).Path;$guardName=$prepared.guardTask
    $state=Read-QuiescenceJson $operation
    if ((Split-Path -Parent $operation) -ne $operationRoot -or $state.phase -ne 'prepared' -or $state.targetTask -ne $taskName -or $state.guardTask -ne $guardName) { throw 'Prepared operation identity/readback failed.' }
    Register-WorkerGuard $config $configPath $helper $operation $guardName
    [IO.File]::WriteAllText((Join-Path $evidence 'guard.xml'),(Export-ScheduledTask -TaskName $guardName -TaskPath '\'),[Text.UTF8Encoding]::new($false))
    if ([DateTimeOffset]::Now.AddMinutes(2) -ge $start) { throw 'Insufficient future window before worker pause.' }
    $paused=Invoke-QuiescencePython $config.python @('-X','utf8',$helper,'--config',$configPath,'--pause','--operation',$operation) (Join-Path $evidence 'pause-receipt.json')
    if ($paused.phase -ne 'paused' -or $paused.operation -ne $operation -or (Read-QuiescenceJson $operation).phase -ne 'paused') { throw 'Worker pause readback failed.' }
    Start-ScheduledTask -TaskName $guardName -TaskPath '\'
    Start-Sleep -Seconds 2
    if ((Get-ScheduledTask -TaskName $guardName -TaskPath '\').State -ne 'Running' -or [DateTimeOffset]::Now.AddMinutes(2) -ge $start) { throw 'Restore watchdog not running in a future restart window.' }
    $result=[ordered]@{status='registered-and-paused';at=[DateTimeOffset]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz');restart=$registration;operation=$operation;guardTask=$guardName;guardRunning=$true;evidence=$evidence;helperSha256=$metadata.sourceSha256}
    Write-QuiescenceJson (Join-Path $evidence 'result.json') $result
    $result | ConvertTo-Json -Depth 12
} catch {
    $originalError=$_.Exception.Message
    if (-not $operation -and $taskName -and (Test-Path -LiteralPath $operationRoot)) {
        $found=@(Get-ChildItem -LiteralPath $operationRoot -Filter '*.json' | Where-Object {$_.FullName -notin $oldOperationPaths -and (Read-QuiescenceJson $_.FullName).targetTask -eq $taskName})
        if ($found.Count -eq 1) { $operation=$found[0].FullName;$guardName=(Read-QuiescenceJson $operation).guardTask }
    }
    $errors=Invoke-QuiescenceCancellation {
        if ($ownsTask -and $taskName) { Disable-BoundRestart $config $configPath $runtime $taskName $start }
    } {
        if ($operation -and (Read-QuiescenceJson $operation).phase -ne 'restored') {
            Register-WorkerGuard $config $configPath $helper $operation $guardName
            Start-ScheduledTask -TaskName $guardName -TaskPath '\'
            $deadline=[DateTimeOffset]::Now.AddSeconds(60)
            do { Start-Sleep -Seconds 2;$state=Read-QuiescenceJson $operation } while ($state.phase -ne 'restored' -and [DateTimeOffset]::Now -lt $deadline)
            if ($state.phase -ne 'restored') { throw 'Worker recovery remains pending; retain helper and recovery task.' }
        }
    }
    $restored=(-not $operation -or (Read-QuiescenceJson $operation).phase -eq 'restored')
    $safe=($errors.Count -eq 0 -and $restored)
    if ($safe -and $guardName) {
        try {
            if ((Get-ScheduledTask -TaskName $guardName -TaskPath '\').State -eq 'Running') { Start-Sleep -Seconds 2 }
            if ((Get-ScheduledTask -TaskName $guardName -TaskPath '\').State -eq 'Running') { throw 'Recovery helper still running; retain runtime.' }
            Register-WorkerGuard $config $configPath $helper $operation $guardName
            Disable-ScheduledTask -TaskName $guardName -TaskPath '\' | Out-Null
        } catch { $safe=$false;$errors+=('guard-finalize: '+$_.Exception.Message) }
    }
    if ($safe -and $replaced) {
        try {
            $code="import json,pathlib,runpy,sys; m=runpy.run_path(sys.argv[1]); c=m['read'](sys.argv[2]); assert not m['pending'](c); print('rollback-ready')"
            $check=& $config.python -X utf8 -c $code $runtime $configPath
            if ($LASTEXITCODE -ne 0 -or $check -ne 'rollback-ready') { throw 'Maintenance pending; retain runtime.' }
            if ($hadHelper) { Copy-Item -LiteralPath (Join-Path $evidence 'resource_hub_pause.before.py') -Destination $helper }
            Copy-Item -LiteralPath (Join-Path $evidence 'weekly_restart.before.py') -Destination $runtime
        } catch { $safe=$false;$errors+=('runtime-rollback: '+$_.Exception.Message) }
    }
    $failure=[ordered]@{status='failed';at=[DateTimeOffset]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz');reason=$originalError;errors=@($errors);operation=$operation;guardTask=$guardName;recovered=$restored;runtimeRollbackSafe=$safe;evidence=$evidence}
    Write-QuiescenceJson (Join-Path $evidence 'result.json') $failure
    $failure | ConvertTo-Json -Depth 12
    throw 'Quiesced registration failed; see the preserved result and recovery evidence.'
}
