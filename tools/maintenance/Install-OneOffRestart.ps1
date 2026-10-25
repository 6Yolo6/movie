[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ConfigFile,
    [Parameter(Mandatory=$true)][string]$StartAt,
    [switch]$Apply
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'TaskVerification.ps1')
$configPath=(Resolve-Path -LiteralPath $ConfigFile).Path
$config=Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$start=[DateTimeOffset]::Parse($StartAt)
if ($start.Offset -ne [TimeSpan]::FromHours(8) -or $start -le [DateTimeOffset]::Now.AddMinutes(5)) { throw 'Require an explicit future +08:00 window at least five minutes ahead.' }
$user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$admin=(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($env:COMPUTERNAME -ne $config.host -or $user -ne ($config.host+'\ASUS') -or (Get-TimeZone).Id -ne 'China Standard Time') { throw 'Unexpected host, owner or timezone.' }
$root=Split-Path -Parent $configPath
$runtime=Join-Path $root 'runtime'
$target=Join-Path $runtime 'weekly_restart.py'
$source=Join-Path $PSScriptRoot 'weekly_restart.py'
$weekly=Get-ScheduledTask -TaskName 'GYing Weekly Graceful Restart'
$recovery=Get-ScheduledTask -TaskName 'GYing Maintenance Recovery'
if ($weekly.State -eq 'Running' -or $recovery.State -eq 'Running') { throw 'Maintenance is running; refuse runtime replacement.' }
if ($weekly.Principal.UserId -ne $recovery.Principal.UserId -or $weekly.Principal.RunLevel -ne 'Highest' -or $weekly.Principal.LogonType -ne 'Interactive') { throw 'Unexpected existing maintenance principals.' }
if ($weekly.Actions.Count -ne 1 -or $weekly.Actions[0].Execute -ne $config.python -or -not $weekly.Actions[0].Arguments.Contains($target)) { throw 'Existing weekly runtime is not the reviewed target.' }
& $config.python -X utf8 $source --config $configPath --readiness
if ($LASTEXITCODE -ne 0) { throw 'Readiness failed; nothing installed.' }
$name='GYing One-off Graceful Restart '+$start.ToString('yyyyMMdd-HHmm')
if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) { throw 'One-off task already exists; refuse duplicate registration.' }
$report=[ordered]@{mode='dry-run';task=$name;startAt=$start.ToString('o');latestStartAt=$start.AddMinutes(10).ToString('o');finishBefore=$start.AddMinutes(60).ToString('o');admin=$admin;runtime=$target;startWhenAvailable=$false;forceShutdown=$false;recovery='existing ASUS logon task';sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
if (-not $Apply) { $report|ConvertTo-Json;return }
if (-not $admin) { throw 'Administrator token required to update the protected runtime and register an Interactive/Highest task.' }
$evidence=Join-Path $root ('oneoff-installation-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $evidence | Out-Null
$backup=Join-Path $evidence 'weekly_restart.before.py'
Copy-Item -LiteralPath $target -Destination $backup
foreach ($existing in @('GYing Weekly Graceful Restart','GYing Maintenance Recovery')) { Export-ScheduledTask -TaskName $existing | Set-Content -LiteralPath (Join-Path $evidence ($existing+'.xml')) -Encoding UTF8 }
$permitRoot=Join-Path $config.stateRoot 'one-off'
New-Item -ItemType Directory -Force -Path $permitRoot | Out-Null
$id=[guid]::NewGuid().ToString('N')
$permitPath=Join-Path $permitRoot ($id+'.json')
$staged=Join-Path $runtime ($id+'.tmp')
$registered=$false
try {
    Copy-Item -LiteralPath $source -Destination $staged
    [IO.File]::Replace($staged,$target,$null)
    $permit=[ordered]@{version=1;id=$id;host=$config.host;configSha256=(Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash.ToLowerInvariant();runtimeSha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant();startAt=$report.startAt;latestStartAt=$report.latestStartAt;finishBefore=$report.finishBefore;createdAt=[DateTimeOffset]::Now.ToString('o')}
    [IO.File]::WriteAllText($permitPath,($permit|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $action=New-ScheduledTaskAction -Execute $config.python -Argument ('-X utf8 "'+$target+'" --config "'+$configPath+'" --execute --once-window "'+$permitPath+'"') -WorkingDirectory $runtime
    $trigger=New-ScheduledTaskTrigger -Once -At $start.LocalDateTime
    $trigger.StartBoundary=$start.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
    $settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -DontStopIfGoingOnBatteries
    $settings.StartWhenAvailable=$false;$settings.WakeToRun=$false;$settings.AllowHardTerminate=$false
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $weekly.Principal -Description 'Explicit one-off graceful maintenance; single-use bounded permit, idle and authenticated backup required, no forced shutdown or catch-up.' | Out-Null
    $registered=$true
    $actual=Get-ScheduledTask -TaskName $name
    $next=(Get-ScheduledTaskInfo -TaskName $name).NextRunTime
    if ($actual.Settings.StartWhenAvailable -or $actual.Settings.AllowHardTerminate -or -not (Test-WeeklyTaskTime $start $actual.Triggers[0].StartBoundary $next)) { throw 'One-off task readback did not match the bounded schedule.' }
    $report.mode='registered';$report['nextRunTime']=$next.ToString('o');$report['permit']=$permitPath;$report['evidence']=$evidence
    Export-ScheduledTask -TaskName $name | Set-Content -LiteralPath (Join-Path $evidence 'task.xml') -Encoding UTF8
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding UTF8
    $report | ConvertTo-Json
} catch {
    if ($registered) { Unregister-ScheduledTask -TaskName $name -Confirm:$false }
    if (Test-Path -LiteralPath $permitPath) { Remove-Item -LiteralPath $permitPath }
    Copy-Item -LiteralPath $backup -Destination $staged -Force
    [IO.File]::Replace($staged,$target,$null)
    throw
} finally { if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged } }
