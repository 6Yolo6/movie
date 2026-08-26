param(
    [int]$Port = 8092,
    [string]$Token = "",
    [string]$RunLogFile = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"

if (-not $Token -and (Test-Path $envFile)) {
    $envMap = @{}
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$') {
            $envMap[$matches[1]] = $matches[2].Trim('"')
        }
    }
    $Token = if ($envMap["QQ_CHANNEL_PUBLISHER_TOKEN"]) {
        $envMap["QQ_CHANNEL_PUBLISHER_TOKEN"]
    } else {
        $envMap["APP_INTERNAL_TOKEN"]
    }
}

if (-not $Token) {
    throw "QQ channel publisher token is not configured"
}
if (-not $RunLogFile) {
    $RunLogFile = Join-Path $repoRoot "logs\qq-channel-publisher-bridge.log"
}
$logDir = Split-Path -Parent $RunLogFile
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-BridgeLog {
    param([string]$Message)
    Add-Content -LiteralPath $RunLogFile -Encoding UTF8 -Value "[$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff'))] $Message"
}

function Send-Json {
    param(
        [System.Net.Sockets.NetworkStream]$Stream,
        [int]$StatusCode,
        [object]$Payload
    )
    $json = $Payload | ConvertTo-Json -Depth 6 -Compress
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)
    $reason = if ($StatusCode -eq 200) { "OK" } elseif ($StatusCode -eq 401) { "Unauthorized" } elseif ($StatusCode -eq 404) { "Not Found" } else { "Bad Gateway" }
    $headers = "HTTP/1.1 $StatusCode $reason`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    $Stream.Write($body, 0, $body.Length)
    $Stream.Flush()
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
$listener.Start()
Write-BridgeLog "START port=$Port"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        try {
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 4096, $true)
            $requestLine = $reader.ReadLine()
            $headers = @{}
            while ($true) {
                $line = $reader.ReadLine()
                if (-not $line) {
                    break
                }
                $separator = $line.IndexOf(":")
                if ($separator -gt 0) {
                    $headers[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
                }
            }
            $parts = $requestLine -split " ", 3
            $method = if ($parts.Count -ge 1) { $parts[0] } else { "" }
            $path = if ($parts.Count -ge 2) { $parts[1].Split("?")[0] } else { "" }

            if ($method -eq "GET" -and $path -eq "/health") {
                Send-Json $stream 200 @{ ok = $true }
                continue
            }
            if ($headers["X-Internal-Token"] -ne $Token) {
                Send-Json $stream 401 @{ error = "Unauthorized" }
                continue
            }
            if ($method -ne "POST" -or $path -notmatch '^/posts/([0-9]+)$') {
                Send-Json $stream 404 @{ error = "Not found" }
                continue
            }

            $postLogId = [long]$matches[1]
            Write-BridgeLog "POST start postLogId=$postLogId"
            $output = & (Join-Path $PSScriptRoot "publish-latest-resource-to-qq-channel.ps1") `
                -Limit 1 `
                -PostLogId $postLogId 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "Publisher exited with code $LASTEXITCODE"
            }
            Write-BridgeLog "POST completed postLogId=$postLogId"
            Send-Json $stream 200 @{
                ok = $true
                postLogId = $postLogId
                output = (($output | Out-String).Trim())
            }
        } catch {
            Write-BridgeLog "POST failed error=$($_.Exception.Message)"
            Send-Json $stream 502 @{ error = $_.Exception.Message }
        } finally {
            $stream.Dispose()
            $client.Dispose()
        }
    }
} finally {
    Write-BridgeLog "STOP"
    $listener.Stop()
}
