"use strict";
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const helper = require('../xunlei-env-browser-login.cjs');

const NOW = Date.UTC(2026, 10, 2, 10);
const LOGIN = 'https://pan.xunlei.com/login/';
const FRAME = 'https://i.xunlei.com/center/account/personal/auth/';
const jwt = (hours, user = 'fixture-user') => ['header', Buffer.from(JSON.stringify({ sub: user,
    aud: 'fixture-client', exp: Math.floor((NOW + hours * 3600000) / 1000) })).toString('base64url'), 'fixture'].join('.');
const state = (hours, user) => ({ access_token: jwt(hours, user), token_type: 'Bearer',
    expires_at: NOW + hours * 3600000, user_id: user || 'fixture-user', client_id: 'fixture-client',
    device_id: 'fixture-device', refresh_token: 'fixture-refresh-' + hours, captcha_token: 'fixture-captcha',
    client_version: '1.82.0', package_name: 'pan.xunlei.com' });
function fixture(extra = {}) {
    const calls = { launch: 0, close: 0, credentials: 0, submit: 0, guard: [], validation: [], actions: [] };
    let guard = { loginBlocked: false };
    let submitted = false;
    const page = new EventEmitter();
    page._url = LOGIN; page.url = () => page._url;
    page.setBypassServiceWorker = async () => {};
    page.setRequestInterception = async () => {};
    const frame = { url: () => FRAME };
    page.frames = () => [frame];
    page.goto = async () => {};
    const options = {
        current: state(-1), now: () => NOW, edgePath: 'fixture-edge', profilePath: path.join(os.tmpdir(), 'private-sync-v2', 'env-login-profile'),
        naturalRounds: 1, loginRounds: 2, delay: async () => {},
        puppeteer: { launch: async launch => { calls.launch++; assert.equal(launch.headless, true);
            return { pages: async () => [page], close: async () => { calls.close++; } }; } },
        readGuard: () => guard,
        writeGuard: value => { calls.guard.push({ ...value }); guard = { ...value }; },
        readCredentials: async () => { calls.credentials++; return { account: 'fixture-account', password: 'fixture-password' }; },
        validate: async candidate => { calls.validation.push(candidate.access_token); return { ok: true, status: 200 }; },
        storageCredentials: async () => submitted ? [state(12)] : [],
        formAction: async (_page, _frame, action, credentials) => {
            calls.actions.push(action);
            if (action === 'fill') assert.deepEqual(credentials, { account: 'fixture-account', password: 'fixture-password' });
            if (action === 'submit') {
                assert.equal(guard.loginBlocked, true, 'guard must persist before the only submit');
                calls.submit++; submitted = true; page._url = 'https://pan.xunlei.com/';
            }
            return { formReady: true };
        }, ...extra,
    };
    return { options, calls, page, frame, setGuard: value => { guard = value; } };
}

