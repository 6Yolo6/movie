param(
    [Parameter(Mandatory = $true)]
    [string]$Title,

    [Parameter(Mandatory = $true)]
    [string]$Link,

    [Parameter(Mandatory = $true)]
    [string]$Intro,

    [string]$GuildId = $env:QQ_CHANNEL_GUILD_ID,
    [string]$ChannelId = $env:QQ_CHANNEL_ID
)

if (-not $GuildId) {
    $GuildId = "86486581783412489"
}

if (-not $ChannelId) {
    # Default to the movie board. Pass QQ_CHANNEL_ID or -ChannelId for TV shows.
    $ChannelId = "736142774"
}

$cli = Get-Command tencent-channel-cli -ErrorAction SilentlyContinue
if (-not $cli) {
    throw "tencent-channel-cli is not installed. Run: npm install -g tencent-channel-cli"
}

$titleLabel = -join ([char[]](0x6807, 0x9898, 0xff1a))
$linkLabel = -join ([char[]](0x94fe, 0x63a5, 0xff1a))
$introLabel = -join ([char[]](0x7b80, 0x4ecb, 0xff1a))
$content = "$titleLabel$Title`n$linkLabel[$Link]($Link)`n$introLabel$Intro"

& $cli.Source feed publish-feed `
    --guild-id $GuildId `
    --channel-id $ChannelId `
    --content $content `
    --json
