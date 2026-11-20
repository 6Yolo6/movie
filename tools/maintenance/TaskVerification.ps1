function Test-WeeklyTaskTime {
    param([DateTimeOffset]$Expected, [string]$StartBoundary, [datetime]$NextRunTime)
    try { $boundary = [DateTimeOffset]::Parse($StartBoundary) } catch { return $false }
    $offset = ($NextRunTime - $Expected.LocalDateTime).TotalSeconds
    return $boundary -eq $Expected -and $offset -ge 0 -and $offset -lt 60
}
