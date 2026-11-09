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
            '@ant-design/icons': { SearchOutlined: () => null },
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
        await act(() => root.render(React.createElement(Page)));
        return { all, byId, calls, translations, act, reply, item, second, result, review, click, select, choose, startReview, confirm, mergeReply,
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
    return results;
}

test('Full-library duplicate review and batch merge: real React with mocked API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(duplicateSuite, { scanner: 'src/components/admin/DuplicateMovieScanner.tsx' });
    assert.equal(results.length, 18);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