test('a verified current token covers the next two-hour run without a browser or password', async () => {
    const f = fixture({ current: state(3) });
    const result = await helper.run(f.options);
    assert.equal(result.state.access_token, state(3).access_token);
    assert.equal(result.status, 'unchanged'); assert.equal(result.validationHttpStatus, 200);
    assert.equal(f.calls.launch, 0); assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
});
test('network or business failure with sufficient TTL never causes another password login', async () => {
    for (const response of [{ ok: false, status: null }, { ok: false, status: 502 }, { ok: false, status: 200 }]) {
        const f = fixture({ current: state(3), validate: async () => response });
        const result = await helper.run(f.options);
        assert.equal(result.state, null); assert.equal(result.reason, 'validation_unavailable');
        assert.equal(f.calls.launch, 0); assert.equal(f.calls.credentials, 0);
    }
});
test('server rejection of a still-dated token permits independent session renewal', async () => {
    const f = fixture({ current: state(3), storageCredentials: async () => [state(12)] });
    f.options.validate = async candidate => ({ ok: candidate.access_token === state(12).access_token,
        status: candidate.access_token === state(12).access_token ? 200 : 401 });
    const result = await helper.run(f.options);
    assert.equal(result.status, 'renewed'); assert.equal(f.calls.credentials, 0); assert.equal(f.calls.close, 1);
});
test('natural independent browser renewal never reads an account password', async () => {
    const f = fixture({ storageCredentials: async () => [state(12)] });
    const result = await helper.run(f.options);
    assert.equal(result.status, 'renewed'); assert.equal(result.live.loginSubmitted, false);
    assert.equal(result.state.refresh_token, state(12).refresh_token);
    assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0); assert.equal(f.calls.close, 1);
});
test('internal forceBrowser can verify a live private profile while forbidding any password fallback', async () => {
    const f = fixture({ current: state(3), forceBrowser: true, allowPasswordLogin: false,
        storageCredentials: async () => [state(12)] });
    const result = await helper.capture(f.options);
    assert.equal(result.validationHttpStatus, 200); assert.equal(f.calls.launch, 1);
    assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
});
test('a forced browser probe with no usable session cannot submit credentials', async () => {
    const f = fixture({ forceBrowser: true, allowPasswordLogin: false, storageCredentials: async () => [] });
    const result = await helper.capture(f.options);
    assert.equal(result.reason, 'browser_session_unavailable'); assert.equal(result.live.needsInteraction, true);
    assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
});
test('password fallback persists protection before one submit and accepts only verified complete same-account credentials', async () => {
    const f = fixture();
    const result = await helper.run(f.options);
    assert.equal(result.status, 'logged_in'); assert.equal(result.validationHttpStatus, 200);
    assert.equal(f.calls.credentials, 1); assert.equal(f.calls.submit, 1);
    assert.equal(f.calls.guard[0].loginBlocked, true); assert.equal(f.calls.guard.at(-1).loginBlocked, false);
    assert.equal(result.live.loginSubmitted, true); assert.equal(f.calls.close, 1);
});
test('missing or incompatible frame origins prevent credential reads', async () => {
    for (const url of ['https://i.xunlei.com.evil.test/center/account/personal/auth/',
        'http://i.xunlei.com/center/account/personal/auth/', 'https://i.xunlei.com/another/path']) {
        const f = fixture(); f.frame.url = () => url;
        const result = await helper.run(f.options);
        assert.equal(result.state, null); assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
    }
});
test('a navigation after form inspection is rejected again before filling', async () => {
    const f = fixture();
    f.options.readCredentials = async () => { f.calls.credentials++; f.page._url = 'https://evil.test/';
        return { account: 'fixture-account', password: 'fixture-password' }; };
    const result = await helper.run(f.options);
    assert.equal(result.reason, 'login_origin_rejected'); assert.equal(f.calls.submit, 0);
    assert.equal(f.calls.actions.includes('fill'), false);
});
test('a failed guard persistence leaves the form unsubmitted', async () => {
    const f = fixture({ writeGuard: () => { throw new helper.SafeFailure('login_guard_write_failed'); } });
    const result = await helper.run(f.options);
    assert.equal(result.reason, 'login_guard_write_failed'); assert.equal(f.calls.submit, 0);
    assert.equal(f.calls.close, 1);
});
test('a blocked or unreadable persisted attempt allows reads but prevents another credential submit', async () => {
    for (const guardRead of [() => ({ loginBlocked: true }), () => { throw new helper.SafeFailure('login_guard_unreadable'); }]) {
        const f = fixture({ readGuard: guardRead });
        const result = await helper.run(f.options);
        assert.equal(result.state, null); assert.equal(result.live.needsInteraction, true);
        assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
    }
});
test('a timeout keeps the persisted protection and a following run does not resubmit', async () => {
    let guard = { loginBlocked: false };
    const readGuard = () => guard;
    const writeGuard = value => { guard = value; };
    const first = fixture({ readGuard, writeGuard, storageCredentials: async () => [] });
    // The injected submit checks the fixture guard, so use a minimal submit here.
    first.options.formAction = async (_p, _f, action) => { if (action === 'submit') first.calls.submit++;
        return { formReady: true }; };
    const failed = await helper.run(first.options);
    assert.equal(failed.reason, 'login_timeout'); assert.equal(first.calls.submit, 1); assert.equal(guard.loginBlocked, true);
    const second = fixture({ readGuard, writeGuard });
    assert.equal((await helper.run(second.options)).reason, 'login_blocked');
    assert.equal(second.calls.credentials, 0); assert.equal(second.calls.submit, 0);
});
test('a visible challenge or password rejection stops without repeated submissions', async () => {
    for (const field of ['challenge', 'rejected']) {
        const f = fixture({ formAction: async () => ({ [field]: true }) });
        const result = await helper.run(f.options);
        assert.equal(result.reason, field === 'challenge' ? 'verification_required' : 'password_rejected');
        assert.equal(result.live.needsInteraction, true); assert.equal(f.calls.submit, 0);
    }
});
test('another account in the private session stops before reading credentials or switching accounts', async () => {
    const f = fixture({ storageCredentials: async () => [state(12, 'other-user')] });
    const result = await helper.run(f.options);
    assert.equal(result.reason, 'account_mismatch'); assert.equal(f.calls.credentials, 0); assert.equal(f.calls.submit, 0);
});
test('a rotated candidate missing refresh cannot borrow a previous token refresh', async () => {
    const candidate = state(12); delete candidate.refresh_token;
    const f = fixture({ allowPasswordLogin: false, storageCredentials: async () => [candidate] });
    const result = await helper.run(f.options);
    assert.equal(result.state, null); assert.equal(f.calls.validation.length, 0); assert.equal(f.calls.credentials, 0);
});
test('a new candidate with failed official validation never becomes an output state', async () => {
    const f = fixture({ storageCredentials: async () => [state(12)], validate: async () => ({ ok: false, status: 502 }) });
    const result = await helper.run(f.options);
    assert.equal(result.reason, 'validation_unavailable'); assert.equal(result.state, null); assert.equal(f.calls.credentials, 0);
});
test('only this invocation browser is closed, even on navigation errors', async () => {
    const f = fixture(); f.page.goto = async () => { throw new Error('fixture private password token URL'); };
    const result = await helper.run(f.options);
    assert.equal(result.reason, 'browser_login_failed'); assert.equal(f.calls.close, 1);
    assert.equal(JSON.stringify(result).includes('fixture private password'), false);
});
test('browser close failure reports a fixed cleanup reason instead of a successful state', async () => {
    const f = fixture({ storageCredentials: async () => [state(12)] });
    f.options.puppeteer.launch = async () => ({ pages: async () => [f.page], close: async () => { throw Error('fixture-password'); } });
    await assert.rejects(helper.run(f.options), error => error.reason === 'browser_cleanup_failed' && !error.message.includes('fixture-password'));
});
test('all Drive host writes and auth route variants are blocked', () => {
    for (const url of ['https://api-pan.xunlei.com/drive/v1/files', 'https://api-pan.xunlei.com/drive/v10/files',
        'https://api-pan.xunlei.com/anything', 'https://xluser-ssl.xunlei.com/v1/auth/token/evil']) {
        assert.equal(helper.blockedRequest({ url: () => url, method: () => 'POST' }), true, url);
    }
    assert.equal(helper.blockedRequest({ url: () => 'https://api-pan.xunlei.com/drive/v1/files', method: () => 'GET' }), false);
    assert.equal(helper.blockedRequest({ url: () => 'https://xluser-ssl.xunlei.com/v1/auth/token', method: () => 'POST' }), false);
});

