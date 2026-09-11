[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [string]$SnapshotName = ''
)

$ErrorActionPreference = 'Stop'
if ($RepoRoot) {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
} else {
    $candidate = (Resolve-Path -LiteralPath $PSScriptRoot).Path
    while ($candidate) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'docker-compose.prod.yml')) {
            $RepoRoot = $candidate
            break
        }
        $parent = Split-Path -Parent $candidate
        if ($parent -eq $candidate) { $candidate = $null } else { $candidate = $parent }
    }
    if (-not $RepoRoot) { throw '无法自动定位仓库根目录，请使用 -RepoRoot 指定项目路径' }
}
$MigrationRoot = Join-Path $RepoRoot 'migration-data'
if (-not $SnapshotName) {
    $SnapshotName = Get-Date -Format 'yyyyMMdd-HHmmss'
}
$SnapshotRoot = Join-Path $MigrationRoot $SnapshotName
$ManifestRoot = Join-Path $SnapshotRoot 'manifest'
$Errors = New-Object System.Collections.Generic.List[string]
$DockerLog = Join-Path $ManifestRoot 'docker-cp.log'

New-Item -ItemType Directory -Force -Path $SnapshotRoot, $ManifestRoot | Out-Null

function Add-Error([string]$Message) {
    $Errors.Add($Message)
    Write-Warning $Message
}

function Copy-RequiredFile([string]$Source, [string]$Destination) {
    try {
        if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
            throw "文件不存在: $Source"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
    } catch {
        Add-Error "复制文件失败: $Source；$($_.Exception.Message)"
    }
}

function Copy-OptionalPath([string]$Source, [string]$Destination) {
    try {
        if (-not (Test-Path -LiteralPath $Source)) {
            Add-Error "可选路径不存在，未导出: $Source"
            return
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
    } catch {
        Add-Error "复制目录失败: $Source；$($_.Exception.Message)"
    }
}

function Invoke-DockerCopy([string]$Container, [string]$ContainerPath, [string]$Destination) {
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        if (Test-Path -LiteralPath $Destination) {
            throw "目标路径已存在，避免覆盖: $Destination"
        }
        $output = & docker cp -L "${Container}:$ContainerPath" $Destination 2>&1
        $exitCode = $LASTEXITCODE
        if ($output) { $output | Out-File -FilePath $DockerLog -Encoding utf8 -Append }
        if ($exitCode -ne 0) {
            throw "docker cp 退出码 $exitCode"
        }
    } catch {
        Add-Error "Docker 数据导出失败: ${Container}:$ContainerPath；$($_.Exception.Message)"
    }
}

function Invoke-DockerInspect([string]$Container) {
    try {
        $safeName = $Container -replace '[^A-Za-z0-9._-]', '_'
        $output = & docker inspect $Container 2>&1
        if ($LASTEXITCODE -ne 0) { throw "docker inspect 退出码 $LASTEXITCODE" }
        $output | Out-File -FilePath (Join-Path $ManifestRoot "$safeName.txt") -Encoding utf8
    } catch {
        Add-Error "Docker 检查失败: $Container；$($_.Exception.Message)"
    }
}

function Read-EnvValue([string]$Name, [string]$DefaultValue = '') {
    $line = Get-Content -LiteralPath (Join-Path $RepoRoot '.env') -Encoding UTF8 |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -First 1
    if (-not $line) { return $DefaultValue }
    return ($line -replace "^$([regex]::Escape($Name))=", '').Trim().Trim('"')
}

