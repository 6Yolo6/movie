param(
    [string]$HelperRoot = 'E:\gying-tools\baidu-login',
    [string]$TaskName = 'GYing Baidu QR Login'
)
$ErrorActionPreference = 'Stop'
$runner = Join-Path ([IO.Path]::GetFullPath($HelperRoot)) 'run-baidu-browser-login.ps1'
if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw 'Baidu login runner is not installed' }
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) { throw 'A task with this name already exists; preserve it and review before replacing' }
# The task uses the interactive user's limited token. Its shell is hidden; Edge
# only opens when an authenticated administrator has created a pending request.
$config = Join-Path ([IO.Path]::GetFullPath($HelperRoot)) 'private\login-config.json'
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $runner + '" -HelperRoot "' + [IO.Path]::GetFullPath($HelperRoot) + '" -ConfigPath "' + $config + '"'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 12)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Write-Output 'BAIDU_QR_LOGIN_TASK=registered'
