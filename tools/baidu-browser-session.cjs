'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFile, execFileSync } = require('node:child_process');

const REQUEST_PATH = '/app/data/baidu/login-request.json';
const CONTAINER_NAME = 'gying-movie-backend-1';
const STATUS_STATES = new Set(['PENDING', 'WAITING_SCAN', 'CONNECTED', 'FAILED', 'EXPIRED']);
const INSPECT_FORMAT = '{"id":{{json .Id}},"image":{{json .Image}},"running":{{json .State.Running}},"name":{{json .Name}},"user":{{json .Config.User}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}}}';
class SafeFailure extends Error { constructor(code) { super(code); this.code = code; } }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function safePath(file) {
    if (typeof file !== 'string' || !path.isAbsolute(file)) throw new SafeFailure('configuration_rejected');
    let current = path.resolve(file);
    while (true) {
        if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) throw new SafeFailure('private_path_rejected');
        const parent = path.dirname(current); if (parent === current) break; current = parent;
    }
    return path.resolve(file);
}
function configuration(file) {
    const source = safePath(file);
    if (path.basename(source) !== 'login-config.json' || path.basename(path.dirname(source)) !== 'private') {
        throw new SafeFailure('private_configuration_rejected');
    }
    const stat = fs.statSync(source);
    if (!stat.isFile() || stat.size > 16384) throw new SafeFailure('configuration_rejected');
    if (process.platform === 'win32') {
        const script = "$ErrorActionPreference='Stop'; $p=$env:GYING_BAIDU_ACL_CONFIG; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; foreach($item in @($p,(Split-Path -Parent $p))) { $acl=Get-Acl -LiteralPath $item; if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){exit 1}; foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) { if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin @($sid.Value,'S-1-5-18')) {exit 1} } }; if(-not (Get-Acl -LiteralPath (Split-Path -Parent $p)).AreAccessRulesProtected){exit 1}";
        try {
            execFileSync(path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
                ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')],
                { windowsHide: true, timeout: 15000, maxBuffer: 16384, stdio: 'ignore', env: { ...process.env, PSModulePath: path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'Modules'), GYING_BAIDU_ACL_CONFIG: source } });
        } catch { throw new SafeFailure('private_configuration_rejected'); }
    } else if ((stat.mode & 0o077) || (fs.statSync(path.dirname(source)).mode & 0o077)) {
        throw new SafeFailure('private_configuration_rejected');
    }
    let value;
    try { value = JSON.parse(fs.readFileSync(source, 'utf8').replace(/^\uFEFF/, '')); }
    catch { throw new SafeFailure('configuration_rejected'); }
    const allowed = new Set(['version', 'backendContainer', 'expectedBackendImage', 'requestPath', 'backendOrigin',
        'dockerPath', 'puppeteerPath', 'edgePath', 'profilePath']);
    if (!value || Array.isArray(value) || Object.keys(value).some(key => !allowed.has(key))
            || value.version !== 1 || value.backendContainer !== CONTAINER_NAME
            || !/^sha256:[a-f0-9]{64}$/.test(value.expectedBackendImage || '')
            || value.requestPath !== REQUEST_PATH || value.backendOrigin !== 'http://127.0.0.1:8880') {
        throw new SafeFailure('configuration_rejected');
    }
    for (const key of ['dockerPath', 'puppeteerPath', 'edgePath', 'profilePath']) value[key] = safePath(value[key]);
    // The profile is dedicated to this helper; never copy or connect to a user's normal Edge profile.
    if (value.profilePath !== path.resolve(path.dirname(source), 'baidu-qr-profile')) {
        throw new SafeFailure('private_profile_rejected');
    }
    return value;
}
function docker(options, args) {
    return new Promise((resolve, reject) => execFile(options.dockerPath, args,
        { windowsHide: true, timeout: 15000, maxBuffer: 256 * 1024 },
        (error, stdout) => error ? reject(new SafeFailure('backend_unavailable')) : resolve(stdout)));
}
function validateBackend(meta, expectedImage) {
    if (!meta || !/^[a-f0-9]{64}$/.test(meta.id || '') || meta.name !== '/' + CONTAINER_NAME
            || meta.image !== expectedImage || meta.running !== true || meta.project !== 'gying-movie'
            || meta.service !== 'backend' || !['gying', '10001', '10001:10001'].includes(meta.user)) {
        throw new SafeFailure('backend_identity_rejected');
    }
    return meta.id;
}
function parseTicket(raw, now = Date.now()) {
    if (typeof raw !== 'string' || Buffer.byteLength(raw) > 16384) throw new SafeFailure('ticket_rejected');
    let ticket; try { ticket = JSON.parse(raw); } catch { throw new SafeFailure('ticket_rejected'); }
    if (!ticket || ticket.version !== 1 || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(ticket.ticketId || '')
            || !STATUS_STATES.has(ticket.state) || !Number.isSafeInteger(ticket.createdAt) || !Number.isSafeInteger(ticket.expiresAt)
            || ticket.expiresAt <= ticket.createdAt || ticket.expiresAt - ticket.createdAt > 600000
            || ticket.createdAt > now + 10000) throw new SafeFailure('ticket_rejected');
    if (ticket.state !== 'PENDING' || ticket.expiresAt <= now) return null;
    if (!/^[A-Za-z0-9_-]{43}$/.test(ticket.nonce || '')) throw new SafeFailure('ticket_rejected');
    return ticket;
}
async function pendingRequest(options, runDocker = docker) {
    let meta;
    try { meta = JSON.parse(await runDocker(options, ['inspect', '--format', INSPECT_FORMAT, CONTAINER_NAME])); }
    catch { throw new SafeFailure('backend_unavailable'); }
    const id = validateBackend(meta, options.expectedBackendImage);
    const uid = await runDocker(options, ['exec', id, 'id', '-u']);
    if (typeof uid !== 'string' || uid.trim() !== '10001') throw new SafeFailure('backend_identity_rejected');
    // The command is fixed, checks the file and bounds it; no secret or user text enters argv.
    const command = 'test ! -L /app/data/baidu/login-request.json && test -f /app/data/baidu/login-request.json && test "$(wc -c < /app/data/baidu/login-request.json)" -le 16384 && cat /app/data/baidu/login-request.json';
    let raw;
    try { raw = await runDocker(options, ['exec', id, 'sh', '-c', command]); }
    catch { return null; }
    const ticket = parseTicket(raw); raw = null;
    return ticket ? { ticket, containerId: id } : null;
}
async function post(options, route, body, http = fetch) {
    if (!['login-state', 'session'].includes(route)) throw new SafeFailure('route_rejected');
    const response = await http(options.backendOrigin + '/api/internal/resource-hub/baidu/' + route, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
        redirect: 'error', signal: AbortSignal.timeout(45000),
    });
    if (!response.ok) throw new SafeFailure(response.status === 409 ? 'account_or_request_conflict' : 'session_import_rejected');
    const text = await response.text();
    if (text.length > 16384) throw new SafeFailure('backend_response_rejected');
    let value; try { value = JSON.parse(text); } catch { throw new SafeFailure('backend_response_rejected'); }
    if (!value || value.code !== 'OK' || !value.data || !STATUS_STATES.has(value.data.state)) throw new SafeFailure('backend_response_rejected');
    return value.data;
}
function trustedPage(url) {
    try { const u = new URL(url); return u.protocol === 'https:' && !u.username && !u.password
        && !u.port && ['pan.baidu.com', 'passport.baidu.com'].includes(u.hostname); } catch { return false; }
}
function blockedRequest(request) {
    let url; try { url = new URL(request.url()); } catch { return true; }
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method())) return false;
    const body = typeof request.postData === 'function' ? request.postData() || '' : '';
    let decoded = body;
    try { decoded = decodeURIComponent(body.replace(/\+/g, ' ')); } catch { /* Keep malformed data fail closed below. */ }
    if (/(?:^|[&{\s"])(?:password|passwd|userName|username)(?:"?\s*[:=])/i.test(decoded)) return true;
    // Only official passport login can write. Drive/transfer/share/delete requests are blocked even if clicked.
    return request.method() !== 'POST' || url.protocol !== 'https:' || url.hostname !== 'passport.baidu.com' || Boolean(url.port)
        || !/^\/v[23]\/(?:api\/|login(?:\/|$))/.test(url.pathname);
}
function browserCookies(values) {
    if (!Array.isArray(values) || values.length > 500) throw new SafeFailure('browser_session_rejected');
    const domains = new Set(['.baidu.com', 'baidu.com', 'pan.baidu.com', '.pan.baidu.com', 'passport.baidu.com', '.passport.baidu.com']);
    const cookies = values.filter(c => c && typeof c.domain === 'string'
        && domains.has(c.domain.toLowerCase())
        && typeof c.name === 'string' && typeof c.value === 'string' && c.value
        && typeof c.path === 'string' && c.path.startsWith('/')
        && !/[\r\n]/.test(c.value) && c.name.length <= 100 && c.value.length <= 8192
        && (c.expires === -1 || Number.isFinite(c.expires) && c.expires * 1000 > Date.now()));
    if (!cookies.some(c => ['BDUSS', 'BDUSS_BFESS'].includes(c.name)
            && ['.baidu.com', 'pan.baidu.com', '.pan.baidu.com'].includes(c.domain.toLowerCase()))
            || cookies.length > 80) throw new SafeFailure('browser_session_rejected');
    return cookies.map(c => ({ name: c.name, value: c.value, domain: c.domain, path: c.path,
        expires: c.expires, secure: Boolean(c.secure) }));
}
async function loggedIn(page) {
    if (!trustedPage(page.url()) || new URL(page.url()).hostname !== 'pan.baidu.com') return false;
    try {
        return await page.evaluate(async () => {
            if (location.protocol !== 'https:' || location.hostname !== 'pan.baidu.com') return false;
            try {
                const response = await fetch('/api/user/getinfo?need_selfinfo=1', { credentials: 'include', redirect: 'error' });
                if (!response.ok) return false;
                const value = await response.json();
                return value.errno === 0 && Boolean(value.records && value.records[0] && value.records[0].uk
                    || value.user_info && (value.user_info.uk || value.user_info.userid));
            } catch { return false; }
        });
    } catch { return false; }
}
async function run(options, dependencies = {}) {
    const request = await pendingRequest(options, dependencies.docker || docker);
    if (!request) return { status: 'idle', browserOpened: false };
    const { ticket, containerId } = request;
    const rpc = (route, body) => post(options, route, body, dependencies.fetch || fetch);
    await rpc('login-state', { ticketId: ticket.ticketId, nonce: ticket.nonce, state: 'WAITING_SCAN' });
    let browser;
    try {
        const profile = safePath(options.profilePath);
        if (!fs.existsSync(profile)) throw new SafeFailure('private_profile_missing');
        const puppeteer = dependencies.puppeteer || require(options.puppeteerPath);
        browser = await puppeteer.launch({ executablePath: options.edgePath, userDataDir: profile, headless: false,
            args: ['--no-first-run', '--no-default-browser-check', '--disable-save-password-bubble',
                '--disable-features=PasswordManagerOnboarding,PasswordLeakDetection,AutofillServerCommunication'],
            dumpio: false });
        const pages = await browser.pages(); const page = pages[0] || await browser.newPage();
        await page.setRequestInterception(true);
        page.on('request', request => { if (blockedRequest(request)) request.abort().catch(() => {}); else request.continue().catch(() => {}); });
        await page.goto('https://pan.baidu.com/disk/main#/index', { waitUntil: 'domcontentloaded', timeout: 45000 });
        // Prefer the site's QR tab without handling any password or submitting credentials.
        for (const frame of page.frames ? page.frames() : [page]) {
            if (!trustedPage(frame.url())) continue;
            await frame.evaluate(() => {
                if (!['pan.baidu.com', 'passport.baidu.com'].includes(location.hostname)) return;
                const labels = ['扫码登录', '二维码登录'];
                const nodes = [...document.querySelectorAll('a,button,span')].filter(node => labels.includes(node.textContent.trim())
                    && node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0);
                if (nodes.length === 1) nodes[0].click();
            }).catch(() => {});
        }
        while (Date.now() < ticket.expiresAt) {
            if (await (dependencies.loggedIn || loggedIn)(page)) {
                const cookies = browserCookies(await (browser.cookies ? browser.cookies() : page.cookies()));
                // Recheck exact production application before importing. A deployment invalidates this handoff.
                const fresh = JSON.parse(await (dependencies.docker || docker)(options, ['inspect', '--format', INSPECT_FORMAT, CONTAINER_NAME]));
                if (validateBackend(fresh, options.expectedBackendImage) !== containerId) throw new SafeFailure('backend_identity_changed');
                const result = await rpc('session', { ticketId: ticket.ticketId, nonce: ticket.nonce, cookies });
                if (result.state !== 'CONNECTED' || result.configured !== true) throw new SafeFailure('session_import_rejected');
                cookies.length = 0;
                return { status: 'connected', browserOpened: true, accountValidated: true };
            }
            if (page.isClosed()) throw new SafeFailure('browser_closed');
            await (dependencies.delay || delay)(3000);
        }
        await rpc('login-state', { ticketId: ticket.ticketId, nonce: ticket.nonce, state: 'FAILED', failureCode: 'SCAN_TIMEOUT' }).catch(() => {});
        return { status: 'expired', browserOpened: true };
    } catch (error) {
        const reason = error instanceof SafeFailure ? error.code : 'helper_failed';
        await rpc('login-state', { ticketId: ticket.ticketId, nonce: ticket.nonce, state: 'FAILED', failureCode: 'HELPER_FAILED' }).catch(() => {});
        throw new SafeFailure(reason);
    } finally { ticket.nonce = null; if (browser) await browser.close().catch(() => {}); }
}
module.exports = { SafeFailure, configuration, validateBackend, parseTicket, pendingRequest, post, trustedPage,
    blockedRequest, browserCookies, run };
if (require.main === module) {
    (async () => {
        try {
            const options = configuration(process.env.BAIDU_LOGIN_CONFIG);
            const result = await run(options);
            process.stdout.write(JSON.stringify(result) + '\n');
        } catch (error) {
            process.stdout.write(JSON.stringify({ status: 'failed', reason: error instanceof SafeFailure ? error.code : 'helper_failed' }) + '\n');
            process.exitCode = 1;
        }
    })();
}
