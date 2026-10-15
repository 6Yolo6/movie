param([Parameter(Mandatory=$true)][string]$HelperPath,[Parameter(Mandatory=$true)][string]$InputPath,[Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
function Invoke-WebRequest { throw 'Network is forbidden in QQ channel fixture' }
function Invoke-RestMethod { throw 'Network is forbidden in QQ channel fixture' }
. $HelperPath
$job = [IO.File]::ReadAllText($InputPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
try {
    switch ($job.Action) {
        'sql' { $value = Get-QqChannelResourcesSql -MovieId ([string]$job.MovieId) }
        'parse' { $value = @(ConvertFrom-QqChannelResourceRows -Rows @($job.Rows)) }
        'links' { $value = Format-QqChannelResourceLinks -Resources @($job.Resources) -FallbackLink ([string]$job.Link) }
        'content' { $value = Format-QqChannelPostContent -Title ([string]$job.Title) -Link ([string]$job.Link) -Intro ([string]$job.Intro) -Year ([string]$job.Year) -MediaType ([string]$job.MediaType) -ContentTemplate ([string]$job.Template) -Resources @($job.Resources) }
        default { throw 'Unknown fixture operation' }
    }
    $result = @{ Ok=$true; Value=$value }
} catch { $result = @{ Ok=$false; Error=$_.Exception.Message } }
[IO.File]::WriteAllText($OutputPath, ($result | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
