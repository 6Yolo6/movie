param(
    [string]$HelperRoot = 'E:\gying-tools\baidu-login',
    [string]$SourcePath = (Join-Path $PSScriptRoot 'baidu-login-hidden-launcher.cs')
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($HelperRoot)
$source = [IO.Path]::GetFullPath($SourcePath)
$destination = Join-Path $root 'baidu-login-hidden.exe'
if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Baidu helper is not installed' }
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Baidu hidden launcher source is missing' }
if (Test-Path -LiteralPath $destination) { throw 'Preserve and review the existing launcher before replacing it' }
$compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) { throw 'The .NET Framework C# compiler is unavailable' }
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = $compiler
$start.Arguments = '/nologo /target:winexe /optimize+ /out:"' + $destination + '" "' + $source + '"'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$process = [Diagnostics.Process]::Start($start)
try {
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf)) { throw 'Baidu hidden launcher build failed' }
} finally { $process.Dispose() }
Write-Output 'BAIDU_QR_LOGIN_LAUNCHER=installed'
