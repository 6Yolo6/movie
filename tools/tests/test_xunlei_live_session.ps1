$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\refresh-xunlei-live-session.ps1')
. (Join-Path $PSScriptRoot '..\xunlei-token-sync-common.ps1')
$passed=0
function Check([bool]$Value,[string]$Name) {
    if(-not $Value){throw "FAILED:$Name"}
    $script:passed++; Write-Output "PASS:$Name"
}
foreach($url in @('https://pan.xunlei.com/','https://pan.xunlei.com:443/drive','pan.xunlei.com/')){Check (Test-XunleiOrigin $url) 'trusted_origin'}
foreach($url in @('http://pan.xunlei.com/','https://pan.xunlei.com.evil.test/','https://user@pan.xunlei.com/','https://pan.xunlei.com:444/','file:///pan.xunlei.com','javascript:alert(1)','https://evil.test/?pan.xunlei.com','')){Check (-not(Test-XunleiOrigin $url)) 'reject_untrusted_origin'}
$root=New-XunleiPrivateDirectory (Join-Path $env:TEMP ('gying-xunlei-test-'+[Guid]::NewGuid().ToString('N')))
Check (Get-Acl -LiteralPath $root).AreAccessRulesProtected 'private_acl'
Check ((New-XunleiPrivateDirectory $root) -eq $root) 'private_acl_idempotent'
$run=New-XunleiPrivateDirectory (Join-Path $root ('run-'+[Guid]::NewGuid().ToString('N')))
[IO.File]::WriteAllText((Join-Path $run '.xunlei-sync-owned'),(Split-Path -Leaf $run))
[void][IO.Directory]::CreateDirectory((Join-Path $run 'nested'))
[IO.File]::WriteAllText((Join-Path $run 'nested\synthetic.txt'),'synthetic-only')
$outside=Join-Path $root 'outside.txt';[IO.File]::WriteAllText($outside,'preserve')
$rejected=$false
try {Clear-XunleiRunFiles $root $root}catch{$rejected=$true}
Check $rejected 'reject_cleanup_outside_run'
Check (Test-Path -LiteralPath $outside) 'preserve_outside_file'
Clear-XunleiRunFiles $root $run
Check (-not(Test-Path -LiteralPath (Join-Path $run 'nested\synthetic.txt'))) 'clear_run_leaf_files'
Check (Test-Path -LiteralPath (Join-Path $run '.xunlei-sync-owned')) 'keep_ownership_marker'
$backup=Join-Path $root 'synthetic.dpapi'
Save-XunleiEncryptedBackup ([Text.Encoding]::UTF8.GetBytes('synthetic-only')) $backup
Check ((Get-Item -LiteralPath $backup).Length -gt 0) 'dpapi_roundtrip'
foreach($file in @($outside,$backup)){Remove-Item -LiteralPath $file -Force}
foreach($source in @('refresh-xunlei-live-session.ps1','sync-xunlei-edge-token.ps1','xunlei-token-sync-common.ps1')){
    $tokens=$null;$errors=$null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot ('..\'+$source)),[ref]$tokens,[ref]$errors)
    Check (@($errors).Count -eq 0) 'powershell_parse'
}
Write-Output "XUNLEI_WINDOWS_TESTS=$passed"
