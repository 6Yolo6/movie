"use strict";

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFile } = require('node:child_process');
const sync = require('./xunlei-edge-token-sync.cjs');

const RENEW_AHEAD_MS = 125 * 60 * 1000;
const LOGIN_PATH = '/center/account/personal/auth/';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
class SafeFailure extends Error {
    constructor(reason) { super(reason); this.reason = reason; }
}
function trustedPage(url) { return sync.trustedUrl(url, 'pan.xunlei.com', '/'); }
function trustedFrame(url) {
    try { return sync.trustedUrl(url, 'i.xunlei.com', LOGIN_PATH) && new URL(url).pathname === LOGIN_PATH; }
    catch { return false; }
}
function trustedLoginPage(url) {
    try { return trustedPage(url) && ['/login', '/login/'].includes(new URL(url).pathname); }
    catch { return false; }
}
function assertPrivateProfile(profile, privateRoot) {
    const root = path.resolve(privateRoot);
    const full = path.resolve(profile);
    if (path.basename(root) !== 'private-sync-v2' || path.basename(full) !== 'env-login-profile'
            || path.dirname(full) !== root) throw new SafeFailure('private_profile_rejected');
    for (let current = full; ; current = path.dirname(current)) {
        if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) throw new SafeFailure('private_profile_rejected');
        if (path.dirname(current) === current) break;
    }
    return full;
}
function readGuard(file) {
    try {
        const value = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
        if (!value || typeof value !== 'object' || Array.isArray(value) || typeof value.loginBlocked !== 'boolean') {
            throw new SafeFailure('login_guard_unreadable');
        }
        return value;
    } catch (error) {
        if (error && error.code === 'ENOENT') return { loginBlocked: false };
        throw new SafeFailure('login_guard_unreadable');
    }
}
function writeGuard(file, value) {
    const temporary = file + '.' + crypto.randomUUID() + '.tmp';
    try {
        fs.writeFileSync(temporary, JSON.stringify(value), { encoding: 'utf8', mode: 0o600, flag: 'wx' });
        fs.renameSync(temporary, file);
    } catch { throw new SafeFailure('login_guard_write_failed'); }
    finally { try { fs.unlinkSync(temporary); } catch {} }
}
function inspectDocker(docker, container) {
    return new Promise((resolve, reject) => {
        execFile(docker, ['inspect', container], { timeout: 10000, maxBuffer: 4 * 1024 * 1024, windowsHide: true },
            (error, stdout) => error ? reject(new SafeFailure('backend_credentials_unavailable')) : resolve(stdout));
    });
}
async function loadBackendCredentials(options) {
    if (!/^[a-f0-9]{12,64}$/.test(options.container || '')) throw new SafeFailure('backend_container_rejected');
    let rows;
    try { rows = JSON.parse(await (options.inspect || inspectDocker)(options.docker, options.container)); }
    catch { throw new SafeFailure('backend_credentials_unavailable'); }
    const item = Array.isArray(rows) && rows.length === 1 ? rows[0] : null;
    const labels = item && item.Config && item.Config.Labels || {};
    if (!item || typeof item.Id !== 'string' || !item.Id.startsWith(options.container)
            || labels['com.docker.compose.project'] !== 'gying-movie'
            || labels['com.docker.compose.service'] !== 'backend' || !item.State || item.State.Running !== true) {
        throw new SafeFailure('backend_container_rejected');
    }
    const env = Object.fromEntries((item.Config.Env || []).filter(v => typeof v === 'string' && v.includes('='))
        .map(v => { const at = v.indexOf('='); return [v.slice(0, at), v.slice(at + 1)]; }));
    if (!env.XUNLEI_ACCOUNT || !env.XUNLEI_PASSWORD) throw new SafeFailure('backend_credentials_unavailable');
    return { account: env.XUNLEI_ACCOUNT, password: env.XUNLEI_PASSWORD };
}
function loginFrame(page) {
    if (!trustedLoginPage(page.url())) throw new SafeFailure('login_origin_rejected');
    const frames = page.frames().filter(frame => trustedFrame(frame.url()));
    if (frames.length !== 1) throw new SafeFailure('login_frame_unavailable');
    return frames[0];
}
function assertLoginFrame(page, frame) {
    if (!trustedLoginPage(page.url()) || !trustedFrame(frame.url())) throw new SafeFailure('login_origin_rejected');
}
// Each DOM action checks the iframe origin again inside the renderer, so a
// navigation between the outer URL check and evaluation cannot receive a password.
async function formAction(page, frame, action, credentials) {
    assertLoginFrame(page, frame);
    return frame.evaluate(({ action, credentials }) => {
        if (location.protocol !== 'https:' || location.hostname !== 'i.xunlei.com'
                || !['', '443'].includes(location.port) || location.pathname !== '/center/account/personal/auth/') {
            return { unsafe: true };
        }
        const visible = e => e && e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0
            && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
        const named = (selector, text) => [...document.querySelectorAll(selector)]
            .filter(e => visible(e) && e.textContent.replace(/\s/g, '') === text);
        const leaves = [...document.querySelectorAll('div,span,a,button')].filter(e => visible(e) && !e.children.length);
        const challenge = leaves.some(e => ['安全验证', '请完成验证', '请拖动滑块', '请输入验证码', '请输入短信验证码']
            .includes(e.textContent.replace(/\s/g, '')));
        const rejected = leaves.some(e => /^(账号或密码错误|帐号或密码错误|密码错误|登录失败)$/.test(e.textContent.trim()));
        if (challenge || rejected) return { challenge, rejected };
        if (action === 'mode') {
            const tabs = leaves.filter(e => e.textContent.replace(/\s/g, '') === '账号密码登录');
            if (tabs.length > 1) return { ambiguous: true };
            if (tabs.length === 1) tabs[0].click();
            return { modeClicked: tabs.length === 1 };
        }
        const accounts = [...document.querySelectorAll('input[placeholder="请输入手机号/邮箱/账号"]')].filter(visible);
        const passwords = [...document.querySelectorAll('input[type="password"]')].filter(visible);
        const checkboxes = [...document.querySelectorAll('input[type="checkbox"]')].filter(visible);
        const buttons = named('button', '登录');
        if (accounts.length !== 1 || passwords.length !== 1 || checkboxes.length > 1 || buttons.length !== 1) {
            return { formReady: false };
        }
        if (action === 'fill') {
            const set = (element, value) => {
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(element, value);
                element.dispatchEvent(new Event('input', { bubbles: true }));
                element.dispatchEvent(new Event('change', { bubbles: true }));
            };
            set(accounts[0], credentials.account); set(passwords[0], credentials.password);
            if (checkboxes.length && !checkboxes[0].checked) checkboxes[0].click();
        }
        if (action === 'submit') {
            if (!accounts[0].value || !passwords[0].value || buttons[0].disabled
                    || checkboxes.length && !checkboxes[0].checked) return { formReady: false };
            buttons[0].click();
        }
        return { formReady: true };
    }, { action, credentials });
}
function checkForm(result) {
    if (result && result.challenge) throw new SafeFailure('verification_required');
    if (result && result.rejected) throw new SafeFailure('password_rejected');
    if (result && (result.unsafe || result.ambiguous)) throw new SafeFailure('login_origin_rejected');
}
function captchaUrl(value) {
    try { return sync.trustedUrl(value, 'xluser-ssl.xunlei.com', '/v1/shield/captcha/init')
        && new URL(value).pathname === '/v1/shield/captcha/init'; }
    catch { return false; }
}
function riskBootstrapRequest(request) {
    try {
        const url = request.url();
        const headers = typeof request.headers === 'function' ? request.headers() : {};
        if (request.method() !== 'POST' || !sync.trustedUrl(url, 'xluser-ssl.xunlei.com', '/risk')
                || new URL(url).pathname !== '/risk'
                || Object.keys(headers || {}).some(key => key.toLowerCase() === 'authorization')) return false;
        const types = Object.entries(headers || {}).filter(([key]) => key.toLowerCase() === 'content-type');
        if (types.length !== 1 || typeof types[0][1] !== 'string'
                || types[0][1].split(';', 1)[0].trim().toLowerCase() !== 'application/json') return false;
        const body = JSON.parse(typeof request.postData === 'function' ? request.postData() : null);
        return body !== null && typeof body === 'object' && !Array.isArray(body) && !hasPasswordField(request);
    } catch { return false; }
}
function blockedRequest(request) {
    try {
        const url = request.url();
        const headers = typeof request.headers === 'function' ? request.headers() : {};
        const authorization = headers.authorization || headers.Authorization || '';
        const official = sync.driveUrl(url) || sync.authUrl(url) || captchaUrl(url);
        // A 307/308 must not carry credentials or a Bearer token to another
        // host. Writes are restricted to exact auth endpoints and credential-free bootstrap.
        if (/^Bearer\s+/i.test(authorization) && !official) return true;
        if (!SAFE_METHODS.has(request.method())) return !sync.authUrl(url) && !captchaUrl(url)
            && !riskBootstrapRequest(request);
        return false;
    } catch { return true; }
}

