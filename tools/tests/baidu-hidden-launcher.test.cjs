'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');

const windows = process.platform === 'win32';
const root = windows ? fs.mkdtempSync(path.join(process.env.GYING_BAIDU_LAUNCHER_TEST_ROOT || require('node:os').tmpdir(), 'gying-baidu-hidden-')) : '';
const launcher = path.join(root, 'baidu-login-hidden.exe');
const shell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
const shellArgs = ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass'];
const childEnvironment = { ...process.env, PSModulePath: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'Modules') };
if (windows) {
    const build = spawnSync(shell, [...shellArgs, '-File', path.resolve(__dirname, '../install-baidu-login-launcher.ps1'), '-HelperRoot', root],
        { windowsHide: true, encoding: 'utf8', env: childEnvironment, timeout: 30000 });
    assert.equal(build.status, 0, 'isolated hidden launcher build must succeed');
}

test('scheduled-task launcher is a GUI executable and rejects empty invocation', { skip: !windows }, () => {
    const image = fs.readFileSync(launcher);
    const pe = image.readUInt32LE(0x3c);
    assert.equal(image.toString('ascii', pe, pe + 4), 'PE\u0000\u0000');
    assert.equal(image.readUInt16LE(pe + 24 + 68), 2, 'IMAGE_SUBSYSTEM_WINDOWS_GUI');
    assert.equal(spawnSync(launcher, [], { timeout: 10000 }).status, 1);
});

test('Unicode paths and literal arguments reach a child with no console; launcher waits and returns failure', { skip: !windows }, () => {
    const directory = path.join(root, '中文 测试'); fs.mkdirSync(directory);
    const script = path.join(directory, '记录参数.ps1');
    const marker = path.join(directory, 'result.json');
    const source = `param([string]$Marker,[string]$Value,[int]$ExitCode)
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class ConsoleProbe { [DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow(); }'
$result=@{value=$Value;consoleWindow=[ConsoleProbe]::GetConsoleWindow().ToInt64();pid=$PID}
[IO.File]::WriteAllText($Marker,($result|ConvertTo-Json -Compress),(New-Object Text.UTF8Encoding($false)))
Start-Sleep -Milliseconds 500
exit $ExitCode
`;
    fs.writeFileSync(script, '\ufeff' + source);
    const value = '中文 & literal $() `tick` "quote" end\\';
    const started = Date.now();
    const run = spawnSync(launcher, [...shellArgs, '-File', script, '-Marker', marker, '-Value', value, '-ExitCode', '17'],
        { timeout: 20000, env: childEnvironment });
    assert.equal(run.status, 17, 'original nonzero PowerShell exit must reach Task Scheduler');
    assert(Date.now() - started >= 500, 'host must wait for its child');
    const result = JSON.parse(fs.readFileSync(marker, 'utf8'));
    assert.equal(result.value, value);
    assert.equal(result.consoleWindow, 0, 'child must have no console HWND');
});

test('empty and trailing-backslash arguments are preserved', { skip: !windows }, () => {
    const script = path.join(root, 'argument-roundtrip.ps1');
    const marker = path.join(root, 'roundtrip.json');
    fs.writeFileSync(script, '\ufeff' + `param([string]$Marker,[string]$First,[string]$Second)
[IO.File]::WriteAllText($Marker,(@($First,$Second)|ConvertTo-Json -Compress),(New-Object Text.UTF8Encoding($false)))
exit 0
`);
    const run = spawnSync(launcher, [...shellArgs, '-File', script, '-Marker', marker, '-First', '', '-Second', 'folder name\\'],
        { timeout: 10000, env: childEnvironment });
    assert.equal(run.status, 0);
    assert.deepEqual(JSON.parse(fs.readFileSync(marker, 'utf8')), ['', 'folder name\\']);
});

test('overlapping hidden invocations retain the runner mutex behavior', { skip: !windows }, async () => {
    const script = path.join(root, 'mutex-runner.ps1');
    const first = path.join(root, 'first.json'); const second = path.join(root, 'second.json');
    const name = 'Local\\GYing.Baidu.HiddenFixture.' + crypto.randomUUID();
    fs.writeFileSync(script, '\ufeff' + `param([string]$Marker,[string]$MutexName)
$mutex=New-Object Threading.Mutex($false,$MutexName)
$owned=$mutex.WaitOne(0)
try {
 [IO.File]::WriteAllText($Marker,(@{owned=$owned}|ConvertTo-Json -Compress),(New-Object Text.UTF8Encoding($false)))
 if($owned){Start-Sleep -Seconds 2}
} finally {if($owned){$mutex.ReleaseMutex()};$mutex.Dispose()}
exit 0
`);
    const start = marker => spawn(launcher, [...shellArgs, '-File', script, '-Marker', marker, '-MutexName', name],
        { env: childEnvironment, stdio: 'ignore' });
    const completion = child => new Promise((resolve, reject) => {
        child.once('error', reject); child.once('exit', code => resolve(code));
    });
    const firstChild = start(first); const firstDone = completion(firstChild);
    const deadline = Date.now() + 10000;
    while (!fs.existsSync(first) && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
    assert(fs.existsSync(first), 'first child must hold fixture mutex before overlapping invocation');
    const secondChild = start(second); const secondDone = completion(secondChild);
    assert.deepEqual(await Promise.all([firstDone, secondDone]), [0, 0]);
    assert.equal(JSON.parse(fs.readFileSync(first, 'utf8')).owned, true);
    assert.equal(JSON.parse(fs.readFileSync(second, 'utf8')).owned, false);
});
