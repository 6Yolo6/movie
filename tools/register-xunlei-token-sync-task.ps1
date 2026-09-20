param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$TaskName = 'GYing Xunlei Token Sync',
    [ValidateRange(1, 12)]
    [int]$IntervalHours = 2
)

$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'run-xunlei-token-sync.cmd'
$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/d /c `"`"$runnerPath`"`""
$firstRun = (Get-Date).AddMinutes(2)
$periodic = New-ScheduledTaskTrigger -Once -At $firstRun -RepetitionInterval (New-TimeSpan -Hours $IntervalHours)
$logon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -ErrorAction Stop -TaskName $TaskName -Action $action -Trigger @($periodic, $logon) -Settings $settings -Principal $principal -Force | Out-Null
Write-Output "TASK_REGISTERED=$TaskName"
