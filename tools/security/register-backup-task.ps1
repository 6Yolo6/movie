[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$ConfigFile,
    [Parameter(Mandatory)][string]$PythonExe,
    [string]$At = "02:30",
    [switch]$Apply
)
$ErrorActionPreference = "Stop"
$scriptPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "backup.py")).Path
$configPath = (Resolve-Path -LiteralPath $ConfigFile).Path
$pythonPath = (Resolve-Path -LiteralPath $PythonExe).Path
if (-not $Apply) {
    Write-Output "DRY RUN: GYing Encrypted Backup at $At; no scheduled task changed."
    & $pythonPath $scriptPath --config $configPath
    exit $LASTEXITCODE
}
if ($PSCmdlet.ShouldProcess("GYing Encrypted Backup", "Register current-user backup task")) {
    $arguments = '"{0}" --config "{1}" --execute' -f $scriptPath, $configPath
    $action = New-ScheduledTaskAction -Execute $pythonPath -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -Daily -At $At
    $settings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 3)
    $principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName "GYing Encrypted Backup" -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Encrypted GYing backups; check Task Scheduler results and restore drills" -Force | Out-Null
    Write-Output "Registered; interactive logon and Docker Desktop must be available. No backup was triggered."
}
