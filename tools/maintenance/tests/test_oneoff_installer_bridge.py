"""Exercise the installer's PowerShell/Python handoff without registering tasks."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).parents[1]
HARNESS = r'''
param([string]$Installer, [string]$Python, [string]$Runtime, [string]$ConfigPath, [string]$PermitPath, [string]$Scenario)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($Installer,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Installer syntax error' }
$functions=$ast.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]},$false)
foreach ($name in @('ConvertTo-OneOffTimestamp','Confirm-OneOffPermit')) {
    $function=$functions | Where-Object { $_.Name -eq $name }
    if (-not $function) { throw 'Missing installer helper' }
    Invoke-Expression $function.Extent.Text
}
$start=[DateTimeOffset]::Parse('2026-10-26T14:00:00.9876543+08:00')
$permit=Get-Content -LiteralPath $PermitPath -Raw -Encoding UTF8 | ConvertFrom-Json
$permit.startAt=ConvertTo-OneOffTimestamp $start
$permit.latestStartAt=ConvertTo-OneOffTimestamp $start.AddMinutes(10)
$permit.finishBefore=ConvertTo-OneOffTimestamp $start.AddMinutes(60)
if ($Scenario -eq 'legacy') {
    $permit.startAt=$start.ToString('o')
    $permit.latestStartAt=$start.AddMinutes(10).ToString('o')
    $permit.finishBefore=$start.AddMinutes(60).ToString('o')
}
if ($Scenario -eq 'timezone') { $permit.startAt='2026-10-26T06:00:00+00:00' }
if ($Scenario -eq 'start-budget') { $permit.latestStartAt='2026-10-26T14:11:00+08:00' }
if ($Scenario -eq 'finish-budget') { $permit.finishBefore='2026-10-26T15:01:00+08:00' }
if ($Scenario -eq 'config-hash') { $permit.configSha256='0'*64 }
if ($Scenario -eq 'runtime-hash') { $permit.runtimeSha256='0'*64 }
[IO.File]::WriteAllText($PermitPath,($permit | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Confirm-OneOffPermit -Python $Python -Runtime $Runtime -ConfigPath $ConfigPath -PermitPath $PermitPath
'validated-with-configured-python'
'''


@unittest.skipUnless(os.name == 'nt', 'Requires Windows PowerShell for the installer handoff')
class OneOffInstallerBridge(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.config = self.root / 'config.json'
        self.config.write_text(json.dumps({'host': 'FIXTURE', 'stateRoot': str(self.root)}), encoding='utf-8')
        self.runtime = ROOT / 'weekly_restart.py'
        self.permit = self.root / 'one-off' / 'fixture.json'
        self.permit.parent.mkdir()
        self.permit_data = {
            'version': 1, 'id': 'fixture', 'host': 'FIXTURE',
            'configSha256': hashlib.sha256(self.config.read_bytes()).hexdigest(),
            'runtimeSha256': hashlib.sha256(self.runtime.read_bytes()).hexdigest(),
            'startAt': '', 'latestStartAt': '', 'finishBefore': '',
        }
        self.harness = self.root / 'bridge.ps1'
        self.harness.write_text(HARNESS, encoding='utf-8')

    def bridge(self, scenario):
        self.permit.write_text(json.dumps(self.permit_data), encoding='utf-8')
        return subprocess.run([
            'powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.harness),
            '-Installer', str(ROOT / 'Install-OneOffRestart.ps1'),
            '-Python', sys.executable, '-Runtime', str(self.runtime),
            '-ConfigPath', str(self.config), '-PermitPath', str(self.permit), '-Scenario', scenario,
        ], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)

    def test_installer_window_is_accepted_by_configured_python(self):
        result = self.bridge('valid')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # Validation emits no extra stdout that would corrupt the installation JSON.
        self.assertEqual(result.stdout.strip(), 'validated-with-configured-python')
        permit = json.loads(self.permit.read_text(encoding='utf-8-sig'))
        times = [dt.datetime.fromisoformat(permit[k]) for k in ('startAt', 'latestStartAt', 'finishBefore')]
        self.assertTrue(all(v.utcoffset() == dt.timedelta(hours=8) and v.microsecond == 0 for v in times))
        self.assertEqual(times[1] - times[0], dt.timedelta(minutes=10))
        self.assertEqual(times[2] - times[0], dt.timedelta(minutes=60))

    def test_python_310_rejects_the_original_dotnet_roundtrip_permit(self):
        if sys.version_info >= (3, 11):
            self.skipTest('The production regression reproduces with Python 3.10')
        result = self.bridge('legacy')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('validated-with-configured-python', result.stdout)
        self.assertIn('Invalid bounded one-off window', result.stderr)

    def test_registration_gate_preserves_timezone_window_and_identity_checks(self):
        expected = {
            'timezone': 'Invalid bounded one-off window',
            'start-budget': 'Invalid bounded one-off window',
            'finish-budget': 'Invalid bounded one-off window',
            'config-hash': 'One-off configuration changed',
            'runtime-hash': 'One-off runtime changed',
        }
        for scenario, reason in expected.items():
            with self.subTest(scenario=scenario):
                result = self.bridge(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('validated-with-configured-python', result.stdout)
                self.assertIn(reason, result.stderr)


if __name__ == '__main__':
    unittest.main()
