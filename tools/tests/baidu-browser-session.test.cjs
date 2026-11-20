'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const helper = require('../baidu-browser-session.cjs');
const nonce = 'A'.repeat(43);
const ticketId = '12345678-1234-1234-1234-123456789abc';
const image = 'sha256:' + '1'.repeat(64);
const now = Date.now();
const ticket = { version: 1, ticketId, nonce, state: 'PENDING', createdAt: now, expiresAt: now + 600000 };
const backend = { id: 'a'.repeat(64), image, running: true, name: '/gying-movie-backend-1', user: '10001', project: 'gying-movie', service: 'backend' };
const options = { expectedBackendImage: image, dockerPath: 'unused', backendOrigin: 'http://127.0.0.1:8880' };
function response(data, status = 200) { return { ok: status === 200, status, text: async () => JSON.stringify({ code: 'OK', data }) }; }

test('only fresh pending tickets can launch an interactive browser', () => {
    assert.equal(helper.parseTicket(JSON.stringify(ticket), now).ticketId, ticketId);
    for (const state of ['WAITING_SCAN', 'CONNECTED', 'FAILED', 'EXPIRED']) assert.equal(helper.parseTicket(JSON.stringify({ ...ticket, state }), now), null);
    assert.equal(helper.parseTicket(JSON.stringify(ticket), now + 600000), null);
    assert.throws(() => helper.parseTicket(JSON.stringify({ ...ticket, nonce: 'short' }), now), helper.SafeFailure);
    assert.throws(() => helper.parseTicket(JSON.stringify({ ...ticket, createdAt: now + 20000, expiresAt: now + 600001 }), now), helper.SafeFailure);
    assert.throws(() => helper.parseTicket('x'.repeat(20000)), helper.SafeFailure);
});

test('exact image, owner, name and Compose project are required', () => {
    assert.equal(helper.validateBackend(backend, image), backend.id);
    assert.equal(helper.validateBackend({ ...backend, user: 'gying' }, image), backend.id);
    for (const change of [{ image: 'sha256:' + '2'.repeat(64) }, { user: '0' }, { running: false }, { name: '/other-backend' }, { project: 'movie' }, { service: 'frontend' }]) {
        assert.throws(() => helper.validateBackend({ ...backend, ...change }, image), helper.SafeFailure);
    }
});

test('a named application user still needs the expected numeric runtime UID', async () => {
    await assert.rejects(helper.pendingRequest(options, async (_, args) =>
        args[0] === 'inspect' ? JSON.stringify({ ...backend, user: 'gying' }) : '0\n'), /backend_identity_rejected/);
});

