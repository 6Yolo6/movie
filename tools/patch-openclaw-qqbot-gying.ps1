param(
    [string]$OpenClawHome = "$env:USERPROFILE\.openclaw",
    [string]$SearchUrl = "http://host.docker.internal:8880/api/qq-bot/search-reply",
    [string]$OpenClawContainer = "openclaw-openclaw-gateway-1",
    [switch]$SkipGatewayRestart
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

$projectDir = Get-ChildItem -Path $pluginRoot -Directory |
    Where-Object { $pluginFile.FullName.StartsWith($_.FullName, [System.StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 1
if (-not $projectDir) {
    throw "OpenClaw QQBot npm project root could not be resolved"
}
$qrPackage = Join-Path $projectDir.FullName "node_modules\qrcode\package.json"
if (-not (Test-Path $qrPackage)) {
    Push-Location $projectDir.FullName
    try {
        & npm.cmd install qrcode@1.5.4 --save-exact --ignore-scripts
        if ($LASTEXITCODE -ne 0) {
            throw "npm install qrcode failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$path = $pluginFile.FullName
$content = Get-Content -Raw -Encoding UTF8 $path

if ($content -notmatch "function runGyingMovieSearch") {
    $helper = @'
function buildGyingDefaultReply() {
    return "\u673a\u5668\u4eba\u4f7f\u7528\u65b9\u6cd5\uff1a@\u673a\u5668\u4eba \u641c/\u627e \u5f71\u7247\u540d\n\u5148\u9009\u62e9\u5f71\u7247\uff0c\u518d\u4ece\u8d44\u6e90\u540d\u79f0/\u753b\u8d28\u5019\u9009\u4e2d\u56de\u590d\u5355\u4e2a\u5e8f\u53f7\uff1b\u53ef\u7528\u201c\u5938\u514b\u201d\u6216\u201c\u8fc5\u96f7\u201d\u7b5b\u9009\uff0c\u4e0d\u652f\u6309\u6570\u91cf\u6279\u91cf\u8f6c\u5b58\u3002";
}
async function buildGyingQrMediaUrls(reply) {
    const matches = String(reply ?? "").match(/https:\/\/pan\.quark\.cn\/s\/[^\s]+/giu) ?? [];
    const urls = [...new Set(matches)].slice(0, 5);
    if (urls.length === 0) {
        return [];
    }
    try {
        const imported = await import("qrcode");
        const qrcode = imported.default ?? imported;
        return await Promise.all(urls.map((url) => qrcode.toDataURL(url, {
            errorCorrectionLevel: "M",
            margin: 2,
            width: 420,
        })));
    } catch (err) {
        console.warn(`[gying-qqbot] QR generation failed: ${err instanceof Error ? err.message : String(err)}`);
        return [];
    }
}
async function runGyingMovieSearch(ctx, keyword) {
    const safeKeyword = String(keyword ?? "").trim();
    if (!safeKeyword) {
        return buildGyingDefaultReply();
    }
    const searchUrl = ctx.accountConfig?.gyingSearchUrl ?? process.env.GYING_QQBOT_SEARCH_URL;
    const searchToken = ctx.accountConfig?.gyingSearchToken ?? process.env.GYING_QQBOT_SEARCH_TOKEN;
    if (!searchUrl) {
        return "\u8d44\u6e90\u641c\u7d22\u63a5\u53e3\u672a\u914d\u7f6e\u3002";
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
    try {
        const response = await fetch(url, { headers });
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
        const reply = body.reply || "\u6682\u65f6\u6ca1\u6709\u53ef\u7528\u56de\u590d\u3002";
        const mediaUrls = await buildGyingQrMediaUrls(reply);
        return mediaUrls.length > 0 ? { text: reply, mediaUrls } : reply;
    } catch (err) {
        return `\u8d44\u6e90\u641c\u7d22\u5931\u8d25\uff1a${err instanceof Error ? err.message : String(err)}`;
    }
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

$desiredHelper = @'
function buildGyingDefaultReply() {
    return "\u673a\u5668\u4eba\u4f7f\u7528\u65b9\u6cd5\uff1a@\u673a\u5668\u4eba \u641c/\u627e \u5f71\u7247\u540d\n\u5148\u9009\u62e9\u5f71\u7247\uff0c\u518d\u4ece\u8d44\u6e90\u540d\u79f0/\u753b\u8d28\u5019\u9009\u4e2d\u56de\u590d\u5355\u4e2a\u5e8f\u53f7\uff1b\u53ef\u7528\u201c\u5938\u514b\u201d\u6216\u201c\u8fc5\u96f7\u201d\u7b5b\u9009\uff0c\u4e0d\u652f\u6309\u6570\u91cf\u6279\u91cf\u8f6c\u5b58\u3002";
}
async function buildGyingQrMediaUrls(reply) {
    const matches = String(reply ?? "").match(/https:\/\/pan\.quark\.cn\/s\/[^\s]+/giu) ?? [];
    const urls = [...new Set(matches)].slice(0, 5);
    if (urls.length === 0) {
        return [];
    }
    try {
        const imported = await import("qrcode");
        const qrcode = imported.default ?? imported;
        return await Promise.all(urls.map((url) => qrcode.toDataURL(url, {
            errorCorrectionLevel: "M",
            margin: 2,
            width: 420,
        })));
    } catch (err) {
        console.warn(`[gying-qqbot] QR generation failed: ${err instanceof Error ? err.message : String(err)}`);
        return [];
    }
}
async function runGyingMovieSearch(ctx, keyword) {
    const safeKeyword = String(keyword ?? "").trim();
    if (!safeKeyword) {
        return buildGyingDefaultReply();
    }
    const searchUrl = ctx.accountConfig?.gyingSearchUrl ?? process.env.GYING_QQBOT_SEARCH_URL;
    const searchToken = ctx.accountConfig?.gyingSearchToken ?? process.env.GYING_QQBOT_SEARCH_TOKEN;
    if (!searchUrl) {
        return "\u8d44\u6e90\u641c\u7d22\u63a5\u53e3\u672a\u914d\u7f6e\u3002";
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
    try {
        const response = await fetch(url, { headers });
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
        const reply = body.reply || "\u6682\u65f6\u6ca1\u6709\u53ef\u7528\u56de\u590d\u3002";
        const mediaUrls = await buildGyingQrMediaUrls(reply);
        return mediaUrls.length > 0 ? { text: reply, mediaUrls } : reply;
    } catch (err) {
        return `\u8d44\u6e90\u641c\u7d22\u5931\u8d25\uff1a${err instanceof Error ? err.message : String(err)}`;
    }
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
$helperPattern = '(?s)(?:function buildGyingDefaultReply\(\)|(?:async\s+)?function runGyingMovieSearch\(ctx, keyword\)).*?(?=// ============ 内置指令 ============)'
if ($content -match $helperPattern) {
    $content = [regex]::Replace($content, $helperPattern, $desiredHelper + "`n", 1)
}
$content = $content -replace '(?m)^\s*const urls = \[\.\.\.new Set\(matches.*$',
        '    const urls = [...new Set(matches)].slice(0, 5);'

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
    $content = $content -replace 'if \(!content\.startsWith\("/"\)\)\r?\n        return null;', "if (!content.startsWith(`"/`")) {`n        const keyword = extractGyingTextCommand(content);`n        if (keyword !== null) {`n            return runGyingMovieSearch(ctx, keyword);`n        }`n        return ctx.gyingMentionFallback ? buildGyingDefaultReply() : null;`n    }"
}
$content = $content -replace 'return null;\r?\n    \}\r?\n    // 解析指令名和参数',
        "return ctx.gyingMentionFallback ? buildGyingDefaultReply() : null;`n    }`n    // 解析指令名和参数"
$content = $content -replace 'if \(!cmd\)\r?\n        return null; // 不是插件级指令，交给框架',
        "if (!cmd)`n        return ctx.gyingMentionFallback ? buildGyingDefaultReply() : null; // 不是插件级指令，交给框架"

[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))

$gatewayPath = $gatewayFile.FullName
$gatewayContent = Get-Content -Raw -Encoding UTF8 $gatewayPath
if ($gatewayContent -notmatch 'sendC2CImageMessage') {
    $gatewayContent = $gatewayContent -replace 'sendC2CMessage, sendChannelMessage, sendGroupMessage,',
            'sendC2CMessage, sendChannelMessage, sendGroupMessage, sendC2CImageMessage, sendGroupImageMessage,'
}
$gatewayContent = $gatewayContent -replace 'const isGyingMovieSearchCommand = \(text\) => .*?;\r?\n', ""
$gatewayContent = $gatewayContent -replace '\s*const isGyingSearchStartCommand = \(text\) => .*?;\r?\n', "`n"
$gatewayContent = $gatewayContent -replace '\s*const isGyingMentionFallback = \(msg\) => .*?;\r?\n', "`n"
if ($gatewayContent -notmatch "const isGyingMovieSearchCommand") {
    $gatewayContent = $gatewayContent -replace 'const URGENT_COMMANDS = \["/stop", "/approve"\];', "const URGENT_COMMANDS = [`"/stop`", `"/approve`"];`n    const isGyingMovieSearchCommand = (text) => /^(?:(?:\u641c|\u627e)(?:\s+.+)?|(?:[1-9]|10)|(?:(?:\u7f51\u76d8|\u4e91\u76d8)?\s*(?:\u5938\u514b|\u767e\u5ea6(?:\u7f51\u76d8|\u4e91\u76d8|\u4e91)?|\u963f\u91cc(?:\u4e91\u76d8|\u7f51\u76d8|\u4e91)?|uc(?:\u7f51\u76d8|\u4e91\u76d8)?|\u8fc5\u96f7(?:\u7f51\u76d8)?|115(?:\u7f51\u76d8|\u4e91\u76d8)?|123(?:\u7f51\u76d8|\u4e91\u76d8)?|pikpak|\u5929\u7ffc(?:\u7f51\u76d8|\u4e91\u76d8)?|(?:\u4e2d\u56fd)?\u79fb\u52a8(?:\u7f51\u76d8|\u4e91\u76d8)?|\u5168\u90e8|\u6240\u6709|\u4efb\u610f|\u7efc\u5408)(?:\s*\d{1,2}\s*(?:\u6761|\u4e2a)?)?|(?:\u8d44\u6e90|\u66f4\u591a)\s*\d{1,2}\s*(?:\u6761|\u4e2a)?))$/iu.test(String(text ?? `"`").trim());`n    const isGyingMentionFallback = (msg) => msg?.type === `"group`" && (msg?.eventType === `"GROUP_AT_MESSAGE_CREATE`" || msg?.mentions?.some((mention) => mention?.is_you));"
}
if ($gatewayContent -notmatch "const isGyingSearchStartCommand") {
    $gatewayContent = $gatewayContent -replace '(const isGyingMovieSearchCommand = .*?;\r?\n)',
            "`$1    const isGyingSearchStartCommand = (text) => /^(?:(?:\\/movie|\\/search)\\s+.+|(?:\\u641c|\\u627e)\\s+.+)$/iu.test(String(text ?? `"`").trim());`n"
}

$gatewayContent = $gatewayContent -replace 'const isGyingSearchStartCommand = .*', '    const isGyingSearchStartCommand = (text) => /^(?:(?:\/movie|\/search)\s+.+|(?:\u641c|\u627e)\s+.+)$/iu.test(String(text ?? "").trim());'

if ($gatewayContent -match 'if \(!content\.startsWith\("/"\)\) \{\r?\n            msgQueue\.enqueue\(msg\);\r?\n            return;\r?\n        \}') {
    $gatewayContent = $gatewayContent -replace 'if \(!content\.startsWith\("/"\)\) \{\r?\n            msgQueue\.enqueue\(msg\);\r?\n            return;\r?\n        \}', "if (!content.startsWith(`"/`") && !isGyingMovieSearchCommand(content) && !isGyingMentionFallback(msg)) {`n            msgQueue.enqueue(msg);`n            return;`n        }"
}
$gatewayContent = $gatewayContent -replace 'if \(!content\.startsWith\("/"\) && !isGyingMovieSearchCommand\(content\)\) \{',
        'if (!content.startsWith("/") && !isGyingMovieSearchCommand(content) && !isGyingMentionFallback(msg)) {'
$gatewayContent = $gatewayContent -replace '\r?\n\s*gyingMentionFallback: isGyingMentionFallback\(msg\),', ""
$gatewayContent = $gatewayContent -replace 'queueSnapshot: msgQueue\.getSnapshot\(peerId\),',
        "queueSnapshot: msgQueue.getSnapshot(peerId),`n            gyingMentionFallback: isGyingMentionFallback(msg),"
if ($gatewayContent -notmatch 'Gying search progress sent') {
    $progressHandler = @'
            let gyingProgressToken = null;
            if (isGyingSearchStartCommand(content)) {
                try {
                    gyingProgressToken = await getAccessToken(account.appId, account.clientSecret);
                    const progressText = msg.type === "group" && msg.senderId
                        ? `<@${msg.senderId}> 正在搜索资源，请稍后...`
                        : "正在搜索资源，请稍后...";
                    if (msg.type === "group" && msg.groupOpenid) {
                        await sendGroupMessage(gyingProgressToken, msg.groupOpenid, progressText, msg.messageId);
                    }
                    else if (msg.type === "c2c" || msg.type === "dm") {
                        await sendC2CMessage(gyingProgressToken, msg.senderId, progressText, msg.messageId);
                    }
                    log?.info(`[qqbot:${account.accountId}] Gying search progress sent`);
                }
                catch (progressErr) {
                    log?.warn?.(`[qqbot:${account.accountId}] Failed to send Gying search progress: ${progressErr}`);
                }
            }
'@
    $gatewayContent = $gatewayContent -replace '(?m)^\s*const reply = await matchSlashCommand\(cmdCtx\);$',
            ($progressHandler + "`n            const reply = await matchSlashCommand(cmdCtx);")
}
$gatewayContent = $gatewayContent -replace 'const token = await getAccessToken\(account\.appId, account\.clientSecret\);',
        'const token = gyingProgressToken ?? await getAccessToken(account.appId, account.clientSecret);'
$gatewayContent = [regex]::Replace(
        $gatewayContent,
        'const token = gyingProgressToken \?\? await getAccessToken\(account\.appId, account\.clientSecret\);',
        'const token = await getAccessToken(account.appId, account.clientSecret);',
        1)
$gatewayContent = [regex]::Replace(
        $gatewayContent,
        '(let gyingProgressToken = null;[\s\S]*?const token = )await getAccessToken\(account\.appId, account\.clientSecret\);',
        '$1gyingProgressToken ?? await getAccessToken(account.appId, account.clientSecret);',
        1)
$gatewayContent = $gatewayContent -replace 'const isFileResult = typeof reply === "object" && reply !== null && "filePath" in reply;\r?\n\s*const replyText = isFileResult \? reply\.text : reply;\r?\n\s*const replyFile = isFileResult \? reply\.filePath : null;',
        "const isStructuredResult = typeof reply === `"object`" && reply !== null;`n            const rawReplyText = isStructuredResult ? reply.text : reply;`n            const replyText = msg.type === `"group`" && msg.senderId ? ``<@`${msg.senderId}> `${rawReplyText}`` : rawReplyText;`n            const replyFile = isStructuredResult && `"filePath`" in reply ? reply.filePath : null;`n            const replyMediaUrls = isStructuredResult ? (reply.mediaUrls ?? (reply.mediaUrl ? [reply.mediaUrl] : [])) : [];"
if ($gatewayContent -notmatch 'Gying QR image sent') {
    $mediaHandler = @'
            for (const mediaUrl of replyMediaUrls) {
                try {
                    if (msg.type === "group" && msg.groupOpenid) {
                        await sendGroupImageMessage(token, msg.groupOpenid, mediaUrl, msg.messageId);
                    }
                    else if (msg.type === "c2c" || msg.type === "dm") {
                        await sendC2CImageMessage(token, msg.senderId, mediaUrl, msg.messageId);
                    }
                    log?.info(`[qqbot:${account.accountId}] Gying QR image sent`);
                }
                catch (mediaErr) {
                    log?.error(`[qqbot:${account.accountId}] Failed to send Gying QR image: ${mediaErr}`);
                }
            }
'@
    $directReplyPattern = '(?s)(\s*if \(replyFile\) \{.*?\r?\n\s*\})(\r?\n\s*\}\r?\n\s*catch \(err\) \{)'
    $gatewayContent = [regex]::Replace(
            $gatewayContent,
            $directReplyPattern,
            '$1' + "`n" + $mediaHandler + '$2',
            1)
}
[System.IO.File]::WriteAllText($gatewayPath, $gatewayContent, [System.Text.UTF8Encoding]::new($false))

$runtimePluginPath = $null
$containerRunning = $false
if ($OpenClawContainer -and (Get-Command docker -ErrorAction SilentlyContinue)) {
    $containerRunning = (docker inspect --format '{{.State.Running}}' $OpenClawContainer 2>$null) -eq "true"
}
if ($containerRunning) {
    $containerProject = "/home/node/.openclaw/npm/projects/$($projectDir.Name)"
    $runtimeProject = "/home/node/.openclaw-runtime-plugins/$($projectDir.Name)"
    $runtimePluginPath = "$runtimeProject/node_modules/@tencent-connect/openclaw-qqbot"
    docker exec -u 0 $OpenClawContainer mkdir -p $runtimeProject | Out-Null
    docker exec -u 0 $OpenClawContainer cp -a "$containerProject/." "$runtimeProject/" | Out-Null
    docker exec -u 0 $OpenClawContainer chown -R node:node /home/node/.openclaw-runtime-plugins | Out-Null
    docker exec -u 0 $OpenClawContainer chmod -R go-w /home/node/.openclaw-runtime-plugins | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to synchronize the secured OpenClaw QQBot runtime copy"
    }
}

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
    if ($runtimePluginPath) {
        if (-not $config.plugins) {
            $config | Add-Member -NotePropertyName plugins -NotePropertyValue ([pscustomobject]@{})
        }
        if (-not $config.plugins.load) {
            $config.plugins | Add-Member -NotePropertyName load -NotePropertyValue ([pscustomobject]@{})
        }
        $paths = @($config.plugins.load.paths)
        if ($paths -notcontains $runtimePluginPath) {
            $paths += $runtimePluginPath
        }
        $config.plugins.load | Add-Member -NotePropertyName paths -NotePropertyValue $paths -Force
    }
    $config | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $configPath
}

if ($containerRunning -and -not $SkipGatewayRestart) {
    docker restart $OpenClawContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restart $OpenClawContainer"
    }
}

Write-Output "Patched $path"
Write-Output "Patched $gatewayPath"
if ($runtimePluginPath) {
    Write-Output "Synchronized secured runtime plugin: $runtimePluginPath"
}
