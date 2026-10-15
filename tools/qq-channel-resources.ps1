# Shared, side-effect-free selection and rendering for the host QQ channel publisher.
function Get-QqChannelResourcesSql {
    param([Parameter(Mandatory = $true)][string]$MovieId)
    if ([string]::IsNullOrWhiteSpace($MovieId)) { throw "Movie ID is required" }
    $movieHex = ([System.BitConverter]::ToString([System.Text.Encoding]::UTF8.GetBytes($MovieId))).Replace("-", "")
    return @"
WITH eligible_resources AS (
    SELECT rl.id, UPPER(COALESCE(NULLIF(TRIM(rl.provider), ''), 'OTHER')) AS provider,
           rl.url, COALESCE(rl.code, '') AS code, rl.updated_at
    FROM resource_link rl
    JOIN movie_metadata m ON m.id = rl.movie_id
    WHERE rl.movie_id = _utf8mb4 0x$movieHex
      AND rl.type = 'DISK'
      AND rl.audit_status = 1
      AND rl.status = 'ACTIVE'
      AND rl.deleted_at IS NULL
      AND rl.link_status = 'NORMAL'
      AND (rl.url LIKE 'https://%' OR rl.url LIKE 'http://%')
      AND m.status = 'ACTIVE'
      AND m.deleted_at IS NULL
), ranked_resources AS (
    SELECT id, provider, url, code,
           ROW_NUMBER() OVER (PARTITION BY provider ORDER BY updated_at DESC, id DESC) AS provider_rank
    FROM eligible_resources
)
SELECT id, HEX(provider), HEX(url), HEX(code)
FROM ranked_resources
WHERE provider_rank = 1
ORDER BY provider ASC, id DESC;
"@
}

function ConvertFrom-QqChannelHex {
    param([AllowEmptyString()][string]$Hex)
    if ([string]::IsNullOrEmpty($Hex)) { return "" }
    if (($Hex.Length % 2) -ne 0 -or $Hex -notmatch '^[0-9A-Fa-f]+$') {
        throw "Invalid encoded QQ channel resource field"
    }
    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    }
    return [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)
}

function ConvertFrom-QqChannelResourceRows {
    param([AllowNull()][AllowEmptyCollection()][string[]]$Rows)
    foreach ($row in @($Rows)) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        $parts = $row -split "`t", 4
        if ($parts.Count -ne 4 -or $parts[0] -notmatch '^[0-9]+$') {
            throw "Invalid QQ channel resource row"
        }
        [pscustomobject]@{
            ResourceId = [long]$parts[0]
            Provider = (ConvertFrom-QqChannelHex $parts[1]).Trim().ToUpperInvariant()
            Url = (ConvertFrom-QqChannelHex $parts[2]).Trim()
            Code = (ConvertFrom-QqChannelHex $parts[3]).Trim()
        }
    }
}

function Get-QqChannelProviderLabel {
    param([string]$Provider)
    switch ($Provider.ToUpperInvariant()) {
        "QUARK" { return "夸克网盘" }
        "XUNLEI" { return "迅雷云盘" }
        "BAIDU" { return "百度网盘" }
        "ALIYUN" { return "阿里云盘" }
        "ALIPAN" { return "阿里云盘" }
        "UC" { return "UC网盘" }
        "115" { return "115网盘" }
        "123PAN" { return "123云盘" }
        "PIKPAK" { return "PikPak" }
        "TIANYI" { return "天翼云盘" }
        "MOBILE" { return "移动云盘" }
        default {
            $safeProvider = $Provider -replace '[^A-Za-z0-9_-]', ''
            if ($safeProvider -and $safeProvider -ne "OTHER") { return "$safeProvider 网盘" }
            return "网盘"
        }
    }
}