try {
    $configRoot = Join-Path $SnapshotRoot 'config'
    Copy-RequiredFile (Join-Path $RepoRoot '.env') (Join-Path $configRoot 'gying.env')
    Copy-RequiredFile (Join-Path $RepoRoot '.env.example') (Join-Path $configRoot 'gying.env.example')
    Copy-RequiredFile (Join-Path $RepoRoot 'docker-compose.prod.yml') (Join-Path $configRoot 'docker-compose.prod.yml')
    Copy-RequiredFile (Join-Path $RepoRoot 'MIGRATION.md') (Join-Path $configRoot 'MIGRATION.md')

    $dockerVersion = & docker version --format '{{.Server.Version}}' 2>&1
    $dockerVersion | Out-File -FilePath (Join-Path $ManifestRoot 'docker-version.txt') -Encoding ascii
    if ($LASTEXITCODE -ne 0) { Add-Error '无法读取 Docker Server 版本' }
    & docker ps --format '{{.Names}}' | Out-File -FilePath (Join-Path $ManifestRoot 'docker-ps.txt') -Encoding ascii

    $containers = @(
        'gying-movie-backend-1',
        'gying-movie-gying-source-1',
        'gying-movie-social-publisher-1',
        'gying-quark-auto-save',
        'minio-server',
        'openclaw-openclaw-gateway-1'
    )
    foreach ($container in $containers) { Invoke-DockerInspect $container }

    Invoke-DockerCopy 'gying-movie-backend-1' '/app/data' (Join-Path $SnapshotRoot 'docker\backend-data')
    Invoke-DockerCopy 'gying-movie-backend-1' '/app/logs' (Join-Path $SnapshotRoot 'docker\backend-logs')
    Invoke-DockerCopy 'minio-server' '/home/minio/data' (Join-Path $SnapshotRoot 'docker\minio-data')
    Invoke-DockerCopy 'minio-server' '/home/minio/config' (Join-Path $SnapshotRoot 'docker\minio-config')
    Invoke-DockerCopy 'gying-movie-social-publisher-1' '/data/qq-accounts' (Join-Path $SnapshotRoot 'docker\social-publisher-qq-accounts')
    Invoke-DockerCopy 'gying-movie-social-publisher-1' '/data/weibo' (Join-Path $SnapshotRoot 'docker\social-publisher-weibo')
    Invoke-DockerCopy 'openclaw-openclaw-gateway-1' '/home/node' (Join-Path $SnapshotRoot 'docker\openclaw-home')

    Copy-OptionalPath (Join-Path $RepoRoot 'data\quark-auto-save') (Join-Path $SnapshotRoot 'external\quark-auto-save')
    Copy-OptionalPath (Join-Path $env:USERPROFILE '.openclaw') (Join-Path $SnapshotRoot 'external\openclaw')
    Copy-OptionalPath (Join-Path $env:USERPROFILE '.openclaw-auth-profile-secrets') (Join-Path $SnapshotRoot 'external\openclaw-auth-profile-secrets')

    $mcpPath = Join-Path $env:USERPROFILE '.codex\config.toml'
    Copy-OptionalPath $mcpPath (Join-Path $SnapshotRoot 'mcp\codex-config.toml')

    try {
        $taskRoot = Join-Path $SnapshotRoot 'scheduled-tasks'
        New-Item -ItemType Directory -Force -Path $taskRoot | Out-Null
        Get-ScheduledTask | Select-Object TaskName, TaskPath, State | Export-Csv `
            -LiteralPath (Join-Path $taskRoot 'all-tasks.csv') `
            -NoTypeInformation -Encoding UTF8
        foreach ($task in Get-ScheduledTask) {
            $safeName = (($task.TaskPath + $task.TaskName) -replace '[^A-Za-z0-9._-]', '_').Trim('_')
            if (-not $safeName) { $safeName = 'unnamed-task' }
            try {
                Export-ScheduledTask -TaskName $task.TaskName -TaskPath $task.TaskPath |
                    Out-File -LiteralPath (Join-Path $taskRoot "$safeName.xml") -Encoding utf8
            } catch {
                Add-Error "计划任务 XML 导出失败: $($task.TaskPath)$($task.TaskName)；$($_.Exception.Message)"
            }
        }
    } catch {
        Add-Error "计划任务清单导出失败；$($_.Exception.Message)"
    }

    $dbPassword = Read-EnvValue 'DB_PASSWORD'
    if ([string]::IsNullOrWhiteSpace($dbPassword)) {
        Add-Error '未找到 DB_PASSWORD，跳过 MySQL 导出'
    } else {
        $dbUser = Read-EnvValue 'DB_USER' 'root'
        $dbName = Read-EnvValue 'DB_NAME' 'gying'
        $dbPort = Read-EnvValue 'DB_PORT' '3306'
        $mysqlDir = Join-Path $SnapshotRoot 'mysql'
        New-Item -ItemType Directory -Force -Path $mysqlDir | Out-Null
        $dumpPath = Join-Path $mysqlDir 'gying.sql'
        $dumpLog = Join-Path $ManifestRoot 'mysqldump.log'
        try {
            $env:MYSQL_PWD = $dbPassword
            & mysqldump `
                --single-transaction `
                --routines `
                --triggers `
                --events `
                --set-gtid-purged=OFF `
                --default-character-set=utf8mb4 `
                --host=127.0.0.1 `
                --port=$dbPort `
                --user=$dbUser `
                --result-file=$dumpPath `
                $dbName 2>&1 | Out-File -FilePath $dumpLog -Encoding utf8
            if ($LASTEXITCODE -ne 0) { throw "mysqldump 退出码 $LASTEXITCODE" }
            if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) { throw '未生成 gying.sql' }
        } catch {
            Add-Error "MySQL 导出失败；$($_.Exception.Message)"
        } finally {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
    }

    $readme = @"
GYing Movie 当前迁移快照

生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
源部署目录：$RepoRoot

本目录由 export-current-migration.ps1 生成，包含敏感配置和生产数据。
NapCat、Redis 缓存和 PanSou 缓存不在导出范围内。
正式迁移前必须在维护窗口暂停 Worker、机器人和发布调度，并重新执行最终导出。
"@
    $readme | Out-File -FilePath (Join-Path $ManifestRoot 'README.txt') -Encoding utf8

    $hashPath = Join-Path $SnapshotRoot 'sha256.txt'
    $hashLines = New-Object System.Collections.Generic.List[string]
    Get-ChildItem -LiteralPath $SnapshotRoot -Recurse -File |
        Where-Object { $_.FullName -ne $hashPath } |
        ForEach-Object {
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $relative = $_.FullName.Substring($SnapshotRoot.Length + 1).Replace('\', '/')
            $hashLines.Add("$hash  $relative")
        }
    $hashLines | Sort-Object | Out-File -LiteralPath $hashPath -Encoding ascii

    if ($Errors.Count -gt 0) {
        $Errors | Out-File -LiteralPath (Join-Path $ManifestRoot 'export-errors.txt') -Encoding utf8
        throw "迁移快照存在 $($Errors.Count) 个错误，未更新 LATEST.txt"
    }

    $latestPath = Join-Path $MigrationRoot 'LATEST.txt'
    $SnapshotRoot | Out-File -LiteralPath $latestPath -Encoding ascii -NoNewline
    Write-Output "迁移快照已生成: $SnapshotRoot"
} catch {
    if ($Errors.Count -eq 0) { Add-Error $_.Exception.Message }
    $Errors | Out-File -LiteralPath (Join-Path $ManifestRoot 'export-errors.txt') -Encoding utf8
    exit 1
}