test('host configuration pins the loopback backend and the helper private profile', () => {
    const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gying-baidu-config-test-'));
    const privateRoot = path.join(root, 'private'); fs.mkdirSync(privateRoot, { mode: 0o700 });
    const file = path.join(privateRoot, 'login-config.json');
    const config = { version: 1, backendContainer: 'gying-movie-backend-1', expectedBackendImage: image,
        requestPath: '/app/data/baidu/login-request.json', backendOrigin: 'http://127.0.0.1:8880',
        dockerPath: path.join(root, 'docker.exe'), puppeteerPath: path.join(root, 'puppeteer'),
        edgePath: path.join(root, 'edge.exe'), profilePath: path.join(root, 'private', 'baidu-qr-profile') };
    try {
        fs.writeFileSync(file, JSON.stringify(config), { mode: 0o600 });
        if (process.platform === 'win32') {
            const script = "$ErrorActionPreference='Stop'; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; foreach($p in @($env:GYING_BAIDU_TEST_PRIVATE,$env:GYING_BAIDU_TEST_FILE)) { $directory=Test-Path -LiteralPath $p -PathType Container; $acl=if($directory){New-Object Security.AccessControl.DirectorySecurity}else{New-Object Security.AccessControl.FileSecurity}; $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false); foreach($owner in @($sid,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) { $inherit=if($directory){'ContainerInherit,ObjectInherit'}else{'None'}; $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner,'FullControl',$inherit,'None','Allow'))) }; Set-Acl -LiteralPath $p -AclObject $acl }";
            require('node:child_process').execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden',
                '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')],
                { windowsHide: true, stdio: 'ignore', env: { ...process.env, PSModulePath: path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'Modules'), GYING_BAIDU_TEST_PRIVATE: privateRoot, GYING_BAIDU_TEST_FILE: file } });
        }
        assert.equal(helper.configuration(file).profilePath, config.profilePath);
        for (const change of [{ backendOrigin: 'https://evil.example' }, { expectedBackendImage: 'latest' },
            { requestPath: '/run/secrets/session' }, { password: 'fixture-rejected' }, { profilePath: path.join(root, 'baidu-qr-profile') },
            { dockerPath: 'docker.exe' }]) {
            fs.writeFileSync(file, JSON.stringify({ ...config, ...change }));
            assert.throws(() => helper.configuration(file), helper.SafeFailure);
        }
        assert.throws(() => helper.configuration(root), helper.SafeFailure);
        const unsafePrivate = path.join(root, 'unsafe', 'private'); fs.mkdirSync(unsafePrivate, { recursive: true });
        const unsafeFile = path.join(unsafePrivate, 'login-config.json'); fs.writeFileSync(unsafeFile, JSON.stringify(config));
        if (process.platform !== 'win32') { fs.chmodSync(unsafeFile, 0o644); }
        assert.throws(() => helper.configuration(unsafeFile), /private_configuration_rejected/);
    } finally {
        assert.equal(path.dirname(root), path.resolve(os.tmpdir())); assert(path.basename(root).startsWith('gying-baidu-config-test-'));
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('missing request stays idle and receives no browser or import capability', async () => {
    const result = await helper.run(options, { docker: async (_, args) => {
        if (args[0] === 'inspect') return JSON.stringify(backend);
        if (args[2] === 'id') return '10001\n';
        throw Error('missing');
    }, puppeteer: { launch: () => { throw Error('must not launch'); } } });
    assert.deepEqual(result, { status: 'idle', browserOpened: false });
});

test('ticket transport uses a JSON body and rejects redirects, bad schemas and account conflict', async () => {
    let body;
    const result = await helper.post(options, 'login-state', { nonce, ticketId, state: 'WAITING_SCAN' }, async (url, request) => {
        assert.equal(url, 'http://127.0.0.1:8880/api/internal/resource-hub/baidu/login-state');
        assert.equal(request.redirect, 'error'); assert.equal(request.method, 'POST'); body = JSON.parse(request.body);
        return response({ state: 'WAITING_SCAN' });
    });
    assert.equal(result.state, 'WAITING_SCAN'); assert.equal(body.nonce, nonce);
    await assert.rejects(helper.post(options, 'other', {}, async () => response({ state: 'CONNECTED' })), helper.SafeFailure);
    await assert.rejects(helper.post(options, 'session', {}, async () => response({}, 409)), /account_or_request_conflict/);
    await assert.rejects(helper.post(options, 'session', {}, async () => response({ state: 'OTHER' })), /backend_response_rejected/);
});

test('QR helper blocks all drive writes and credentials to foreign endpoints', () => {
    const req = (url, method) => ({ url: () => url, method: () => method, postData: () => '' });
    assert.equal(helper.blockedRequest(req('https://pan.baidu.com/api/list', 'GET')), false);
    for (const url of ['https://pan.baidu.com/share/transfer', 'https://pan.baidu.com/share/pset', 'https://pan.baidu.com/api/filemanager', 'https://evil.example/v2/api/login']) {
        assert.equal(helper.blockedRequest(req(url, 'POST')), true);
    }
    assert.equal(helper.blockedRequest(req('https://passport.baidu.com/v2/api/qrcode', 'POST')), false);
    assert.equal(helper.trustedPage('https://pan.baidu.com/disk/main'), true);
    for (const url of ['http://pan.baidu.com/disk/main', 'https://pan.baidu.com.evil.example/disk/main', 'https://user@pan.baidu.com/', 'https://pan.baidu.com:444/']) assert.equal(helper.trustedPage(url), false);
});

test('official passport endpoints cannot accept password or account-name submissions', () => {
    for (const body of ['password=fixture', 'passwd=fixture', 'username=fixture', 'userName=fixture',
        '%70assword=fixture', '{"password":"fixture"}', '{"username":"fixture"}']) {
        assert.equal(helper.blockedRequest({ url: () => 'https://passport.baidu.com/v3/login/main/login',
            method: () => 'POST', postData: () => body }), true);
    }
    assert.equal(helper.blockedRequest({ url: () => 'https://passport.baidu.com/v2/api/qrcode',
        method: () => 'PUT', postData: () => '' }), true);
});

test('only bounded live Baidu-domain session cookies leave the browser', () => {
    const session = { name: 'BDUSS', value: 'fixture-browser-session', domain: '.baidu.com', path: '/', expires: -1, secure: true };
    const cookies = helper.browserCookies([session, { ...session, name: 'OTHER', domain: '.evil.example' }]);
    assert.equal(cookies.length, 1); assert.equal(cookies[0].name, 'BDUSS');
    assert.equal(helper.browserCookies([{ ...session, name: 'BDUSS_BFESS' }])[0].name, 'BDUSS_BFESS');
    assert.equal(helper.browserCookies([session, { ...session, name: 'OTHER', domain: 'wappass.baidu.com' }]).length, 1);
    assert.throws(() => helper.browserCookies([{ ...session, expires: now / 1000 - 100 }]), /browser_session_rejected/);
    assert.throws(() => helper.browserCookies([{ ...session, value: 'invalid\r\nvalue' }]), /browser_session_rejected/);
    assert.throws(() => helper.browserCookies(new Array(501).fill(session)), /browser_session_rejected/);
});

test('requested QR login imports only after browser validation and closes the dedicated browser', async () => {
    const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gying-baidu-qr-test-'));
    const profile = path.join(root, 'baidu-qr-profile'); fs.mkdirSync(profile);
    let closed = false; let launched = false; const requests = [];
    const page = { setRequestInterception: async () => {}, on: () => {}, goto: async url => assert.equal(url, 'https://pan.baidu.com/disk/main#/index'),
        frames: () => [], isClosed: () => false };
    const browser = { pages: async () => [page], cookies: async () => [{ name: 'BDUSS', value: 'fixture-browser-session', domain: '.baidu.com', path: '/', expires: -1, secure: true }],
        close: async () => { closed = true; } };
    try {
        const result = await helper.run({ ...options, profilePath: profile, edgePath: 'unused' }, {
            docker: async (_, args) => args[0] === 'inspect' ? JSON.stringify(backend) : args[2] === 'id' ? '10001\n' : JSON.stringify(ticket),
            puppeteer: { launch: async launch => { assert.equal(launch.headless, false); assert.equal(launch.dumpio, false); assert.equal(launch.userDataDir, profile); launched = true; return browser; } },
            loggedIn: async () => true,
            fetch: async (url, request) => { const payload = JSON.parse(request.body); requests.push({ url, payload });
                return response({ state: url.endsWith('/session') ? 'CONNECTED' : 'WAITING_SCAN', configured: url.endsWith('/session') }); },
        });
        assert.deepEqual(result, { status: 'connected', browserOpened: true, accountValidated: true });
        assert.equal(launched, true); assert.equal(closed, true); assert.equal(requests.length, 2);
        assert.equal(requests[0].payload.state, 'WAITING_SCAN'); assert.equal(requests[1].payload.cookies[0].name, 'BDUSS');
        assert.equal(JSON.stringify(result).includes('fixture-browser-session'), false); assert.equal(JSON.stringify(result).includes(nonce), false);
    } finally {
        assert.equal(path.dirname(root), path.resolve(os.tmpdir())); assert(path.basename(root).startsWith('gying-baidu-qr-test-'));
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('application replacement during a scan blocks credential import and closes the browser', async () => {
    const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gying-baidu-qr-test-')); const profile = path.join(root, 'baidu-qr-profile'); fs.mkdirSync(profile);
    let inspected = 0; let imported = false; let closed = false;
    const page = { setRequestInterception: async () => {}, on: () => {}, goto: async () => {}, frames: () => [], isClosed: () => false };
    try {
        await assert.rejects(helper.run({ ...options, profilePath: profile, edgePath: 'unused' }, {
            docker: async (_, args) => args[0] === 'inspect' ? JSON.stringify({ ...backend, id: ++inspected === 1 ? backend.id : 'b'.repeat(64) }) : args[2] === 'id' ? '10001\n' : JSON.stringify(ticket),
            puppeteer: { launch: async () => ({ pages: async () => [page], cookies: async () => [{ name: 'BDUSS', value: 'fixture-browser-session', domain: '.baidu.com', path: '/', expires: -1, secure: true }], close: async () => { closed = true; } }) },
            loggedIn: async () => true,
            fetch: async (url, request) => { if (url.endsWith('/session')) imported = true; return response({ state: JSON.parse(request.body).state || 'CONNECTED' }); },
        }), /backend_identity_changed/);
        assert.equal(imported, false); assert.equal(closed, true);
    } finally {
        assert.equal(path.dirname(root), path.resolve(os.tmpdir())); assert(path.basename(root).startsWith('gying-baidu-qr-test-'));
        fs.rmSync(root, { recursive: true, force: true });
    }
});


test('browser proxy is optional and limited to the explicit loopback port', () => {
    assert.equal(helper.browserArguments({}).some(arg => arg.startsWith('--proxy-server=')), false);
    assert(helper.browserArguments({ proxyServer: 'http://127.0.0.1:7897' }).includes('--proxy-server=http://127.0.0.1:7897'));
    for (const proxyServer of [null, '', 'http://evil.example:7897', 'http://localhost:7897', 'https://127.0.0.1:7897',
        'http://user@127.0.0.1:7897', 'http://127.0.0.1:0', 'http://127.0.0.1:65536', 'http://127.0.0.1:7897/path',
        'http://127.0.0.1:7897 --unsafe', 'http://127.0.0.1:07897']) {
        assert.throws(() => helper.browserArguments({ proxyServer }), /proxy_rejected/);
    }
});


test('Windows limited wrapper validates existing private directories without mutating ACLs', { skip: process.platform !== 'win32' }, () => {
    const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gying-baidu-acl-test-'));
    const script = `
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$ast=[Management.Automation.Language.Parser]::ParseFile($env:GYING_BAIDU_RUNNER,[ref]$null,[ref]$null)
foreach($name in @('Assert-BaiduPlainPath','New-BaiduPrivateDirectory')) {
 $definition=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$false)
 if(-not $definition){throw 'missing_function'}
 . ([ScriptBlock]::Create($definition.Extent.Text))
}
$private=Join-Path $env:GYING_BAIDU_TEST_ROOT 'private'
New-BaiduPrivateDirectory $private
$before=(Get-Acl -LiteralPath $private).Sddl
function Set-Acl { throw 'must_not_mutate_existing_acl' }
New-BaiduPrivateDirectory $private
New-BaiduPrivateDirectory $private
if((Get-Acl -LiteralPath $private).Sddl -cne $before){throw 'acl_changed'}
$file=Join-Path $private 'not-directory'; [IO.File]::WriteAllText($file,'fixture')
$rejected=$false;try{New-BaiduPrivateDirectory $file}catch{$rejected=$_.Exception.Message -eq 'private_path_rejected'}
if(-not $rejected){throw 'file_not_rejected'}
Remove-Item Function:Set-Acl
$unsafe=Join-Path $env:GYING_BAIDU_TEST_ROOT 'unsafe'; [void][IO.Directory]::CreateDirectory($unsafe)
$acl=New-Object Security.AccessControl.DirectorySecurity
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false)
foreach($identity in @($sid,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) { $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))) }
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier('S-1-1-0')),'Read','ContainerInherit,ObjectInherit','None','Allow')))
Set-Acl -LiteralPath $unsafe -AclObject $acl
function Set-Acl { throw 'must_not_repair_unsafe_acl' }
$rejected=$false;try{New-BaiduPrivateDirectory $unsafe}catch{$rejected=$_.Exception.Message -eq 'private_acl_rejected'}
if(-not $rejected){throw 'public_acl_not_rejected'}
Write-Output 'existing-idempotent-file-and-public-acl-rejected'
`;
    try {
        const output = require('node:child_process').execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden',
            '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { windowsHide: true, timeout: 20000,
            env: { ...process.env, PSModulePath: path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'Modules'),
                GYING_BAIDU_RUNNER: path.resolve(__dirname, '../run-baidu-browser-login.ps1'), GYING_BAIDU_TEST_ROOT: root }, encoding: 'utf8' });
        assert.equal(output.trim(), 'existing-idempotent-file-and-public-acl-rejected');
    } finally {
        assert.equal(path.dirname(root), path.resolve(os.tmpdir())); assert(path.basename(root).startsWith('gying-baidu-acl-test-'));
        fs.rmSync(root, { recursive: true, force: true });
    }
});
