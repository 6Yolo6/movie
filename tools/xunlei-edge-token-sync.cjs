'use strict';

const fs = require('node:fs');
const path = require('node:path');
const MIN_TTL_MS = 5 * 60 * 1000;
const DRIVE_CHECK_URL = 'https://api-pan.xunlei.com/drive/v1/files?parent_id=&usage=DISPLAY&limit=1';

function jwtPayload(token) {
    try {
        const value = JSON.parse(Buffer.from(String(token).split('.')[1], 'base64url').toString('utf8'));
        return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    }
    catch { return {}; }
}
function normalizeExpiry(value) {
    const n = Number(value || 0);
    return Number.isFinite(n) && n > 0 ? (n < 100000000000 ? n * 1000 : n) : 0;
}
function trustedUrl(value, host, prefix) {
    try {
        const u = new URL(value);
        return u.protocol === 'https:' && u.hostname === host && !u.username && !u.password &&
            (!u.port || u.port === '443') && u.pathname.startsWith(prefix);
    } catch { return false; }
}
function driveUrl(value) { return trustedUrl(value, 'api-pan.xunlei.com', '/drive/v1/'); }
function authUrl(value) {
    if (!trustedUrl(value, 'xluser-ssl.xunlei.com', '/v1/auth/')) return false;
    return ['/v1/auth/token', '/v1/auth/signin', '/v1/auth/signin/token'].includes(new URL(value).pathname);
}
function readJson(file) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); }
    catch { return {}; }
}
function writeJson(file, value) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(value), { encoding: 'utf8', mode: 0o600 });
}
function nonempty(value) { return typeof value === 'string' && value.trim() ? value.trim() : ''; }
function scalarAudience(value) { return Array.isArray(value) ? (value.length === 1 ? nonempty(value[0]) : '') : nonempty(value); }

function candidateFrom(credentials, headers = {}, source = 'storage') {
    const token = nonempty(credentials.access_token);
    if (!token) return null;
    const claims = jwtPayload(token);
    return {
        token, claims, source,
        expiresAt: normalizeExpiry(claims.exp || credentials.expires_at || credentials.expire_time),
        refreshToken: nonempty(credentials.refresh_token),
        userId: nonempty(String(claims.sub || credentials.user_id || '')),
        clientId: nonempty(headers['x-client-id']) || nonempty(credentials.client_id) || scalarAudience(claims.aud),
        deviceId: nonempty(headers['x-device-id']) || nonempty(credentials.device_id),
        captchaToken: nonempty(headers['x-captcha-token']) || nonempty(credentials.captcha_token),
        clientVersion: nonempty(headers['x-client-version']) || nonempty(credentials.client_version),
    };
}
function rememberCandidate(candidates, candidate) {
    if (!candidate) return;
    const previous = candidates.get(candidate.token);
    if (!previous) { candidates.set(candidate.token, candidate); return; }
    // Responses/storage supply rotating refresh tokens; authenticated requests supply matching headers.
    const merged = { ...previous, ...candidate, expiresAt: Math.max(previous.expiresAt, candidate.expiresAt) };
    for (const key of ['refreshToken','userId','clientId','deviceId','captchaToken','clientVersion']) {
        merged[key] = candidate[key] || previous[key] || '';
    }
    candidates.set(candidate.token, merged);
}
function expectedUser(current) { return nonempty(String(current.user_id || jwtPayload(current.access_token || '').sub || '')); }
function eligibleCandidates(candidates, current, now = Date.now()) {
    const user = expectedUser(current);
    return [...candidates.values()].filter(c => c.expiresAt > now + MIN_TTL_MS && c.userId &&
        (!user || user === c.userId)).sort((a, b) => b.expiresAt - a.expiresAt).slice(0, 3);
}
function buildState(candidate, current) {
    if (expectedUser(current) && candidate.userId !== expectedUser(current)) throw new Error('account_mismatch');
    const sameToken = candidate.token === current.access_token &&
        (!candidate.clientId || candidate.clientId === current.client_id) &&
        (!candidate.deviceId || candidate.deviceId === current.device_id);
    const state = {
        token_type: 'Bearer', access_token: candidate.token, expires_at: candidate.expiresAt,
        user_id: candidate.userId,
        // Never combine a newly rotated access token with an old refresh token or another client/device.
        refresh_token: candidate.refreshToken || (sameToken ? nonempty(current.refresh_token) : ''),
        client_id: candidate.clientId || (sameToken ? nonempty(current.client_id) : ''),
        device_id: candidate.deviceId || (sameToken ? nonempty(current.device_id) : ''),
        captcha_token: candidate.captchaToken || (sameToken ? nonempty(current.captcha_token) : ''),
        client_version: candidate.clientVersion || (sameToken ? nonempty(current.client_version) : '') || '1.82.0',
        package_name: 'pan.xunlei.com',
    };
    if (!state.client_id || !state.device_id) throw new Error('missing_client_identity');
    return state;
}
function changedState(state, current) { return Object.keys(state).some(k => state[k] !== current[k]); }
async function selectValidated(candidates, current, validate, now = Date.now()) {
    let lastStatus = null;
    for (const candidate of eligibleCandidates(candidates, current, now)) {
        let state;
        try { state = buildState(candidate, current); } catch { continue; }
        let result;
        try { result = await validate(state); } catch { result = { ok: false, status: null }; }
        lastStatus = result.status || null;
        if (result.ok === true) return { state, source: candidate.source, validationHttpStatus: lastStatus };
    }
    return { state: null, validationHttpStatus: lastStatus };
}