test('a login token shorter than the next scheduled interval is never accepted', async () => {
    const f = fixture(); let afterSubmit = false;
    f.options.storageCredentials = async () => afterSubmit ? [state(1)] : [];
    f.options.formAction = async (_page, _frame, action) => {
        if (action === 'submit') { afterSubmit = true; f.calls.submit++; }
        return { formReady: true };
    };
    const result = await helper.run(f.options);
    assert.equal(result.state, null); assert.equal(result.reason, 'short_token_ttl');
    assert.equal(f.calls.submit, 1); assert.equal(f.calls.guard.at(-1).loginBlocked, true);
});
test('the fast path refuses incomplete or contradictory current account identity', async () => {
    for (const field of ['refresh_token', 'user_id', 'client_id', 'device_id']) {
        const current = state(3); delete current[field];
        const f = fixture({ current });
        const result = await helper.run(f.options);
        assert.equal(result.state, null); assert.equal(result.reason, 'current_state_incomplete');
        assert.equal(f.calls.validation.length, 0); assert.equal(f.calls.launch, 0);
    }
    const f = fixture({ current: { ...state(3), user_id: 'another-user' } });
    assert.equal((await helper.run(f.options)).reason, 'current_account_mismatch');
    assert.equal(f.calls.validation.length, 0);
});
test('only the precise official HTTPS captcha initialization endpoint is allowed to POST', () => {
    const endpoint = 'https://xluser-ssl.xunlei.com/v1/shield/captcha/init';
    assert.equal(helper.blockedRequest({ url: () => endpoint, method: () => 'POST' }), false);
    for (const url of [endpoint + '/evil', endpoint + '2', endpoint.replace('https:', 'http:'),
        endpoint.replace('.com/', '.com:444/'), endpoint.replace('.com/', '.com.evil.test/')]) {
        // A lookalike is not trusted; test the URL predicate even when it is another host.
        assert.equal(helper.captchaUrl(url), false, url);
        if (new URL(url).hostname === 'xluser-ssl.xunlei.com') {
            assert.equal(helper.blockedRequest({ url: () => url, method: () => 'POST' }), true, url);
        }
    }
});

