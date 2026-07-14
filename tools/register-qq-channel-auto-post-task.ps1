param(
    [string]$TaskName = "GYing QQ Channel Auto Post",
    [int]$EveryMinutes = 1
)

$ErrorActionPreference = "Stop"

$script = Join-Path $PSScriptRoot "publish-latest-resource-to-qq-channel.ps1"
if (-not (Test-Path $script)) {
    throw "publish-latest-resource-to-qq-channel.ps1 not found at $script"
}

$action = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$script`""

schtasks /Create `
    /TN $TaskName `
    /SC MINUTE `
    /MO $EveryMinutes `
    /TR $action `
    /RL LIMITED `
    /F

if ($LASTEXITCODE -ne 0) {
    throw "Failed to register scheduled task. Run this script from an elevated PowerShell window."
}

schtasks /Change /TN $TaskName /ENABLE | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Scheduled task was created but could not be enabled."
}

Write-Host "Registered scheduled task: $TaskName"
