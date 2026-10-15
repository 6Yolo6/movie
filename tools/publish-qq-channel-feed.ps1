param(
    [Parameter(Mandatory = $true)]
    [string]$Title,

    [Parameter(Mandatory = $true)]
    [string]$Link,

    [Parameter(Mandatory = $true)]
    [string]$Intro,

    [string]$Year = "",
    [string]$MediaType = "",
    [string]$PosterUrl = "",
    [string]$GuildId = $env:QQ_CHANNEL_GUILD_ID,
    [string]$ChannelId = $env:QQ_CHANNEL_ID,
    [ValidateSet("movie", "tv")]
    [string]$ChannelType = "movie",
    [string]$MovieChannelId = $env:QQ_CHANNEL_MOVIE_ID,
    [string]$TvChannelId = $env:QQ_CHANNEL_TV_ID,
    [string]$ContentTemplate = "",
    [AllowNull()][AllowEmptyCollection()][object[]]$Resources = @()
)

$ErrorActionPreference = "Stop"

if (-not $GuildId -or $GuildId -eq "736090076") {
    $GuildId = "86486581783412489"
}

if (-not $ChannelId) {
    if ($ChannelType -eq "tv") {
        $ChannelId = $TvChannelId
    } else {
        $ChannelId = if ($MovieChannelId) { $MovieChannelId } else { "736142774" }
    }
}

if (-not $ChannelId) {
    throw "QQ channel board is not configured for type '$ChannelType'. Set QQ_CHANNEL_MOVIE_ID or QQ_CHANNEL_TV_ID."
}

$cli = Get-Command tencent-channel-cli.cmd -ErrorAction SilentlyContinue
if (-not $cli) {
    $cli = Get-Command tencent-channel-cli -ErrorAction SilentlyContinue
}
$cliPath = if ($cli) { $cli.Source } else { $null }
if (-not $cliPath -and (Test-Path "C:\Program Files\nodejs\tencent-channel-cli.cmd")) {
    $cliPath = "C:\Program Files\nodejs\tencent-channel-cli.cmd"
}
if (-not $cliPath) {
    throw "tencent-channel-cli is not installed. Run: npm install -g tencent-channel-cli"
}
if ($cliPath.EndsWith(".ps1", [System.StringComparison]::OrdinalIgnoreCase)) {
    $cmdPath = [System.IO.Path]::ChangeExtension($cliPath, ".cmd")
    if (Test-Path $cmdPath) {
        $cliPath = $cmdPath
    }
}

. (Join-Path $PSScriptRoot "qq-channel-resources.ps1")
$content = Format-QqChannelPostContent -Title $Title -Link $Link -Intro $Intro -Year $Year -MediaType $MediaType -ContentTemplate $ContentTemplate -Resources $Resources

$posterPath = $null
$posterDownloadPath = $null
$contentPath = $null
try {
    $contentDir = Join-Path ([System.IO.Path]::GetTempPath()) "gying-qq-channel-content"
    New-Item -ItemType Directory -Force -Path $contentDir | Out-Null
    $contentPath = Join-Path $contentDir (([System.Guid]::NewGuid().ToString("N")) + ".txt")
    [System.IO.File]::WriteAllText($contentPath, $content, [System.Text.UTF8Encoding]::new($false))
    if ($PosterUrl) {
        try {
            $posterDir = Join-Path ([System.IO.Path]::GetTempPath()) "gying-qq-channel-posters"
            New-Item -ItemType Directory -Force -Path $posterDir | Out-Null
            $extension = [System.IO.Path]::GetExtension(([System.Uri]$PosterUrl).AbsolutePath).ToLowerInvariant()
            if ($extension -notin @(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")) {
                $extension = ".jpg"
            }
            $posterDownloadPath = Join-Path $posterDir (([System.Guid]::NewGuid().ToString("N")) + $extension)
            Invoke-WebRequest -Uri $PosterUrl -OutFile $posterDownloadPath -UseBasicParsing
            if (-not (Test-Path $posterDownloadPath) -or (Get-Item $posterDownloadPath).Length -le 0) {
                throw "Downloaded poster is empty"
            }

            if ($extension -eq ".avif") {
                $converter = Join-Path $PSScriptRoot "convert-image-to-jpeg.cjs"
                $node = Get-Command node.exe -ErrorAction SilentlyContinue
                $nodePath = if ($node) { $node.Source } else { "C:\Program Files\nodejs\node.exe" }
                if (-not (Test-Path $nodePath) -or -not (Test-Path $converter)) {
                    throw "AVIF converter is unavailable"
                }
                $posterPath = Join-Path $posterDir (([System.Guid]::NewGuid().ToString("N")) + ".jpg")
                $conversionOutput = & $nodePath $converter $posterDownloadPath $posterPath 2>&1
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $posterPath) -or (Get-Item $posterPath).Length -le 0) {
                    throw "AVIF conversion failed: $($conversionOutput | Out-String)"
                }
            } else {
                $posterPath = $posterDownloadPath
            }
        } catch {
            Write-Warning ("Poster preparation failed; publishing without image: " + $_.Exception.Message)
            if ($posterPath) {
                Remove-Item -LiteralPath $posterPath -Force -ErrorAction SilentlyContinue
            }
            if ($posterDownloadPath -and $posterDownloadPath -ne $posterPath) {
                Remove-Item -LiteralPath $posterDownloadPath -Force -ErrorAction SilentlyContinue
            }
            $posterPath = $null
            $posterDownloadPath = $null
        }
    }

    $arguments = @(
        "feed", "publish-feed",
        "--guild-id", $GuildId,
        "--channel-id", $ChannelId,
        "--content-file", $contentPath
    )
    if ($posterPath) {
        $arguments += @("--image", $posterPath)
    }
    $arguments += "--json"

    $output = & $cliPath @arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($output) {
        $output | Write-Output
    }

    if ($exitCode -ne 0) {
        $detail = ($output | Out-String).Trim()
        if ($detail.Length -gt 1000) {
            $detail = $detail.Substring(0, 1000)
        }
        throw "tencent-channel-cli publish-feed failed with exit code $exitCode. $detail"
    }
} finally {
    if ($contentPath) {
        Remove-Item -LiteralPath $contentPath -Force -ErrorAction SilentlyContinue
    }
    if ($posterPath) {
        Remove-Item -LiteralPath $posterPath -Force -ErrorAction SilentlyContinue
    }
    if ($posterDownloadPath -and $posterDownloadPath -ne $posterPath) {
        Remove-Item -LiteralPath $posterDownloadPath -Force -ErrorAction SilentlyContinue
    }
}
