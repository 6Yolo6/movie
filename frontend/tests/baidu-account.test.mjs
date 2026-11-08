import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

async function accountSuite({ React, createRoot, loadPage, loadModule }) {
    const results = [];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + ': ' + JSON.stringify(actual));
    };
    const disconnected = () => ({ configured: false, state: 'DISCONNECTED', ticketId: null, expiresAt: null,
        accountName: null, lastValidatedAt: null, failureCode: null });
    const pending = patch => ({ ...disconnected(), state: 'PENDING', ticketId: 'fixture-ticket', expiresAt: Date.now() + 600000, ...patch });
    const deferred = () => {
        let resolve, reject;
        const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
        return { promise, resolve, reject };
    };
    async function fixture() {
        const nativeSetTimeout = window.setTimeout, nativeClearTimeout = window.clearTimeout;
        const timers = new Map(); let timerId = 100000;
        window.setTimeout = (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; };
        window.clearTimeout = id => timers.delete(id);
        const container = document.createElement('div'); document.body.append(container);
        const root = createRoot(container); const reads = [], starts = [], connections = [];
        let refreshError = false;
        const read = signal => { const call = { signal, ...deferred() }; reads.push(call); return call.promise; };
        const start = signal => { const call = { signal, ...deferred() }; starts.push(call); return call.promise; };
        const connected = async signal => { connections.push(signal); if (refreshError) throw new Error('raw-cookie-never-display'); };
        const capture = name => function Fixture(props) {
            return React.createElement('div', { 'data-fixture': name, ref: node => { if (node) node.fixtureProps = props; } }, props.children);
        };
        const mocks = {
            antd: { Alert: capture('Alert'), Button: capture('Button'), Space: capture('Space'), Tag: capture('Tag'), Typography: { Text: capture('Text') } },
            '@ant-design/icons': { ReloadOutlined: () => null },
            'react-i18next': { useTranslation: () => ({ t: key => key }) },
        };
        mocks['@/lib/cloudDriveWorkflows'] = loadModule('driveWorkflows', mocks);
        const Account = loadPage('baiduAccount', mocks);
        const act = async action => { await React.act(async () => { action(); }); };
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const button = id => all('Button').find(props => props['data-testid'] === id);
        const f = { reads, starts, connections, timers, all, button, act,
            reply: (call, status) => act(() => call.resolve(status)),
            fail: call => act(() => call.reject(new Error('BDUSS=raw-cookie-never-display'))),
            start: () => act(() => button('baidu-start-login').onClick()),
            refresh: () => act(() => button('baidu-refresh-session').onClick()),
            stepTimer: () => act(() => { const [id, timer] = Array.from(timers.entries())[0] || []; if (!timer) throw new Error('Missing poll timer'); timers.delete(id); timer.callback(); }),
            state: () => all('Text').find(props => props['data-testid'] === 'baidu-login-state')?.children,
            setRefreshError: value => { refreshError = value; },
            unmount: () => act(() => root.unmount()),
            dispose: async () => { await act(() => root.unmount()); window.setTimeout = nativeSetTimeout; window.clearTimeout = nativeClearTimeout; container.remove(); },
        };
        await act(() => root.render(React.createElement(Account, { onReadSession: read, onStartLogin: start, onConnected: connected })));
        return f;
    }
    async function check(name, run) {
        let f;
        try { f = await fixture(); await run(f); results.push({ name, ok: true }); }
        catch (error) { results.push({ name, ok: false, error: error.stack || String(error) }); }
        finally { if (f) await f.dispose(); }
    }
    await check('reading a disconnected account does not start login or polling', async f => {
        same(f.reads.length, 1, 'one initial read');
        await f.reply(f.reads[0], disconnected());
        same(f.starts.length, 0, 'no auto login'); same(f.timers.size, 0, 'no idle polling');
        same(f.button('baidu-start-login').disabled, false, 'explicit sign-in available');
    });
    await check('explicit login is deduplicated and only pending tickets poll every five seconds', async f => {
        await f.reply(f.reads[0], disconnected());
        await f.act(() => { f.button('baidu-start-login').onClick(); f.button('baidu-start-login').onClick(); });
        same(f.starts.length, 1, 'one start');
        await f.reply(f.starts[0], pending());
        same(f.timers.size, 1, 'one pending timer'); same(Array.from(f.timers.values())[0].delay, 5000, 'five-second polling');
        await f.stepTimer(); same(f.reads.length, 2, 'one follow-up read');
        await f.reply(f.reads[1], { ...pending(), configured: true, state: 'CONNECTED', accountName: 'f***e', lastValidatedAt: Date.now() });
        same(f.connections.length, 1, 'connected config refresh'); same(f.timers.size, 0, 'terminal stops polling');
        same(f.state(), 'cloudDriveWorkflows.baiduAccount.states.CONNECTED', 'connected shown');
    });
    await check('an expired request stops locally without another network read', async f => {
        await f.reply(f.reads[0], pending({ expiresAt: Date.now() - 1 }));
        same(f.state(), 'cloudDriveWorkflows.baiduAccount.states.EXPIRED', 'expired state');
        same(f.timers.size, 0, 'no expired timer'); same(f.reads.length, 1, 'no redundant network');
        same(f.button('baidu-start-login').disabled, false, 'retry available');
    });
    await check('unmount cancels polling and prevents late connection side effects', async f => {
        await f.reply(f.reads[0], pending()); await f.stepTimer();
        const read = f.reads[1]; await f.unmount(); same(read.signal.aborted, true, 'active request aborted');
        await f.reply(read, { ...pending(), configured: true, state: 'CONNECTED', lastValidatedAt: Date.now() });
        same(f.connections.length, 0, 'late result ignored'); same(f.timers.size, 0, 'timers cleaned up');
    });
    await check('a late initial read cannot overwrite a newer login request', async f => {
        const initial = f.reads[0];
        await f.start(); const start = f.starts[0];
        await f.reply(start, pending()); await f.reply(initial, disconnected());
        same(initial.signal.aborted, true, 'initial read cancelled');
        same(f.state(), 'cloudDriveWorkflows.baiduAccount.states.PENDING', 'new ticket kept');
    });
    await check('polling failure stops retries and only shows fixed safe error text', async f => {
        await f.reply(f.reads[0], pending()); await f.stepTimer(); await f.fail(f.reads[1]);
        same(f.timers.size, 0, 'failure stops polling');
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduAccount.readFailed'), true, 'safe read error');
        same(f.all('Alert').some(props => String(props.title).includes('raw-cookie')), false, 'no raw upstream error');
        await f.refresh(); await f.reply(f.reads[2], { ...pending(), state: 'FAILED', failureCode: 'UNKNOWN_RAW_CODE' });
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduAccount.failed'), true, 'unknown failure uses fixed fallback');
    });
    await check('invalid account metadata is rejected without leaking unknown fields', async f => {
        await f.reply(f.reads[0], { ...pending(), expiresAt: 'invalid', cookie: 'raw-cookie' });
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduAccount.readFailed'), true, 'invalid shape handled');
        same(f.timers.size, 0, 'invalid input cannot start polling');
    });
    await check('a failed config refresh remains visible and manual refresh can retry', async f => {
        f.setRefreshError(true); const connected = { ...disconnected(), configured: true, state: 'CONNECTED', accountName: 'f***e', lastValidatedAt: Date.now() };
        await f.reply(f.reads[0], connected);
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduAccount.refreshFailed'), true, 'config refresh error');
        f.setRefreshError(false); await f.refresh(); await f.reply(f.reads[1], connected);
        same(f.connections.length, 2, 'same connected state can retry config refresh');
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduAccount.refreshFailed'), false, 'old error cleared');
    });
    return results;
}

test('Baidu QR account UI: real React state, request and lifecycle boundaries', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(accountSuite, {
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
        baiduAccount: 'src/components/admin/BaiduAccountConnection.tsx',
    });
    assert.equal(results.length, 8);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
