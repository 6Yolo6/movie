[CmdletBinding()]
param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"
$zh = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
    "eyJ0aXRsZSI6IiMgR1lpbmcg6aG555uu6L+Q57u05bCx57uq5qOA5p+lIiwicmVwb3NpdG9yeSI6IuS7k+W6k++8mnswfSIsInRhYmxlSGVhZGVyIjoifCDnirbmgIEgfCDmo4Dmn6XpobkgfCDor6bmg4UgfCIsInBhc3MiOiLpgJrov4ciLCJ3YXJuIjoi6K2m5ZGKIiwiZmFpbCI6IuWksei0pSIsInN1bW1hcnkiOiLmsYfmgLvvvJrpgJrov4c9ezB9IOitpuWRij17MX0g5aSx6LSlPXsyfSIsInJlcXVpcmVkRmlsZXMiOiLlv4XpnIDpobnnm67mlofku7YiLCJtaXNzaW5nIjoi57y65aSx77yaezB9IiwicmVxdWlyZWRQcmVzZW50IjoiezB9IOS4quW/hemcgOaWh+S7tuWdh+WtmOWcqCIsInN0YXR1c0hlYWRpbmdzIjoi54q25oCB5paH5qGj56ug6IqCIiwiaGVhZGluZ3NQcmVzZW50Ijoi5YWo6YOo57u05oqk56ug6IqC5Z2H5a2Y5ZyoIiwic3RhdHVzRGF0ZSI6IueKtuaAgeabtOaWsOaXtumXtCIsImRhdGVGb3JtYXQiOiLkvb/nlKjnjrDmnInkuK3mlofmm7TmlrDml7bpl7TmoIfnrb7vvIzlkI7mjqUgWVlZWS1NTS1ERCIsInV0ZjhUZXh0IjoiVVRGLTgg5paH5pysIiwibW9qaWJha2UiOiLmo4DmtYvliLDnlpHkvLzkubHnoIHmiJbmm7/mjaLlrZfnrKYiLCJub01vamliYWtlIjoi5pyq5qOA5rWL5Yiw5bi46KeB5Lmx56CB5qCH6K6wIiwic2VjcmV0VHJhY2tpbmciOiLmlY/mhJ/mlofku7bot5/ouKoiLCJ0cmFja2VkRW52Ijoi5Y+R546w5bey6Lef6Liq55qE5Zyo57q/546v5aKD5paH5Lu277yaezB9Iiwibm9UcmFja2VkRW52Ijoi5rKh5pyJ6Lef6Liq5Zyo57q/546v5aKD5paH5Lu2IiwibGVnYWN5Q3JlZGVudGlhbHMiOiLljoblj7Llh63mja7lrZfpnaLph48iLCJyZXZpZXdMZWdhY3kiOiLov5DooYzliY3lv4Xpobvmo4Dmn6XvvJp7MH0iLCJub0xlZ2FjeSI6IuacquajgOa1i+WIsOW3suefpSBjcmF3bGVyIOWHreaNruWtl+mdoumHjyIsImNvbXBvc2VTZXJ2aWNlcyI6IkNvbXBvc2Ug5pyN5YqhIiwiZGV0ZWN0ZWQiOiLlt7Lmo4DmtYvvvJp7MH0iLCJtaWdyYXRpb25EcmlmdCI6Iui/geenu+aWh+aho+a8guenuyIsIm5vdExpc3RlZCI6ImRvY3MvZGF0YWJhc2UubWQg5pyq5YiX5Ye677yaezB9IiwiYWxsTGlzdGVkIjoi5YWo6YOo6L+B56e75paH5Lu25Z2H5bey5YiX5Ye6IiwiZnJlc2hDb3ZlcmFnZSI6IuaWsOW6k+aetuaehOimhuebliIsIm1pZ3JhdGlvbk9ubHkiOiLku4XnlLHov4Hnp7vliJvlu7rnmoTooajvvJp7MH0iLCJub01pZ3JhdGlvbk9ubHkiOiLmnKrlj5HnjrDlj6rnlLHov4Hnp7sgQ1JFQVRFIFRBQkxFIOeahOihqCIsImVudkRyaWZ0Ijoi546v5aKD5Y+Y6YeP5aWR57qm5ryC56e7IiwiZW52QWJzZW50Ijoi55Sf5Lqn6YWN572u5bey5byV55So5L2GIC5lbnYuZXhhbXBsZSDnvLrlsJHvvJp7MH0iLCJlbnZDb3ZlcmVkIjoiLmVudi5leGFtcGxlIOW3suimhueblueUn+S6p+mFjee9ruW8leeUqCIsInNraWxsTGlua3MiOiJTa2lsbCDlvJXnlKjpk77mjqUiLCJsaW5rc1Jlc29sdmUiOiJ7MH0g5Liq55u05o6l5byV55So5Z2H5Y+v6Kej5p6QIiwic2tpbGxTaXplIjoiU2tpbGwg5YWl5Y+j5aSn5bCPIiwibGluZUNvdW50IjoiezB9IOihjCIsImxpbmVMaW1pdCI6InswfSDooYzvvJvlupTkv53mjIHlnKggNTAwIOihjOS7peWGhSJ9"
)) | ConvertFrom-Json

