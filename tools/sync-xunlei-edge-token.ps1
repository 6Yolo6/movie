param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ComposeProject = 'gying-movie',
    [string]$NodePath = 'D:\nvm\nodejs\node.exe',
    [string]$DockerPath = 'C:\Users\ASUS\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
)

$ErrorActionPreference = 'Stop'
$logPath = 'E:\gying-tools\xunlei-auth-helper\sync-status.log'
$composeFile = Join-Path $RepoRoot 'docker-compose.prod.yml'
if (-not (Test-Path -LiteralPath $composeFile)) {
    throw 'docker-compose.prod.yml not found'
}

$helperRoot = 'E:\gying-tools\xunlei-auth-helper'
$liveProfileRoot = 'C:\Users\ASUS\AppData\Local\Microsoft\Edge\User Data'
$liveProfileName = 'Default'
$profileRoot = Join-Path $helperRoot 'xunlei-edge-profile-sync'
$profileName = 'Default'
$puppeteerPath = Join-Path $helperRoot 'node_modules\puppeteer-core'
$edgePath = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$nodeScript = Join-Path $PSScriptRoot 'xunlei-edge-token-sync.cjs'
if (-not (Test-Path -LiteralPath $puppeteerPath)) { throw 'puppeteer-core is not installed for the Xunlei helper' }
if (-not (Test-Path -LiteralPath (Join-Path $liveProfileRoot $liveProfileName))) { throw 'Default Edge profile is not prepared' }
if (-not (Test-Path -LiteralPath $edgePath)) { throw 'Microsoft Edge is not installed' }

if (-not (Test-Path -LiteralPath $NodePath)) { throw 'Node.js executable not found' }
if (-not (Test-Path -LiteralPath $DockerPath)) { throw 'Docker executable not found' }

# Discover the live backend by Compose labels first. This avoids evaluating the
# entire Compose file (and unrelated required environment variables) during a
# scheduled token refresh.
$containerLines = @(& $DockerPath ps --filter "label=com.docker.compose.project=$ComposeProject" --filter "label=com.docker.compose.service=backend" --format '{{.ID}}')
$container = ($containerLines | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
if ($container) { $container = $container.Trim() }
if (-not $container) {
    $composeLines = @(& $DockerPath compose -p $ComposeProject -f $composeFile ps -q backend)
    $container = ($composeLines | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
    if ($container) { $container = $container.Trim() }
}
if (-not $container) { throw 'GYing backend container is not running' }

New-Item -ItemType Directory -Force -Path $helperRoot | Out-Null
New-Item -ItemType Directory -Force -Path $profileRoot | Out-Null
$localState = Join-Path $liveProfileRoot 'Local State'
if (Test-Path -LiteralPath $localState) {
    Copy-Item -LiteralPath $localState -Destination (Join-Path $profileRoot 'Local State') -Force
}
$sourceProfile = Join-Path $liveProfileRoot $liveProfileName
$targetProfile = Join-Path $profileRoot $profileName
New-Item -ItemType Directory -Force -Path $targetProfile | Out-Null
$copyDirs = @('Local Storage','Session Storage','IndexedDB','Network')
$copyFiles = @('Preferences','Secure Preferences','Web Data','Login Data','Login Data For Account')
foreach ($dir in $copyDirs) {
    $src = Join-Path $sourceProfile $dir
    $dst = Join-Path $targetProfile $dir
    if (Test-Path -LiteralPath $src) {
        & robocopy $src $dst /E /Z /R:1 /W:1 /COPY:DAT /DCOPY:DAT /XD 'Cache' 'Code Cache' 'GPUCache' /XF 'LOCK' 'lockfile' 'Cookies' 'Cookies-journal' /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -ge 8) { throw "Failed to copy Edge authentication data: $dir" }
    }
}
foreach ($file in $copyFiles) {
    $src = Join-Path $sourceProfile $file
    if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $targetProfile $file) -Force -ErrorAction SilentlyContinue }
}
$nonce = [Guid]::NewGuid().ToString('N')
$existing = Join-Path $helperRoot "state-$nonce.json"
$output = Join-Path $helperRoot "updated-$nonce.json"
$meta = Join-Path $helperRoot "updated-$nonce.meta.json"
try {
    $existingJson = & $DockerPath exec $container cat /app/data/xunlei-auth.json 2>$null
    if ($LASTEXITCODE -eq 0 -and $existingJson) {
        [IO.File]::WriteAllText($existing, ($existingJson -join "`n"), (New-Object Text.UTF8Encoding($false)))
    } else {
        [IO.File]::WriteAllText($existing, '{}', (New-Object Text.UTF8Encoding($false)))
    }

    $env:PUPPETEER_PATH = $puppeteerPath
    $env:EDGE_PATH = $edgePath
    $env:EDGE_PROFILE = $profileRoot
    $env:EDGE_PROFILE_NAME = $profileName
    $env:XUNLEI_EXISTING_STATE = $existing
    $env:XUNLEI_OUTPUT_STATE = $output
    $env:XUNLEI_OUTPUT_META = $meta
    & $NodePath $nodeScript
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) {
        throw 'Edge did not provide a usable authenticated Xunlei request'
    }
    $syncMeta = if (Test-Path -LiteralPath $meta) { Get-Content -Raw -LiteralPath $meta | ConvertFrom-Json } else { $null }
    if ($syncMeta -and $syncMeta.status -eq 'unchanged') {
        Write-Output 'XUNLEI_TOKEN_SYNC=unchanged'
        Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format s) unchanged" -Encoding UTF8
        return
    }

    & $DockerPath cp $output "${container}:/app/data/xunlei-auth.json.next"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to copy refreshed Xunlei state into backend-data' }
    & $DockerPath exec $container sh -lc 'chmod 600 /app/data/xunlei-auth.json.next && mv /app/data/xunlei-auth.json.next /app/data/xunlei-auth.json'
    if ($LASTEXITCODE -ne 0) { throw 'Failed to activate refreshed Xunlei state' }
    Write-Output 'XUNLEI_TOKEN_SYNC=success'
    Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format s) success" -Encoding UTF8
} catch {
    Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format s) failed: $($_.Exception.Message)" -Encoding UTF8
    throw
} finally {
    foreach ($name in @('PUPPETEER_PATH','EDGE_PATH','EDGE_PROFILE','EDGE_PROFILE_NAME','XUNLEI_EXISTING_STATE','XUNLEI_OUTPUT_STATE','XUNLEI_OUTPUT_META')) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $existing -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $meta -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $profileRoot -Recurse -Force -ErrorAction SilentlyContinue
}
