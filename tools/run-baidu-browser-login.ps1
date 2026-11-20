param(
    [string]$HelperRoot = 'E:\gying-tools\baidu-login',
    [string]$NodePath = 'D:\nvm\nvm\v24.22.1\node.exe',
    [string]$ConfigPath = 'E:\gying-tools\baidu-login\private\login-config.json'
)
$ErrorActionPreference = 'Stop'
$mutex = $null; $ownsMutex = $false; $priorConfig = $env:BAIDU_LOGIN_CONFIG
function Assert-BaiduPlainPath([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -Force -LiteralPath $current
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'private_path_rejected' }
        }
        $parent = Split-Path -Parent $current
        if ($parent -eq $current) { break }
        $current = $parent
    }
}
function New-BaiduPrivateDirectory([string]$Path) {
    Assert-BaiduPlainPath $Path
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    if (Test-Path -LiteralPath $Path) {
        if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw 'private_path_rejected' }
        $existing = Get-Acl -LiteralPath $Path
        if ($existing.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $identity.Value -or -not $existing.AreAccessRulesProtected) { throw 'private_acl_rejected' }
        foreach ($rule in $existing.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin @($identity.Value, 'S-1-5-18')) { throw 'private_acl_rejected' }
        }
        # Existing private directories are checked, never re-owned by a limited task.
        return
    }
    [void][IO.Directory]::CreateDirectory($Path)
    $system = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($identity); $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($identity, $system)) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
try {
    $mutex = New-Object Threading.Mutex($false, 'Local\GYing.Baidu.QrLogin.v1')
    try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { Write-Output 'BAIDU_QR_LOGIN=busy'; exit 0 }
    $root = [IO.Path]::GetFullPath($HelperRoot)
    Assert-BaiduPlainPath $root; Assert-BaiduPlainPath $ConfigPath
    $privateRoot = Join-Path $root 'private'; $profile = Join-Path $privateRoot 'baidu-qr-profile'
    if ([IO.Path]::GetFullPath($ConfigPath) -cne [IO.Path]::GetFullPath((Join-Path $privateRoot 'login-config.json'))) { throw 'private_configuration_rejected' }
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'configuration_missing' }
    New-BaiduPrivateDirectory $privateRoot
    $config = Get-Content -Encoding UTF8 -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
    if ([IO.Path]::GetFullPath($config.profilePath) -cne [IO.Path]::GetFullPath($profile)) { throw 'private_profile_rejected' }
    foreach ($required in @($NodePath, $config.dockerPath, $config.edgePath, $config.puppeteerPath)) {
        Assert-BaiduPlainPath $required
        if (-not (Test-Path -LiteralPath $required)) { throw 'dependency_missing' }
    }
    New-BaiduPrivateDirectory $privateRoot; New-BaiduPrivateDirectory $profile
    $defaultProfile = Join-Path $profile 'Default'; New-BaiduPrivateDirectory $defaultProfile
    $preferences = Join-Path $defaultProfile 'Preferences'; Assert-BaiduPlainPath $preferences
    $prefs = if (Test-Path -LiteralPath $preferences) { Get-Content -Raw -Encoding UTF8 -LiteralPath $preferences | ConvertFrom-Json } else { [pscustomobject]@{} }
    $prefs | Add-Member -Force -NotePropertyName credentials_enable_service -NotePropertyValue $false
    if (-not $prefs.profile) { $prefs | Add-Member -Force -NotePropertyName profile -NotePropertyValue ([pscustomobject]@{}) }
    $prefs.profile | Add-Member -Force -NotePropertyName password_manager_enabled -NotePropertyValue $false
    if (-not $prefs.autofill) { $prefs | Add-Member -Force -NotePropertyName autofill -NotePropertyValue ([pscustomobject]@{}) }
    $prefs.autofill | Add-Member -Force -NotePropertyName profile_enabled -NotePropertyValue $false
    $prefs.autofill | Add-Member -Force -NotePropertyName credit_card_enabled -NotePropertyValue $false
    [IO.File]::WriteAllText($preferences, ($prefs | ConvertTo-Json -Depth 100 -Compress), (New-Object Text.UTF8Encoding($false)))
    $env:BAIDU_LOGIN_CONFIG = [IO.Path]::GetFullPath($ConfigPath)
    & $NodePath (Join-Path $root 'baidu-browser-session.cjs')
    $code = $LASTEXITCODE
    if ($code -ne 0) { exit 1 }
} catch {
    # Never print browser exceptions, HTTP bodies, ticket nonces or credentials.
    Write-Output 'BAIDU_QR_LOGIN=failed'
    exit 1
} finally {
    $env:BAIDU_LOGIN_CONFIG = $priorConfig
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