function Resolve-GyingRepoRoot {
    param([string]$RequestedRoot)

    if ($RequestedRoot) {
        return (Resolve-Path -LiteralPath $RequestedRoot).Path
    }

    $candidate = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -eq 0 -and $candidate) {
        return (Resolve-Path -LiteralPath $candidate.Trim()).Path
    }

    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..\..")).Path
}

function Get-EnvKeyNames {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return @()
    }

    return @(
        Get-Content -Encoding UTF8 -LiteralPath $Path |
            ForEach-Object {
                if ($_ -match '^\s*([^#=\s]+)=') {
                    $matches[1]
                }
            } |
            Sort-Object -Unique
    )
}

function Get-CreateTableNames {
    param([string[]]$Paths)

    $names = [System.Collections.Generic.List[string]]::new()
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) {
            continue
        }
        Get-Content -Encoding UTF8 -LiteralPath $path |
            ForEach-Object {
                if ($_ -match '^\s*CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([A-Za-z0-9_]+)`?') {
                    $names.Add($matches[1])
                }
            }
    }
    return @($names | Sort-Object -Unique)
}

function Add-Check {
    param(
        [ValidateSet("PASS", "WARN", "FAIL")][string]$Status,
        [string]$Name,
        [string]$Detail
    )
    $script:Checks.Add([pscustomobject]@{
        Status = $Status
        Name = $Name
        Detail = $Detail
    }) | Out-Null
}

$root = Resolve-GyingRepoRoot -RequestedRoot $RepoRoot
$checks = [System.Collections.Generic.List[object]]::new()
$script:Checks = $checks

$requiredFiles = @(
    ".env.example",
    "docker-compose.prod.yml",
    "docs/current-project-status.md",
    "backend/pom.xml",
    "backend/Dockerfile",
    "backend/src/main/resources/application-prod.yml",
    "backend/src/main/resources/db/schema.sql",
    "backend/mysql_gying_mcp.py",
    "backend/docker_mcp.py",
    "backend/src/main/resources/db/migration_movie_source_identity.sql",
    "crawler/Dockerfile",
    "social-publisher/Dockerfile",
    "social-publisher/package.json",
    "backend/src/main/resources/db/migration_gying_owned_share_source.sql",
    "backend/src/main/resources/db/migration_qq_channel_template_year_type.sql",
    "backend/src/main/resources/db/migration_social_publishing.sql",
    "frontend/package.json",
    "frontend/Dockerfile",
    "nginx/nginx.conf",
    ".agents/skills/gying-project-ops/SKILL.md",
    ".agents/skills/gying-project-ops/agents/openai.yaml"
)
$missingRequired = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) })
if ($missingRequired) {
    Add-Check FAIL $zh.requiredFiles ($zh.missing -f ($missingRequired -join ", "))
} else {
    Add-Check PASS $zh.requiredFiles ($zh.requiredPresent -f $requiredFiles.Count)
}

