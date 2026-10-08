param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ComposeProject = 'gying-movie',
    [string]$NodePath = 'D:\nvm\nodejs\node.exe',
    [string]$DockerPath = 'C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe',
    [string]$HelperRoot = 'E:\gying-tools\xunlei-auth-helper',
    [string]$LiveProfileRoot = "$env:LOCALAPPDATA\Microsoft\Edge\User Data",
    [string]$LiveProfileName = 'Default',
    [switch]$NoActivate,
    [switch]$SkipLiveRefresh,
    [switch]$AllowLoginRetry
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'xunlei-token-sync-common.ps1')
$mutex = $null; $ownsMutex = $false; $runPath = $null; $privateRoot = $null
$stage = 'configuration'; $exitCode = 0; $live = $null; $syncMeta = $null
$environmentBefore = @{}
$environmentNames = @('PUPPETEER_PATH','EDGE_PATH','EDGE_PROFILE','EDGE_PROFILE_NAME','XUNLEI_EXISTING_STATE','XUNLEI_OUTPUT_STATE','XUNLEI_OUTPUT_META')
$logPath = Join-Path $HelperRoot 'sync-status.log'
try {
    $mutex = New-Object Threading.Mutex($false, 'Local\GYing.Xunlei.TokenSync.v2')
    try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { Write-Output 'XUNLEI_TOKEN_SYNC=busy'; return }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'docker-compose.prod.yml'))) { throw 'compose_missing' }
    if ($LiveProfileName -notmatch '^(Default|Profile [0-9]+)$') { throw 'profile_name_invalid' }
    $sourceProfile = Join-Path $LiveProfileRoot $LiveProfileName
    $puppeteerPath = Join-Path $HelperRoot 'node_modules\puppeteer-core'
    $edgePath = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
    foreach ($required in @($sourceProfile,$puppeteerPath,$edgePath,$NodePath,$DockerPath)) {
        if (-not (Test-Path -LiteralPath $required)) { throw 'dependency_missing' }
    }
    # Match a single running production backend, never fall back to another Compose project.
    $containerIds = @(& $DockerPath ps -q --filter "label=com.docker.compose.project=$ComposeProject" --filter 'label=com.docker.compose.service=backend')
    if ($LASTEXITCODE -ne 0 -or $containerIds.Count -ne 1 -or $containerIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'backend_ambiguous' }
    $container = $containerIds[0].Trim()
    $uid = & $DockerPath exec $container id -u
    if ($LASTEXITCODE -ne 0 -or $uid.Trim() -ne '10001') { throw 'backend_user_unexpected' }
    $privateRoot = New-XunleiPrivateDirectory (Join-Path $HelperRoot 'private-sync-v2')
    $backupRoot = New-XunleiPrivateDirectory (Join-Path $privateRoot 'rollback')
    $runPath = New-XunleiPrivateDirectory (Join-Path $privateRoot ('run-' + [Guid]::NewGuid().ToString('N')))
    [IO.File]::WriteAllText((Join-Path $runPath '.xunlei-sync-owned'), (Split-Path -Leaf $runPath))
    $existing = Join-Path $runPath 'existing.json'
    $output = Join-Path $runPath 'updated.json'
    $meta = Join-Path $runPath 'updated.meta.json'
    $statusPath = Join-Path $privateRoot 'live-session-status.json'
    $priorStatus = if (Test-Path -LiteralPath $statusPath) { Get-Content -Raw -Encoding UTF8 -LiteralPath $statusPath | ConvertFrom-Json } else { $null }
    $allowLogin = $AllowLoginRetry -or -not ($priorStatus -and $priorStatus.loginBlocked)
    $stage = 'backend_snapshot'
    $encoded = @(& $DockerPath exec $container base64 /app/data/xunlei-auth.json)
    if ($LASTEXITCODE -ne 0 -or -not $encoded) { throw 'current_state_unreadable' }
    $originalBytes = [Convert]::FromBase64String(($encoded -join ''))
    $encoded = $null
    [IO.File]::WriteAllBytes($existing, $originalBytes)
    $current = [Text.Encoding]::UTF8.GetString($originalBytes) | ConvertFrom-Json
    if (-not $current.access_token -or -not $current.user_id) { throw 'current_account_missing' }
    $expectedHash = (Get-FileHash -LiteralPath $existing -Algorithm SHA256).Hash.ToLowerInvariant()
    $stage = 'live_refresh'
    if (-not $SkipLiveRefresh) {
        . (Join-Path $PSScriptRoot 'refresh-xunlei-live-session.ps1') -AllowSavedPasswordLogin:$allowLogin
        $live = Invoke-XunleiLiveRefresh -BeforeLoginSubmit {
            Write-XunleiSafeJson $statusPath @{checkedAt=(Get-Date).ToString('o');status='login_attempt';loginBlocked=$true}
        }
        Write-Output "XUNLEI_LIVE_SESSION=$($live.status);REFRESHED=$($live.refreshed);LOGIN_SUBMITTED=$($live.loginSubmitted)"
        # Persist before capture: a failed verification or process interruption must not cause repeated logins.
        if ($live.loginSubmitted -or $live.needsInteraction) {
            $attemptStatus = if (Test-Path -LiteralPath $statusPath) { Get-Content -Raw -Encoding UTF8 -LiteralPath $statusPath | ConvertFrom-Json } else { $null }
            Write-XunleiSafeJson $statusPath @{checkedAt=(Get-Date).ToString('o');status=$live.status;loginBlocked=([bool]$live.loginSubmitted -or [bool]($attemptStatus -and $attemptStatus.loginBlocked))}
        }
    } else { $live = @{status='skipped';refreshed=$false;loginSubmitted=$false;needsInteraction=$false} }
    $stage = 'browser_snapshot'
    $profileRoot = Join-Path $runPath 'profile'
    $targetProfile = Join-Path $profileRoot 'Default'
    [void][IO.Directory]::CreateDirectory($targetProfile)
    # Capture only application web storage after the REAL window's refresh.
    # Never copy Login Data, Web Data, Local State, cookies, preferences or saved passwords.
    foreach ($dir in @('Local Storage','Session Storage','IndexedDB')) {
        $src = Join-Path $sourceProfile $dir
        if (-not (Test-Path -LiteralPath $src)) { continue }
        Assert-XunleiPlainPath $src
        $dst = Join-Path $targetProfile $dir
        & robocopy $src $dst /E /XJ /R:1 /W:1 /COPY:DAT /DCOPY:DAT /XF LOCK lockfile /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -ge 8) { throw 'snapshot_failed' }
    }
    $stage = 'capture_validate'
    foreach ($name in $environmentNames) { $environmentBefore[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $env:PUPPETEER_PATH=$puppeteerPath; $env:EDGE_PATH=$edgePath; $env:EDGE_PROFILE=$profileRoot
    $env:EDGE_PROFILE_NAME='Default'; $env:XUNLEI_EXISTING_STATE=$existing
    $env:XUNLEI_OUTPUT_STATE=$output; $env:XUNLEI_OUTPUT_META=$meta
    & $NodePath (Join-Path $PSScriptRoot 'xunlei-edge-token-sync.cjs')
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) { throw 'verified_token_unavailable' }
    $syncMeta = Get-Content -Raw -Encoding UTF8 -LiteralPath $meta | ConvertFrom-Json
    if ($syncMeta.validated -ne $true -or $syncMeta.validationHttpStatus -ne 200) { throw 'validation_missing' }
    $stateJson = Get-Content -Raw -Encoding UTF8 -LiteralPath $output
    $parsedState = $stateJson | ConvertFrom-Json
    if (-not $parsedState.access_token -or -not $parsedState.client_id -or -not $parsedState.device_id -or
        $parsedState.user_id -ne $current.user_id -or $parsedState.expires_at -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+300000) { throw 'candidate_invalid' }
    Write-XunleiSafeJson $statusPath @{checkedAt=(Get-Date).ToString('o');status='verified';liveStatus=$live.status;loginBlocked=$false;expiresAt=$parsedState.expires_at}
    if ($NoActivate) {
        Write-Output 'XUNLEI_TOKEN_SYNC=verified_no_activate'
    } elseif ($syncMeta.status -eq 'unchanged') {
        Write-Output 'XUNLEI_TOKEN_SYNC=unchanged;VALIDATED=true'
    } else {
        $stage = 'encrypted_backup'
        $backupPath = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N') + '.dpapi')
        Save-XunleiEncryptedBackup $originalBytes $backupPath
        $stage = 'activate'
        $previousOutputEncoding = $OutputEncoding
        try {
            $OutputEncoding = New-Object Text.UTF8Encoding($false)
            # Unprivileged, same-directory atomic replacement. If a backend refresh is observed,
            # leave its new state in place instead of writing our older snapshot over it.
            $stateJson | & $DockerPath exec -i $container sh -c 'set -eu; umask 077; tmp=$(mktemp /app/data/.xunlei-auth.XXXXXX); trap ''rm -f -- "$tmp"'' EXIT; cat > "$tmp"; test -s "$tmp"; chmod 600 "$tmp"; actual=$(sha256sum /app/data/xunlei-auth.json); actual=${actual%% *}; [ "$actual" = "$1" ] || exit 75; mv -f -- "$tmp" /app/data/xunlei-auth.json' xunlei-sync $expectedHash
            if ($LASTEXITCODE -eq 75) { throw 'concurrent_backend_refresh' }
            if ($LASTEXITCODE -ne 0) { throw 'activation_failed' }
        } finally { $OutputEncoding = $previousOutputEncoding }
        $stage = 'readback'
        $readback = (& $DockerPath exec $container cat /app/data/xunlei-auth.json) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'readback_failed' }
        $active = $readback | ConvertFrom-Json
        foreach ($field in @('access_token','refresh_token','client_id','device_id','user_id','expires_at','captcha_token')) {
            if ($active.$field -cne $parsedState.$field) { throw 'readback_changed' }
        }
        $mode = & $DockerPath exec $container stat -c '%u:%g %a' /app/data/xunlei-auth.json
        if ($LASTEXITCODE -ne 0 -or $mode.Trim() -ne '10001:10001 600') { throw 'readback_permissions' }
        Write-Output 'XUNLEI_TOKEN_SYNC=success;VALIDATED=true;PERMISSIONS=10001:10001_600'
    }
    Write-XunleiSafeJson (Join-Path $privateRoot 'last-result.json') @{checkedAt=(Get-Date).ToString('o');status= $(if($NoActivate){'verified_no_activate'}else{$syncMeta.status});validated=$true;live=$live;expiresAt=$parsedState.expires_at;hasRefreshToken=[bool]$parsedState.refresh_token}
    Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format s) validated status=$($syncMeta.status) noActivate=$NoActivate live=$($live.status)" -Encoding UTF8
} catch {
    $exitCode=1
    # Do not put raw exceptions/HTTP bodies/credential-bearing process arguments in logs.
    Write-Output "XUNLEI_TOKEN_SYNC=failed;STAGE=$stage;TYPE=$($_.Exception.GetType().Name);LINE=$($_.InvocationInfo.ScriptLineNumber)"
    if ($privateRoot) {
        Write-XunleiSafeJson (Join-Path $privateRoot 'last-result.json') @{checkedAt=(Get-Date).ToString('o');status='failed';stage=$stage;validated=$false;live=$live}
    }
    if (Test-Path -LiteralPath $HelperRoot) { Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format s) failed stage=$stage" -Encoding UTF8 }
} finally {
    foreach ($name in $environmentBefore.Keys) { [Environment]::SetEnvironmentVariable($name,$environmentBefore[$name],'Process') }
    $stateJson=$null; $parsedState=$null; $current=$null; $active=$null; $readback=$null
    if ($originalBytes) { [Array]::Clear($originalBytes,0,$originalBytes.Length) }
    if ($runPath -and $privateRoot) {
        try { Clear-XunleiRunFiles $privateRoot $runPath }
        catch { Write-Output 'XUNLEI_PRIVATE_CLEANUP=needs_review'; $exitCode=1 }
    }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
exit $exitCode