async function storageCredentials(page) {
    return page.evaluate(() => {
        const values = [];
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (!key || !key.startsWith('credentials_')) continue;
            try {
                const value = JSON.parse(localStorage.getItem(key) || '{}');
                if (typeof value.access_token !== 'string') continue;
                const safe = {};
                for (const field of ['access_token','refresh_token','expires_at','expire_time','user_id','client_id','device_id','captcha_token','client_version']) {
                    if (['string','number'].includes(typeof value[field])) safe[field] = value[field];
                }
                values.push(safe);
            } catch {}
        }
        return values;
    });
}
async function validateState(state, fetchImpl = globalThis.fetch) {
    // Validate outside the renderer: the site's CORS policy may reject custom headers
    // even when the same credential is accepted by the backend's Drive request.
    const headers = { Authorization: 'Bearer ' + state.access_token, 'X-Client-Id': state.client_id, 'X-Device-Id': state.device_id };
    if (state.captcha_token) headers['X-Captcha-Token'] = state.captcha_token;
    const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 15000);
    try {
        const response = await fetchImpl(DRIVE_CHECK_URL, { method: 'GET', headers, credentials: 'omit', redirect: 'error', signal: controller.signal });
        const body = await response.json().catch(() => null);
        const error = body && [body.error, body.error_code, body.errorCode].some(
            value => value !== undefined && value !== null && value !== '' && value !== 0 && value !== '0' && value !== false);
        const files = body && (body.files || (body.data && body.data.files));
        return { ok: response.status === 200 && !error && Array.isArray(files), status: response.status };
    } catch { return { ok: false, status: null }; }
    finally { clearTimeout(timeout); }
}

