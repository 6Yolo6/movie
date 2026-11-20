import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

async function duplicateSuite({ React, createRoot, loadPage }) {
    const results = [];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + ': ' + JSON.stringify(actual));
    };
    async function fixture() {
        const container = document.createElement('div'); document.body.append(container);
        const root = createRoot(container); const calls = [], translations = [];
        const capture = name => function FixtureComponent(props) { return React.createElement('div', { 'data-fixture': name,
            ref: node => { if (node) node.fixtureProps = props; } }, props.children); };
        const t = (key, values) => { translations.push({ key, values }); return key; };
        const api = (url, options, ...unexpected) => {
            if (unexpected.length > 0) throw new Error('API accepts path and RequestInit only');
            let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
            calls.push({ url, options, resolve, reject }); return promise;
        };
        const Page = loadPage('scanner', {
            antd: { ...Object.fromEntries(['Alert', 'Button', 'Space', 'Table', 'Tag', 'Modal', 'Select', 'Checkbox'].map(name => [name, capture(name)])),
                Typography: { Text: capture('Text') } },
            '@ant-design/icons': { SearchOutlined: () => null, MergeCellsOutlined: () => null },
            'react-i18next': { useTranslation: () => ({ t }) },
            '@/lib/api': { api }, '@/store/authStore': { useAuthStore: () => ({ token: 'fixture-admin' }) },
        });
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const byId = id => Array.from(container.querySelectorAll('[data-fixture]'), node => node.fixtureProps).find(props => props['data-testid'] === id);
        const act = action => React.act(async () => { action(); });
        const reply = (call, data, status = 200) => act(() => call.resolve(new Response(JSON.stringify({ data }), { status })));
        const item = { fromMovieId: 'tmdb_tv_247718', fromTitle: 'MobLand', fromResources: 0,
            toMovieId: '1YKW', toTitle: '黑帮领地 第一季', toResources: 8,
            category: 'tv', year: 2025, season: 1, evidence: 'NAME_AND_PEOPLE', reviewRequired: true };
        const second = { ...item, fromMovieId: 'tmdb_movie_2', toMovieId: 'gying_2', fromTitle: 'Second', toTitle: '第二部', category: 'mv', season: 0 };
        const result = extra => ({ scanMode: 'FULL_LIBRARY', dryRun: true, mergeEnabled: false, complete: true,
            scannedMovies: 15366, candidates: 144, conflicts: 6, excludedMovies: 12,
            page: 1, pageSize: 100, items: [item], ...extra });
        const review = (pairs = [{ fromMovieId: item.fromMovieId, toMovieId: item.toMovieId }], patch = {}) => ({
            reviewId: 'review-fixture-1', expiresAt: new Date(Date.now() + 600000).toISOString(), canMerge: true,
            items: pairs.map(pair => ({ ...pair, fromTitle: 'Source', toTitle: 'Retained', eligible: true, blockers: [],
                impact: [{ reference: 'resource_link', fromCount: 0, toCount: 8, action: 'MOVE_REFERENCES' },
                    { reference: 'user_favorite', fromCount: 3, toCount: 4, action: 'MERGE_FAVORITES_PRESERVE_HISTORY' }] })), ...patch,
        });
        const click = id => act(() => byId(id).onClick());
        const select = (row = item, checked = true) => act(() => byId('duplicate-candidates').rowSelection.onSelect(row, checked));
        const choose = (row, id) => act(() => byId('duplicate-candidates').columns.find(column => column.key === 'retain').render(null, row).props.onChange(id));
        const startReview = async (patch = {}) => {
            await click('duplicate-scan'); await reply(calls[0], result());
            await select(); await click('duplicate-preview'); const data = review(undefined, patch); await reply(calls[1], data); return data;
        };
        const confirm = pair => act(() => byId('duplicate-confirm-' + (pair || item).fromMovieId + ':' + (pair || item).toMovieId).onChange({ target: { checked: true } }));
        const mergeReply = call => {
            const body = JSON.parse(call.options.body);
            const selected = JSON.parse(calls.find(row => row.url.endsWith('/preview')).options.body).pairs;
            return reply(call, { ...body, status: 'SUCCEEDED', merged: selected.length, items: selected, replayed: false });
        };
        const candidates = (size = 1) => Array.from({ length: size }, (_, index) => ({ ...second,
            fromMovieId: 'bulk-source-' + index, toMovieId: 'bulk-retained-' + index,
            fromTitle: 'Source ' + index, toTitle: 'Retained ' + index }));
        let bulkRows = [];
        const replyReview = async (call, patch = {}) => {
            const requested = JSON.parse(call.options.body).pairs;
            const data = review(requested, { reviewId: 'bulk-review-' + calls.indexOf(call), ...patch });
            data.items = data.items.map(item => {
                const row = bulkRows.find(row => row.fromMovieId === item.fromMovieId && row.toMovieId === item.toMovieId);
                return row ? { ...item, fromTitle: row.fromTitle, toTitle: row.toTitle } : item;
            });
            await reply(call, data); return data;
        };
        const startBulk = async (rows = [item], patches = []) => {
            bulkRows = rows;
            await click('duplicate-deduplicate');
            for (let page = 1; page <= Math.max(1, Math.ceil(rows.length / 100)); page++) {
                await reply(calls[page - 1], result({ candidates: rows.length, page, items: rows.slice((page - 1) * 100, page * 100) }));
            }
            let index = Math.max(1, Math.ceil(rows.length / 100)), patchIndex = 0;
            while (calls[index]?.url.endsWith('/preview')) await replyReview(calls[index++], patches[patchIndex++] || {});
        };
        const confirmBulk = () => act(() => byId('duplicate-bulk-confirm').onChange({ target: { checked: true } }));
        const bulkMergeReply = call => {
            const body = JSON.parse(call.options.body);
            const previewCall = calls.find(row => row.url.endsWith('/preview') && 'bulk-review-' + calls.indexOf(row) === body.reviewId);
            const selected = JSON.parse(previewCall.options.body).pairs;
            return reply(call, { ...body, status: 'SUCCEEDED', merged: selected.length, items: selected, replayed: false });
        };
        await act(() => root.render(React.createElement(Page)));
        return { all, byId, calls, translations, act, reply, item, second, result, review, click, select, choose, startReview, confirm, mergeReply, candidates, replyReview, startBulk, confirmBulk, bulkMergeReply,
            dispose: async () => { await act(() => root.unmount()); container.remove(); } };
    }
    const check = async (name, run) => {
        const f = await fixture();
        try { await run(f); results.push({ name, ok: true }); }
        catch (error) { results.push({ name, ok: false, error: error.stack || String(error) }); }
        finally { await f.dispose(); }
    };
    await check('scan is explicit, authenticated, full-library and deduplicated', async f => {
        same(f.calls.length, 0, 'no automatic scan');
        await f.act(() => { f.byId('duplicate-scan').onClick(); f.byId('duplicate-scan').onClick(); });
        same(f.calls.length, 1, 'one request');
        same(f.calls[0].url, '/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100&page=1', 'full scan');
        same(f.calls[0].options.method, 'POST', 'request');
        same(new Headers(f.calls[0].options.headers).get('Authorization'), 'Bearer fixture-admin', 'admin auth');
    });
    await check('scan displays both records, resources and a visible review action with no default selection', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result());
        const table = f.byId('duplicate-candidates');
        same([table.dataSource[0].fromResources, table.dataSource[0].toResources], [0, 8], 'resource counts');
        same(table.pagination.total, 144, 'all candidate pairs');
        same(table.rowSelection.selectedRowKeys, [], 'nothing selected');
        same(table.rowSelection.hideSelectAll, true, 'no automatic whole-library selection');
        same(f.byId('duplicate-preview').disabled, true, 'review requires a choice');
        await f.click('duplicate-preview'); same(f.calls.length, 1, 'handler also guards');
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.summary' && row.values.scanned === 15366), true, 'full count');
    });
    await check('candidate paging preserves explicit choices across pages without a merge request', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result({ candidates: 250 })); await f.select();
        await f.act(() => f.byId('duplicate-candidates').pagination.onChange(2));
        same(f.calls[1].url.endsWith('dryRun=true&limit=100&page=2'), true, 'next page');
        await f.reply(f.calls[1], f.result({ candidates: 250, page: 2, items: [f.second] })); await f.select(f.second);
        same(f.byId('duplicate-candidates').rowSelection.selectedRowKeys.length, 2, 'two deliberate selections');
        await f.click('duplicate-preview');
        same(JSON.parse(f.calls[2].options.body).pairs, [
            { fromMovieId: f.item.fromMovieId, toMovieId: f.item.toMovieId }, { fromMovieId: f.second.fromMovieId, toMovieId: f.second.toMovieId },
        ], 'cross-page review');
        same(f.calls.some(call => call.url.endsWith('/merge')), false, 'no merge');
    });
    await check('incomplete scan blocks selecting and previewing candidates', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result({ complete: false }));
        same(f.byId('duplicate-scan-summary').type, 'warning', 'partial result');
        same(f.byId('duplicate-candidates').rowSelection.getCheckboxProps().disabled, true, 'selection disabled');
        await f.select(); await f.click('duplicate-preview'); same(f.calls.length, 1, 'handlers guarded');
    });
    await check('scan failure is fixed text and explicit retry can show a confirmed empty result', async f => {
        await f.click('duplicate-scan'); await f.act(() => f.calls[0].reject(new Error('raw credential')));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.failed', 'safe error');
        same(f.all('Table').length, 0, 'no invented zero');
        await f.click('duplicate-scan'); await f.reply(f.calls[1], f.result({ candidates: 0, items: [] }));
        same(f.byId('duplicate-candidates').pagination.total, 0, 'confirmed zero');
    });
    await check('legacy or malformed scan responses cannot masquerade as empty candidates', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], { candidates: 0, items: [], dryRun: true });
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.failed', 'old result rejected');
        await f.click('duplicate-scan'); await f.reply(f.calls[1], f.result({ mergeEnabled: true }));
        same(f.all('Table').length, 0, 'unsafe scan rejected');
    });
    await check('retained record choice controls the server preview direction without mutating anything', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result()); await f.select(); await f.choose(f.item, f.item.fromMovieId);
        await f.act(() => { f.byId('duplicate-preview').onClick(); f.byId('duplicate-preview').onClick(); });
        same(f.calls.length, 2, 'preview double click deduplicated');
        same(f.calls[1].url, '/api/admin/resource-hub/duplicates/preview', 'preview endpoint');
        same(JSON.parse(f.calls[1].options.body), { pairs: [{ fromMovieId: f.item.toMovieId, toMovieId: f.item.fromMovieId }] }, 'chosen retained record');
        same(f.calls.some(call => call.url.endsWith('/merge')), false, 'preview only');
    });
    await check('overlapping movie IDs and batches over twenty pairs cannot reach preview', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result()); await f.select();
        await f.select({ ...f.second, fromMovieId: f.item.toMovieId });
        same(f.byId('duplicate-selection-warning').title, 'resourceHubDuplicates.overlapping', 'overlap shown');
        await f.click('duplicate-preview'); same(f.calls.length, 1, 'overlap blocked');
        await f.select({ ...f.second, fromMovieId: f.item.toMovieId }, false);
        for (let index = 0; index < 20; index++) await f.select({ ...f.second, fromMovieId: 'from-' + index, toMovieId: 'to-' + index });
        same(f.byId('duplicate-selection-warning').title, 'resourceHubDuplicates.tooMany', 'limit shown');
        await f.click('duplicate-preview'); same(f.calls.length, 1, 'limit blocked');
    });
    await check('server impact is visible and every reviewed pair needs explicit confirmation before merge', async f => {
        const review = await f.startReview();
        same(f.byId('duplicate-review').open, true, 'review dialog');
        same(f.byId('duplicate-impact-' + f.item.fromMovieId + ':' + f.item.toMovieId).dataSource[1].reference, 'user_favorite', 'favorite impact');
        same(f.byId('duplicate-merge').disabled, true, 'review not confirmed');
        await f.click('duplicate-merge'); same(f.calls.length, 2, 'handler guarded');
        await f.confirm(); await f.act(() => { f.byId('duplicate-merge').onClick(); f.byId('duplicate-merge').onClick(); });
        same(f.calls.length, 3, 'one confirmed merge');
        const body = JSON.parse(f.calls[2].options.body);
        same([body.reviewId, body.confirmed, Object.keys(body).sort()], [review.reviewId, true, ['confirmed', 'idempotencyKey', 'reviewId']], 'review-based merge only');
        same(typeof body.idempotencyKey, 'string', 'durable operation ID');
        same(new Headers(f.calls[2].options.headers).get('Authorization'), 'Bearer fixture-admin', 'merge auth');
    });
    await check('active or unknown tasks remain blocked and cannot be merged even via direct handlers', async f => {
        const review = f.review(); review.canMerge = false; review.items[0].eligible = false;
        review.items[0].blockers = [{ code: 'ACTIVE_OR_UNKNOWN_TASK', reference: 'baidu_transfer_task', count: 1 }];
        await f.startReview(review);
        same(f.byId('duplicate-blockers-' + f.item.fromMovieId + ':' + f.item.toMovieId).type, 'warning', 'blocker shown');
        same(f.byId('duplicate-confirm-' + f.item.fromMovieId + ':' + f.item.toMovieId).disabled, true, 'cannot confirm');
        await f.confirm(); await f.click('duplicate-merge'); same(f.calls.length, 2, 'blocked merge guarded');
    });
    await check('mismatched or malformed preview cannot grant merge permission', async f => {
        await f.click('duplicate-scan'); await f.reply(f.calls[0], f.result()); await f.select(); await f.click('duplicate-preview');
        await f.reply(f.calls[1], f.review([{ fromMovieId: 'unexpected', toMovieId: f.item.toMovieId }]));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.previewFailed', 'echo mismatch rejected');
        same(f.byId('duplicate-review').open, false, 'no unsafe review');
        same(f.byId('duplicate-candidates').rowSelection.selectedRowKeys.length, 1, 'selection retained');
    });
    await check('expired review cannot create a fresh merge request', async f => {
        await f.startReview({ expiresAt: new Date(Date.now() - 1).toISOString() });
        same(f.byId('duplicate-merge').disabled, true, 'expired action disabled');
        await f.confirm(); await f.click('duplicate-merge'); same(f.calls.length, 2, 'expired handler guarded');
    });
    await check('changing a retained record invalidates its prior review and confirmations', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-review-cancel');
        await f.choose(f.item, f.item.fromMovieId);
        same(f.byId('duplicate-review').open, false, 'review invalidated');
        await f.click('duplicate-preview');
        same(JSON.parse(f.calls[2].options.body).pairs[0].toMovieId, f.item.fromMovieId, 'fresh direction');
    });
    await check('successful merge clears reviewed selections and refreshes full-library candidates', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-merge'); await f.mergeReply(f.calls[2]);
        same(f.byId('duplicate-review').open, false, 'review closed');
        same(f.byId('duplicate-merge-success').type, 'success', 'verified result');
        same(f.calls[3].url.endsWith('dryRun=true&limit=100&page=1'), true, 'fresh scan');
        await f.reply(f.calls[3], f.result({ candidates: 143, items: [] }));
        same(f.byId('duplicate-candidates').rowSelection.selectedRowKeys, [], 'selection cleared');
    });
    await check('stale server review preserves choices but requires a fresh preview before retrying', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-merge'); await f.reply(f.calls[2], null, 409);
        same(f.byId('duplicate-review').open, false, 'stale review removed');
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.reviewChanged', 'refresh instruction');
        same(f.byId('duplicate-candidates').rowSelection.selectedRowKeys.length, 1, 'choice kept');
        await f.click('duplicate-preview'); same(f.calls[3].url.endsWith('/preview'), true, 'new preview not blind merge');
    });
    await check('uncertain merge result locks selection and retries only the identical operation ID', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-merge'); const original = f.calls[2];
        await f.act(() => original.reject(new Error('raw upstream cookie')));
        same(f.byId('duplicate-scan').disabled, true, 'new scan locked');
        same(f.byId('duplicate-preview').disabled, true, 'new review locked');
        same(f.byId('duplicate-review').closable, false, 'cannot discard unknown operation');
        same(f.byId('duplicate-merge').children, 'resourceHubDuplicates.retryMerge', 'explicit safe retry');
        await f.click('duplicate-scan'); await f.select(f.item, false); same(f.calls.length, 3, 'no new operations');
        await f.click('duplicate-merge'); same(f.calls[3].options.body, original.options.body, 'identical review and key');
        await f.mergeReply(f.calls[3]); same(f.calls[4].url.endsWith('page=1'), true, 'success after retry refreshes');
    });
    await check('unexpected merge payload is uncertain and never displayed as success', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-merge');
        const body = JSON.parse(f.calls[2].options.body);
        await f.reply(f.calls[2], { ...body, status: 'SUCCEEDED', merged: 0, items: [], replayed: false });
        same(f.byId('duplicate-merge-success'), undefined, 'no invented success');
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.mergeUncertain', 'safe uncertainty');
        same(f.calls.length, 3, 'no automatic retry');
    });
    await check('unmount aborts pending mutations and ignores their late success', async f => {
        await f.startReview(); await f.confirm(); await f.click('duplicate-merge'); const pending = f.calls[2];
        await f.dispose(); same(pending.options.signal.aborted, true, 'mutation aborted');
        await f.mergeReply(pending); same(f.calls.length, 3, 'no late scan or message');
    });
    await check('one-click deduplication is visible before scanning and duplicate clicks prepare only one operation', async f => {
        same(f.byId('duplicate-deduplicate').children, 'resourceHubDuplicates.oneClick', 'visible primary action');
        same(f.calls.length, 0, 'no automatic mutation');
        await f.act(() => { f.byId('duplicate-deduplicate').onClick(); f.byId('duplicate-deduplicate').onClick(); });
        same(f.calls.length, 1, 'one scan');
        same(f.calls[0].url.endsWith('dryRun=true&limit=100&page=1'), true, 'safe scan first');
        same(new Headers(f.calls[0].options.headers).get('Authorization'), 'Bearer fixture-admin', 'authenticated scan');
        await f.reply(f.calls[0], f.result({ candidates: 0, items: [] }));
        same(f.byId('duplicate-bulk-merge').disabled, true, 'empty plan cannot merge');
    });
    await check('one-click preparation reads every page and batch before explicit global confirmation', async f => {
        const rows = f.candidates(101); await f.startBulk(rows);
        const scans = f.calls.filter(call => call.url.includes('dryRun=true'));
        same(scans.length, 2, 'all pages scanned'); same(scans[1].url.endsWith('page=2'), true, 'second page requested');
        same(f.calls.filter(call => call.url.endsWith('/preview')).map(call => JSON.parse(call.options.body).pairs.length), [20, 20, 20, 20, 20, 1], 'bounded review batches');
        same(f.byId('duplicate-bulk-candidates').dataSource.length, 101, 'all pairs visible for review');
        same(f.byId('duplicate-bulk-confirm').checked, false, 'global review not preconfirmed');
        same(f.byId('duplicate-bulk-merge').disabled, true, 'merge guarded');
        await f.click('duplicate-bulk-merge');
        same(f.calls.some(call => call.url.endsWith('/merge')), false, 'no writes before global confirmation');
        f.byId('duplicate-bulk-candidates').expandable.expandedRowRender(rows[0]);
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.references.user_favorite'), true, 'favorite references available');
    });
    await check('name-only and globally overlapping candidates stay skipped without preview or merge', async f => {
        const rows = f.candidates(4); rows[0].evidence = 'NAME_ONLY'; rows[2].fromMovieId = rows[1].toMovieId;
        await f.startBulk(rows);
        same(f.byId('duplicate-bulk-candidates').dataSource.map(row => row.fromMovieId), [rows[3].fromMovieId], 'independent supported pair only');
        same(f.byId('duplicate-bulk-skipped').dataSource.map(item => item.reason), ['bulkNameOnly', 'overlapping', 'overlapping'], 'every overlap member excluded');
        same(JSON.parse(f.calls[1].options.body).pairs, [{ fromMovieId: rows[3].fromMovieId, toMovieId: rows[3].toMovieId }], 'safe preview subset');
    });
    await check('blocked candidates stay visible and eligible subset receives a fresh preview', async f => {
        const rows = f.candidates(2), pairs = rows.map(({ fromMovieId, toMovieId }) => ({ fromMovieId, toMovieId }));
        const blocked = f.review(pairs); blocked.canMerge = false; blocked.items[1].eligible = false;
        blocked.items[1].blockers = [{ code: 'ACTIVE_OR_UNKNOWN_TASK', reference: 'baidu_transfer_task', count: 1 }];
        await f.startBulk(rows, [blocked]);
        same(f.calls.filter(call => call.url.endsWith('/preview')).length, 2, 'fresh safe preview');
        same(JSON.parse(f.calls[2].options.body).pairs, [pairs[0]], 'blocked pair removed');
        same(f.byId('duplicate-bulk-skipped').dataSource[0].blockers[0].code, 'ACTIVE_OR_UNKNOWN_TASK', 'blocker retained');
        same(f.byId('duplicate-bulk-candidates').dataSource.length, 1, 'eligible proposal only');
        same(f.calls.some(call => call.url.endsWith('/merge')), false, 'no implicit merge');
    });
    await check('incomplete scan cannot create a bulk proposal', async f => {
        await f.click('duplicate-deduplicate'); await f.reply(f.calls[0], f.result({ complete: false }));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.bulkIncomplete', 'partial scan explained');
        same(f.byId('duplicate-bulk-review').open, false, 'no partial library merge'); same(f.calls.length, 1, 'no preview');
    });
    await check('malformed bulk scan fails without invented empty success', async f => {
        await f.click('duplicate-deduplicate'); await f.reply(f.calls[0], { candidates: 0, items: [] });
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.failed', 'malformed scan rejected');
        same(f.byId('duplicate-bulk-review').open, false, 'no fake plan'); same(f.byId('duplicate-merge-success'), undefined, 'no fake success');
    });
    await check('changing page counts stops bulk preparation before preview', async f => {
        const rows = f.candidates(101); await f.click('duplicate-deduplicate');
        await f.reply(f.calls[0], f.result({ candidates: 101, items: rows.slice(0, 100) }));
        await f.reply(f.calls[1], f.result({ candidates: 102, page: 2, items: rows.slice(100) }));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.bulkChanged', 'mixed scans rejected'); same(f.calls.length, 2, 'no preview');
    });
    await check('duplicate pairs across pages stop preparation despite stable counts', async f => {
        const rows = f.candidates(101); await f.click('duplicate-deduplicate');
        await f.reply(f.calls[0], f.result({ candidates: 101, items: rows.slice(0, 100) }));
        await f.reply(f.calls[1], f.result({ candidates: 101, page: 2, items: [rows[0]] }));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.bulkChanged', 'duplicate page rejected'); same(f.calls.length, 2, 'no preview');
    });
    await check('malformed bulk preview cannot grant merge permission', async f => {
        const rows = f.candidates(); await f.click('duplicate-deduplicate');
        await f.reply(f.calls[0], f.result({ candidates: 1, items: rows }));
        await f.reply(f.calls[1], f.review([{ fromMovieId: 'unexpected', toMovieId: rows[0].toMovieId }]));
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.previewFailed', 'mismatched preview rejected');
        same(f.byId('duplicate-bulk-review').open, false, 'no invalid review');
    });
    await check('confirmed bulk merge executes bounded batches, sums successes and rescans', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk();
        await f.act(() => { f.byId('duplicate-bulk-merge').onClick(); f.byId('duplicate-bulk-merge').onClick(); });
        same(f.calls.length, 4, 'single first merge');
        same(new Headers(f.calls[3].options.headers).get('Authorization'), 'Bearer fixture-admin', 'authenticated merge');
        await f.bulkMergeReply(f.calls[3]); same(f.calls[4].url.endsWith('/merge'), true, 'next batch');
        same(f.byId('duplicate-bulk-progress').type, 'success', 'partial progress visible');
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.bulkProgress' && row.values.count === 20), true, 'first batch count');
        await f.bulkMergeReply(f.calls[4]);
        same(f.byId('duplicate-bulk-review').open, false, 'finished dialog closed');
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.merged' && row.values.count === 21), true, 'aggregate count');
        same(f.calls[5].url.endsWith('dryRun=true&limit=100&page=1'), true, 'fresh scan');
        await f.reply(f.calls[5], f.result({ candidates: 0, items: [] }));
        same(f.byId('duplicate-candidates').rowSelection.selectedRowKeys, [], 'selection cleared');
    });
    await check('stale later batch stops bulk merging while preserving completed progress', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk(); await f.click('duplicate-bulk-merge');
        await f.bulkMergeReply(f.calls[3]); await f.reply(f.calls[4], null, 409);
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.reviewChanged', 'stale batch explained');
        same(f.byId('duplicate-bulk-review').open, true, 'partial result remains visible');
        same(f.byId('duplicate-bulk-merge').disabled, true, 'no blind resume');
        same(f.byId('duplicate-bulk-progress').type, 'success', 'first batch retained');
        await f.click('duplicate-bulk-merge'); same(f.calls.length, 5, 'direct retry guarded');
    });
    await check('unknown bulk response locks the plan and retries its identical review and UUID', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk(); await f.click('duplicate-bulk-merge');
        await f.bulkMergeReply(f.calls[3]); const pending = f.calls[4];
        await f.act(() => pending.reject(new Error('raw upstream secret')));
        same(f.byId('duplicate-deduplicate').disabled, true, 'new bulk action locked');
        same(f.byId('duplicate-scan').disabled, true, 'scan locked');
        same(f.byId('duplicate-bulk-review').closable, false, 'unknown plan not discardable');
        same(f.byId('duplicate-bulk-merge').children, 'resourceHubDuplicates.retryMerge', 'safe retry explicit');
        await f.click('duplicate-deduplicate'); await f.click('duplicate-scan'); same(f.calls.length, 5, 'new handlers guarded');
        await f.click('duplicate-bulk-merge'); same(f.calls[5].options.body, pending.options.body, 'same UUID and review');
        await f.bulkMergeReply(f.calls[5]);
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.merged' && row.values.count === 21), true, 'no double-counted first batch');
        same(f.calls[6].url.endsWith('page=1'), true, 'completion rescan');
    });
    await check('unexpected bulk merge payload stays uncertain and never advances batches', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk(); await f.click('duplicate-bulk-merge');
        const body = JSON.parse(f.calls[3].options.body);
        await f.reply(f.calls[3], { ...body, status: 'SUCCEEDED', merged: 0, items: [], replayed: false });
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.mergeUncertain', 'uncertainty shown');
        same(f.byId('duplicate-merge-success'), undefined, 'no success'); same(f.calls.length, 4, 'no next batch');
    });
    await check('expired bulk preview cannot issue a fresh merge', async f => {
        await f.startBulk(f.candidates(), [{ expiresAt: new Date(Date.now() - 1).toISOString() }]);
        await f.confirmBulk(); await f.click('duplicate-bulk-merge');
        same(f.calls.length, 2, 'no expired merge'); same(f.byId('duplicate-bulk-merge').disabled, true, 'expired action disabled');
    });
    await check('unmount aborts bulk mutations and ignores late replies for remaining batches', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk(); await f.click('duplicate-bulk-merge'); const pending = f.calls[3];
        await f.dispose(); same(pending.options.signal.aborted, true, 'pending request aborted');
        await f.bulkMergeReply(pending); same(f.calls.length, 4, 'no late next batch');
    });
    await check('changed titles between scan and preview invalidate the full bulk proposal', async f => {
        const rows = f.candidates(); await f.click('duplicate-deduplicate');
        await f.reply(f.calls[0], f.result({ candidates: 1, items: rows }));
        const pairs = rows.map(({ fromMovieId, toMovieId }) => ({ fromMovieId, toMovieId })), preview = f.review(pairs);
        preview.items[0].fromTitle = 'Different work'; preview.items[0].toTitle = rows[0].toTitle;
        await f.reply(f.calls[1], preview);
        same(f.byId('duplicate-scan-error').title, 'resourceHubDuplicates.bulkChanged', 'identity drift explained');
        same(f.byId('duplicate-bulk-review').open, false, 'cannot approve changed titles'); same(f.calls.length, 2, 'no merge');
    });
    await check('surrounding title whitespace is tolerated when scan and preview identify the same records', async f => {
        const rows = f.candidates(); await f.click('duplicate-deduplicate');
        await f.reply(f.calls[0], f.result({ candidates: 1, items: rows }));
        const preview = f.review(rows.map(({ fromMovieId, toMovieId }) => ({ fromMovieId, toMovieId })));
        preview.items[0].fromTitle = '  ' + rows[0].fromTitle + ' '; preview.items[0].toTitle = '\t' + rows[0].toTitle + ' ';
        await f.reply(f.calls[1], preview);
        same(f.byId('duplicate-bulk-review').open, true, 'same titles accepted');
        same(f.byId('duplicate-bulk-merge').disabled, true, 'still requires confirmation');
    });
    await check('closing a partially completed bulk plan preserves its verified success and refreshes candidates', async f => {
        await f.startBulk(f.candidates(21)); await f.confirmBulk(); await f.click('duplicate-bulk-merge');
        await f.bulkMergeReply(f.calls[3]); await f.reply(f.calls[4], null, 409);
        await f.click('duplicate-bulk-cancel');
        same(f.byId('duplicate-bulk-review').open, false, 'stopped plan closed');
        same(f.byId('duplicate-merge-success').type, 'success', 'completed success remains visible');
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.merged' && row.values.count === 20), true, 'only verified completed count');
        same(f.calls[5].url.endsWith('dryRun=true&limit=100&page=1'), true, 'candidate list refreshed');
    });
    return results;
}

test('Full-library duplicate review and batch merge: real React with mocked API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(duplicateSuite, { scanner: 'src/components/admin/DuplicateMovieScanner.tsx' });
    assert.equal(results.length, 36);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