function hasPasswordField(request) {
    const body = typeof request.postData === 'function' ? request.postData() : null;
    if (!body) return false;
    const passwordKey = key => ['password', 'passwd'].includes(String(key).replace(/[_-]/g, '').toLowerCase());
    try {
        const walk = (value, depth = 0) => {
            if (!value || typeof value !== 'object') return false;
            if (depth > 8) return true; // Uninspected nested fields cannot establish a password-free request.
            return Object.keys(value).some(key => passwordKey(key) || walk(value[key], depth + 1));
        };
        return walk(JSON.parse(body));
    } catch {
        // Form data is inspected by field names only. Never retain or log bodies.
        return [...new URLSearchParams(body).keys()].some(passwordKey);
    }
}
function passwordSigninRequest(request) {
    if (!sync.authUrl(request.url()) || SAFE_METHODS.has(request.method())) return false;
    const pathname = new URL(request.url()).pathname;
    return pathname === '/v1/auth/signin' || pathname === '/v1/auth/signin/token' && hasPasswordField(request);
}
function createPasswordRequestGate() {
    let authorized = false, count = 0;
    return {
        authorize() { authorized = true; },
        get count() { return count; },
        blocked(request) {
            if (blockedRequest(request)) return true;
            if (!passwordSigninRequest(request)) return false;
            if (!authorized || count >= 1) return true;
            count++; return false;
        },
    };
}