$statusPath = Join-Path $root "docs\current-project-status.md"
if (Test-Path -LiteralPath $statusPath) {
    $statusText = Get-Content -Raw -Encoding UTF8 -LiteralPath $statusPath
    $requiredHeadings = @(
        (-join ([char[]](0x5F53, 0x524D, 0x76EE, 0x6807))),
        (-join ([char[]](0x8FD0, 0x884C, 0x67B6, 0x6784))),
        (-join ([char[]](0x5DF2, 0x5177, 0x5907, 0x80FD, 0x529B))),
        (-join ([char[]](0x914D, 0x7F6E, 0x4E0E, 0x5B89, 0x5168))),
        (-join ([char[]](0x4ECD, 0x9700, 0x5904, 0x7406))),
        (-join ([char[]](0x957F, 0x671F, 0x4E0D, 0x53D8, 0x91CF))),
        (-join ([char[]](0x9A8C, 0x6536)))
    )
    $missingHeadings = @($requiredHeadings | Where-Object { $statusText -notmatch "(?m)^##\s+$([regex]::Escape($_))\s*$" })
    if ($missingHeadings) {
        Add-Check FAIL $zh.statusHeadings ($zh.missing -f ($missingHeadings -join ", "))
    } else {
        Add-Check PASS $zh.statusHeadings $zh.headingsPresent
    }

    $datePrefix = -join ([char[]](0x66F4, 0x65B0, 0x65F6, 0x95F4, 0xFF1A))
    $dateLine = @($statusText -split "`r?`n" | Where-Object { $_.StartsWith($datePrefix) } | Select-Object -First 1)
    if ($dateLine -and $dateLine[0].Substring($datePrefix.Length) -match '^\d{4}-\d{2}-\d{2}\s*$') {
        Add-Check PASS $zh.statusDate $dateLine[0]
    } else {
        Add-Check WARN $zh.statusDate $zh.dateFormat
    }

    $mojibakeMarkers = @([char]0x951B, [char]0x9225, [char]0x9286)
    $hasMojibakeMarker = @($mojibakeMarkers | Where-Object { $statusText.Contains($_) }).Count -gt 0
    if ($statusText.Contains([char]0xFFFD) -or $hasMojibakeMarker) {
        Add-Check WARN $zh.utf8Text $zh.mojibake
    } else {
        Add-Check PASS $zh.utf8Text $zh.noMojibake
    }
}

$trackedSecretPaths = @(
    & git -C $root ls-files -- ".env" "backend/.env" "frontend/.env.local" 2>$null
)
if ($trackedSecretPaths) {
    Add-Check FAIL $zh.secretTracking ($zh.trackedEnv -f ($trackedSecretPaths -join ", "))
} else {
    Add-Check PASS $zh.secretTracking $zh.noTrackedEnv
}

$legacyCredentialFiles = [System.Collections.Generic.List[string]]::new()
$legacyScriptPaths = @(
    "crawler/add_name_column.py",
    "crawler/db_migration.py",
    "crawler/gyingmovie.py",
    "crawler/reset_password.py",
    "crawler/run_migration.py"
)
foreach ($relative in $legacyScriptPaths) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path)) {
        continue
    }
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    if ($content -match '(?m)^\s*(DB_PASS|COOKIE_STR)\s*=\s*["''][^"'']+["'']') {
        $legacyCredentialFiles.Add($relative)
    }
}
if ($legacyCredentialFiles.Count -gt 0) {
    Add-Check WARN $zh.legacyCredentials ($zh.reviewLegacy -f ($legacyCredentialFiles -join ", "))
} else {
    Add-Check PASS $zh.legacyCredentials $zh.noLegacy
}

$composePath = Join-Path $root "docker-compose.prod.yml"
if (Test-Path -LiteralPath $composePath) {
    $composeLines = Get-Content -Encoding UTF8 -LiteralPath $composePath
    $services = [System.Collections.Generic.List[string]]::new()
    $inServices = $false
    foreach ($line in $composeLines) {
        if ($line -match '^services:\s*$') {
            $inServices = $true
            continue
        }
        if ($inServices -and $line -match '^[A-Za-z]') {
            break
        }
        if ($inServices -and $line -match '^  ([A-Za-z0-9-]+):\s*$') {
            $services.Add($matches[1])
        }
    }
    $requiredServices = @("nginx", "backend", "frontend", "gying-source", "social-publisher")
    $missingServices = @($requiredServices | Where-Object { $_ -notin $services })
    if ($missingServices) {
        Add-Check FAIL $zh.composeServices ($zh.missing -f ($missingServices -join ", "))
    } else {
        Add-Check PASS $zh.composeServices ($zh.detected -f ($services -join ", "))
    }
}

