"""Windows-only loopback fixtures for PowerShell firewall probes; no firewall mutation."""
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.parse

ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / 'tools/security/firewall_health.ps1'

@unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe') and shutil.which('curl.exe'), 'requires Windows PowerShell and curl')
class FirewallHealthTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seen = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                path = urllib.parse.urlsplit(self.path).path
                cls.seen.append(path)
                if path == '/slow':
                    time.sleep(2)
                redirects = {'/redirect': '/login', '/foreign': 'https://example.invalid/login', '/query-redirect': '/login?token=fixture-only', '/wrong-redirect': '/unexpected'}
                code = 302 if path in redirects else (503 if path == '/unavailable' else (404 if path == '/missing' else 200))
                self.send_response(code)
                if path in redirects:
                    self.send_header('Location', redirects[path])
                self.send_header('Set-Cookie', 'fixture-only=private-value')
                self.end_headers()
                try:
                    self.wfile.write(b'fixture-private-body')
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        port = cls.server.server_port
        base = 'http://127.0.0.1:' + str(port)
        cases = [
            {'name': 'ok', 'url': base + '/ok?token=fixture-only', 'expected': 200},
            {'name': 'login', 'url': base + '/login', 'expected': 200},
            {'name': 'redirect', 'url': base + '/redirect', 'expected': 302, 'redirect': '/login'},
            {'name': 'foreign', 'url': base + '/foreign', 'expected': 302, 'redirect': '/login'},
            {'name': 'query_redirect', 'url': base + '/query-redirect', 'expected': 302, 'redirect': '/login'},
            {'name': 'wrong_redirect', 'url': base + '/wrong-redirect', 'expected': 302, 'redirect': '/login'},
            {'name': 'unavailable', 'url': base + '/unavailable', 'expected': 200},
            {'name': 'missing', 'url': base + '/missing', 'expected': 404},
            {'name': 'timeout', 'url': base + '/slow', 'expected': 200, 'timeout': 1},
            {'name': 'tls_failure', 'url': 'https://127.0.0.1:' + str(port) + '/ok', 'expected': 200},
        ]
        with socket.socket() as reserved, tempfile.TemporaryDirectory() as temp:
            reserved.bind(('127.0.0.1', 0))
            cases.append({'name': 'connection_failure', 'url': 'http://127.0.0.1:' + str(reserved.getsockname()[1]) + '/', 'expected': 200})
            folder = Path(temp)
            (folder / 'cases.json').write_text(json.dumps(cases), encoding='utf-8')
            runner = folder / 'run.ps1'
            runner.write_text(r'''param([string]$Module,[string]$Cases)
$ErrorActionPreference='Stop'
. $Module
$out=@()
foreach($c in (Get-Content -LiteralPath $Cases -Raw -Encoding UTF8 | ConvertFrom-Json)) {
    $timeout=3
    if($c.timeout) { $timeout=[int]$c.timeout }
    $r=Invoke-GyingHttpProbe -Url $c.url -Expected $c.expected -ExpectedRedirectPath ([string]$c.redirect) -TimeoutSeconds $timeout
    $out += [pscustomobject]@{name=$c.name;result=$r}
}
$out | ConvertTo-Json -Depth 8 -Compress
''', encoding='utf-8')
            result = subprocess.run(['powershell.exe', '-NoProfile', '-File', str(runner), '-Module', str(MODULE), '-Cases', str(folder / 'cases.json')], capture_output=True, text=True, encoding='utf-8', timeout=45, creationflags=subprocess.CREATE_NO_WINDOW)
            if result.returncode:
                raise AssertionError('PowerShell probe fixture runner failed: ' + result.stderr[:1200])
            cls.raw = result.stdout
            cls.results = {r['name']: r['result'] for r in json.loads(result.stdout)}

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_public_and_expected_auth_statuses(self):
        for name in ['ok', 'login', 'missing']:
            self.assertTrue(self.results[name]['passed'], name)

    def test_same_origin_login_redirect_is_checked_not_followed(self):
        self.assertTrue(self.results['redirect']['passed'])
        self.assertTrue(self.results['redirect']['redirect_matched'])
        self.assertEqual(self.seen.count('/login'), 1)
        self.assertNotIn('/unexpected', self.seen)

    def test_arbitrary_or_token_bearing_redirects_fail_closed(self):
        for name in ['foreign', 'query_redirect', 'wrong_redirect']:
            self.assertFalse(self.results[name]['passed'], name)
            self.assertEqual(self.results[name]['error'], 'unexpected_redirect')

    def test_bad_status_is_not_swallowed(self):
        self.assertFalse(self.results['unavailable']['passed'])
        self.assertEqual(self.results['unavailable']['observed'], '503')

    def test_transport_timeout_and_tls_failure_fail_closed(self):
        for name in ['timeout', 'connection_failure', 'tls_failure']:
            self.assertFalse(self.results[name]['passed'], name)
            self.assertNotEqual(self.results[name]['exit'], 0)

    def test_bodies_headers_and_query_values_are_not_reported(self):
        for secret in ['fixture-only', 'private-value', 'fixture-private-body', 'Set-Cookie']:
            self.assertNotIn(secret, self.raw)
        self.assertTrue(self.results['ok']['query_present'])
        self.assertNotIn('?', self.results['ok']['url'])

    def test_explicit_ipv4_and_tls_validation_contract(self):
        for result in self.results.values():
            self.assertEqual(result['address_family'], 'IPv4')
            self.assertEqual(result['tls_verification'], 'enabled')
        source = MODULE.read_text(encoding='utf-8')
        self.assertIn("'--ipv4'", source)
        self.assertNotIn("'--insecure'", source)
        self.assertNotIn("'--location'", source)
        self.assertNotIn("'--ssl-no-revoke'", source)


@unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'requires Windows PowerShell')
class FirewallAttemptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            sandbox = folder / 'sandbox'
            sandbox.mkdir()
            sentinel = sandbox / 'firewall-step-before.json'
            sentinel.write_text('previous-attempt-evidence', encoding='utf-8')
            script = (ROOT / 'tools/security/Apply-FirewallStep.ps1').read_text(encoding='utf-8')
            script = script.replace("$base='G:\\gying-tools\\security-20260925'", "$base='" + str(sandbox).replace("'", "''") + "'")
            copied = folder / 'Apply-FirewallStep.ps1'
            copied.write_text(script, encoding='utf-8')
            (folder / 'firewall_health.ps1').write_text(r'''function Test-GyingHealthEndpoints {
    [pscustomobject]@{url='http://127.0.0.1/fixture';expected=200;observed=if($global:testHealthy){'200'}else{'503'};exit=0;passed=$global:testHealthy;error='fixture'}
}
function Write-GyingJson($Value,[string]$Path) {
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
}
''', encoding='utf-8')
            runner = folder / 'attempts.ps1'
            runner.write_text(r'''param([string]$Script,[string]$Sandbox)
$ErrorActionPreference='Stop'
$global:mutations=0
function Get-NetAdapter { param([switch]$Physical); [pscustomobject]@{Name='FixtureWiFi'} }
function New-NetFirewallRule { $global:mutations++; throw 'Firewall mutation prohibited in fixture' }
function Set-NetFirewallProfile { $global:mutations++; throw 'Firewall mutation prohibited in fixture' }
function Remove-NetFirewallRule { $global:mutations++; throw 'Firewall mutation prohibited in fixture' }
$global:testHealthy=$false
try { & $Script -CheckOnly } catch { $firstCaught=$true }
$root=Join-Path $Sandbox 'firewall-attempts'
$first=Get-ChildItem -LiteralPath $root -Directory | Select-Object -First 1
$firstResult=Get-Content -LiteralPath (Join-Path $first.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$beforeText=Get-Content -LiteralPath (Join-Path $first.FullName 'result.json') -Raw -Encoding UTF8
$global:testHealthy=$true
& $Script -CheckOnly
$dirs=@(Get-ChildItem -LiteralPath $root -Directory)
$second=$dirs | Where-Object { $_.FullName -ne $first.FullName }
$secondResult=Get-Content -LiteralPath (Join-Path $second.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
try { & $Script -Apply -CheckOnly } catch { $mixedRejected=$true }
$report=[ordered]@{firstCaught=[bool]$firstCaught;firstStatus=$firstResult.status;secondStatus=$secondResult.status;attempts=$dirs.Count;oldResultPreserved=($beforeText -ceq (Get-Content -LiteralPath (Join-Path $first.FullName 'result.json') -Raw -Encoding UTF8));legacyPreserved=((Get-Content -LiteralPath (Join-Path $Sandbox 'firewall-step-before.json') -Raw) -eq 'previous-attempt-evidence');firstBaselineExists=(Test-Path -LiteralPath (Join-Path $first.FullName 'baseline.json'));noActiveMarker=(-not (Test-Path -LiteralPath (Join-Path $first.FullName 'active.json')));noPolicySnapshot=(-not (Test-Path -LiteralPath (Join-Path $first.FullName 'before.json')));mutations=$global:mutations;mixedRejected=[bool]$mixedRejected}
Write-Output ('FIXTURE_RESULT:'+($report | ConvertTo-Json -Compress))
''', encoding='utf-8')
            result = subprocess.run(['powershell.exe', '-NoProfile', '-File', str(runner), '-Script', str(copied), '-Sandbox', str(sandbox)], capture_output=True, text=True, encoding='utf-8', timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
            if result.returncode:
                raise AssertionError('Read-only attempt fixture failed: ' + result.stderr[:1200])
            line = next(x for x in result.stdout.splitlines() if x.startswith('FIXTURE_RESULT:'))
            cls.result = json.loads(line.split(':', 1)[1])

    def test_failed_baseline_records_details_without_firewall_mutation(self):
        self.assertTrue(self.result['firstCaught'])
        self.assertEqual(self.result['firstStatus'], 'baseline_failed')
        self.assertTrue(self.result['firstBaselineExists'])
        self.assertTrue(self.result['noActiveMarker'])
        self.assertTrue(self.result['noPolicySnapshot'])
        self.assertEqual(self.result['mutations'], 0)

    def test_retry_uses_new_directory_and_preserves_old_evidence(self):
        self.assertEqual(self.result['attempts'], 2)
        self.assertEqual(self.result['secondStatus'], 'healthy')
        self.assertTrue(self.result['oldResultPreserved'])
        self.assertTrue(self.result['legacyPreserved'])

    def test_read_only_and_apply_modes_cannot_be_mixed(self):
        self.assertTrue(self.result['mixedRejected'])

if __name__ == '__main__':
    unittest.main()
