# Helpers never touch the real browser profile or use recursive filesystem deletion.
function Assert-XunleiPlainPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    while ($null -ne $item) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse_path_rejected' }
        $item = $item.Parent
    }
}
function New-XunleiPrivateDirectory([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $parent = Split-Path -Parent $full
    Assert-XunleiPlainPath $parent
    if (-not (Test-Path -LiteralPath $full)) { [void][IO.Directory]::CreateDirectory($full) }
    Assert-XunleiPlainPath $full
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $full
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { throw 'private_owner_unexpected' }
    # Modify only the DACL, not Owner/Group (which requires unavailable restore privileges).
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($oldRule in @($acl.Access)) { [void]$acl.RemoveAccessRuleSpecific($oldRule) }
    foreach ($identity in @($sid, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        [void]$acl.AddAccessRule($rule)
    }
    $daclOnly = New-Object Security.AccessControl.DirectorySecurity
    $daclOnly.SetSecurityDescriptorSddlForm($acl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access), [Security.AccessControl.AccessControlSections]::Access)
    ([IO.DirectoryInfo]$full).SetAccessControl($daclOnly)
    $actual = Get-Acl -LiteralPath $full
    if (-not $actual.AreAccessRulesProtected) { throw 'private_acl_not_applied' }
    foreach ($rule in $actual.Access) {
        $ruleSid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($ruleSid -notin @($sid.Value, 'S-1-5-18') -or $rule.AccessControlType -ne 'Allow') { throw 'private_acl_unexpected' }
    }
    return $full
}
function Clear-XunleiRunFiles([string]$PrivateRoot, [string]$RunPath) {
    # Only this newly-created run's leaf files; keep directories/marker for audit.
    # Preflight the entire path list first. A junction/symlink leaves everything untouched.
    $root = [IO.Path]::GetFullPath($PrivateRoot).TrimEnd('\')
    $run = [IO.Path]::GetFullPath($RunPath).TrimEnd('\')
    if ((Split-Path -Parent $run) -ne $root -or (Split-Path -Leaf $run) -notmatch '^run-[a-f0-9]{32}$') { throw 'cleanup_scope_rejected' }
    Assert-XunleiPlainPath $run
    $marker = Join-Path $run '.xunlei-sync-owned'
    if (-not (Test-Path -LiteralPath $marker) -or [IO.File]::ReadAllText($marker) -ne (Split-Path -Leaf $run)) { throw 'cleanup_marker_rejected' }
    $queue = New-Object 'Collections.Generic.Queue[string]'
    $files = New-Object 'Collections.Generic.List[string]'
    $queue.Enqueue($run)
    while ($queue.Count -gt 0) {
        foreach ($entry in Get-ChildItem -LiteralPath $queue.Dequeue() -Force) {
            $absolute = [IO.Path]::GetFullPath($entry.FullName)
            if (-not $absolute.StartsWith($run + '\', [StringComparison]::OrdinalIgnoreCase) -or
                ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'cleanup_scope_rejected' }
            if ($entry.PSIsContainer) { $queue.Enqueue($absolute) }
            elseif ($absolute -ne $marker) { $files.Add($absolute) }
        }
    }
    foreach ($file in $files) {
        Assert-XunleiPlainPath (Split-Path -Parent $file)
        if ((Get-Item -LiteralPath $file -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'cleanup_scope_rejected' }
        Remove-Item -LiteralPath $file -Force -ErrorAction Stop
    }
}
function Write-XunleiSafeJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 8 -Compress), (New-Object Text.UTF8Encoding($false)))
}
function Save-XunleiEncryptedBackup([byte[]]$Bytes, [string]$Path) {
    Add-Type -AssemblyName System.Security
    $encrypted = [Security.Cryptography.ProtectedData]::Protect($Bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($Path, $encrypted)
    $verified = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($Path), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        if ([Convert]::ToBase64String($sha.ComputeHash($verified)) -ne [Convert]::ToBase64String($sha.ComputeHash($Bytes))) { throw 'backup_verification_failed' }
    } finally { $sha.Dispose(); [Array]::Clear($verified,0,$verified.Length) }
}
