"""Exercise rollback state transitions with mocked firewall cmdlets only."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]

@unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'requires Windows PowerShell')
class FirewallRollbackTest(unittest.TestCase):
    def run_fixture(self, mode):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            attempts = folder / 'firewall-attempts'
            attempt = attempts / 'fixture'
            attempt.mkdir(parents=True)
            (attempt / 'active.json').write_text('{}', encoding='utf-8')
            before = {'rule_name': 'GYing-Hardening-20260925-PhysicalSensitiveTCP', 'rule_preexisted': False, 'public_enabled': False, 'default_inbound': 'NotConfigured'}
            (attempt / 'before.json').write_text(json.dumps(before), encoding='utf-8')
            source = (ROOT / 'tools/security/Rollback-FirewallStep.ps1').read_text(encoding='utf-8')
            source = source.replace('G:\\gying-tools\\security-20260925\\firewall-attempts', str(attempts).replace("'", "''"))
            script = folder / 'Rollback-FirewallStep.ps1'
            script.write_text(source, encoding='utf-8')
            shutil.copyfile(ROOT / 'tools/security/firewall_health.ps1', folder / 'firewall_health.ps1')
            runner = folder / 'fixture.ps1'
            runner.write_text(r'''param([string]$Script,[string]$Attempt,[string]$Mode)
$ErrorActionPreference='Stop'
$global:ruleExists=$true; $global:mutations=0
$global:profile=[pscustomobject]@{Enabled=$true;DefaultInboundAction='Block'}
function Get-NetFirewallRule { param($PolicyStore,$Name,$ErrorAction); if($global:ruleExists) { [pscustomobject]@{Name=$Name} } }
function Remove-NetFirewallRule { param($PolicyStore,$Name); $global:mutations++; if($Mode -ne 'remove_failed') { $global:ruleExists=$false } }
function Set-NetFirewallProfile { param($PolicyStore,$Name,$Enabled,$DefaultInboundAction); $global:mutations++; $global:profile=[pscustomobject]@{Enabled=$Enabled;DefaultInboundAction=$DefaultInboundAction} }
function Get-NetFirewallProfile { param($PolicyStore,$Name); $global:profile }
$caught=$false
$target=$Attempt
if($Mode -eq 'outside') { $target=Split-Path -Parent (Split-Path -Parent $Attempt) }
if($Mode -eq 'cancelled') { Remove-Item -LiteralPath (Join-Path $Attempt 'active.json') }
try { if($Mode -eq 'cancelled') { & $Script -AttemptDirectory $target -Watch } else { & $Script -AttemptDirectory $target } } catch { $caught=$true }
[ordered]@{caught=$caught;mutations=$global:mutations;rule_exists=$global:ruleExists;public_enabled=[bool]$global:profile.Enabled;inbound=$global:profile.DefaultInboundAction;active=(Test-Path -LiteralPath (Join-Path $Attempt 'active.json'));rollback=(Test-Path -LiteralPath (Join-Path $Attempt 'rollback.json'));error=(Test-Path -LiteralPath (Join-Path $Attempt 'rollback-error.json'))} | ConvertTo-Json -Compress
''', encoding='utf-8')
            result = subprocess.run(['powershell.exe', '-NoProfile', '-File', str(runner), '-Script', str(script), '-Attempt', str(attempt), '-Mode', mode], capture_output=True, text=True, encoding='utf-8', timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(result.returncode, 0, result.stderr[:1000])
            return json.loads(result.stdout)

    def test_success_restores_only_owned_rule_and_profile(self):
        r = self.run_fixture('success')
        self.assertFalse(r['caught'])
        self.assertFalse(r['rule_exists'])
        self.assertFalse(r['public_enabled'])
        self.assertEqual(r['inbound'], 'NotConfigured')
        self.assertFalse(r['active'])
        self.assertTrue(r['rollback'])
        self.assertFalse(r['error'])
        self.assertEqual(r['mutations'], 2)

    def test_failed_rule_removal_keeps_active_marker_and_error(self):
        r = self.run_fixture('remove_failed')
        self.assertTrue(r['caught'])
        self.assertTrue(r['rule_exists'])
        self.assertTrue(r['active'])
        self.assertTrue(r['error'])
        self.assertFalse(r['rollback'])

    def test_outside_attempt_directory_rejected_without_mutation(self):
        r = self.run_fixture('outside')
        self.assertTrue(r['caught'])
        self.assertEqual(r['mutations'], 0)

    def test_cancelled_watchdog_does_not_mutate(self):
        r = self.run_fixture('cancelled')
        self.assertFalse(r['caught'])
        self.assertEqual(r['mutations'], 0)
        self.assertFalse(r['rollback'])

if __name__ == '__main__':
    unittest.main()
