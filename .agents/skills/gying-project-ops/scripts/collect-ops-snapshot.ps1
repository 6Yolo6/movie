[CmdletBinding()]
param(
    [string]$RepoRoot,
    [string]$OutputPath,
    [switch]$ProbeHealth
)

$ErrorActionPreference = "Stop"
$zh = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
    "eyJ0aXRsZSI6IiMgR1lpbmcgTW92aWUg6L+Q57u05b+r54WnIiwiY29sbGVjdGVkIjoiLSDph4fpm4bml7bpl7TvvJp7MH0iLCJyZXBvc2l0b3J5IjoiLSDku5PlupPvvJpgezB9YCIsImJyYW5jaCI6Ii0g5YiG5pSv77yaYHswfWAiLCJjb21taXQiOiItIOaPkOS6pO+8mmB7MH1gIiwiY29tbWl0UmVjb3JkIjoiLSDmj5DkuqTorrDlvZXvvJp7MH0iLCJ0cmFja2VkRmlsZXMiOiItIOW3sui3n+i4quaWh+S7tu+8mnswfSIsIndvcmt0cmVlQ2hhbmdlcyI6Ii0g5bel5L2c5qCR5pS55Yqo77yaezB9Iiwic3RhdHVzSGVhZGluZyI6IiMjIOeKtuaAgeaWh+ahoyIsInBhdGgiOiItIOi3r+W+hO+8mmBkb2NzL2N1cnJlbnQtcHJvamVjdC1zdGF0dXMubWRgIiwiZGVjbGFyZWRVcGRhdGUiOiItIOWjsOaYjuabtOaWsOaXtumXtO+8mnswfSIsImxpbmVzIjoiLSDooYzmlbDvvJp7MH0iLCJsYXN0R2l0Q2hhbmdlIjoiLSDmnIDov5EgR2l0IOWPmOabtO+8mnswfSIsIm1pc3NpbmdTdGF0dXMiOiItIOe8uuWkse+8mmBkb2NzL2N1cnJlbnQtcHJvamVjdC1zdGF0dXMubWRgIiwiYnVpbGRIZWFkaW5nIjoiIyMg5p6E5bu65LiO6YWN572uIiwiZnJvbnRlbmQiOiItIOWJjeerr++8mk5leHQuanMgezB977yMUmVhY3QgezF9IiwiYmFja2VuZCI6Ii0g5ZCO56uv77yaU3ByaW5nIEJvb3QgezB977yMSmF2YSB7MX0iLCJ1bmtub3duIjoi5pyq55+lIiwicm9vdEVudktleXMiOiItIOagueeOr+Wig+WPmOmHj+Wlkee6pumUru+8mnswfSIsImxpdmVFbnZLZXlzIjoiLSDlnKjnur8gYC5lbnZgIOmUruWQjeensO+8mnswfe+8iOacquivu+WPluaIlui+k+WHuuWAvO+8iSIsIm1pc3NpbmdMaXZlS2V5cyI6Ii0g5Zyo57q/IGAuZW52YCDnvLrlsJHnmoTnpLrkvovplK7vvJp7MH0iLCJub25lIjoi5pegIiwibGl2ZUVudkFic2VudCI6Ii0g5b2T5YmN5qOA5Ye655uu5b2V5rKh5pyJ5Zyo57q/IGAuZW52YCIsImNvbmZpZ1BhdGgiOiItIHswfSDphY3nva7ot6/lvoTvvJp7MX3vvIhgezJ9YO+8iSIsInByZXNlbnQiOiLlrZjlnKgiLCJub3RIZXJlIjoi5q2k5aSE5LiN5a2Y5ZyoIiwibWlzc2luZyI6Iue8uuWksSIsImRiSGVhZGluZyI6IiMjIOaVsOaNruW6kyBTUUwiLCJkYlRhYmxlSGVhZGVyIjoifCDmlofku7YgfCDooYzmlbAgfCDmnIDov5EgR2l0IOWPmOabtCB8IiwidGFibGVzRGVjbGFyZWQiOiItIGBzY2hlbWEuc3FsYCDlo7DmmI7nmoTooajvvIh7MH3vvInvvJp7MX0iLCJyZXNvdXJjZUhlYWRpbmciOiIjIyBSZXNvdXJjZSBIdWIg6YWN572u5aWR57qmIiwiZW52S2V5cyI6Ii0g546v5aKD5Y+Y6YeP6ZSu77yIezB977yJ77yaezF9IiwicnVudGltZUtleXMiOiItIOi/kOihjOaXtiBgc3lzX2NvbmZpZ2Ag6ZSu77yIezB977yJ77yaezF9IiwibWNwSGVhZGluZyI6IiMjIE1DUCIsImZpbGVQcmVzZW5jZSI6Ii0gYHswfWDvvJp7MX0iLCJtY3BTZWN0aW9ucyI6Ii0gQ29kZXggTUNQIOmFjee9rueroOiKgu+8mnswfSIsIm1jcEFic2VudCI6Ii0gQ29kZXggTUNQIOmFjee9ruS4jeWtmOWcqCIsImRvY2tlckhlYWRpbmciOiIjIyBEb2NrZXIiLCJkb2NrZXJVbmF2YWlsYWJsZSI6Ii0gRG9ja2VyIENMSSDkuI3lj6/nlKgiLCJkb2NrZXJTZXJ2ZXIiOiItIERvY2tlciBTZXJ2ZXLvvJp7MH0iLCJ1bmF2YWlsYWJsZSI6IuS4jeWPr+eUqCIsImNvbnRhaW5lckhlYWRlciI6Inwg5a655ZmoIHwg6ZWc5YOPIHwg54q25oCBIHwg56uv5Y+jIHwiLCJub25lRGV0ZWN0ZWQiOiJ8IOacquWPkeeOsOebuOWFs+WuueWZqCB8IHwgfCB8IiwiY29tcG9zZUZpbGUiOiItIENvbXBvc2Ug5paH5Lu277yaezB977yI5aeL57uI5L2/55SoIGAtZiBkb2NrZXItY29tcG9zZS5wcm9kLnltbGDvvIkiLCJoZWFsdGhIZWFkaW5nIjoiIyMg5pys5py65YGl5bq35qOA5p+lIiwicHJvYmVGYWlsZWQiOiItIGB7MH1gIC0+IOWksei0pe+8iHsxfe+8iSIsInNuYXBzaG90V3JpdHRlbiI6IuW/q+eFp+W3suWGmeWFpSB7MH0ifQ=="
)) | ConvertFrom-Json

