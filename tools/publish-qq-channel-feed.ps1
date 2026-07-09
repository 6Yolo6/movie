param(
    [Parameter(Mandatory = $true)]
    [string]$Title,

    [Parameter(Mandatory = $true)]
    [string]$Link,

    [Parameter(Mandatory = $true)]
    [string]$Intro,

    [string]$GuildId = $env:QQ_CHANNEL_GUILD_ID,
    [string]$ChannelId = $env:QQ_CHANNEL_ID,
    [ValidateSet("movie", "tv")]
    [string]$ChannelType = "movie",
    [string]$MovieChannelId = $env:QQ_CHANNEL_MOVIE_ID,
    [string]$TvChannelId = $env:QQ_CHANNEL_TV_ID
)

$ErrorActionPreference = "Stop"

if (-not $GuildId) {
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

$cli = Get-Command tencent-channel-cli -ErrorAction SilentlyContinue
if (-not $cli) {
    throw "tencent-channel-cli is not installed. Run: npm install -g tencent-channel-cli"
}
$cliPath = $cli.Source
if ($cliPath.EndsWith(".ps1", [System.StringComparison]::OrdinalIgnoreCase)) {
    $cmdPath = [System.IO.Path]::ChangeExtension($cliPath, ".cmd")
    if (Test-Path $cmdPath) {
        $cliPath = $cmdPath
    }
}

$titleLabel = -join ([char[]](0x6807, 0x9898, 0xff1a))
$linkLabel = -join ([char[]](0x94fe, 0x63a5, 0xff1a))
$introLabel = -join ([char[]](0x7b80, 0x4ecb, 0xff1a))
$content = "$titleLabel$Title`n$linkLabel[$Link]($Link)`n$introLabel$Intro"

& $cliPath feed publish-feed `
    --guild-id $GuildId `
    --channel-id $ChannelId `
    --content $content `
    --json

if ($LASTEXITCODE -ne 0) {
    throw "tencent-channel-cli publish-feed failed with exit code $LASTEXITCODE"
}
