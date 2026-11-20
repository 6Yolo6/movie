. (Join-Path $PSScriptRoot '../TaskVerification.ps1')
$expected = [DateTimeOffset]::Parse('2026-10-25T09:30:00+08:00')
foreach ($case in @(@(0,$true), @(30,$true), @(59,$true), @(-1,$false), @(60,$false), @(604800,$false))) {
    if ((Test-WeeklyTaskTime $expected $expected.ToString('o') $expected.LocalDateTime.AddSeconds($case[0])) -ne $case[1]) { throw "Incorrect next-run offset: $($case[0])" }
}
if (Test-WeeklyTaskTime $expected '2026-10-25T09:30:30+08:00' $expected.LocalDateTime) { throw 'Changed boundary accepted' }
if (Test-WeeklyTaskTime $expected 'invalid' $expected.LocalDateTime) { throw 'Invalid boundary accepted' }
'8 task-time verification cases passed.'