function Resolve-GyingRepoRoot {
    param([string]$RequestedRoot)

    if ($RequestedRoot) {
        return (Resolve-Path -LiteralPath $RequestedRoot).Path
    }

    $candidate = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -eq 0 -and $candidate) {
        return (Resolve-Path -LiteralPath $candidate.Trim()).Path
    }

    $fromSkill = Join-Path $PSScriptRoot "..\..\..\.."
    return (Resolve-Path -LiteralPath $fromSkill).Path
}

function Get-EnvKeyNames {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return @()
    }

    return @(
        Get-Content -Encoding UTF8 -LiteralPath $Path |
            ForEach-Object {
                if ($_ -match '^\s*([^#=\s]+)=') {
                    $matches[1]
                }
            } |
            Sort-Object -Unique
    )
}

function Escape-MarkdownCell {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return $Value.Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
}

function Add-Line {
    param([string]$Text = "")
    $script:ReportLines.Add($Text) | Out-Null
}

$root = Resolve-GyingRepoRoot -RequestedRoot $RepoRoot
$statusPath = Join-Path $root "docs\current-project-status.md"
$composePath = Join-Path $root "docker-compose.prod.yml"
$rootEnvExample = Join-Path $root ".env.example"
$liveEnv = Join-Path $root ".env"
$dbDir = Join-Path $root "backend\src\main\resources\db"
$reportLines = [System.Collections.Generic.List[string]]::new()
$script:ReportLines = $reportLines