async function run(options) {
    const current = options.current || {};
    const now = options.now || Date.now;
    const validate = options.validate || sync.validateState;
    const wait = options.delay || delay;
    const guardRead = options.readGuard || (() => readGuard(options.guardPath));
    const guardWrite = options.writeGuard || (value => writeGuard(options.guardPath, value));
    const getCredentials = options.readCredentials || (() => loadBackendCredentials(options));
    const action = options.formAction || formAction;
    const passwordGate = createPasswordRequestGate();
    const live = { status: 'checking', refreshed: false, loginSubmitted: false, needsInteraction: false };
    const finish = (state, status, httpStatus = null) => ({ state, status, validated: !!state,
        validationHttpStatus: httpStatus, live: { ...live, status }, reason: state ? null : status,
        passwordSigninRequests: passwordGate.count });
    if (!current.access_token || !current.refresh_token || !current.user_id || !current.client_id || !current.device_id
            || !Number.isFinite(current.expires_at)) return finish(null, 'current_state_incomplete');
    const claimedUser = sync.jwtPayload(current.access_token).sub;
    if (claimedUser && String(claimedUser) !== String(current.user_id)) return finish(null, 'current_account_mismatch');
    if (!options.forceBrowser && current.access_token && current.expires_at > now() + RENEW_AHEAD_MS) {
        const checked = await validate(current).catch(() => ({ ok: false, status: null }));
        if (checked.ok === true && checked.status === 200) {
            guardWrite({ checkedAt: new Date(now()).toISOString(), status: 'verified', loginBlocked: false, expiresAt: current.expires_at });
            return finish(current, 'unchanged', 200);
        }
        // Transport/proxy failures cannot establish expired authorization. Keep
        // the old state and do not turn a temporary outage into another login.
        if (![401, 403].includes(checked.status)) return finish(null, 'validation_unavailable', checked.status);
    }
    let browser;
    let submitted = false;
    const candidates = new Map();
    const headers = new Map();
    const pending = new Set();
    let authRejected = null;
    try {
        assertPrivateProfile(options.profilePath, options.privateRoot || path.dirname(path.resolve(options.profilePath)));
        browser = await options.puppeteer.launch({ headless: true, executablePath: options.edgePath,
            userDataDir: options.profilePath, timeout: 45000,
            args: ['--no-first-run', '--disable-background-networking'] });
        const page = (await browser.pages())[0] || await browser.newPage();
        await page.setBypassServiceWorker(true);
        await page.setRequestInterception(true);
        page.on('request', request => {
            const blocked = passwordGate.blocked(request);
            void (blocked ? request.abort() : request.continue()).catch(() => {});
            if (blocked || !sync.driveUrl(request.url())) return;
            const h = request.headers();
            const authorization = h.authorization || h.Authorization || '';
            if (!/^Bearer\s+\S+$/i.test(authorization)) return;
            const token = authorization.replace(/^Bearer\s+/i, '').trim();
            const matching = {};
            for (const key of ['x-client-id', 'x-device-id', 'x-captcha-token', 'x-client-version']) if (h[key]) matching[key] = h[key];
            headers.set(token, matching);
            sync.rememberCandidate(candidates, sync.candidateFrom({ access_token: token }, matching, 'request'));
        });
        page.on('response', response => {
            if (!sync.authUrl(response.url())) return;
            const promise = response.json().then(body => {
                const value = body && (body.data || body);
                sync.rememberCandidate(candidates, sync.candidateFrom(value || {}, {}, 'auth_response'));
                const message = String(body && (body.error || body.error_description || body.message) || '').toLowerCase();
                if (submitted && /review_panel|captcha|verification|验证码|安全验证/.test(message)) authRejected = 'verification_required';
                else if (submitted && /invalid.*password|password.*invalid|账号或密码错误|帐号或密码错误|密码错误/.test(message)) authRejected = 'password_rejected';
            }).catch(() => {}).finally(() => pending.delete(promise));
            pending.add(promise);
        });
        await page.goto('https://pan.xunlei.com/', { waitUntil: 'domcontentloaded', timeout: 45000 });
        live.refreshed = true;
        async function collect(minimumTtl) {
            if (!trustedPage(page.url())) throw new SafeFailure('browser_origin_rejected');
            const stored = await (options.storageCredentials || sync.storageCredentials)(page).catch(() => []);
            for (const value of stored) sync.rememberCandidate(candidates,
                sync.candidateFrom(value, headers.get(value.access_token) || {}, 'storage'));
            await Promise.allSettled([...pending]);
            const expectedUser = String(current.user_id || sync.jwtPayload(current.access_token || '').sub || '');
            if (!expectedUser) throw new SafeFailure('current_account_unavailable');
            if ([...candidates.values()].some(c => c.userId && c.userId !== expectedUser)) throw new SafeFailure('account_mismatch');
            const complete = new Map([...candidates].filter(([, c]) => {
                if (c.expiresAt <= now() + minimumTtl) return false;
                try {
                    const state = sync.buildState(c, current);
                    return !!state.refresh_token && !!state.client_id && !!state.device_id;
                } catch { return false; }
            }));
            if (!complete.size) return null;
            const selected = await sync.selectValidated(complete, current, validate, now());
            if (selected.state) return selected;
            if (![401, 403].includes(selected.validationHttpStatus)) throw new SafeFailure('validation_unavailable');
            return null;
        }
        async function accept(selected, status) {
            guardWrite({ checkedAt: new Date(now()).toISOString(), status: 'verified', loginBlocked: false,
                expiresAt: selected.state.expires_at });
            return finish(selected.state, status, selected.validationHttpStatus);
        }
        for (let round = 0; round < (options.naturalRounds || 8); round++) {
            const selected = await collect(RENEW_AHEAD_MS);
            if (selected) return await accept(selected, 'renewed');
            await wait(1000);
        }
        if (options.allowPasswordLogin === false) {
            live.needsInteraction = true;
            return finish(null, 'browser_session_unavailable');
        }
        const guard = guardRead();
        if (guard.loginBlocked && !options.allowLoginRetry) {
            live.needsInteraction = true;
            return finish(null, 'login_blocked');
        }
        if (!trustedLoginPage(page.url())) {
            if (!trustedPage(page.url())) throw new SafeFailure('login_origin_rejected');
            await page.goto('https://pan.xunlei.com/login/', { waitUntil: 'domcontentloaded', timeout: 45000 });
        }
        const assertWaitingPage = () => {
            if (!trustedLoginPage(page.url())
                    && (!trustedPage(page.url()) || new URL(page.url()).pathname !== '/')) {
                throw new SafeFailure('login_origin_rejected');
            }
        };
        let frame;
        for (let round = 0; round < (options.loginFrameRounds || 60); round++) {
            assertWaitingPage();
            const selected = await collect(RENEW_AHEAD_MS);
            assertWaitingPage();
            if (selected) return await accept(selected, 'renewed');
            if (trustedLoginPage(page.url())) {
                const frames = page.frames().filter(item => trustedFrame(item.url()));
                if (frames.length > 1) throw new SafeFailure('login_frame_unavailable');
                if (frames.length === 1) { frame = frames[0]; break; }
            }
            await wait(250);
        }
        if (!frame) throw new SafeFailure('login_frame_unavailable');
        let result = await action(page, frame, 'mode'); checkForm(result);
        await wait(700);
        frame = loginFrame(page);
        result = await action(page, frame, 'inspect'); checkForm(result);
        if (!result || result.formReady !== true) throw new SafeFailure('login_form_unavailable');
        const credentials = await getCredentials();
        try {
            frame = loginFrame(page);
            result = await action(page, frame, 'fill', credentials); checkForm(result);
            if (!result || result.formReady !== true) throw new SafeFailure('login_form_unavailable');
        } finally { credentials.account = ''; credentials.password = ''; }
        frame = loginFrame(page);
        result = await action(page, frame, 'inspect'); checkForm(result);
        if (!result || result.formReady !== true) throw new SafeFailure('login_form_unavailable');
        guardWrite({ checkedAt: new Date(now()).toISOString(), status: 'env_login_attempt', loginBlocked: true });
        // A persisted guard precedes the only possible password submission.
        passwordGate.authorize();
        submitted = true;
        result = await action(page, frame, 'submit'); checkForm(result);
        if (!result || result.formReady !== true) throw new SafeFailure('login_form_unavailable');
        live.loginSubmitted = true;
        for (let round = 0; round < (options.loginRounds || 60); round++) {
            if (authRejected) throw new SafeFailure(authRejected);
            const selected = await collect(RENEW_AHEAD_MS);
            if (authRejected) throw new SafeFailure(authRejected);
            if (selected) return await accept(selected, 'logged_in');
            if (trustedLoginPage(page.url())) {
                try { checkForm(await action(page, loginFrame(page), 'inspect')); }
                catch (error) { if (error instanceof SafeFailure && error.reason !== 'login_frame_unavailable') throw error; }
            }
            await wait(1000);
        }
        const shortToken = [...candidates.values()].some(c => c.expiresAt > now() + sync.MIN_TTL_MS
            && c.expiresAt <= now() + RENEW_AHEAD_MS && c.refreshToken && c.clientId && c.deviceId);
        throw new SafeFailure(shortToken ? 'short_token_ttl' : 'login_timeout');
    } catch (error) {
        const reason = error instanceof SafeFailure ? error.reason : 'browser_login_failed';
        if (['verification_required', 'password_rejected'].includes(reason)) {
            try { guardWrite({ checkedAt: new Date(now()).toISOString(), status: reason, loginBlocked: true }); }
            catch { live.needsInteraction = true; return finish(null, 'login_guard_write_failed'); }
        }
        live.needsInteraction = submitted || ['verification_required', 'password_rejected', 'account_mismatch',
            'login_guard_unreadable', 'login_blocked'].includes(reason);
        return finish(null, reason);
    } finally {
        if (browser) {
            try { await browser.close(); }
            catch { throw new SafeFailure('browser_cleanup_failed'); }
        }
    }
}
async function main(env = process.env, overrides = {}) {
    let result;
    let original = {};
    try {
        for (const name of ['PUPPETEER_PATH', 'EDGE_PATH', 'XUNLEI_EXISTING_STATE', 'XUNLEI_OUTPUT_STATE',
            'XUNLEI_OUTPUT_META', 'XUNLEI_ENV_PROFILE', 'XUNLEI_LOGIN_STATUS', 'XUNLEI_BACKEND_CONTAINER', 'XUNLEI_DOCKER_PATH']) {
            if (!env[name]) throw new SafeFailure('missing_configuration');
        }
        const root = path.dirname(path.resolve(env.XUNLEI_ENV_PROFILE));
        const profilePath = assertPrivateProfile(env.XUNLEI_ENV_PROFILE, root);
        if (path.resolve(env.XUNLEI_LOGIN_STATUS) !== path.join(root, 'live-session-status.json')) throw new SafeFailure('login_guard_path_rejected');
        const current = JSON.parse(fs.readFileSync(env.XUNLEI_EXISTING_STATE, 'utf8').replace(/^\uFEFF/, ''));
        original = current;
        result = await run({ current, profilePath, guardPath: env.XUNLEI_LOGIN_STATUS,
            edgePath: env.EDGE_PATH, container: env.XUNLEI_BACKEND_CONTAINER, docker: env.XUNLEI_DOCKER_PATH,
            allowLoginRetry: ['1', 'true'].includes(String(env.XUNLEI_ALLOW_LOGIN_RETRY || '').toLowerCase()),
            puppeteer: overrides.puppeteer || require(env.PUPPETEER_PATH), ...overrides });
    } catch (error) {
        const reason = error instanceof SafeFailure ? error.reason : 'browser_login_failed';
        result = { state: null, validated: false, reason, status: 'failed', validationHttpStatus: null,
            live: { status: reason, refreshed: false, loginSubmitted: false, needsInteraction: true } };
    }
    const { state, ...meta } = result;
    if (state) {
        fs.writeFileSync(env.XUNLEI_OUTPUT_STATE, JSON.stringify(state), { encoding: 'utf8', mode: 0o600 });
    }
    if (env.XUNLEI_OUTPUT_META) fs.writeFileSync(env.XUNLEI_OUTPUT_META, JSON.stringify({ ...meta,
        status: state ? (sync.changedState(state, original) ? 'updated' : 'unchanged') : 'failed',
        changed: !!state && sync.changedState(state, original), expiresAt: state ? state.expires_at : null }), { encoding: 'utf8', mode: 0o600 });
    console.log(`XUNLEI_ENV_SYNC=${state ? result.status : 'failed'};REASON=${result.reason || 'none'};VALIDATED=${!!state};HTTP=${result.validationHttpStatus || 'none'};LOGIN_SUBMITTED=${!!result.live.loginSubmitted}`);
    return state ? 0 : 2;
}
module.exports = { run, capture: run, main, readGuard, writeGuard, loadBackendCredentials, trustedPage, trustedFrame,
    assertPrivateProfile, blockedRequest, captchaUrl, riskBootstrapRequest, hasPasswordField, passwordSigninRequest, createPasswordRequestGate, formAction, RENEW_AHEAD_MS, SafeFailure };
if (require.main === module) main().then(code => { process.exitCode = code; }).catch(() => {
    console.log('XUNLEI_ENV_SYNC=failed;REASON=output_unavailable'); process.exitCode = 1;
});
