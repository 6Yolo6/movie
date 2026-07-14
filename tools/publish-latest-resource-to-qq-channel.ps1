param(
    [int]$Limit = 0,
    [string]$StateFile = "$env:USERPROFILE\.gying\qq-channel-posted.txt",
    [string]$RunLogFile = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $RunLogFile) {
    $RunLogFile = Join-Path $repoRoot "logs\qq-channel-auto-post-task.log"
}
$runLogDir = Split-Path -Parent $RunLogFile
New-Item -ItemType Directory -Force -Path $runLogDir | Out-Null

function Write-RunLog {
    param([string]$Message)

    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss.fff")
    Add-Content -LiteralPath $RunLogFile -Value "[$timestamp] $Message" -Encoding UTF8
}

Write-RunLog "START pid=$PID user=$([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) limit=$Limit"

$scriptMutex = [System.Threading.Mutex]::new($false, "Global\GYingQqChannelAutoPost")
$mutexAcquired = $false
try {
    try {
        $mutexAcquired = $scriptMutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] {
        $mutexAcquired = $true
    }
    if (-not $mutexAcquired) {
        Write-Host "QQ channel auto post skipped because another instance is running."
        Write-RunLog "SKIP another instance is running"
        return
    }

$envFile = Join-Path $repoRoot ".env"
if (-not (Test-Path $envFile)) {
    throw ".env not found at $envFile"
}

$envMap = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$') {
        $envMap[$matches[1]] = $matches[2].Trim('"')
    }
}

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
$mysqlPath = if ($mysql) { $mysql.Source } else { $null }
if (-not $mysqlPath) {
    $mysqlCandidates = @(
        $envMap["MYSQL_CLI_PATH"],
        "D:\MySQL8.0.28\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    )
    $mysqlPath = $mysqlCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
if (-not $mysqlPath) {
    throw "mysql client is not installed or configured"
}
Write-RunLog "MYSQL path=$mysqlPath"

$stateDir = Split-Path -Parent $StateFile
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
if (-not (Test-Path $StateFile)) {
    New-Item -ItemType File -Path $StateFile | Out-Null
}

$postedIds = [System.Collections.Generic.HashSet[string]]::new()
Get-Content $StateFile | ForEach-Object {
    if ($_ -and $_.Trim()) {
        [void]$postedIds.Add($_.Trim())
    }
}

$hostName = $envMap["DB_HOST"]
if (-not $hostName -or $hostName -eq "host.docker.internal") {
    $hostName = "127.0.0.1"
}
$port = if ($envMap["DB_PORT"]) { $envMap["DB_PORT"] } else { "3306" }
$user = if ($envMap["DB_USER"]) { $envMap["DB_USER"] } else { "root" }
$db = if ($envMap["DB_NAME"]) { $envMap["DB_NAME"] } else { "gying" }
$env:MYSQL_PWD = if ($envMap["GYING_DB_PASSWORD"]) { $envMap["GYING_DB_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
$defaultIntro = -join ([char[]](0x6682, 0x65e0, 0x7b80, 0x4ecb))

function Invoke-GyingMysql {
    param([string]$Sql)

    $output = & $mysqlPath `
        -h $hostName `
        -P $port `
        -u $user `
        $db `
        --default-character-set=utf8mb4 `
        --batch `
        --raw `
        --skip-column-names `
        -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Escape-Sql {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return ""
    }
    return $Value.Replace("\", "\\").Replace("'", "''")
}

function Convert-HexUtf8 {
    param([string]$Hex)

    if (-not $Hex) {
        return ""
    }
    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Read-ConfigMap {
    $rows = Invoke-GyingMysql "SELECT config_key, HEX(config_value) FROM sys_config WHERE config_key LIKE 'qq.channel.%';"
    $map = @{}
    foreach ($row in $rows) {
        $parts = $row -split "`t", 2
        if ($parts.Count -eq 2) {
            $map[$parts[0]] = Convert-HexUtf8 $parts[1]
        }
    }
    return $map
}

$config = Read-ConfigMap
$autoEnabled = if ($config["qq.channel.auto_post.enabled"]) { $config["qq.channel.auto_post.enabled"] } else { "false" }
$dailyTime = if ($config["qq.channel.auto_post.daily_time"]) { $config["qq.channel.auto_post.daily_time"] } else { "09:00" }
$intervalMinutes = if ($config["qq.channel.auto_post.interval_minutes"]) { [int]$config["qq.channel.auto_post.interval_minutes"] } else { 60 }
$maxPostsPerRun = if ($Limit -gt 0) { $Limit } elseif ($config["qq.channel.auto_post.post_total"]) { [int]$config["qq.channel.auto_post.post_total"] } elseif ($config["qq.channel.auto_post.max_posts_per_run"]) { [int]$config["qq.channel.auto_post.max_posts_per_run"] } else { 1 }
$postIntervalSeconds = if ($config["qq.channel.auto_post.post_interval_seconds"]) { [int]$config["qq.channel.auto_post.post_interval_seconds"] } else { 60 }
$defaultPostTemplate = (-join ([char[]](0x6807, 0x9898, 0xff1a))) + "{{title}}`n" + (-join ([char[]](0x94fe, 0x63a5, 0xff1a))) + "{{link}}`n" + (-join ([char[]](0x7b80, 0x4ecb, 0xff1a))) + "{{intro}}"
$postTemplate = if ($config["qq.channel.auto_post.template"]) { $config["qq.channel.auto_post.template"] } else { $defaultPostTemplate }
$candidateLimit = if ($config["qq.channel.auto_post.candidate_limit"]) { [int]$config["qq.channel.auto_post.candidate_limit"] } else { 10 }
$guildId = if ($envMap["QQ_CHANNEL_GUILD_ID"]) { $envMap["QQ_CHANNEL_GUILD_ID"] } elseif ($config["qq.channel.guild_id"]) { $config["qq.channel.guild_id"] } else { $env:QQ_CHANNEL_GUILD_ID }
if (-not $guildId -or $guildId -eq "736090076") {
    $guildId = "86486581783412489"
}
$movieChannelId = if ($envMap["QQ_CHANNEL_MOVIE_ID"]) { $envMap["QQ_CHANNEL_MOVIE_ID"] } elseif ($config["qq.channel.movie_channel_id"]) { $config["qq.channel.movie_channel_id"] } else { $env:QQ_CHANNEL_MOVIE_ID }
$tvChannelId = if ($envMap["QQ_CHANNEL_TV_ID"]) { $envMap["QQ_CHANNEL_TV_ID"] } elseif ($config["qq.channel.tv_channel_id"]) { $config["qq.channel.tv_channel_id"] } else { $env:QQ_CHANNEL_TV_ID }
$minioUrlPrefix = if ($envMap["MINIO_URL_PREFIX"]) { $envMap["MINIO_URL_PREFIX"] } else { "http://127.0.0.1:9000/gying/" }
$minioUrlPrefix = $minioUrlPrefix.Replace("host.docker.internal", "127.0.0.1").TrimEnd("/") + "/"

function Resolve-PosterUrl {
    param([string]$Poster)

    if (-not $Poster) {
        return ""
    }
    if ($Poster -match '^https?://') {
        return $Poster.Replace("host.docker.internal", "127.0.0.1")
    }
    return $minioUrlPrefix + $Poster.TrimStart("/")
}

$pendingSql = @"
SELECT
  rl.id,
  rl.movie_id,
  HEX(COALESCE(NULLIF(m.title_cn, ''), NULLIF(m.title_en, ''), NULLIF(qcp.title, ''), m.id)) AS title_hex,
  COALESCE(NULLIF(qcp.link_url, ''), rl.url) AS url,
  HEX(LEFT(COALESCE(NULLIF(m.summary, ''), '$defaultIntro'), 180)) AS intro_hex,
  LOWER(COALESCE(NULLIF(qcp.channel_type, ''), NULLIF(m.tmdb_type, ''), NULLIF(m.category, ''), 'movie')) AS media_type,
  COALESCE(m.poster_url, '') AS poster_url
FROM qq_channel_post_log qcp
JOIN resource_link rl ON rl.id = qcp.resource_link_id
JOIN movie_metadata m ON m.id = rl.movie_id
WHERE qcp.status = 'PENDING'
  AND rl.status = 'ACTIVE'
  AND COALESCE(rl.link_status, 'NORMAL') = 'NORMAL'
  AND COALESCE(rl.url, '') <> ''
ORDER BY qcp.created_at ASC
LIMIT $candidateLimit;
"@

try {
    $pendingRows = Invoke-GyingMysql $pendingSql
    Write-RunLog "PENDING rows=$(@($pendingRows).Count)"
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

$rows = @()
$pendingIds = [System.Collections.Generic.HashSet[string]]::new()
if ($pendingRows) {
    $rows += $pendingRows
    foreach ($pendingRow in $pendingRows) {
        $pendingParts = $pendingRow -split "`t", 2
        if ($pendingParts.Count -ge 1 -and $pendingParts[0]) {
            [void]$pendingIds.Add($pendingParts[0])
        }
    }
}

$lastRunFile = Join-Path $stateDir "qq-channel-last-run.txt"
$autoAllowed = $autoEnabled.ToLowerInvariant() -eq "true"
if ($autoAllowed -and (Test-Path $lastRunFile)) {
    $lastRunText = (Get-Content $lastRunFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $lastRun = [datetime]::MinValue
    if ([datetime]::TryParse($lastRunText, [ref]$lastRun)) {
        if ((Get-Date) -lt $lastRun.AddMinutes($intervalMinutes)) {
            Write-Host "QQ channel auto post skipped by interval."
            $autoAllowed = $false
        }
    }
}

$todayStamp = (Get-Date).ToString("yyyy-MM-dd")
$dailyRunFile = Join-Path $stateDir "qq-channel-last-daily-run.txt"
if ($autoAllowed -and $dailyTime -match '^\d{1,2}:\d{2}$') {
    $target = [datetime]::ParseExact("$todayStamp $dailyTime", "yyyy-MM-dd H:mm", $null)
    if ((Get-Date) -lt $target) {
        Write-Host "QQ channel auto post skipped before daily time $dailyTime."
        $autoAllowed = $false
    }
    if ((Test-Path $dailyRunFile) -and ((Get-Content $dailyRunFile -ErrorAction SilentlyContinue | Select-Object -First 1) -eq $todayStamp)) {
        Write-Host "QQ channel auto post already ran today."
        $autoAllowed = $false
    }
}

if ($autoAllowed) {
    $sql = @"
SELECT
  rl.id,
  rl.movie_id,
  HEX(COALESCE(NULLIF(m.title_cn, ''), NULLIF(m.title_en, ''), m.id)) AS title_hex,
  rl.url,
  HEX(LEFT(COALESCE(NULLIF(m.summary, ''), '$defaultIntro'), 180)) AS intro_hex,
  LOWER(COALESCE(NULLIF(m.tmdb_type, ''), NULLIF(m.category, ''), 'movie')) AS media_type,
  COALESCE(m.poster_url, '') AS poster_url
FROM resource_link rl
JOIN movie_metadata m ON m.id = rl.movie_id
LEFT JOIN qq_channel_post_log qcp ON qcp.resource_link_id = rl.id AND qcp.status = 'POSTED'
WHERE rl.status = 'ACTIVE'
  AND COALESCE(rl.link_status, 'NORMAL') = 'NORMAL'
  AND rl.source = 'RESOURCE_HUB'
  AND COALESCE(rl.url, '') <> ''
  AND qcp.id IS NULL
ORDER BY rl.created_at DESC
LIMIT $candidateLimit;
"@

    try {
        $env:MYSQL_PWD = if ($envMap["GYING_DB_PASSWORD"]) { $envMap["GYING_DB_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
        $autoRows = Invoke-GyingMysql $sql
        Write-RunLog "AUTO rows=$(@($autoRows).Count)"
        if ($autoRows) {
            $rows += $autoRows
        }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
} elseif (-not $rows -or $rows.Count -eq 0) {
    Write-Host "QQ channel auto post is disabled or not due, and no manual pending posts."
    Write-RunLog "SKIP no due auto rows and no manual PENDING rows"
    exit 0
}

function Resolve-ChannelType {
    param([string]$MediaType)

    $normalized = if ($MediaType) { $MediaType.Trim().ToLowerInvariant() } else { "" }
    if ($normalized -in @("tv", "series", "show", "drama")) {
        return "tv"
    }
    return "movie"
}

$published = 0
$rowIndex = 0
$totalRows = @($rows).Count
foreach ($row in @($rows)) {
    $rowIndex++
    if ($published -ge $maxPostsPerRun) {
        break
    }
    $parts = $row -split "`t", 7
    if ($parts.Count -lt 7) {
        continue
    }
    $resourceId = $parts[0]
    $movieId = $parts[1]
    if ($postedIds.Contains($resourceId) -and -not $pendingIds.Contains($resourceId)) {
        continue
    }
    $title = Convert-HexUtf8 $parts[2]
    $link = $parts[3]
    $intro = Convert-HexUtf8 $parts[4]
    $channelType = Resolve-ChannelType $parts[5]
    $posterUrl = Resolve-PosterUrl $parts[6]
    $channelId = if ($channelType -eq "tv") { $tvChannelId } else { $movieChannelId }
    Write-RunLog "POST start resource=$resourceId movie=$movieId channelType=$channelType poster=$([bool]$posterUrl)"

    try {
        & (Join-Path $PSScriptRoot "publish-qq-channel-feed.ps1") `
            -Title $title `
            -Link $link `
            -Intro $intro `
            -PosterUrl $posterUrl `
            -ChannelType $channelType `
            -GuildId $guildId `
            -MovieChannelId $movieChannelId `
            -TvChannelId $tvChannelId `
            -ContentTemplate $postTemplate

        $logSql = "INSERT INTO qq_channel_post_log (resource_link_id, movie_id, title, link_url, channel_type, channel_id, status, posted_at, created_at) VALUES ($resourceId, '$(Escape-Sql $movieId)', '$(Escape-Sql $title)', '$(Escape-Sql $link)', '$(Escape-Sql $channelType)', '$(Escape-Sql $channelId)', 'POSTED', NOW(), NOW()) ON DUPLICATE KEY UPDATE status='POSTED', error_message=NULL, posted_at=NOW(), created_at=created_at;"
        $env:MYSQL_PWD = if ($envMap["GYING_DB_PASSWORD"]) { $envMap["GYING_DB_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
        Invoke-GyingMysql $logSql | Out-Null
        Write-RunLog "POST success resource=$resourceId"
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        Add-Content -Path $StateFile -Value $resourceId
        $published++
        if ($postIntervalSeconds -gt 0 -and $published -lt $maxPostsPerRun -and $rowIndex -lt $totalRows) {
            Start-Sleep -Seconds $postIntervalSeconds
        }
    } catch {
        $errorMessage = $_.Exception.Message
        Write-RunLog "POST failed resource=$resourceId error=$errorMessage"
        $logSql = "INSERT INTO qq_channel_post_log (resource_link_id, movie_id, title, link_url, channel_type, channel_id, status, error_message, created_at) VALUES ($resourceId, '$(Escape-Sql $movieId)', '$(Escape-Sql $title)', '$(Escape-Sql $link)', '$(Escape-Sql $channelType)', '$(Escape-Sql $channelId)', 'FAILED', '$(Escape-Sql $errorMessage)', NOW()) ON DUPLICATE KEY UPDATE status='FAILED', error_message='$(Escape-Sql $errorMessage)', created_at=created_at;"
        $env:MYSQL_PWD = if ($envMap["GYING_DB_PASSWORD"]) { $envMap["GYING_DB_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
        Invoke-GyingMysql $logSql | Out-Null
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

if ($published -gt 0) {
    Set-Content -Path $lastRunFile -Value (Get-Date).ToString("o")
    Set-Content -Path $dailyRunFile -Value $todayStamp
}
} finally {
    Write-RunLog "END published=$published"
    if ($mutexAcquired) {
        [void]$scriptMutex.ReleaseMutex()
    }
    $scriptMutex.Dispose()
}