Add-Line $zh.title
Add-Line
Add-Line ($zh.collected -f ((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')))
Add-Line ($zh.repository -f $root)

$branch = (& git -C $root branch --show-current 2>$null).Trim()
if (-not $branch) {
    $branch = "(detached)"
}
$commit = (& git -C $root rev-parse HEAD 2>$null).Trim()
$commitSummary = (& git -C $root log -1 --format="%cI %s" 2>$null).Trim()
$statusRows = @(& git -C $root status --short 2>$null)
$trackedCount = @(& git -C $root ls-files 2>$null).Count

Add-Line ($zh.branch -f $branch)
Add-Line ($zh.commit -f $commit)
Add-Line ($zh.commitRecord -f (Escape-MarkdownCell $commitSummary))
Add-Line ($zh.trackedFiles -f $trackedCount)
Add-Line ($zh.worktreeChanges -f $statusRows.Count)

Add-Line
Add-Line $zh.statusHeading
if (Test-Path -LiteralPath $statusPath) {
    $statusLines = @(Get-Content -Encoding UTF8 -LiteralPath $statusPath)
    $datePrefix = -join ([char[]](0x66F4, 0x65B0, 0x65F6, 0x95F4, 0xFF1A))
    $dateLine = $statusLines | Where-Object { $_.StartsWith($datePrefix) } | Select-Object -First 1
    $statusCommit = (& git -C $root log -1 --format="%cI %h %s" -- "docs/current-project-status.md" 2>$null).Trim()
    Add-Line $zh.path
    Add-Line ($zh.declaredUpdate -f (Escape-MarkdownCell $dateLine))
    Add-Line ($zh.lines -f $statusLines.Count)
    Add-Line ($zh.lastGitChange -f (Escape-MarkdownCell $statusCommit))
} else {
    Add-Line $zh.missingStatus
}

Add-Line
Add-Line $zh.buildHeading
$packagePath = Join-Path $root "frontend\package.json"
if (Test-Path -LiteralPath $packagePath) {
    $package = Get-Content -Raw -Encoding UTF8 -LiteralPath $packagePath | ConvertFrom-Json
    Add-Line ($zh.frontend -f $package.dependencies.next, $package.dependencies.react)
}
$pomPath = Join-Path $root "backend\pom.xml"
if (Test-Path -LiteralPath $pomPath) {
    $pomText = Get-Content -Raw -Encoding UTF8 -LiteralPath $pomPath
    $springVersion = if ($pomText -match '<parent>[\s\S]*?<version>([^<]+)</version>') { $matches[1] } else { $zh.unknown }
    $javaVersion = if ($pomText -match '<java.version>([^<]+)</java.version>') { $matches[1] } else { $zh.unknown }
    Add-Line ($zh.backend -f $springVersion, $javaVersion)
}

$exampleKeys = @(Get-EnvKeyNames -Path $rootEnvExample)
$liveKeys = @(Get-EnvKeyNames -Path $liveEnv)
Add-Line ($zh.rootEnvKeys -f $exampleKeys.Count)
if (Test-Path -LiteralPath $liveEnv) {
    $missingLiveKeys = @($exampleKeys | Where-Object { $_ -notin $liveKeys })
    $missingKeyText = if ($missingLiveKeys) { $missingLiveKeys -join ", " } else { $zh.none }
    Add-Line ($zh.liveEnvKeys -f $liveKeys.Count)
    Add-Line ($zh.missingLiveKeys -f $missingKeyText)
} else {
    Add-Line $zh.liveEnvAbsent
}

$externalConfigChecks = @(
    [pscustomobject]@{ Name = "quark-auto-save"; Path = (Join-Path $root "data\quark-auto-save\quark_config.json") },
    [pscustomobject]@{ Name = "OpenClaw"; Path = (Join-Path $HOME ".openclaw\openclaw.json") }
)
foreach ($item in $externalConfigChecks) {
    $configState = if (Test-Path -LiteralPath $item.Path) { $zh.present } else { $zh.notHere }
    Add-Line ($zh.configPath -f $item.Name, $configState, $item.Path)
}

Add-Line
Add-Line $zh.dbHeading
if (Test-Path -LiteralPath $dbDir) {
    Add-Line $zh.dbTableHeader
    Add-Line "| --- | ---: | --- |"
    Get-ChildItem -LiteralPath $dbDir -Filter "*.sql" |
        Sort-Object Name |
        ForEach-Object {
            $lineCount = @(Get-Content -Encoding UTF8 -LiteralPath $_.FullName).Count
            $relative = "backend/src/main/resources/db/$($_.Name)"
            $lastChange = (& git -C $root log -1 --format="%cs %h" -- $relative 2>$null).Trim()
            Add-Line "| ``$($_.Name)`` | $lineCount | $(Escape-MarkdownCell $lastChange) |"
        }
}

$schemaPath = Join-Path $dbDir "schema.sql"
if (Test-Path -LiteralPath $schemaPath) {
    $schemaTables = @(
        Get-Content -Encoding UTF8 -LiteralPath $schemaPath |
            ForEach-Object {
                if ($_ -match '^\s*CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([A-Za-z0-9_]+)`?') {
                    $matches[1]
                }
            }
    )
    Add-Line
    Add-Line ($zh.tablesDeclared -f $schemaTables.Count, ($schemaTables -join ", "))
}

Add-Line
Add-Line $zh.resourceHeading
$resourceEnvKeys = @($exampleKeys | Where-Object { $_ -match '^(RESOURCE_HUB|TMDB|QUARK|PANSOU_API|GYING|QQ_CHANNEL_PUBLISHER|SOCIAL_PUBLISHER|WEIBO_WEB)' })
Add-Line ($zh.envKeys -f $resourceEnvKeys.Count, ($resourceEnvKeys -join ", "))
$configService = Join-Path $root "backend\src\main\java\com\gying\movie\service\impl\ResourceHubConfigServiceImpl.java"
if (Test-Path -LiteralPath $configService) {
    $runtimeKeys = @(
        Get-Content -Encoding UTF8 -LiteralPath $configService |
            ForEach-Object {
                if ($_ -match 'private static final String KEY_[A-Z0-9_]+\s*=\s*"([^"]+)"') {
                    $matches[1]
                }
            } |
            Sort-Object -Unique
    )
    Add-Line ($zh.runtimeKeys -f $runtimeKeys.Count, ($runtimeKeys -join ", "))
}

Add-Line
Add-Line $zh.mcpHeading
$mcpScripts = @("backend\mysql_gying_mcp.py", "backend\docker_mcp.py")
foreach ($relative in $mcpScripts) {
    $scriptState = if (Test-Path -LiteralPath (Join-Path $root $relative)) { $zh.present } else { $zh.missing }
    Add-Line ($zh.filePresence -f $relative.Replace("\", "/"), $scriptState)
}
$codexConfig = Join-Path $HOME ".codex\config.toml"
if (Test-Path -LiteralPath $codexConfig) {
    $sections = @(
        Get-Content -Encoding UTF8 -LiteralPath $codexConfig |
            ForEach-Object {
                if ($_ -match '^\[(mcp_servers[^\]]*)\]') {
                    $matches[1]
                }
            }
    )
    Add-Line ($zh.mcpSections -f ($sections -join ", "))
} else {
    Add-Line $zh.mcpAbsent
}

Add-Line
Add-Line $zh.dockerHeading
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    Add-Line $zh.dockerUnavailable
} else {
    $serverVersion = (& docker version --format "{{.Server.Version}}" 2>$null).Trim()
    $serverText = if ($serverVersion) { $serverVersion } else { $zh.unavailable }
    Add-Line ($zh.dockerServer -f $serverText)
    Add-Line
    Add-Line $zh.containerHeader
    Add-Line "| --- | --- | --- | --- |"
    $containers = @(& docker ps -a --format "{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}" 2>$null)
    $relevant = @($containers | Where-Object {
        $_ -match '(?i)gying|openclaw|minio|pansou|quark' -and $_ -notmatch '(?i)napcat'
    })
    if ($relevant.Count -eq 0) {
        Add-Line $zh.noneDetected
    } else {
        foreach ($row in $relevant) {
            $parts = $row -split '\|', 4
            Add-Line "| $(Escape-MarkdownCell $parts[0]) | $(Escape-MarkdownCell $parts[1]) | $(Escape-MarkdownCell $parts[2]) | $(Escape-MarkdownCell $parts[3]) |"
        }
    }
    Add-Line
    $composeState = if (Test-Path -LiteralPath $composePath) { $zh.present } else { $zh.missing }
    Add-Line ($zh.composeFile -f $composeState)
}

if ($ProbeHealth) {
    Add-Line
    Add-Line $zh.healthHeading
    $healthTargets = @(
        "http://127.0.0.1/",
        "http://127.0.0.1:8880/api/qq-bot/health",
        "http://127.0.0.1:5005/",
        "http://127.0.0.1:9000/minio/health/live",
        "http://127.0.0.1:18789/healthz"
    )
    foreach ($uri in $healthTargets) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 8
            Add-Line "- ``$uri`` -> $([int]$response.StatusCode)"
        } catch {
            $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            Add-Line ($zh.probeFailed -f $uri, $statusCode)
        }
    }
}

$report = $reportLines -join [Environment]::NewLine
if ($OutputPath) {
    $target = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        $OutputPath
    } else {
        Join-Path $root $OutputPath
    }
    $parent = Split-Path -Parent $target
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -Encoding UTF8 -LiteralPath $target -Value $report
    Write-Output ($zh.snapshotWritten -f $target)
} else {
    Write-Output $report
}
