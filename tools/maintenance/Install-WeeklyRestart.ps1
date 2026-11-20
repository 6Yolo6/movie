[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ConfigFile,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'TaskVerification.ps1')
$configPath = (Resolve-Path -LiteralPath $ConfigFile).Path
$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$controller = Join-Path $PSScriptRoot 'weekly_restart.py'
$names = @('GYing Weekly Graceful Restart', 'GYing Maintenance Recovery')
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$admin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($env:COMPUTERNAME -ne $config.host -or $user -ne ($config.host + '\ASUS')) { throw 'Unexpected host/user; installation refused.' }
if ((Get-TimeZone).Id -ne 'China Standard Time') { throw 'Expected Asia/Shanghai Windows timezone.' }
$start = [DateTimeOffset]::Parse($config.firstRun)
if ($start.LocalDateTime -le (Get-Date)) { throw 'First trigger is no longer in the future; review a new date.' }
& $config.python -X utf8 $controller --config $configPath --readiness
if ($LASTEXITCODE -ne 0) { throw 'Readiness checks failed; no tasks registered.' }
$report = [ordered]@{ mode='dry-run'; user=$user; admin=$admin; firstRun=$config.firstRun; schedule='Sunday 09:30 Asia/Shanghai'; startWhenAvailable=$false; recovery='ASUS logon, 90-second delay'; tasks=$names; automaticLogin=$false }
if (-not $Apply) { $report | ConvertTo-Json -Depth 5; return }
if (-not $admin) { throw 'Administrator token required. Run this exact installer with -Apply in an elevated PowerShell; no tasks were registered.' }
foreach ($name in $names) {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) { throw "Existing task needs explicit reviewed update: $name" }
}
$root = Split-Path -Parent $configPath
$runtime = Join-Path $root 'runtime'
$rollback = Join-Path $root ('installation-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runtime, $rollback -Force | Out-Null
# Runtime/config/journals are readable only by the owner, SYSTEM and Administrators.
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
    $identity = New-Object Security.Principal.SecurityIdentifier($sid)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $root -AclObject $acl
Copy-Item -LiteralPath $controller -Destination (Join-Path $runtime 'weekly_restart.py')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'bounded_backup.py') -Destination (Join-Path $runtime 'bounded_backup.py')
Copy-Item -LiteralPath $config.backupScript -Destination (Join-Path $runtime 'backup.py')
Copy-Item -LiteralPath $config.verifyBackupScript -Destination (Join-Path $runtime 'verify_backup.py')
Copy-Item -LiteralPath $configPath -Destination (Join-Path $rollback 'config.before.json')
$config.backupScript = Join-Path $runtime 'backup.py'
$config.boundedBackupScript = Join-Path $runtime 'bounded_backup.py'
$config.verifyBackupScript = Join-Path $runtime 'verify_backup.py'
$config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath -Encoding UTF8
$runtimeScript = Join-Path $runtime 'weekly_restart.py'
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -DontStopIfGoingOnBatteries
$settings.StartWhenAvailable = $false
$settings.WakeToRun = $false
$settings.AllowHardTerminate = $false
$weeklyTrigger = New-ScheduledTaskTrigger -Weekly -WeeksInterval 1 -DaysOfWeek Sunday -At $start.LocalDateTime
$weeklyTrigger.StartBoundary = $start.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
$recoveryTrigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$recoveryTrigger.Delay = 'PT90S'
$recoverySettings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 2)
$recoverySettings.AllowHardTerminate = $false
$registered = @()
try {
    $recoverArgs = '-X utf8 "' + $runtimeScript + '" --config "' + $configPath + '" --recover'
    $restartArgs = '-X utf8 "' + $runtimeScript + '" --config "' + $configPath + '" --execute'
    $recoverAction = New-ScheduledTaskAction -Execute $config.python -Argument $recoverArgs -WorkingDirectory $runtime
    $restartAction = New-ScheduledTaskAction -Execute $config.python -Argument $restartArgs -WorkingDirectory $runtime
    Register-ScheduledTask -TaskName $names[1] -Action $recoverAction -Trigger $recoveryTrigger -Settings $recoverySettings -Principal $principal -Description 'Restore only recorded GYING/local AI services after ASUS manually logs on; never publish a test post.' | Out-Null
    $registered += $names[1]
    Register-ScheduledTask -TaskName $names[0] -Action $restartAction -Trigger $weeklyTrigger -Settings $settings -Principal $principal -Description 'Sunday 09:30 Asia/Shanghai, graceful only, backup before reboot, cancel on active jobs or failure, no catch-up.' | Out-Null
    $registered += $names[0]
    foreach ($name in $names) {
        Export-ScheduledTask -TaskName $name | Set-Content -LiteralPath (Join-Path $rollback ($name + '.xml')) -Encoding UTF8
    }
    $actual = Get-ScheduledTask -TaskName $names[0]
    $next = (Get-ScheduledTaskInfo -TaskName $names[0]).NextRunTime
    $verification = [ordered]@{ startWhenAvailable=$actual.Settings.StartWhenAvailable; runLevel=[string]$actual.Principal.RunLevel; logonType=[string]$actual.Principal.LogonType; nextRunTime=$next.ToString('o'); expectedNextRunTime=$start.LocalDateTime.ToString('o') }
    $offset = ($next - $start.LocalDateTime).TotalSeconds
    $verification['scheduledOffsetSeconds'] = $offset
    $verification | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $rollback 'verification.json') -Encoding UTF8
    # The scheduler on this host reports a 30-second execution offset. Keep the exact
    # configured boundary and allow only the first minute of the reviewed trigger.
    if ($actual.Settings.StartWhenAvailable -or $actual.Principal.RunLevel -ne 'Highest' -or $actual.Principal.LogonType -ne 'Interactive' -or -not (Test-WeeklyTaskTime $start $actual.Triggers[0].StartBoundary $next)) { throw ('Registered task verification failed: ' + ($verification | ConvertTo-Json -Compress)) }
    $report.mode = 'registered'
    $report['nextRunTime'] = $next.ToString('yyyy-MM-dd HH:mm:ss')
    $report['evidence'] = $rollback
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $rollback 'result.json') -Encoding UTF8
    $report | ConvertTo-Json -Depth 5
} catch {
    foreach ($name in $registered) { Unregister-ScheduledTask -TaskName $name -Confirm:$false }
    Copy-Item -LiteralPath (Join-Path $rollback 'config.before.json') -Destination $configPath -Force
    throw
}