async function capture(options) {
    const { puppeteer, edgePath, profilePath, profileName = 'Default', current = {} } = options;
    const delay = options.delay || (ms => new Promise(resolve => setTimeout(resolve, ms)));
    const candidates = new Map(); const pending = new Set(); const requestHeaders = new Map();
    let refreshHttpStatus = null; let storageCredentialCount = 0; let browser;
    try {
        browser = await puppeteer.launch({ headless: true, executablePath: edgePath, userDataDir: profilePath,
            args: ['--no-first-run', `--profile-directory=${profileName}`, '--disable-background-networking'] });
        const page = (await browser.pages())[0] || await browser.newPage();
        await page.setBypassServiceWorker(true);
        await page.setRequestInterception(true);
        // The disposable reader must not rotate the live browser's refresh token, log in,
        // or perform cloud writes. Only the real, user-owned window may refresh login.
        page.on('request', request => {
            const safeMethod = ['GET', 'HEAD', 'OPTIONS'].includes(request.method());
            const block = (!safeMethod && driveUrl(request.url())) || authUrl(request.url());
            void (block ? request.abort() : request.continue()).catch(() => {});
        });
        page.on('request', request => {
            if (!driveUrl(request.url())) return;
            const h = request.headers(); const authorization = h.authorization || h.Authorization || '';
            if (!/^Bearer\s+\S+$/i.test(authorization)) return;
            const token = authorization.replace(/^Bearer\s+/i, '').trim();
            const safe = {};
            for (const key of ['x-client-id','x-device-id','x-captcha-token','x-client-version']) if (h[key]) safe[key] = h[key];
            requestHeaders.set(token, safe);
            rememberCandidate(candidates, candidateFrom({ access_token: token }, safe, 'request'));
        });
        page.on('response', response => {
            if (!authUrl(response.url())) return;
            refreshHttpStatus = response.status();
            if (response.status() !== 200) return;
            const promise = response.json().then(body => {
                const value = body && (body.data || body);
                rememberCandidate(candidates, candidateFrom(value || {}, {}, 'auth_response'));
            }).catch(() => {}).finally(() => pending.delete(promise));
            pending.add(promise);
        });
        await page.goto('https://pan.xunlei.com/', { waitUntil: 'domcontentloaded', timeout: 45000 });
        for (let round = 0; round < 12; round++) {
            await delay(1000);
            if (!trustedUrl(page.url(), 'pan.xunlei.com', '/')) break;
            const stored = await storageCredentials(page).catch(() => []);
            storageCredentialCount = stored.length;
            for (const value of stored) rememberCandidate(candidates,
                candidateFrom(value, requestHeaders.get(value.access_token) || {}, 'storage'));
            if (eligibleCandidates(candidates, current).some(c => c.clientId && c.deviceId)) break;
        }
        // Never invalidate localStorage or borrow a password database. If the live session is
        // still expired, allow one ordinary web-client reload and preserve the old backend state.
        if (!eligibleCandidates(candidates, current).length) {
            await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 });
            await delay(5000);
            if (trustedUrl(page.url(), 'pan.xunlei.com', '/')) {
                const stored = await storageCredentials(page).catch(() => []); storageCredentialCount = stored.length;
                for (const value of stored) rememberCandidate(candidates,
                    candidateFrom(value, requestHeaders.get(value.access_token) || {}, 'storage'));
            }
        }
        await Promise.allSettled([...pending]);
        const selected = await selectValidated(candidates, current, state => validateState(state));
        return { ...selected, candidateCount: candidates.size, storageCredentialCount, refreshHttpStatus,
            eligibleCount: eligibleCandidates(candidates,current).length,
            identityCount: [...candidates.values()].filter(c=>c.clientId && c.deviceId).length,
            reason: selected.state ? null : (candidates.size && !eligibleCandidates(candidates, current).length ? 'expired_or_account_mismatch' : 'no_verified_authenticated_token') };
    } finally { if (browser) await browser.close().catch(() => {}); }
}

async function main(env = process.env) {
    if (!env.EDGE_PATH || !env.EDGE_PROFILE || !env.XUNLEI_OUTPUT_STATE || !env.XUNLEI_OUTPUT_META) throw new Error('missing_configuration');
    const current = readJson(env.XUNLEI_EXISTING_STATE);
    const result = await capture({ puppeteer: require(env.PUPPETEER_PATH), edgePath: env.EDGE_PATH,
        profilePath: env.EDGE_PROFILE, profileName: env.EDGE_PROFILE_NAME || 'Default', current });
    const { state, ...diagnostics } = result;
    if (!state) {
        writeJson(env.XUNLEI_OUTPUT_META, { status: 'failed', validated: false, ...diagnostics });
        console.log(`XUNLEI_EDGE_SYNC=failed;REASON=${result.reason};CANDIDATES=${result.candidateCount};STORED=${result.storageCredentialCount};ELIGIBLE=${result.eligibleCount};IDENTITY=${result.identityCount};REFRESH_HTTP=${result.refreshHttpStatus};VALIDATION_HTTP=${result.validationHttpStatus}`);
        return 2;
    }
    const changed = changedState(state, current);
    writeJson(env.XUNLEI_OUTPUT_STATE, state);
    writeJson(env.XUNLEI_OUTPUT_META, { status: changed ? 'updated' : 'unchanged', validated: true, changed,
        expiresAt: state.expires_at, ...diagnostics });
    console.log(`XUNLEI_EDGE_SYNC=${changed ? 'updated' : 'unchanged'};VALIDATED=true;EXPIRES=${new Date(state.expires_at).toISOString()}`);
    return 0;
}

module.exports = { jwtPayload, normalizeExpiry, trustedUrl, driveUrl, authUrl, candidateFrom, rememberCandidate,
    eligibleCandidates, buildState, changedState, selectValidated, validateState, storageCredentials, capture, main, MIN_TTL_MS };
if (require.main === module) main().then(code => { process.exitCode = code; }).catch(error => {
    const type = error && /^[A-Za-z0-9_$]+$/.test(error.name || '') ? error.name : 'Error';
    if (process.env.XUNLEI_OUTPUT_META) writeJson(process.env.XUNLEI_OUTPUT_META, { status: 'failed', validated: false, reason: type });
    console.log(`XUNLEI_EDGE_SYNC=failed;TYPE=${type}`); process.exitCode = 1;
});
