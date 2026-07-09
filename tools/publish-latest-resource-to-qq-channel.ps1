param(
    [int]$Limit = 10,
    [string]$StateFile = "$env:USERPROFILE\.gying\qq-channel-posted.txt"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
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
if (-not $mysql) {
    throw "mysql client is not installed or not in PATH"
}
$mysqlPath = $mysql.Source

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
$guildId = if ($envMap["QQ_CHANNEL_GUILD_ID"]) { $envMap["QQ_CHANNEL_GUILD_ID"] } else { $env:QQ_CHANNEL_GUILD_ID }
$movieChannelId = if ($envMap["QQ_CHANNEL_MOVIE_ID"]) { $envMap["QQ_CHANNEL_MOVIE_ID"] } else { $env:QQ_CHANNEL_MOVIE_ID }
$tvChannelId = if ($envMap["QQ_CHANNEL_TV_ID"]) { $envMap["QQ_CHANNEL_TV_ID"] } else { $env:QQ_CHANNEL_TV_ID }
$env:MYSQL_PWD = if ($envMap["GYING_DB_PASSWORD"]) { $envMap["GYING_DB_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
$defaultIntro = -join ([char[]](0x6682, 0x65e0, 0x7b80, 0x4ecb))

$sql = @"
SELECT
  rl.id,
  COALESCE(NULLIF(m.title_cn, ''), NULLIF(m.title_en, ''), m.id) AS title,
  rl.url,
  LEFT(COALESCE(NULLIF(m.summary, ''), '$defaultIntro'), 180) AS intro,
  LOWER(COALESCE(NULLIF(m.tmdb_type, ''), NULLIF(m.category, ''), 'movie')) AS media_type
FROM resource_link rl
JOIN movie_metadata m ON m.id = rl.movie_id
WHERE rl.status = 'ACTIVE'
  AND COALESCE(rl.link_status, 'NORMAL') <> 'INVALID'
  AND rl.source = 'RESOURCE_HUB'
  AND COALESCE(rl.url, '') <> ''
ORDER BY rl.created_at DESC
LIMIT $Limit;
"@

try {
    $rows = & $mysqlPath `
        -h $hostName `
        -P $port `
        -u $user `
        $db `
        --default-character-set=utf8mb4 `
        --batch `
        --raw `
        --skip-column-names `
        -e $sql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

function Resolve-ChannelType {
    param([string]$MediaType)

    $normalized = if ($MediaType) { $MediaType.Trim().ToLowerInvariant() } else { "" }
    if ($normalized -in @("tv", "series", "show", "drama")) {
        return "tv"
    }
    return "movie"
}

foreach ($row in $rows) {
    $parts = $row -split "`t", 5
    if ($parts.Count -lt 5) {
        continue
    }
    $resourceId = $parts[0]
    if ($postedIds.Contains($resourceId)) {
        continue
    }
    $title = $parts[1]
    $link = $parts[2]
    $intro = $parts[3]
    $channelType = Resolve-ChannelType $parts[4]

    & (Join-Path $PSScriptRoot "publish-qq-channel-feed.ps1") `
        -Title $title `
        -Link $link `
        -Intro $intro `
        -ChannelType $channelType `
        -GuildId $guildId `
        -MovieChannelId $movieChannelId `
        -TvChannelId $tvChannelId

    Add-Content -Path $StateFile -Value $resourceId
    break
}