test('the direct capture entry also rejects a regular user browser profile', async () => {
    const f = fixture({ forceBrowser: true, profilePath: path.join(os.tmpdir(), 'User Data', 'Default') });
    const result = await helper.capture(f.options);
    assert.equal(result.reason, 'private_profile_rejected'); assert.equal(f.calls.launch, 0);
});
test('the renderer repeats the official iframe origin check before touching form fields', async () => {
    const prior = global.location; let evaluated = false;
    global.location = { protocol: 'https:', hostname: 'evil.test', port: '', pathname: '/center/account/personal/auth/' };
    const page = { url: () => LOGIN };
    const frame = { url: () => FRAME, evaluate: async (callback, payload) => { evaluated = true; return callback(payload); } };
    try {
        const result = await helper.formAction(page, frame, 'fill', { account: 'fixture', password: 'fixture-private' });
        assert.equal(result.unsafe, true); assert.equal(evaluated, true);
    } finally { global.location = prior; }
});
test('an auth challenge arriving during collection cannot be accepted as a successful login', async () => {
    const f = fixture(); let submitted = false;
    f.options.storageCredentials = async () => submitted ? [state(12)] : [];
    f.options.formAction = async (_page, _frame, action) => {
        if (action === 'submit') {
            submitted = true; f.calls.submit++;
            f.page.emit('response', { url: () => 'https://xluser-ssl.xunlei.com/v1/auth/signin',
                json: async () => ({ error: 'review_panel' }) });
        }
        return { formReady: true };
    };
    const result = await helper.run(f.options);
    assert.equal(result.state, null); assert.equal(result.reason, 'verification_required');
    assert.equal(f.calls.submit, 1); assert.equal(f.calls.guard.at(-1).loginBlocked, true);
});

test('auth redirects cannot carry a POST or Bearer token outside exact official endpoints', () => {
    const request = (url, method = 'GET', bearer = true) => ({ url: () => url, method: () => method,
        headers: () => bearer ? { authorization: 'Bearer fixture-private-token' } : {} });
    for (const url of ['https://evil.test/redirect', 'https://api-pan.xunlei.com.evil.test/drive/v1/files',
        'https://api-pan.xunlei.com/drive/v10/files', 'http://api-pan.xunlei.com/drive/v1/files',
        'https://api-pan.xunlei.com:444/drive/v1/files']) {
        assert.equal(helper.blockedRequest(request(url)), true, url);
        assert.equal(helper.blockedRequest(request(url, 'POST', false)), true, url);
    }
    for (const url of ['https://api-pan.xunlei.com/drive/v1/files',
        'https://xluser-ssl.xunlei.com/v1/auth/token', 'https://xluser-ssl.xunlei.com/v1/shield/captcha/init']) {
        assert.equal(helper.blockedRequest(request(url)), false, url);
    }
    assert.equal(helper.blockedRequest(request('https://xluser-ssl.xunlei.com/v1/auth/signin', 'POST')), false);
    assert.equal(helper.blockedRequest(request('https://ordinary-static.test/file.js', 'GET', false)), false);
});