function ConvertTo-QqChannelLinkUrl {
    param([string]$Url)
    $parsed = $null
    if ([string]::IsNullOrWhiteSpace($Url) -or $Url -match '[\r\n\t]' -or
        -not [System.Uri]::TryCreate($Url, [System.UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @("http", "https")) {
        throw "Invalid QQ channel resource URL"
    }
    return $Url.Replace(" ", "%20").Replace("(", "%28").Replace(")", "%29").Replace("[", "%5B").Replace("]", "%5D").Replace("<", "%3C").Replace(">", "%3E")
}

function Get-QqChannelShareCode {
    param([string]$Code, [string]$Url)
    if (-not [string]::IsNullOrWhiteSpace($Code)) { return $Code.Trim() }
    $parsed = $null
    if ([System.Uri]::TryCreate($Url, [System.UriKind]::Absolute, [ref]$parsed)) {
        foreach ($item in $parsed.Query.TrimStart("?").Split("&")) {
            $pair = $item -split "=", 2
            if ($pair.Count -eq 2 -and $pair[0] -in @("pwd", "password", "code")) {
                return [System.Uri]::UnescapeDataString($pair[1].Replace("+", " "))
            }
        }
    }
    return ""
}

function Format-QqChannelResourceLinks {
    param(
        [AllowNull()][AllowEmptyCollection()][object[]]$Resources = @(),
        [string]$FallbackLink = ""
    )
    $lines = [System.Collections.Generic.List[string]]::new()
    $providers = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $urls = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($resource in @($Resources)) {
        if ($null -eq $resource) { continue }
        $provider = ([string]$resource.Provider).Trim().ToUpperInvariant()
        if (-not $provider) { $provider = "OTHER" }
        $url = ([string]$resource.Url).Trim()
        $safeUrl = ConvertTo-QqChannelLinkUrl $url
        if ($providers.Contains($provider) -or $urls.Contains($url)) { continue }
        [void]$providers.Add($provider)
        [void]$urls.Add($url)
        $label = Get-QqChannelProviderLabel $provider
        $line = "[$label]($safeUrl)"
        $code = Get-QqChannelShareCode -Code ([string]$resource.Code) -Url $url
        if ($code) {
            $safeCode = ($code -replace '[\r\n\t]+', ' ').Replace("\", "\\").Replace("[", "\[").Replace("]", "\]").Replace("*", "\*").Replace("_", "\_")
            $line += "（提取码：$safeCode）"
        }
        $lines.Add($line)
    }
    if ($lines.Count -gt 0) { return $lines -join "`n" }
    if ($FallbackLink) {
        $safeUrl = ConvertTo-QqChannelLinkUrl $FallbackLink
        return "[查看资源]($safeUrl)"
    }
    throw "No usable QQ channel resource links"
}

function Format-QqChannelPostContent {
    param(
        [string]$Title,
        [string]$Link,
        [string]$Intro,
        [string]$Year = "",
        [string]$MediaType = "",
        [string]$ContentTemplate = "",
        [AllowNull()][AllowEmptyCollection()][object[]]$Resources = @()
    )
    $linkMarkup = Format-QqChannelResourceLinks -Resources $Resources -FallbackLink $Link
    if ($ContentTemplate) {
        $hasLinkPlaceholder = $ContentTemplate.Contains("{{link}}")
        $rendered = $ContentTemplate.Replace("{{title}}", $Title).Replace("{{year}}", $Year).Replace("{{type}}", $MediaType).Replace("{{link}}", $linkMarkup).Replace("{{intro}}", $Intro)
        if (-not $hasLinkPlaceholder) { $rendered += "`n链接：$linkMarkup" }
    } else {
        $rendered = "标题：$Title`n年份：$Year`n类型：$MediaType`n链接：$linkMarkup`n简介：$Intro"
    }
    $rendered = $rendered.Replace("\r\n", "`n").Replace("\n", "`n").Replace("`r`n", "`n")
    $paragraphs = @($rendered -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    return $paragraphs -join "`n`n"
}
