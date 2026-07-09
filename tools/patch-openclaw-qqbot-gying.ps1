param(
    [string]$OpenClawHome = "$env:USERPROFILE\.openclaw",
    [string]$SearchUrl = "http://host.docker.internal:8880/api/qq-bot/search-reply"
)

$ErrorActionPreference = "Stop"

$pluginFile = Get-ChildItem -Path (Join-Path $OpenClawHome "npm\projects") -Recurse -File -Filter "slash-commands.js" |
    Where-Object { $_.FullName -like "*@tencent-connect*openclaw-qqbot*dist*src*slash-commands.js" } |
    Select-Object -First 1

if (-not $pluginFile) {
    throw "OpenClaw QQBot slash-commands.js not found under $OpenClawHome"
}

$path = $pluginFile.FullName
$content = Get-Content -Raw -Encoding UTF8 $path

if ($content -notmatch "function runGyingMovieSearch") {
    $helper = @'
function runGyingMovieSearch(ctx, keyword) {
    const safeKeyword = String(keyword ?? "").trim();
    if (!safeKeyword) {
        return Promise.resolve("请输入要搜索的影片名称。");
    }
    const searchUrl = ctx.accountConfig?.gyingSearchUrl ?? process.env.GYING_QQBOT_SEARCH_URL;
    const searchToken = ctx.accountConfig?.gyingSearchToken ?? process.env.GYING_QQBOT_SEARCH_TOKEN;
    if (!searchUrl) {
        return Promise.resolve("资源搜索接口未配置。");
    }
    const url = new URL(searchUrl);
    url.searchParams.set("keyword", safeKeyword);
    const headers = {};
    if (searchToken) {
        headers.Authorization = `Bearer ${searchToken}`;
    }
    return fetch(url, { headers })
        .then(async (response) => {
            const raw = await response.text();
            let body = {};
            try {
                body = raw ? JSON.parse(raw) : {};
            } catch {
                body = {};
            }
            if (!response.ok) {
                return `资源搜索失败：HTTP ${response.status}`;
            }
            return body.reply || "暂时没有可用回复。";
        })
        .catch((err) => `资源搜索失败：${err instanceof Error ? err.message : String(err)}`);
}
function extractGyingTextCommand(content) {
    const normalized = String(content ?? "").replace(/\u3000/g, " ").trim();
    for (const prefix of ["搜", "找"]) {
        if (normalized === prefix) {
            return "";
        }
        if (normalized.startsWith(`${prefix} `)) {
            return normalized.slice(prefix.length).trim();
        }
    }
    return null;
}
'@
    $content = $content -replace "function registerCommand\(cmd\) \{\r?\n    commands\.set\(cmd\.name\.toLowerCase\(\), cmd\);\r?\n\}", "function registerCommand(cmd) {`n    commands.set(cmd.name.toLowerCase(), cmd);`n}`n$helper"
}

if ($content -notmatch 'name: "movie"') {
    $movieCommand = @'
registerCommand({
    name: "movie",
    description: "搜索影视资源",
    usage: [
        `/movie 人生切割术`,
        `/search 人生切割术`,
        `搜 人生切割术`,
        ``,
        `搜索站内影视信息和资源链接。`,
    ].join("\n"),
    handler: (ctx) => runGyingMovieSearch(ctx, ctx.args),
});
registerCommand({
    name: "search",
    description: "搜索影视资源",
    usage: `/search 人生切割术`,
    handler: (ctx) => runGyingMovieSearch(ctx, ctx.args),
});
'@
    $content = $content -replace "(\r?\n/\*\*\r?\n \* /bot-version)", "`n$movieCommand`$1"
}

if ($content -match 'if \(!content\.startsWith\("/"\)\)\r?\n        return null;') {
    $content = $content -replace 'if \(!content\.startsWith\("/"\)\)\r?\n        return null;', "if (!content.startsWith(`"/`")) {`n        const keyword = extractGyingTextCommand(content);`n        if (keyword !== null) {`n            return runGyingMovieSearch(ctx, keyword);`n        }`n        return null;`n    }"
}

Set-Content -Encoding UTF8 $path $content

$configPath = Join-Path $OpenClawHome "openclaw.json"
if (Test-Path $configPath) {
    $config = Get-Content -Raw -Encoding UTF8 $configPath | ConvertFrom-Json
    if (-not $config.channels) {
        $config | Add-Member -NotePropertyName channels -NotePropertyValue ([pscustomobject]@{})
    }
    if (-not $config.channels.qqbot) {
        $config.channels | Add-Member -NotePropertyName qqbot -NotePropertyValue ([pscustomobject]@{})
    }
    $config.channels.qqbot | Add-Member -NotePropertyName gyingSearchUrl -NotePropertyValue $SearchUrl -Force
    $config | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $configPath
}

Write-Output "Patched $path"
