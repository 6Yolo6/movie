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
    $ChannelId = "736090076"
}

$cli = Get-Command tencent-channel-cli -ErrorAction SilentlyContinue
if (-not $cli) {
    throw "tencent-channel-cli is not installed. Run: npm install -g tencent-channel-cli"
}

$content = "标题：$Title`n链接：$Link`n简介：$Intro"

& $cli.Source feed publish-feed `
    --guild-id $GuildId `
    --channel-id $ChannelId `
    --content $content `
    --json
