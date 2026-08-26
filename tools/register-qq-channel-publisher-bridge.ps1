param(
    [string]$TaskName = "GYing QQ Channel Publisher Bridge"
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "serve-qq-channel-publisher.ps1"
if (-not (Test-Path $script)) {
    throw "Publisher bridge script not found: $script"
}

$powerShell = (Get-Command powershell.exe).Source
$action = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)

try {
    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $action `
        -Trigger $trigger `
        -Settings $settings `
        -RunLevel Highest `
        -Force | Out-Null

    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Registered and started scheduled task: $TaskName"
} catch {
    $startup = [Environment]::GetFolderPath("Startup")
    $shortcutPath = Join-Path $startup "GYing QQ Channel Publisher Bridge.lnk"
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $powerShell
    $shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
    $shortcut.WorkingDirectory = Split-Path -Parent $script
    $shortcut.WindowStyle = 7
    $shortcut.Save()
    Write-Host "Scheduled task registration was unavailable; installed current-user startup shortcut: $shortcutPath"
}
