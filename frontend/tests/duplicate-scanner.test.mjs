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
        const capture = name => props => React.createElement('div', { 'data-fixture': name,
            ref: node => { if (node) node.fixtureProps = props; } }, props.children);
        const t = (key, values) => { translations.push({ key, values }); return key; };
        const api = (url, options, ...unexpected) => {
            if (unexpected.length > 0) throw new Error('API accepts path and RequestInit only');
            let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
            calls.push({ url, options, resolve, reject }); return promise;
        };
        const Page = loadPage('scanner', {
            antd: { ...Object.fromEntries(['Alert', 'Button', 'Space', 'Table', 'Tag'].map(name => [name, capture(name)])),
                Typography: { Text: capture('Text') } },
            '@ant-design/icons': { SearchOutlined: () => null },
            'react-i18next': { useTranslation: () => ({ t }) },
            '@/lib/api': { api }, '@/store/authStore': { useAuthStore: () => ({ token: 'fixture-admin' }) },
        });
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const button = () => all('Button')[0];
        const act = action => React.act(async () => { action(); });
        const reply = (call, data, status = 200) => act(() => call.resolve(new Response(JSON.stringify({ data }), { status })));
        const item = { fromMovieId: 'tmdb_tv_247718', fromTitle: 'MobLand', fromResources: 0,
            toMovieId: '1YKW', toTitle: '黑帮领地 第一季', toResources: 8,
            category: 'tv', year: 2025, season: 1, evidence: 'NAME_AND_PEOPLE', reviewRequired: true };
        const result = extra => ({ scanMode: 'FULL_LIBRARY', dryRun: true, mergeEnabled: false, complete: true,
            scannedMovies: 15293, candidates: 92, conflicts: 6, excludedMovies: 12,
            page: 1, pageSize: 100, items: [item], ...extra });
        await act(() => root.render(React.createElement(Page)));
        return { all, calls, translations, button, act, reply, item, result,
            dispose: async () => { await act(() => root.unmount()); container.remove(); } };
    }
    const check = async (name, run) => {
        const f = await fixture();
        try { await run(f); results.push({ name, ok: true }); }
        catch (error) { results.push({ name, ok: false, error: error.stack || String(error) }); }
        finally { await f.dispose(); }
    };
    await check('scan starts only on request and always uses authenticated read-only full-library mode', async f => {
        same(f.calls.length, 0, 'no automatic scan');
        await f.act(() => { f.button().onClick(); f.button().onClick(); });
        same(f.calls.length, 1, 'double clicks are deduplicated');
        same(f.calls[0].url, '/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100&page=1', 'full scan with result page');
        same(f.calls[0].options.method, 'POST', 'request');
        same(new Headers(f.calls[0].options.headers).get('Authorization'), 'Bearer fixture-admin', 'admin auth header');
        same(f.all('Button').length, 1, 'no merge button');
    });
    await check('MobLand candidates show both IDs and resource counts without a merge action', async f => {
        await f.act(() => f.button().onClick()); await f.reply(f.calls[0], f.result());
        const table = f.all('Table')[0]; same(table.dataSource[0].toResources, 8, 'existing resources retained');
        same(table.dataSource[0].fromResources, 0, 'empty duplicate');
        same(table.dataSource[0].toMovieId, '1YKW', 'GYING record');
        same(table.pagination.total, 92, 'all candidates total');
        same(f.all('Alert')[0].type, 'info', 'full scan');
        same(f.translations.some(row => row.key === 'resourceHubDuplicates.summary' && row.values.scanned === 15293), true, 'full catalog count');
        same(f.all('Button').length, 1, 'no merge action after scan');
    });
    await check('candidate pagination keeps the whole-library scan and never executes cleanup', async f => {
        await f.act(() => f.button().onClick()); await f.reply(f.calls[0], f.result({ candidates: 250 }));
        await f.act(() => f.all('Table')[0].pagination.onChange(2));
        same(f.calls[1].url.endsWith('dryRun=true&limit=100&page=2'), true, 'next candidate page');
        await f.reply(f.calls[1], f.result({ candidates: 250, page: 2 }));
        same(f.all('Table')[0].pagination.current, 2, 'server page accepted');
        same(f.calls.some(call => call.url.includes('dryRun=false')), false, 'no mutation');
    });
    await check('bounded scans clearly label their results incomplete', async f => {
        await f.act(() => f.button().onClick()); await f.reply(f.calls[0], f.result({ complete: false }));
        same(f.all('Alert')[0].type, 'warning', 'incomplete warning');
        same(f.all('Alert')[0].title, 'resourceHubDuplicates.partial', 'no full-scan claim');
    });
    await check('network failures preserve a fixed error and allow an explicit retry', async f => {
        await f.act(() => f.button().onClick()); await f.act(() => f.calls[0].reject(new Error('raw credential')));
        same(f.all('Alert')[0].title, 'resourceHubDuplicates.failed', 'safe error');
        same(f.all('Table').length, 0, 'no invented empty result');
        await f.act(() => f.button().onClick()); await f.reply(f.calls[1], f.result({ candidates: 0, items: [] }));
        same(f.all('Alert')[0].title, 'resourceHubDuplicates.summary', 'retry success');
        same(f.all('Table')[0].pagination.total, 0, 'confirmed zero result');
    });
    await check('legacy or malformed responses do not masquerade as zero duplicates', async f => {
        await f.act(() => f.button().onClick()); await f.reply(f.calls[0], { candidates: 0, items: [], dryRun: true });
        same(f.all('Alert')[0].title, 'resourceHubDuplicates.failed', 'old response rejected');
        await f.act(() => f.button().onClick()); await f.reply(f.calls[1], f.result({ mergeEnabled: true }));
        same(f.all('Alert')[0].title, 'resourceHubDuplicates.failed', 'unsafe response rejected');
        same(f.all('Table').length, 0, 'no result invented');
    });
    await check('unmount cancels an in-flight scan and ignores its late result', async f => {
        await f.act(() => f.button().onClick()); const signal = f.calls[0].options.signal;
        await f.dispose(); same(signal.aborted, true, 'request aborted');
        await f.reply(f.calls[0], f.result()); same(f.all('Table').length, 0, 'no late render');
    });
    return results;
}

test('Full-library duplicate review: real React and mocked API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(duplicateSuite, { scanner: 'src/components/admin/DuplicateMovieScanner.tsx' });
    assert.equal(results.length, 7);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
