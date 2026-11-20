"""Exercise quiesced installer gates and recovery ordering without production writes."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

INSTALLER=Path(__file__).parents[1]/"Install-QuiescedRestart.ps1"
HARNESS=r'''
param([string]$Installer,[string]$Scenario,[string]$Report,[string]$Python,[string]$Receipt)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($Installer,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Installer syntax error' }
$functions=$ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]},$false)
foreach ($fn in $functions) { Invoke-Expression $fn.Extent.Text }
if ($Scenario -eq 'readiness') {
    Confirm-QuiescenceReadiness ($Report | ConvertFrom-Json)
    'accepted'
} elseif ($Scenario -eq 'python') {
    $result=Invoke-QuiescencePython $Python @('-c',"import json; print(json.dumps({'phase':'prepared'}))") $Receipt
    if ($result.phase -ne 'prepared') { throw 'Python JSON bridge changed' }
    'bridge-ok'
} else {
    $events=[Collections.Generic.List[string]]::new()
    $result=Invoke-QuiescenceCancellation {
        $events.Add('cancel')
        if ($Scenario -in @('cancel-fails','both-fail')) { throw 'binding refusal' }
    } {
        $events.Add('restore')
        if ($Scenario -in @('restore-fails','both-fail')) { throw 'watchdog unavailable' }
    }
    [ordered]@{events=$events.ToArray();errors=@($result)} | ConvertTo-Json -Compress
}
'''
@unittest.skipUnless(os.name=="nt","Windows native PowerShell bridge")
class QuiescedInstallerBridge(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.harness=self.root/"bridge.ps1"
        self.harness.write_text(HARNESS,encoding="utf-8")
        self.receipt=self.root/"receipt.json"
    def call(self,scenario,report=None):
        return subprocess.run(["powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",str(self.harness),
                               "-Installer",str(INSTALLER),"-Scenario",scenario,"-Report",json.dumps(report),
                               "-Python",sys.executable,"-Receipt",str(self.receipt)],
                              capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=30)
    def test_clean_cancellation_keeps_both_actions_in_order(self):
        r=self.call("clean")
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(json.loads(r.stdout),{"events":["cancel","restore"],"errors":[]})
    def test_cancel_failure_does_not_skip_restoration(self):
        r=self.call("cancel-fails")
        self.assertEqual(r.returncode,0,r.stderr)
        data=json.loads(r.stdout)
        self.assertEqual(data["events"],["cancel","restore"])
        self.assertEqual(data["errors"],["cancel: binding refusal"])
    def test_restore_failure_preserves_separate_cancellation_error(self):
        r=self.call("both-fail")
        self.assertEqual(r.returncode,0,r.stderr)
        data=json.loads(r.stdout)
        self.assertEqual(data["events"],["cancel","restore"])
        self.assertEqual(data["errors"],["cancel: binding refusal","restore: watchdog unavailable"])
    def test_restoration_failure_still_retains_completed_cancellation(self):
        r=self.call("restore-fails")
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(json.loads(r.stdout)["errors"],["restore: watchdog unavailable"])
    def test_only_zero_integer_readonly_readiness_can_replace_runtime(self):
        self.assertEqual(self.call("readiness",{"mode":"read-only","pendingAttempts":0}).returncode,0)
        for report in [{"mode":"read-only","pendingAttempts":1},{"mode":"read-only","pendingAttempts":-1},
                       {"mode":"read-only","pendingAttempts":"0"},{"mode":"read-only"},
                       {"mode":"write","pendingAttempts":0},{"mode":"read-only","pendingAttempts":None}]:
            with self.subTest(report=report):
                self.assertNotEqual(self.call("readiness",report).returncode,0)
    def test_configured_python_receipt_preserves_stdout_and_exit_code(self):
        r=self.call("python")
        self.assertEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertEqual(r.stdout.strip(),"bridge-ok")
        data=json.loads(self.receipt.read_text(encoding="utf-8-sig"))
        self.assertEqual(data["exitCode"],0)
        self.assertEqual(json.loads(data["output"]),{"phase":"prepared"})
if __name__=="__main__":
    unittest.main()
