import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

async function backfillSuite({ React, createRoot, loadPage, loadModule }) {
    const results = [];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + ': ' + JSON.stringify(actual));
    };
    const initial = patch => ({ taskId: null, enabled: false, status: 'PAUSED', phase: 'EXISTING_TASKS', intervalMinutes: 10, batchLimit: 1,
        processed: 0, succeeded: 0, skipped: 0, failed: 0, nextRunAt: null, blockedReason: null, ...patch });
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
        const root = createRoot(container); const reads = [], saves = [], runs = [], errors = [], successes = [];
        const read = signal => { const call = { signal, ...deferred() }; reads.push(call); return call.promise; };
        const save = (settings, signal) => { const call = { settings, signal, ...deferred() }; saves.push(call); return call.promise; };
        const run = signal => { const call = { signal, ...deferred() }; runs.push(call); return call.promise; };
        const capture = name => function Fixture(props) {
            return React.createElement('div', { 'data-fixture': name, ref: node => { if (node) node.fixtureProps = props; } }, props.children);
        };
        const Form = capture('Form'); Form.Item = capture('Form.Item');
        const mocks = {
            antd: { Form, ...Object.fromEntries(['Alert', 'Button', 'Col', 'InputNumber', 'Row', 'Space', 'Switch', 'Tag'].map(name => [name, capture(name)])),
                App: { useApp: () => ({ message: { error: value => errors.push(value), success: value => successes.push(value) } }) },
                Typography: { Text: capture('Text'), Paragraph: capture('Paragraph') } },
            '@ant-design/icons': { CloudSyncOutlined: () => null, ReloadOutlined: () => null, SaveOutlined: () => null },
            'react-i18next': { useTranslation: () => ({ t: key => key }) },
        };
        const library = loadModule('driveWorkflows', mocks);
        mocks['@/lib/cloudDriveWorkflows'] = library;
        const Controls = loadPage('baiduBackfill', mocks);
        let props = { workflow: { provider: 'BAIDU', supported: true, status: 'AVAILABLE', discoveryEnabled: false,
            videoEnabled: false, torrentEnabled: true, transferEnabled: true, gyingPublishEnabled: false, shareEnabled: true,
            credentialConfigured: true, savePath: '/Baidu', batchLimit: 5 },
            workflowEdited: false, workflowBusy: false, hubEnabled: true, onReadStatus: read, onSaveSettings: save, onRun: run };
        const act = async action => { await React.act(async () => { action(); }); };
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const byId = id => ['Button', 'Switch', 'InputNumber', 'Text', 'Alert'].flatMap(all).find(row => row['data-testid'] === 'baidu-backfill-' + id);
        const f = { reads, saves, runs, timers, errors, successes, all, byId, act, library,
            change: (key, value) => act(() => byId(key).onChange(value)),
            save: () => act(() => byId('save').onClick()), run: () => act(() => byId('run').onClick()),
            refresh: () => act(() => byId('refresh').onClick()),
            reply: (call, value) => act(() => call.resolve(value)),
            fail: call => act(() => call.reject(new Error('BDUSS=raw-cookie-never-display'))),
            rerender: patch => act(() => { props = { ...props, ...patch, workflow: { ...props.workflow, ...patch.workflow } }; root.render(React.createElement(Controls, props)); }),
            stepTimer: () => act(() => { const [id, timer] = Array.from(timers.entries())[0] || []; if (!timer) throw new Error('Missing poll timer'); timers.delete(id); timer.callback(); }),
            text: () => container.textContent,
            unmount: () => act(() => root.unmount()),
            dispose: async () => { await act(() => root.unmount()); window.setTimeout = nativeSetTimeout; window.clearTimeout = nativeClearTimeout; container.remove(); },
        };
        await act(() => root.render(React.createElement(Controls, props)));
        return f;
    }
    async function check(name, run) {
        let f;
        try { f = await fixture(); await run(f); results.push({ name, ok: true }); }
        catch (error) { results.push({ name, ok: false, error: error.stack || String(error) }); }
        finally { if (f) await f.dispose(); }
    }
    await check('initial read uses conservative settings and never schedules or starts work', async f => {
        same(f.reads.length, 1, 'one GET');
        await f.save(); await f.run(); same([f.saves.length, f.runs.length], [0, 0], 'no writes while loading');
        await f.reply(f.reads[0], initial());
        same([f.byId('enabled').checked, f.byId('intervalMinutes').value, f.byId('batchLimit').value], [false, 10, 1], 'safe defaults');
        same([f.saves.length, f.runs.length, f.timers.size], [0, 0, 0], 'idle page has no work or polling');
    });
    await check('independent schedule save sends exactly three fields without enabling a workflow', async f => {
        await f.reply(f.reads[0], initial());
        await f.change('intervalMinutes', 30); await f.change('batchLimit', 2); await f.change('enabled', true);
        await f.save(); same(f.saves[0].settings, { enabled: true, intervalMinutes: 30, batchLimit: 2 }, 'strict schedule only');
        await f.reply(f.saves[0], initial({ taskId: 8, enabled: true, status: 'PENDING', intervalMinutes: 30, batchLimit: 2 }));
        same(f.runs.length, 0, 'save never calls manual run'); same(f.successes, ['cloudDriveWorkflows.baiduBackfill.saved'], 'save result');
    });
    await check('refresh preserves unsaved schedule edits and leaves manual execution blocked', async f => {
        await f.reply(f.reads[0], initial()); await f.change('intervalMinutes', 40);
        await f.refresh(); await f.reply(f.reads[1], initial({ processed: 5, succeeded: 4, skipped: 1 }));
        same(f.byId('intervalMinutes').value, 40, 'draft survives'); same(f.byId('run').disabled, true, 'draft cannot run');
        await f.run(); same(f.runs.length, 0, 'direct handler checks draft');
    });
    await check('failed save keeps edits, reports safe text and supports the same retry', async f => {
        await f.reply(f.reads[0], initial()); await f.change('batchLimit', 3); await f.save(); await f.fail(f.saves[0]);
        same(f.byId('batchLimit').value, 3, 'failed draft kept'); same(f.byId('save').disabled, false, 'retry available');
        same(f.all('Alert').some(row => row.title === 'cloudDriveWorkflows.baiduBackfill.saveFailed'), true, 'safe error');
        same(f.text().includes('raw-cookie'), false, 'no upstream payload');
        await f.save(); same(f.saves[1].settings, f.saves[0].settings, 'same retry'); await f.reply(f.saves[1], initial({ batchLimit: 3 }));
    });
    await check('invalid integer schedule values never reach a write callback', async f => {
        await f.reply(f.reads[0], initial());
        for (const value of [0, 1441, 1.5, null]) { await f.change('intervalMinutes', value); await f.save(); }
        await f.change('intervalMinutes', 10);
        for (const value of [0, 6, 1.5, null]) { await f.change('batchLimit', value); await f.save(); }
        same(f.saves.length, 0, 'invalid scheduling blocked'); same(f.errors.length, 8, 'validation shown');
    });
    await check('double clicks are deduplicated and saved settings govern a manual small batch', async f => {
        await f.reply(f.reads[0], initial()); await f.change('batchLimit', 2);
        await f.act(() => { f.byId('save').onClick(); f.byId('save').onClick(); });
        same(f.saves.length, 1, 'one save'); same(f.byId('intervalMinutes').disabled, true, 'pending input locked');
        await f.reply(f.saves[0], initial({ batchLimit: 2 }));
        await f.act(() => { f.byId('run').onClick(); f.byId('run').onClick(); });
        same(f.runs.length, 1, 'one explicit run'); same(f.byId('save').disabled, true, 'run locks settings');
        await f.reply(f.runs[0], initial({ batchLimit: 2, processed: 2, succeeded: 2 }));
    });
    await check('workflow drafts block manual and automatic activation while pausing stays available', async f => {
        await f.reply(f.reads[0], initial()); await f.rerender({ workflowEdited: true });
        await f.run(); same(f.runs.length, 0, 'workflow draft guarded');
        await f.change('enabled', true); await f.save(); same(f.saves.length, 0, 'cannot auto activate old file choices');
        await f.change('enabled', false); await f.save(); same(f.saves.length, 1, 'pause still saves');
        await f.reply(f.saves[0], initial());
    });
    await check('manual gating uses saved hub, account, types and transfer/share settings', async f => {
        await f.reply(f.reads[0], initial());
        for (const patch of [{ hubEnabled: false }, { workflowBusy: true }, { workflow: { credentialConfigured: false } },
            { workflow: { torrentEnabled: false } }, { workflow: { transferEnabled: false } }, { workflow: { shareEnabled: false } }]) {
            await f.rerender(patch); same(f.byId('run').disabled, true, 'required gate'); await f.run();
            await f.rerender({ hubEnabled: true, workflowBusy: false, workflow: { credentialConfigured: true, torrentEnabled: true, transferEnabled: true, shareEnabled: true } });
        }
        same(f.runs.length, 0, 'all direct calls guarded');
        same(f.byId('run').disabled, false, 'existing resources need no discovery');
        await f.refresh(); await f.reply(f.reads[1], initial({ phase: 'SOURCE_SEARCH' }));
        same(f.byId('run').disabled, true, 'search needs discovery');
        await f.rerender({ workflow: { discoveryEnabled: true } }); same(f.byId('run').disabled, false, 'saved discovery permits search');
    });
    await check('blocked status uses fixed reason text and discards raw and unknown fields', async f => {
        await f.reply(f.reads[0], initial({ status: 'BLOCKED', blockedReason: 'BDUSS=raw-cookie', cookie: 'raw-cookie', currentMovieId: 'raw-cookie' }));
        same(f.byId('blocked').title, 'cloudDriveWorkflows.baiduBackfill.blocked.UNKNOWN', 'safe fallback');
        same(f.text().includes('raw-cookie'), false, 'no raw fields');
        const decoded = f.library.baiduBackfillStatus(initial({ blockedReason: 'CHILD_ACTION_REQUIRED', cookie: 'raw-cookie', currentMovieId: 'raw-cookie' }));
        same(decoded.blockedReason, 'CHILD_ACTION_REQUIRED', 'recognized reason'); same(Object.hasOwn(decoded, 'cookie'), false, 'credentials omitted');
        same(Object.hasOwn(decoded, 'currentMovieId'), false, 'unneeded identifiers omitted');
    });
    await check('running schedules poll with GET every ten seconds and stop at completion', async f => {
        await f.reply(f.reads[0], initial({ enabled: true, status: 'PENDING', nextRunAt: '2026-11-09T12:00:00.123456789' }));
        same(f.timers.size, 1, 'one timer'); same(Array.from(f.timers.values())[0].delay, 10000, 'ten seconds');
        await f.stepTimer(); same(f.reads.length, 2, 'read-only poll');
        await f.reply(f.reads[1], initial({ enabled: true, status: 'BLOCKED', blockedReason: 'SESSION_REQUIRED', nextRunAt: '2026-11-09T12:10' }));
        same(f.timers.size, 1, 'blocked auto schedule can recover');
        await f.stepTimer(); await f.reply(f.reads[2], initial({ enabled: true, status: 'COMPLETE', phase: 'COMPLETE', processed: 3, succeeded: 2, skipped: 1 }));
        same(f.timers.size, 0, 'completed stops'); same([f.saves.length, f.runs.length], [0, 0], 'poll never writes');
        same(f.byId('run').disabled, true, 'completed cannot rerun');
    });
    await check('manual RUNNING status polls even with automatic scheduling off then stops paused', async f => {
        await f.reply(f.reads[0], initial({ status: 'RUNNING' })); same(f.timers.size, 1, 'manual job monitored');
        await f.stepTimer(); await f.reply(f.reads[1], initial()); same(f.timers.size, 0, 'paused stops');
    });
    await check('read failure stops automatic polling and malformed metadata cannot enable writes', async f => {
        await f.reply(f.reads[0], initial({ enabled: true, status: 'PENDING' }));
        await f.stepTimer(); await f.fail(f.reads[1]); same(f.timers.size, 0, 'errors stop retries');
        same(f.all('Alert').some(row => row.title === 'cloudDriveWorkflows.baiduBackfill.readFailed'), true, 'fixed error');
        await f.refresh(); await f.reply(f.reads[2], initial({ processed: -1, cookie: 'raw-cookie' }));
        same(f.text().includes('raw-cookie'), false, 'invalid data not shown'); same(f.saves.length, 0, 'invalid read never schedules');
    });
    await check('a late initial read cannot overwrite a newer refresh', async f => {
        const old = f.reads[0]; await f.refresh(); const newer = f.reads[1];
        same(old.signal.aborted, true, 'old read aborted');
        await f.reply(newer, initial({ batchLimit: 3 })); await f.reply(old, initial({ batchLimit: 1 }));
        same(f.byId('batchLimit').value, 3, 'new response kept');
    });
    await check('saving supersedes an in-flight read and ignores its late response', async f => {
        await f.reply(f.reads[0], initial()); await f.refresh(); const old = f.reads[1];
        await f.change('intervalMinutes', 60); await f.save(); same(old.signal.aborted, true, 'read cancelled on write');
        await f.reply(f.saves[0], initial({ intervalMinutes: 60 })); await f.reply(old, initial({ intervalMinutes: 10 }));
        same(f.byId('intervalMinutes').value, 60, 'saved schedule kept'); same(f.byId('run').disabled, false, 'draft cleared');
    });
    await check('uncertain manual run failures require a successful status refresh before retrying', async f => {
        await f.reply(f.reads[0], initial()); await f.run(); await f.fail(f.runs[0]);
        same(f.byId('run').disabled, true, 'uncertain run cannot repeat'); await f.run(); same(f.runs.length, 1, 'handler guarded');
        await f.refresh(); await f.reply(f.reads[1], initial({ processed: 1, succeeded: 1 }));
        same(f.byId('run').disabled, false, 'successful refresh unlocks next batch');
    });
    await check('callback or locale changes during a save cannot leave controls permanently locked', async f => {
        await f.reply(f.reads[0], initial()); await f.change('batchLimit', 4); await f.save(); const pending = f.saves[0];
        let extraReads = 0;
        await f.rerender({ onReadStatus: async () => { extraReads += 1; return initial(); } });
        same(pending.signal.aborted, false, 'active mutation remains valid'); same(extraReads, 0, 'no competing read while saving');
        await f.reply(pending, initial({ batchLimit: 4 }));
        same(f.byId('save').disabled, false, 'save unlocks'); same(f.byId('batchLimit').value, 4, 'accepted result kept');
    });
    await check('unmount aborts mutations and prevents late messages or polling', async f => {
        await f.reply(f.reads[0], initial()); await f.change('enabled', true); await f.save();
        const pending = f.saves[0]; await f.unmount(); same(pending.signal.aborted, true, 'mutation aborted');
        await f.reply(pending, initial({ enabled: true, status: 'PENDING' }));
        same(f.successes.length, 0, 'no late success'); same(f.timers.size, 0, 'no late polling');
    });
    return results;
}

test('Baidu backfill UI: real React scheduling, saved choices and request lifecycle', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(backfillSuite, {
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
        baiduBackfill: 'src/components/admin/BaiduBackfillControls.tsx',
    });
    assert.equal(results.length, 17);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
