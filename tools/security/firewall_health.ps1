# Read-only health probes. Never emit bodies, cookies, response headers or curl stderr.
function Get-GyingHealthEndpoints {
    @(
        @{url='http://127.0.0.1/';expected=200},
        @{url='http://127.0.0.1/api/movies/list?page=1&size=1';expected=200},
        @{url='http://127.0.0.1/api/admin/users';expected=401},
        @{url='http://127.0.0.1:9000/minio/health/live';expected=200},
        @{url='http://127.0.0.1:5005/';expected=302;redirect_path='/login'},
        @{url='http://127.0.0.1:5005/login';expected=200},
        @{url='http://127.0.0.1:20241/ready';expected=200},
        @{url='https://gyinghub.dpdns.org/';expected=200},
        @{url='https://gyinghub.dpdns.org/api/qq-bot/health';expected=404}
    )
}

function Invoke-GyingHttpProbe {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$Url,
        [Parameter(Mandatory=$true)][int]$Expected,
        [string]$ExpectedRedirectPath='',
        [ValidateRange(1,30)][int]$TimeoutSeconds=12
    )
    $uri=[Uri]$Url
    if(-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.Fragment -or $Url -match '[\r\n"]') { throw 'Unsupported probe URL' }
    $result=[ordered]@{url=$uri.GetLeftPart([UriPartial]::Path);query_present=[bool]$uri.Query;expected=$Expected;observed='000';exit=-1;passed=$false;address_family='IPv4';tls_verification='enabled';expected_redirect_path=$ExpectedRedirectPath;redirect_matched=$null;error=$null}
    $argsList=@('--disable','--ipv4','--silent','--connect-timeout',[string][Math]::Min(4,$TimeoutSeconds),'--max-time',[string]$TimeoutSeconds,'--output','NUL','--dump-header','-','--write-out','GYING_HTTP_STATUS:%{http_code}')
    if($uri.IsLoopback) { $argsList+=@('--noproxy','*') }
    $argsList+=$uri.AbsoluteUri
    $psi=[Diagnostics.ProcessStartInfo]::new()
    $psi.FileName=(Get-Command curl.exe -CommandType Application -ErrorAction Stop).Source
    $psi.Arguments=($argsList | ForEach-Object { '"'+$_+'"' }) -join ' '
    $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true
    $p=[Diagnostics.Process]::new(); $p.StartInfo=$psi
    try {
        $null=$p.Start()
        $stdout=$p.StandardOutput.ReadToEndAsync(); $stderr=$p.StandardError.ReadToEndAsync()
        if(-not $p.WaitForExit(($TimeoutSeconds+3)*1000)) { $p.Kill(); $p.WaitForExit(); $result.exit=124; $result.error='process_timeout'; return [pscustomobject]$result }
        $raw=$stdout.Result; $null=$stderr.Result; $result.exit=$p.ExitCode
        $status=[regex]::Match($raw,'(?m)^GYING_HTTP_STATUS:(\d{3})\s*$')
        if($status.Success) { $result.observed=$status.Groups[1].Value }
        if($ExpectedRedirectPath) {
            $result.redirect_matched=$false
            $locations=[regex]::Matches($raw,'(?im)^Location:\s*([^\r\n]+)')
            if($locations.Count -eq 1) {
                try {
                    $target=[Uri]::new($uri,$locations[0].Groups[1].Value.Trim())
                    $result.redirect_matched=($target.Scheme -eq $uri.Scheme -and $target.Host -eq $uri.Host -and $target.Port -eq $uri.Port -and $target.AbsolutePath -ceq $ExpectedRedirectPath -and -not $target.Query -and -not $target.Fragment -and -not $target.UserInfo)
                } catch { $result.redirect_matched=$false }
            }
        }
        $result.passed=($result.exit -eq 0 -and $status.Success -and $result.observed -eq [string]$Expected -and (-not $ExpectedRedirectPath -or $result.redirect_matched))
        if($result.exit -ne 0) { $result.error='curl_transport_failure' }
        elseif(-not $status.Success) { $result.error='missing_http_status' }
        elseif($result.observed -ne [string]$Expected) { $result.error='unexpected_http_status' }
        elseif($ExpectedRedirectPath -and -not $result.redirect_matched) { $result.error='unexpected_redirect' }
    } catch {
        $result.error='probe_process_failure'; $result.passed=$false
    } finally { $p.Dispose() }
    [pscustomobject]$result
}

function Test-GyingHealthEndpoints {
    foreach($e in (Get-GyingHealthEndpoints)) {
        Invoke-GyingHttpProbe -Url $e.url -Expected $e.expected -ExpectedRedirectPath ([string]$e.redirect_path)
    }
}

function Write-GyingJson {
    param([Parameter(Mandatory=$true)]$Value,[Parameter(Mandatory=$true)][string]$Path)
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
}