$dbDir = Join-Path $root "backend\src\main\resources\db"
$migrationFiles = @(
    Get-ChildItem -LiteralPath $dbDir -Filter "migration_*.sql" -ErrorAction SilentlyContinue |
        Sort-Object Name
)
$databaseDocPath = Join-Path $root "docs\database.md"
if (Test-Path -LiteralPath $databaseDocPath) {
    $databaseDoc = Get-Content -Raw -Encoding UTF8 -LiteralPath $databaseDocPath
    $undocumented = @($migrationFiles | Where-Object { $databaseDoc -notmatch [regex]::Escape($_.Name) } | ForEach-Object Name)
    if ($undocumented) {
        Add-Check WARN $zh.migrationDrift ($zh.notListed -f ($undocumented -join ", "))
    } else {
        Add-Check PASS $zh.migrationDrift $zh.allListed
    }
}

$schemaPath = Join-Path $dbDir "schema.sql"
$schemaTables = @(Get-CreateTableNames -Paths @($schemaPath))
$migrationTables = @(Get-CreateTableNames -Paths @($migrationFiles.FullName))
$migrationOnlyTables = @($migrationTables | Where-Object { $_ -notin $schemaTables })
if ($migrationOnlyTables) {
    Add-Check WARN $zh.freshCoverage ($zh.migrationOnly -f ($migrationOnlyTables -join ", "))
} else {
    Add-Check PASS $zh.freshCoverage $zh.noMigrationOnly
}

$rootEnvPath = Join-Path $root ".env.example"
$rootKeys = @(Get-EnvKeyNames -Path $rootEnvPath)
$configPaths = @(
    (Join-Path $root "docker-compose.prod.yml"),
    (Join-Path $root "backend\src\main\resources\application-prod.yml")
)
$referencedKeys = [System.Collections.Generic.List[string]]::new()
foreach ($path in $configPaths) {
    if (-not (Test-Path -LiteralPath $path)) {
        continue
    }
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    [regex]::Matches($content, '\$\{([A-Z][A-Z0-9_]*)') |
        ForEach-Object { $referencedKeys.Add($_.Groups[1].Value) }
}
$missingContractKeys = @($referencedKeys | Sort-Object -Unique | Where-Object { $_ -notin $rootKeys })
if ($missingContractKeys) {
    Add-Check WARN $zh.envDrift ($zh.envAbsent -f ($missingContractKeys -join ", "))
} else {
    Add-Check PASS $zh.envDrift $zh.envCovered
}

$skillRoot = Join-Path $root ".agents\skills\gying-project-ops"
$skillPath = Join-Path $skillRoot "SKILL.md"
if (Test-Path -LiteralPath $skillPath) {
    $skillText = Get-Content -Raw -Encoding UTF8 -LiteralPath $skillPath
    $skillLines = @(Get-Content -Encoding UTF8 -LiteralPath $skillPath).Count
    $referenceLinks = @(
        [regex]::Matches($skillText, '\]\((references/[^)]+)\)') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
    $missingLinks = @($referenceLinks | Where-Object { -not (Test-Path -LiteralPath (Join-Path $skillRoot $_)) })
    if ($missingLinks) {
        Add-Check FAIL $zh.skillLinks ($zh.missing -f ($missingLinks -join ", "))
    } else {
        Add-Check PASS $zh.skillLinks ($zh.linksResolve -f $referenceLinks.Count)
    }
    if ($skillLines -lt 500) {
        Add-Check PASS $zh.skillSize ($zh.lineCount -f $skillLines)
    } else {
        Add-Check WARN $zh.skillSize ($zh.lineLimit -f $skillLines)
    }
}

Write-Output $zh.title
Write-Output ""
Write-Output ($zh.repository -f $root)
Write-Output ""
Write-Output $zh.tableHeader
Write-Output "| --- | --- | --- |"
foreach ($check in $checks) {
    $detail = ([string]$check.Detail).Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
    $displayStatus = switch ($check.Status) {
        "PASS" { $zh.pass }
        "WARN" { $zh.warn }
        "FAIL" { $zh.fail }
    }
    Write-Output "| $displayStatus | $($check.Name) | $detail |"
}

$failCount = @($checks | Where-Object Status -eq "FAIL").Count
$warnCount = @($checks | Where-Object Status -eq "WARN").Count
$passCount = @($checks | Where-Object Status -eq "PASS").Count
Write-Output ""
Write-Output ($zh.summary -f $passCount, $warnCount, $failCount)

if ($failCount -gt 0) {
    exit 1
}