test('a pre-submit challenge persists across runs until successful official verification', async () => {
    let guard = { loginBlocked: false };
    const first = fixture({ readGuard: () => guard, writeGuard: value => { guard = value; },
        formAction: async () => ({ challenge: true }) });
    assert.equal((await helper.run(first.options)).reason, 'verification_required');
    assert.equal(guard.loginBlocked, true); assert.equal(first.calls.submit, 0);
    const second = fixture({ readGuard: () => guard, writeGuard: value => { guard = value; } });
    assert.equal((await helper.run(second.options)).reason, 'login_blocked');
    assert.equal(second.calls.credentials, 0); assert.equal(second.calls.submit, 0);
    const verified = fixture({ current: state(3), readGuard: () => guard, writeGuard: value => { guard = value; } });
    assert.equal((await helper.run(verified.options)).validated, true); assert.equal(guard.loginBlocked, false);
});
test('password-bearing SDK signin requests require the persisted authorization and are accepted at most once', () => {
    const request = (pathname, body) => ({ url: () => 'https://xluser-ssl.xunlei.com' + pathname,
        method: () => 'POST', headers: () => ({}), postData: () => body });
    const signin = request('/v1/auth/signin', JSON.stringify({ username: 'fixture', password: 'fixture-private' }));
    const gate = helper.createPasswordRequestGate();
    assert.equal(gate.blocked(signin), true); assert.equal(gate.count, 0);
    gate.authorize();
    assert.equal(gate.blocked(signin), false); assert.equal(gate.count, 1);
    assert.equal(gate.blocked(signin), true); assert.equal(gate.count, 1);
    const redirected = { ...signin, url: () => 'https://evil.test/redirect' };
    assert.equal(gate.blocked(redirected), true); assert.equal(gate.count, 1);
});
test('pure token exchanges and natural refresh remain allowed while signin-token password fields share the one-submit count', () => {
    const request = (pathname, payload) => ({ url: () => 'https://xluser-ssl.xunlei.com' + pathname,
        method: () => 'POST', headers: () => ({}), postData: () => JSON.stringify(payload) });
    const gate = helper.createPasswordRequestGate();
    assert.equal(gate.blocked(request('/v1/auth/token', { refresh_token: 'fixture-refresh' })), false);
    assert.equal(gate.blocked(request('/v1/auth/signin/token', { signin_token: 'fixture-token' })), false);
    const password = request('/v1/auth/signin/token', { payload: { passWord: 'fixture-private' } });
    assert.equal(gate.blocked(password), true); gate.authorize();
    assert.equal(gate.blocked(password), false); assert.equal(gate.count, 1);
    assert.equal(gate.blocked(request('/v1/auth/signin', { username: 'fixture' })), true);
    assert.equal(gate.blocked(request('/v1/auth/signin/token', { signin_token: 'fixture-token' })), false);
});
test('password detection examines JSON/form field keys without mistaking token values for passwords', () => {
    const body = value => ({ postData: () => value });
    assert.equal(helper.hasPasswordField(body('{"payload":{"password":"fixture"}}')), true);
    assert.equal(helper.hasPasswordField(body('account=fixture&password=fixture-private')), true);
    assert.equal(helper.hasPasswordField(body('{"signin_token":"password"}')), false);
    assert.equal(helper.hasPasswordField(body('refresh_token=password')), false);
});
test('Docker environment credentials are read only from the exact running backend in the production project', async () => {
    const id = 'a'.repeat(64);
    const item = { Id: id, State: { Running: true }, Config: { Labels: {
        'com.docker.compose.project': 'gying-movie', 'com.docker.compose.service': 'backend' },
        Env: ['XUNLEI_ACCOUNT=fixture-account', 'XUNLEI_PASSWORD=fixture=password'] } };
    const read = entry => helper.loadBackendCredentials({ container: id.slice(0, 12), docker: 'fixture-docker',
        inspect: async () => JSON.stringify([entry]) });
    assert.deepEqual(await read(item), { account: 'fixture-account', password: 'fixture=password' });
    for (const invalid of [{ ...item, Id: 'b'.repeat(64) }, { ...item, State: { Running: false } },
        { ...item, Config: { ...item.Config, Labels: { ...item.Config.Labels, 'com.docker.compose.service': 'frontend' } } },
        { ...item, Config: { ...item.Config, Labels: { ...item.Config.Labels, 'com.docker.compose.project': 'other' } } }]) {
        await assert.rejects(read(invalid), error => error.reason === 'backend_container_rejected');
    }
});
function temporary() { return fs.mkdtempSync(path.join(os.tmpdir(), 'gying-xunlei-env-test-')); }
function cleanup(folder) {
    const full = path.resolve(folder), root = path.resolve(os.tmpdir());
    assert.equal(path.dirname(full), root); assert.ok(path.basename(full).startsWith('gying-xunlei-env-test-'));
    fs.rmSync(full, { recursive: true, force: true });
}
test('a malformed existing guard fails closed while an absent file permits the first attempt', () => {
    const folder = temporary();
    try {
        const file = path.join(folder, 'guard.json');
        assert.equal(helper.readGuard(file).loginBlocked, false);
        for (const value of ['{broken', '{}', 'null', '[]']) {
            fs.writeFileSync(file, value);
            assert.throws(() => helper.readGuard(file), error => error.reason === 'login_guard_unreadable');
        }
        helper.writeGuard(file, { loginBlocked: true, status: 'env_login_attempt' });
        assert.equal(helper.readGuard(file).loginBlocked, true);
    } finally { cleanup(folder); }
});
test('the profile path cannot refer to ordinary Edge data or another private directory', () => {
    const root = path.join(os.tmpdir(), 'private-sync-v2');
    assert.equal(helper.assertPrivateProfile(path.join(root, 'env-login-profile'), root), path.resolve(root, 'env-login-profile'));
    for (const profile of [path.join(root, 'Default'), path.join(root, '..', 'env-login-profile'),
        path.join(root, 'nested', 'env-login-profile')]) {
        assert.throws(() => helper.assertPrivateProfile(profile, root), error => error.reason === 'private_profile_rejected');
    }
});
test('main writes compatible state/meta and never exposes injected raw failures in logs or metadata', async () => {
    const folder = temporary(); const root = path.join(folder, 'private-sync-v2'); fs.mkdirSync(root);
    const env = { PUPPETEER_PATH: 'fixture-module', EDGE_PATH: 'fixture-edge', XUNLEI_EXISTING_STATE: path.join(folder, 'existing.json'),
        XUNLEI_OUTPUT_STATE: path.join(folder, 'output.json'), XUNLEI_OUTPUT_META: path.join(folder, 'meta.json'),
        XUNLEI_ENV_PROFILE: path.join(root, 'env-login-profile'), XUNLEI_LOGIN_STATUS: path.join(root, 'live-session-status.json'),
        XUNLEI_BACKEND_CONTAINER: 'a'.repeat(12), XUNLEI_DOCKER_PATH: 'fixture-docker' };
    const prior = console.log; const logs = []; console.log = value => logs.push(value);
    try {
        fs.writeFileSync(env.XUNLEI_EXISTING_STATE, JSON.stringify(state(3)));
        assert.equal(await helper.main(env, { puppeteer: {}, validate: async () => ({ ok: true, status: 200 }), now: () => NOW }), 0);
        const success = JSON.parse(fs.readFileSync(env.XUNLEI_OUTPUT_META));
        assert.equal(success.status, 'unchanged'); assert.equal(success.changed, false); assert.equal(success.validated, true);
        fs.writeFileSync(env.XUNLEI_EXISTING_STATE, JSON.stringify(state(-1)));
        assert.equal(await helper.main(env, { puppeteer: { launch: async () => { throw Error('fixture-password token=fixture-secret https://private/?cookie=fixture'); } }, now: () => NOW }), 2);
        const failed = JSON.parse(fs.readFileSync(env.XUNLEI_OUTPUT_META));
        assert.equal(failed.status, 'failed'); assert.equal(failed.reason, 'browser_login_failed');
        assert.doesNotMatch(JSON.stringify(failed) + logs.join(''), /fixture-password|fixture-secret|private\/\?cookie/);
    } finally { console.log = prior; cleanup(folder); }
});
