import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

async function backfillSuite({ React, createRoot, loadPage, loadModule }) {
    const results = [];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + ': ' + JSON.stringify(actual));
    };
    const initial = patch => ({ revision: 0, deferredCount: 0, deferredItems: [], taskId: null, enabled: false, status: 'PAUSED', phase: 'GYING_TORRENTS', intervalMinutes: 10, batchLimit: 1,
        processed: 0, succeeded: 0, skipped: 0, failed: 0, nextRunAt: null, blockedReason: null,
        currentIdentityId: null, currentMovieId: null, currentGyingType: null, currentGyingId: null, currentChildId: null,
        currentResourceIds: [], currentSeedNames: [], currentSeedCount: 0, currentAction: 'IDLE',
        currentChildStatus: null, currentChildError: null, lastResult: null, legacyTaskId: null, ...patch });
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
        const root = createRoot(container); const reads = [], saves = [], runs = [], defers = [], errors = [], successes = [];
        const read = signal => { const call = { signal, ...deferred() }; reads.push(call); return call.promise; };
        const save = (settings, signal) => { const call = { settings, signal, ...deferred() }; saves.push(call); return call.promise; };
        const run = signal => { const call = { signal, ...deferred() }; runs.push(call); return call.promise; };
        const deferUnknown = (request, signal) => { const call = { request, signal, ...deferred() }; defers.push(call); return call.promise; };
        const capture = name => function Fixture(props) {
            return React.createElement('div', { 'data-fixture': name, ref: node => { if (node) node.fixtureProps = props; } }, props.children);
        };
        const Form = capture('Form'); Form.Item = capture('Form.Item');
        const mocks = {
            antd: { Form, ...Object.fromEntries(['Alert', 'Button', 'Col', 'InputNumber', 'Row', 'Space', 'Switch', 'Tag', 'Modal'].map(name => [name, capture(name)])),
                App: { useApp: () => ({ message: { error: value => errors.push(value), success: value => successes.push(value) } }) },
                Typography: { Text: capture('Text'), Paragraph: capture('Paragraph') } },
            '@ant-design/icons': { CloudSyncOutlined: () => null, ReloadOutlined: () => null, SaveOutlined: () => null },
            'react-i18next': { useTranslation: () => ({ t: (key, values) => key + (values ? JSON.stringify(values) : '') }) },
        };
        const library = loadModule('driveWorkflows', mocks);
        mocks['@/lib/cloudDriveWorkflows'] = library;
        const Controls = loadPage('baiduBackfill', mocks);
        let props = { workflow: { provider: 'BAIDU', supported: true, status: 'AVAILABLE', discoveryEnabled: false,
            videoEnabled: false, torrentEnabled: true, transferEnabled: true, gyingPublishEnabled: false, shareEnabled: true,
            credentialConfigured: true, savePath: '/Baidu', batchLimit: 5 },
            workflowEdited: false, workflowBusy: false, hubEnabled: true, onReadStatus: read, onSaveSettings: save, onRun: run, onDeferUnknown: deferUnknown };
        const act = async action => { await React.act(async () => { action(); }); };
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const byId = id => ['Button', 'Switch', 'InputNumber', 'Text', 'Alert', 'Modal'].flatMap(all).find(row => row['data-testid'] === 'baidu-backfill-' + id);
        const f = { reads, saves, runs, defers, timers, errors, successes, all, byId, act, library,
            change: (key, value) => act(() => byId(key).onChange(value)),
            save: () => act(() => byId('save').onClick()), run: () => act(() => byId('run').onClick()),
            refresh: () => act(() => byId('refresh').onClick()),
            openDefer: () => act(() => byId('defer-unknown').onClick()),
            confirmDefer: () => act(() => byId('defer-confirm').onOk()),
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
    await check('torrent backfill requires saved torrent, account and transfer/share settings independently of video or discovery', async f => {
        await f.reply(f.reads[0], initial());
        for (const patch of [{ hubEnabled: false }, { workflowBusy: true }, { workflow: { credentialConfigured: false } },
            { workflow: { torrentEnabled: false, videoEnabled: true } }, { workflow: { transferEnabled: false } }, { workflow: { shareEnabled: false } }]) {
            await f.rerender(patch); same(f.byId('run').disabled, true, 'required gate'); await f.run();
            await f.rerender({ hubEnabled: true, workflowBusy: false, workflow: { credentialConfigured: true, torrentEnabled: true,
                videoEnabled: false, transferEnabled: true, shareEnabled: true } });
        }
        same(f.runs.length, 0, 'all direct calls guarded');
        same(f.byId('run').disabled, false, 'confirmed library IDs need no video or discovery');
        await f.rerender({ workflow: { discoveryEnabled: true, videoEnabled: true, gyingPublishEnabled: true } });
        same(f.byId('run').disabled, false, 'other settings do not change this scope');
        await f.rerender({ workflow: { torrentEnabled: false } });
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.blocked.TORRENT_DISABLED'), true, 'specific torrent setting shown');
    });
    await check('current seed verification shows specific library IDs, file names, action and archive task', async f => {
        await f.reply(f.reads[0], initial({ taskId: 72, status: 'BLOCKED', blockedReason: 'CHILD_UNKNOWN',
            currentIdentityId: 14, currentMovieId: 'movie-100', currentGyingType: 'mv', currentGyingId: 'G100',
            currentChildId: 481, currentResourceIds: [610, 611], currentSeedNames: ['1080P_sub_123.torrent', '1080P_sub_123.txt'],
            currentSeedCount: 1, currentAction: 'UPLOAD_TORRENT', currentChildStatus: 'UNKNOWN', currentChildError: 'BaiduActionException' }));
        for (const value of ['14', 'movie-100', 'mv/G100', '481', '610, 611', '1080P_sub_123.torrent', '1080P_sub_123.txt']) {
            same(f.text().includes(value), true, 'specific current metadata ' + value);
        }
        same(f.byId('current-action').children[2], 'cloudDriveWorkflows.baiduBackfill.actions.UPLOAD_TORRENT', 'current upload stage');
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.childStates.UNKNOWN'), true, 'submission uncertainty shown');
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.childErrors.BaiduActionException'), true, 'safe error category');
        same(f.byId('blocked').title, 'cloudDriveWorkflows.baiduBackfill.blocked.CHILD_UNKNOWN', 'specific step explanation');
    });
    await check('historical self-share review is retained as information and cannot block a new torrent batch', async f => {
        await f.reply(f.reads[0], initial({ legacyTaskId: 91, legacyStatus: 'UNKNOWN', legacyBlockedReason: 'CHILD_REVIEW_REQUIRED',
            legacySelfShare: 'raw-cookie-never-display' }));
        same(f.byId('legacy-task').type, 'info', 'historical task shown separately');
        same(f.byId('legacy-task').title.includes('91'), true, 'historical ID visible');
        same(Boolean(f.byId('blocked')), false, 'no current blocking reason inherited');
        same(f.byId('run').disabled, false, 'new seed workflow available');
        same(f.text().includes('raw-cookie'), false, 'raw historical payload omitted');
        await f.run(); same(f.runs.length, 1, 'new batch callback reached'); await f.reply(f.runs[0], initial({ legacyTaskId: 91 }));
    });
    await check('blocked metadata uses fixed reason and action text and discards raw unknown fields', async f => {
        await f.reply(f.reads[0], initial({ status: 'BLOCKED', blockedReason: 'BDUSS=raw-cookie', cookie: 'raw-cookie',
            currentAction: 'raw-cookie', currentChildStatus: 'raw-cookie', currentChildError: 'raw-cookie', lastResult: 'raw-cookie' }));
        same(f.byId('blocked').title, 'cloudDriveWorkflows.baiduBackfill.blocked.UNKNOWN', 'safe reason fallback');
        same(f.text().includes('raw-cookie'), false, 'no raw codes or fields');
        const decoded = f.library.baiduBackfillStatus(initial({ blockedReason: 'CHILD_ACTION_REQUIRED', cookie: 'raw-cookie',
            currentIdentityId: 2, currentMovieId: 'movie-2', currentGyingType: 'tv', currentGyingId: 'T2' }));
        same(decoded.blockedReason, 'CHILD_ACTION_REQUIRED', 'recognized reason'); same(Object.hasOwn(decoded, 'cookie'), false, 'credentials omitted');
        same([decoded.currentIdentityId, decoded.currentMovieId, decoded.currentGyingType, decoded.currentGyingId], [2, 'movie-2', 'tv', 'T2'], 'safe identifiers retained');
    });
    await check('malformed current seed metadata and retired search phases are rejected', async f => {
        await f.reply(f.reads[0], initial());
        for (const patch of [{ currentIdentityId: 0 }, { currentChildId: -1 }, { legacyTaskId: 0 },
            { currentResourceIds: [null] }, { currentResourceIds: [1, 1] }, { currentResourceIds: [1, 2, 3, 4, 5] },
            { currentSeedNames: [null] }, { currentSeedNames: ['x'.repeat(256)] }, { currentSeedNames: ['bad\nname.torrent'] },
            { currentSeedNames: ['a', 'b', 'c', 'd', 'e'] }, { currentSeedCount: 3 }, { currentSeedCount: 1.5 },
            { currentGyingType: 'movie' }, { currentGyingId: 'mv/G1' }, { currentMovieId: 'x'.repeat(65) },
            { currentMovieId: 'bad\nID' }, { phase: 'EXISTING_TASKS' }, { phase: 'SOURCE_SEARCH' }]) {
            let rejected = false;
            try { f.library.baiduBackfillStatus(initial(patch)); } catch { rejected = true; }
            same(rejected, true, 'invalid current metadata rejected ' + Object.keys(patch)[0]);
        }
        same([f.saves.length, f.runs.length], [0, 0], 'validation never triggers work');
    });
    await check('completed and skipped seed results remain readable without a generic review prompt', async f => {
        await f.reply(f.reads[0], initial({ processed: 1, skipped: 1, lastResult: 'NO_ELIGIBLE_GYING_TORRENTS' }));
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.results.NO_ELIGIBLE_GYING_TORRENTS'), true, 'specific skip result');
        same(Boolean(f.byId('blocked')), false, 'missing seed is not a manual review');
        await f.refresh(); await f.reply(f.reads[1], initial({ status: 'COMPLETE', phase: 'COMPLETE', lastResult: 'TORRENTS_ARCHIVED' }));
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.results.TORRENTS_ARCHIVED'), true, 'success result');
        same(f.byId('run').disabled, true, 'completed stops work');
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
    const unknown = patch => initial({ taskId: 72, revision: 4, status: 'BLOCKED', blockedReason: 'CHILD_UNKNOWN',
        currentIdentityId: 14, currentMovieId: 'movie-100', currentGyingType: 'mv', currentGyingId: 'G100',
        currentChildId: 481, currentChildStatus: 'UNKNOWN', currentAction: 'UPLOAD_MAGNET_TEXT', ...patch });
    const receipt = patch => ({ identityId: 14, movieId: 'movie-100', gyingType: 'mv', gyingId: 'G100', childId: 481,
        reason: 'CHILD_UNKNOWN', action: 'UPLOAD_MAGNET_TEXT', resourceIds: [610], seedNames: ['1080P_sub_123.txt'],
        seedCount: 1, childPayloadSha256: 'a'.repeat(64), deferredAt: '2026-11-10T00:15:30.123456',
        origin: 'ADMINISTRATOR', administratorId: 2, ...patch });
    await check('unknown isolation requires explicit confirmation and submits the exact fresh parent revision', async f => {
        await f.reply(f.reads[0], unknown());
        same(f.byId('defer-unknown').disabled, false, 'unknown can be preserved');
        await f.openDefer(); same(f.defers.length, 0, 'opening confirmation has no write');
        same(f.byId('defer-confirm').open, true, 'confirmation visible');
        await f.act(() => { f.byId('defer-confirm').onOk(); f.byId('defer-confirm').onOk(); });
        same(f.defers.length, 1, 'one CAS request');
        same(f.defers[0].request, { taskId: 72, childId: 481, revision: 4 }, 'exact safe request');
        await f.run(); await f.save(); same([f.runs.length, f.saves.length], [0, 0], 'other writes locked');
        await f.reply(f.defers[0], initial({ taskId: 72, revision: 5, processed: 1, skipped: 1, deferredCount: 1,
            deferredItems: [receipt()], lastResult: 'UNKNOWN_DEFERRED', status: 'PENDING' }));
        same(f.byId('defer-confirm').open, false, 'confirmation closed');
        same(f.successes, ['cloudDriveWorkflows.baiduBackfill.deferredSaved'], 'preservation confirmed');
        same(f.text().includes('481'), true, 'original task visible in review list');
        same(f.text().includes('cloudDriveWorkflows.baiduBackfill.results.UNKNOWN_DEFERRED'), true, 'not archive success');
        same(f.defers.length, 1, 'no repeat');
    });
    await check('paused unknown archive remains preservable with hub and upload stages disabled', async f => {
        await f.reply(f.reads[0], unknown({ status: 'PAUSED', blockedReason: null, enabled: false, revision: 6 }));
        await f.rerender({ hubEnabled: false, workflow: { transferEnabled: false, shareEnabled: false, torrentEnabled: false } });
        same(f.byId('defer-unknown').disabled, false, 'local preservation is available after pause');
        same(f.byId('run').disabled, true, 'external archive work remains gated');
        await f.openDefer(); await f.confirmDefer();
        same(f.defers[0].request, { taskId: 72, childId: 481, revision: 6 }, 'paused current revision');
        same([f.runs.length, f.saves.length], [0, 0], 'no scheduler activation or cloud submission');
        await f.reply(f.defers[0], initial({ taskId: 72, revision: 7, status: 'PAUSED', enabled: false,
            processed: 1, skipped: 1, deferredCount: 1, deferredItems: [receipt()], lastResult: 'UNKNOWN_DEFERRED' }));
        same(f.byId('enabled').checked, false, 'pause remains saved');
        same(f.byId('defer-confirm').open, false, 'confirmed preservation completes');
    });
    await check('running and action-required children cannot be isolated through the UI', async f => {
        for (const state of ['RUNNING', 'ACTION_REQUIRED']) {
            if (f.reads.length === 1) await f.reply(f.reads[0], unknown({ currentChildStatus: state, blockedReason: state === 'RUNNING' ? 'CHILD_RUNNING' : 'CHILD_ACTION_REQUIRED' }));
            else await f.reply(f.reads.at(-1), unknown({ currentChildStatus: state, blockedReason: 'CHILD_ACTION_REQUIRED' }));
            same(Boolean(f.byId('defer-unknown')), false, 'no isolation action for ' + state);
            await f.confirmDefer(); same(f.defers.length, 0, 'handler guarded');
            await f.refresh();
        }
    });
    await check('changed status revision invalidates an open isolation confirmation', async f => {
        await f.reply(f.reads[0], unknown()); await f.openDefer();
        await f.refresh(); await f.reply(f.reads[1], unknown({ revision: 5 }));
        same(f.byId('defer-confirm').okButtonProps.disabled, true, 'stale confirmation disabled');
        await f.confirmDefer(); same(f.defers.length, 0, 'stale handler cannot write');
        same(f.all('Alert').some(row => row.title === 'cloudDriveWorkflows.baiduBackfill.deferChanged'), true, 'fresh status required');
    });
    await check('uncertain isolation failure requires a fresh status read before another confirmation', async f => {
        await f.reply(f.reads[0], unknown()); await f.openDefer(); await f.confirmDefer(); await f.fail(f.defers[0]);
        same(f.byId('defer-confirm').open, false, 'unknown response closes confirmation');
        same(f.byId('defer-unknown').disabled, true, 'cannot replay');
        await f.openDefer(); await f.confirmDefer(); same(f.defers.length, 1, 'direct retry blocked');
        same(f.text().includes('raw-cookie'), false, 'fixed error only');
        await f.refresh(); await f.reply(f.reads[1], unknown({ revision: 8 })); await f.openDefer(); await f.confirmDefer();
        same(f.defers[1].request.revision, 8, 'fresh CAS revision');
    });
    await check('bounded deferred receipts preserve useful metadata while dropping raw upstream fields', async f => {
        const decoded = f.library.baiduBackfillStatus(initial({ deferredCount: 1, deferredItems: [receipt({ cookie: 'raw-cookie', action: 'raw-cookie' })] }));
        same(decoded.deferredItems[0].action, 'UNKNOWN', 'fixed action fallback');
        same(Object.hasOwn(decoded.deferredItems[0], 'cookie'), false, 'secret fields omitted');
        for (const patch of [{ revision: -1 }, { revision: null }, { deferredCount: 2001 }, { deferredCount: 0, deferredItems: [receipt()] },
            { deferredCount: 21, deferredItems: Array.from({ length: 21 }, () => receipt()) },
            { deferredCount: 1, deferredItems: [receipt({ childId: 0 })] }, { deferredCount: 1, deferredItems: [receipt({ reason: 'FAILED' })] },
            { deferredCount: 1, deferredItems: [receipt({ childPayloadSha256: 'not-a-hash' })] },
            { deferredCount: 1, deferredItems: [receipt({ resourceIds: [610, 610] })] },
            { deferredCount: 1, deferredItems: [receipt({ deferredAt: 'invalid' })] }]) {
            let rejected = false; try { f.library.baiduBackfillStatus(initial(patch)); } catch { rejected = true; }
            same(rejected, true, 'invalid receipt rejected ' + Object.keys(patch)[0]);
        }
        same(f.defers.length, 0, 'parser never changes task state');
    });
    await check('unmount aborts isolation and suppresses late status or success', async f => {
        await f.reply(f.reads[0], unknown()); await f.openDefer(); await f.confirmDefer();
        const pending = f.defers[0]; await f.unmount(); same(pending.signal.aborted, true, 'request aborted');
        await f.reply(pending, initial({ taskId: 72, revision: 5, deferredCount: 1, deferredItems: [receipt()] }));
        same(f.successes.length, 0, 'no late message'); same(f.timers.size, 0, 'no late poll');
    });
    return results;
}

test('Baidu backfill UI: real React scheduling, saved choices and request lifecycle', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(backfillSuite, {
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
        baiduBackfill: 'src/components/admin/BaiduBackfillControls.tsx',
    });
    assert.equal(results.length, 28);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
