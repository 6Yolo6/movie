param(
    [string]$OpenClawHome = "$env:USERPROFILE\.openclaw",
    [string]$SearchUrl = "http://host.docker.internal:8880/api/qq-bot/search-reply"
)

$ErrorActionPreference = "Stop"

$pluginRoot = Join-Path $OpenClawHome "npm\projects"

$pluginFile = Get-ChildItem -Path $pluginRoot -Recurse -File -Filter "slash-commands.js" |
    Where-Object { $_.FullName -like "*@tencent-connect*openclaw-qqbot*dist*src*slash-commands.js" } |
    Select-Object -First 1

if (-not $pluginFile) {
    throw "OpenClaw QQBot slash-commands.js not found under $OpenClawHome"
}

$gatewayFile = Get-ChildItem -Path $pluginRoot -Recurse -File -Filter "gateway.js" |
    Where-Object { $_.FullName -like "*@tencent-connect*openclaw-qqbot*dist*src*gateway.js" } |
    Select-Object -First 1

if (-not $gatewayFile) {
    throw "OpenClaw QQBot gateway.js not found under $OpenClawHome"
}

$path = $pluginFile.FullName
$content = Get-Content -Raw -Encoding UTF8 $path

if ($content -notmatch "function runGyingMovieSearch") {
    $helper = @'
function runGyingMovieSearch(ctx, keyword) {
    const safeKeyword = String(keyword ?? "").trim();
    if (!safeKeyword) {
        return Promise.resolve("\u8bf7\u8f93\u5165\u8981\u641c\u7d22\u7684\u5f71\u7247\u540d\u79f0\u3002");
    }
    const searchUrl = ctx.accountConfig?.gyingSearchUrl ?? process.env.GYING_QQBOT_SEARCH_URL;
    const searchToken = ctx.accountConfig?.gyingSearchToken ?? process.env.GYING_QQBOT_SEARCH_TOKEN;
    if (!searchUrl) {
        return Promise.resolve("\u8d44\u6e90\u641c\u7d22\u63a5\u53e3\u672a\u914d\u7f6e\u3002");
    }
    const url = new URL(searchUrl);
    url.searchParams.set("keyword", safeKeyword);
    if (ctx.senderId) {
        url.searchParams.set("userKey", String(ctx.senderId));
    }
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
                return `\u8d44\u6e90\u641c\u7d22\u5931\u8d25\uff1aHTTP ${response.status}`;
            }
            return body.reply || "\u6682\u65f6\u6ca1\u6709\u53ef\u7528\u56de\u590d\u3002";
        })
        .catch((err) => `\u8d44\u6e90\u641c\u7d22\u5931\u8d25\uff1a${err instanceof Error ? err.message : String(err)}`);
}
function extractGyingTextCommand(content) {
    const normalized = String(content ?? "").replace(/\u3000/g, " ").trim();
    if (/^(?:[1-9]|10)$/.test(normalized)) {
        return normalized;
    }
    const resourcePreferencePattern = /^(?:(?:\u7f51\u76d8|\u4e91\u76d8)?\s*(?:\u5938\u514b|\u767e\u5ea6(?:\u7f51\u76d8|\u4e91\u76d8|\u4e91)?|\u963f\u91cc(?:\u4e91\u76d8|\u7f51\u76d8|\u4e91)?|uc(?:\u7f51\u76d8|\u4e91\u76d8)?|\u8fc5\u96f7(?:\u7f51\u76d8)?|115(?:\u7f51\u76d8|\u4e91\u76d8)?|123(?:\u7f51\u76d8|\u4e91\u76d8)?|pikpak|\u5929\u7ffc(?:\u7f51\u76d8|\u4e91\u76d8)?|(?:\u4e2d\u56fd)?\u79fb\u52a8(?:\u7f51\u76d8|\u4e91\u76d8)?|\u5168\u90e8|\u6240\u6709|\u4efb\u610f|\u7efc\u5408)(?:\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)?|(?:\u8d44\u6e90|\u66f4\u591a)\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)$/iu;
    if (resourcePreferencePattern.test(normalized)) {
        return normalized;
    }
    for (const prefix of ["\u641c", "\u627e"]) {
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

if ($content -notmatch 'url\.searchParams\.set\("userKey", String\(ctx\.senderId\)\);') {
    $content = $content -replace 'url\.searchParams\.set\("keyword", safeKeyword\);',
            "url.searchParams.set(`"keyword`", safeKeyword);`n    if (ctx.senderId) {`n        url.searchParams.set(`"userKey`", String(ctx.senderId));`n    }"
}

if ($content -notmatch '\^\(\?:\[1-9\]\|10\)\$') {
    $content = $content -replace 'function extractGyingTextCommand\(content\) \{\r?\n    const normalized = String\(content \?\? ""\)\.replace\(/\\u3000/g, " "\)\.trim\(\);',
            "function extractGyingTextCommand(content) {`n    const normalized = String(content ?? `"`").replace(/\u3000/g, `" `").trim();`n    if (/^(?:[1-9]|10)$/.test(normalized)) {`n        return normalized;`n    }"
}

if ($content -notmatch "resourcePreferencePattern") {
    $resourcePreferenceHandler = @'
    const resourcePreferencePattern = /^(?:(?:\u7f51\u76d8|\u4e91\u76d8)?\s*(?:\u5938\u514b|\u767e\u5ea6(?:\u7f51\u76d8|\u4e91\u76d8|\u4e91)?|\u963f\u91cc(?:\u4e91\u76d8|\u7f51\u76d8|\u4e91)?|uc(?:\u7f51\u76d8|\u4e91\u76d8)?|\u8fc5\u96f7(?:\u7f51\u76d8)?|115(?:\u7f51\u76d8|\u4e91\u76d8)?|123(?:\u7f51\u76d8|\u4e91\u76d8)?|pikpak|\u5929\u7ffc(?:\u7f51\u76d8|\u4e91\u76d8)?|(?:\u4e2d\u56fd)?\u79fb\u52a8(?:\u7f51\u76d8|\u4e91\u76d8)?|\u5168\u90e8|\u6240\u6709|\u4efb\u610f|\u7efc\u5408)(?:\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)?|(?:\u8d44\u6e90|\u66f4\u591a)\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)$/iu;
    if (resourcePreferencePattern.test(normalized)) {
        return normalized;
    }
'@
    $content = $content -replace '(if \(/\^\(\?:\[1-9\]\|10\)\$/\.test\(normalized\)\) \{\r?\n        return normalized;\r?\n    \})', "`$1`n$resourcePreferenceHandler"
}

$resourcePreferencePatternLine = '    const resourcePreferencePattern = /^(?:(?:\u7f51\u76d8|\u4e91\u76d8)?\s*(?:\u5938\u514b|\u767e\u5ea6(?:\u7f51\u76d8|\u4e91\u76d8|\u4e91)?|\u963f\u91cc(?:\u4e91\u76d8|\u7f51\u76d8|\u4e91)?|uc(?:\u7f51\u76d8|\u4e91\u76d8)?|\u8fc5\u96f7(?:\u7f51\u76d8)?|115(?:\u7f51\u76d8|\u4e91\u76d8)?|123(?:\u7f51\u76d8|\u4e91\u76d8)?|pikpak|\u5929\u7ffc(?:\u7f51\u76d8|\u4e91\u76d8)?|(?:\u4e2d\u56fd)?\u79fb\u52a8(?:\u7f51\u76d8|\u4e91\u76d8)?|\u5168\u90e8|\u6240\u6709|\u4efb\u610f|\u7efc\u5408)(?:\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)?|(?:\u8d44\u6e90|\u66f4\u591a)\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)$/iu;'
$content = $content -replace '(?m)^\s*const resourcePreferencePattern = .*;$', $resourcePreferencePatternLine

if ($content -notmatch 'name: "movie"') {
    $movieCommand = @'
registerCommand({
    name: "movie",
    description: "\u641c\u7d22\u5f71\u89c6\u8d44\u6e90",
    usage: [
        `/movie \u4eba\u751f\u5207\u5272\u672f`,
        `/search \u4eba\u751f\u5207\u5272\u672f`,
        `\u641c \u4eba\u751f\u5207\u5272\u672f`,
        ``,
        `\u641c\u7d22\u7ad9\u5185\u5f71\u89c6\u4fe1\u606f\u548c\u8d44\u6e90\u94fe\u63a5\u3002`,
    ].join("\n"),
    handler: (ctx) => runGyingMovieSearch(ctx, ctx.args),
});
registerCommand({
    name: "search",
    description: "\u641c\u7d22\u5f71\u89c6\u8d44\u6e90",
    usage: `/search \u4eba\u751f\u5207\u5272\u672f`,
    handler: (ctx) => runGyingMovieSearch(ctx, ctx.args),
});
'@
    $content = $content -replace "(\r?\n/\*\*\r?\n \* /bot-version)", "`n$movieCommand`$1"
}

if ($content -match 'if \(!content\.startsWith\("/"\)\)\r?\n        return null;') {
    $content = $content -replace 'if \(!content\.startsWith\("/"\)\)\r?\n        return null;', "if (!content.startsWith(`"/`")) {`n        const keyword = extractGyingTextCommand(content);`n        if (keyword !== null) {`n            return runGyingMovieSearch(ctx, keyword);`n        }`n        return null;`n    }"
}

Set-Content -Encoding UTF8 $path $content

$gatewayPath = $gatewayFile.FullName
$gatewayContent = Get-Content -Raw -Encoding UTF8 $gatewayPath
$gatewayContent = $gatewayContent -replace 'const isGyingMovieSearchCommand = \(text\) => .*?;\r?\n', ""
if ($gatewayContent -notmatch "const isGyingMovieSearchCommand") {
    $gatewayContent = $gatewayContent -replace 'const URGENT_COMMANDS = \["/stop", "/approve"\];', "const URGENT_COMMANDS = [`"/stop`", `"/approve`"];`n    const isGyingMovieSearchCommand = (text) => /^(?:(?:\u641c|\u627e)(?:\s|$)|(?:[1-9]|10)$|(?:(?:\u7f51\u76d8|\u4e91\u76d8)?\s*(?:\u5938\u514b|\u767e\u5ea6(?:\u7f51\u76d8|\u4e91\u76d8|\u4e91)?|\u963f\u91cc(?:\u4e91\u76d8|\u7f51\u76d8|\u4e91)?|uc(?:\u7f51\u76d8|\u4e91\u76d8)?|\u8fc5\u96f7(?:\u7f51\u76d8)?|115(?:\u7f51\u76d8|\u4e91\u76d8)?|123(?:\u7f51\u76d8|\u4e91\u76d8)?|pikpak|\u5929\u7ffc(?:\u7f51\u76d8|\u4e91\u76d8)?|(?:\u4e2d\u56fd)?\u79fb\u52a8(?:\u7f51\u76d8|\u4e91\u76d8)?|\u5168\u90e8|\u6240\u6709|\u4efb\u610f|\u7efc\u5408)(?:\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)?|(?:\u8d44\u6e90|\u66f4\u591a)\s*\d{1,2}\s*(?:\u6761|\u4e2a)?))$/iu.test(String(text ?? `"`").trim());"
}

if ($gatewayContent -match 'if \(!content\.startsWith\("/"\)\) \{\r?\n            msgQueue\.enqueue\(msg\);\r?\n            return;\r?\n        \}') {
    $gatewayContent = $gatewayContent -replace 'if \(!content\.startsWith\("/"\)\) \{\r?\n            msgQueue\.enqueue\(msg\);\r?\n            return;\r?\n        \}', "if (!content.startsWith(`"/`") && !isGyingMovieSearchCommand(content)) {`n            msgQueue.enqueue(msg);`n            return;`n        }"
}
Set-Content -Encoding UTF8 $gatewayPath $gatewayContent

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
Write-Output "Patched $gatewayPath"
