import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

// Exercise the real admin page and range controls, without a server or real credentials.
async function pauseSuite({ React, createRoot, loadPage, loadModule, readApiError }) {
    const results = [];
    const sources = ['HITS_MOVIE', 'HITS_TV', 'HITS_ANIME', 'CSCORE_MOVIE', 'CSCORE_TV', 'CSCORE_ANIME'];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + ': ' + JSON.stringify(actual));
    };
    const deferred = () => {
        let resolve, reject;
        const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
        return { promise, resolve, reject };
    };
    async function fixture(scenario = {}) {
        const container = document.createElement('div');
        document.body.append(container);
        const root = createRoot(container);
        const calls = [], errors = [], successes = [], warnings = [], forms = [], translations = [];
        const user = { id: 1, role: 'ADMIN' };
        const t = (key, values) => { translations.push({ key, values }); return key; };
        const message = { error: value => errors.push(value), success: value => successes.push(value), warning: value => warnings.push(value), info: () => {} };
        const router = { push: () => {} };
        const FormContext = React.createContext(null);
        const act = async action => { await React.act(async () => { action(); }); };
        function createForm() {
            const form = { values: {}, updates: [], listeners: new Set(),
                setFieldsValue(values) {
                    Object.assign(form.values, values);
                    form.updates.push(values);
                    form.listeners.forEach(notify => notify());
                },
                getFieldsValue: () => JSON.parse(JSON.stringify(form.values)),
            };
            forms.push(form);
            return form;
        }
        const capture = name => function FixtureComponent(props) {
            return React.createElement('div', { 'data-fixture': name,
                ref: node => { if (node) node.fixtureProps = props; },
            }, props.children, name === 'Tabs' ? props.items?.map(item => React.createElement(React.Fragment, { key: item.key }, item.children)) : null);
        };
        function Form(props) {
            return React.createElement(FormContext.Provider, { value: props.form },
                React.createElement(capture('Form'), props));
        }
        Form.useForm = () => React.useState(createForm);
        Form.useWatch = key => {
            const form = React.useContext(FormContext);
            return React.useSyncExternalStore(notify => {
                form.listeners.add(notify);
                return () => form.listeners.delete(notify);
            }, () => form.values[key]);
        };
        Form.Item = capture('Form.Item');
        Form.List = props => props.children([]);
        const components = Object.fromEntries(['Alert', 'Button', 'Card', 'Col', 'Descriptions', 'Empty', 'Input', 'InputNumber', 'Row', 'Select', 'Space', 'Statistic', 'Switch', 'Table', 'Tabs', 'Tag', 'Tooltip'].map(name => [name, capture(name)]));
        components.Space.Compact = capture('Space.Compact');
        components.Descriptions.Item = capture('Descriptions.Item');
        components.Input.Password = capture('Input.Password');
        components.Input.TextArea = capture('Input.TextArea');
        components.Input.Search = capture('Input.Search');
        components.Empty.PRESENTED_IMAGE_SIMPLE = 'empty';
        const icons = Object.fromEntries(['ApiOutlined', 'CloudSyncOutlined', 'DatabaseOutlined', 'PauseCircleOutlined', 'PlayCircleOutlined', 'ReloadOutlined', 'SaveOutlined', 'SearchOutlined', 'ShareAltOutlined', 'QuestionCircleOutlined'].map(name => [name, () => null]));
        const config = {
            enabled: true, workerEnabled: true, tmdbAutoSyncSources: 'POPULAR_MOVIE',
            tmdbAutoSyncPage: 1, tmdbAutoSyncEndPage: 500, tmdbAutoSyncRanges: { POPULAR_MOVIE: { startPage: 1, endPage: 500 } },
            gyingAutoSyncEnabled: true, gyingAutoSyncSources: sources.join(','),
            gyingAutoSyncPage: 1, gyingAutoSyncEndPage: 50,
            gyingAutoSyncRanges: Object.fromEntries(sources.map(source => [source, { startPage: 1, endPage: 50 }])),
            gyingAutoSyncPausedSources: Object.fromEntries(sources.map(source => [source, false])),
            metadataCrawlProgress: scenario.progress || [{ provider: 'GYING', source: 'HITS_MOVIE', startPage: 1, endPage: 50, nextPage: 7, nextItem: 11, status: 'PENDING' }],
            weeklyTransferSchedules: [],
        };
        const overview = { config, enabled: true, worker: { enabled: true, publishLimit: 1, quarkLimit: 1 }, taskStatusCounts: {} };
        let firstOverview = true;
        const api = (url, options = {}) => {
            const call = { url, options, ...deferred() };
            calls.push(call);
            if (url.endsWith('/overview')) {
                if (firstOverview) firstOverview = false;
                else call.resolve(new Response(JSON.stringify({ data: overview }), { status: 200 }));
            }
            else if (url.includes('/tasks?')) call.resolve(new Response(JSON.stringify({ data: { records: scenario.tasks || [], total: scenario.tasks?.length || 0 } }), { status: 200 }));
            else if (!url.endsWith('/config') && !url.endsWith('/pause')) call.resolve(new Response(JSON.stringify({ data: { records: [], total: 0 } }), { status: 200 }));
            return call.promise;
        };
        const mocks = {
            antd: { ...components, Form, App: { useApp: () => ({ message }) }, Typography: { Title: capture('Title'), Text: capture('Text'), Paragraph: capture('Paragraph') } },
            '@ant-design/icons': icons,
            '@/lib/api': { api, readApiError },
            '@/store/authStore': { useAuthStore: () => ({ user, token: 'fixture-admin-token' }) },
            'next/navigation': { useRouter: () => router },
            'react-i18next': { useTranslation: () => ({ t }) },
        };
        mocks['@/components/admin/DuplicateMovieScanner'] = () => null;
        mocks['@/lib/cloudDriveWorkflows'] = loadModule('driveWorkflows', mocks);
        const Ranges = loadPage('crawlRanges', mocks);
        mocks['@/components/admin/BaiduAccountConnection'] = loadPage('baiduAccount', mocks);
        mocks['@/components/admin/BaiduBackfillControls'] = loadPage('baiduBackfill', mocks);
        const Drives = loadPage('driveSettings', mocks);
        const Page = loadPage('resourceHub', { ...mocks, '@/components/admin/MetadataCrawlRanges': Ranges, '@/components/admin/DriveWorkflowSettings': Drives });
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const button = source => all('Button').find(props => props['data-testid'] === 'gying-pause-' + source);
        const last = () => calls[calls.length - 1];
        const reply = (call, data, status = 200) => act(() => call.resolve(new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } })));
        const pause = source => act(() => button(source).onClick());
        const refreshed = paused => ({ ...config, gyingAutoSyncPausedSources: { ...config.gyingAutoSyncPausedSources, HITS_MOVIE: paused },
            metadataCrawlProgress: config.metadataCrawlProgress.map(row => ({ ...row, status: paused ? 'PAUSED' : 'PENDING' })) });
        await act(() => root.render(React.createElement(Page)));
        await reply(calls.find(call => call.url.endsWith('/overview')), overview);
        return { calls, errors, successes, warnings, forms, config, translations, all, button, last, act, reply, pause, refreshed,
            cell: async (table, columnKey, row) => {
                const column = table.columns.find(column => (column.key || column.dataIndex) === columnKey);
                const content = column.render(row[column.dataIndex], row);
                const cell = document.createElement('div'); container.append(cell);
                const cellRoot = createRoot(cell);
                await act(() => cellRoot.render(content));
                const text = cell.textContent;
                await act(() => cellRoot.unmount()); cell.remove();
                return text;
            },
            save: values => act(() => all('Form').find(props => props.form === forms[0]).onFinish(values)),
            dispose: async () => { await act(() => root.unmount()); container.remove(); },
        };
    }
    async function check(name, run, options) {
        let f;
        try { f = await fixture(options); await run(f); results.push({ name, ok: true }); }
        catch (error) { results.push({ name, ok: false, error: error.stack || String(error) }); }
        finally { if (f) await f.dispose(); }
    }
    await check('six selected GYING catalogs have controls; TMDB has none', async f => {
        same(f.all('Button').filter(props => String(props['data-testid']).startsWith('gying-pause-')).length, 6, 'six controls');
        same(f.all('Button').filter(props => String(props['data-testid']).startsWith('tmdb-pause-')).length, 0, 'no TMDB controls');
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingPause', 'initial action');
    });
    await check('one strict pause write, no optimistic change or duplicate write', async f => {
        const button = f.button('HITS_MOVIE');
        await f.act(() => { button.onClick(); button.onClick(); });
        const writes = f.calls.filter(call => call.options.method === 'PUT');
        same(writes.length, 1, 'one write');
        same(writes[0].url, '/api/admin/resource-hub/gying/sources/HITS_MOVIE/pause', 'source-scoped endpoint');
        same(JSON.parse(writes[0].options.body), { paused: true }, 'strict body');
        same(writes[0].options.headers.Authorization, 'Bearer fixture-admin-token', 'fixture auth header');
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingPause', 'confirmed state retained while pending');
        same(sources.every(source => f.button(source).disabled), true, 'all pause controls blocked during write');
        await f.save(f.forms[0].getFieldsValue());
        same(f.calls.filter(call => call.options.method === 'PUT').length, 1, 'Enter cannot save while toggling');
        await f.reply(writes[0], { source: 'HITS_MOVIE', paused: true });
        await f.reply(f.last(), f.refreshed(true));
    });
    await check('pause and progress refresh retain unsaved ranges and master settings', async f => {
        await f.act(() => f.forms[0].setFieldsValue({ enabled: false, gyingAutoSyncRanges: { ...f.config.gyingAutoSyncRanges, HITS_MOVIE: { startPage: 3, endPage: 44 } } }));
        const updates = f.forms[0].updates.length;
        await f.pause('HITS_MOVIE');
        await f.reply(f.last(), { source: 'HITS_MOVIE', paused: true });
        same(f.last().url, '/api/admin/resource-hub/config', 'progress read only');
        await f.reply(f.last(), f.refreshed(true));
        same(f.forms[0].updates.length, updates, 'no form replacement');
        same(f.forms[0].values.gyingAutoSyncRanges.HITS_MOVIE, { startPage: 3, endPage: 44 }, 'range draft retained');
        same(f.forms[0].values.enabled, false, 'master switch draft retained');
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingResume', 'pause confirmed');
        const table = f.all('Table').find(props => props['data-testid'] === 'metadata-crawl-progress');
        same([table.dataSource[0].nextPage, table.dataSource[0].nextItem], [7, 11], 'checkpoint retained');
        same(table.columns[3].render('PAUSED', table.dataSource[0]), 'resourceHubCrawlStatus.PAUSED', 'paused progress label');
    });
    await check('resume preserves position and never runs the worker immediately', async f => {
        await f.pause('HITS_MOVIE'); await f.reply(f.last(), { source: 'HITS_MOVIE', paused: true }); await f.reply(f.last(), f.refreshed(true));
        await f.pause('HITS_MOVIE');
        same(JSON.parse(f.last().options.body), { paused: false }, 'resume body');
        await f.reply(f.last(), { source: 'HITS_MOVIE', paused: false }); await f.reply(f.last(), f.refreshed(false));
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingPause', 'resume confirmed');
        same(f.calls.some(call => /run-once|\/metadata\/sync/.test(call.url)), false, 'no worker run');
        same(f.forms[0].values.gyingAutoSyncSources, sources, 'source selection unchanged');
    });
    await check('HTTP write failure retains state and permits an identical retry', async f => {
        await f.pause('HITS_MOVIE'); await f.reply(f.last(), {}, 503);
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingPause', 'prior state retained');
        same(f.button('HITS_MOVIE').disabled, false, 'retry enabled');
        same(f.errors, ['resourceHubGyingPauseFailed'], 'retry guidance');
        await f.pause('HITS_MOVIE'); same(JSON.parse(f.last().options.body), { paused: true }, 'idempotent retry');
        await f.reply(f.last(), { source: 'HITS_MOVIE', paused: true }); await f.reply(f.last(), f.refreshed(true));
    });
    await check('malformed success cannot update a different source', async f => {
        await f.pause('HITS_MOVIE'); await f.reply(f.last(), { source: 'HITS_TV', paused: true });
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingPause', 'original unchanged');
        same(f.button('HITS_TV').children, 'resourceHubGyingPause', 'other unchanged');
        same(f.errors.length, 1, 'malformed response rejected');
    });
    await check('accepted pause survives progress read failure without losing drafts', async f => {
        await f.act(() => f.forms[0].setFieldsValue({ gyingAutoSyncMaxItems: 9 }));
        const updates = f.forms[0].updates.length;
        await f.pause('HITS_MOVIE'); await f.reply(f.last(), { source: 'HITS_MOVIE', paused: true }); await f.reply(f.last(), {}, 503);
        same(f.button('HITS_MOVIE').children, 'resourceHubGyingResume', 'accepted pause retained');
        same(f.warnings, ['resourceHubGyingPauseRefreshFailed'], 'read failure distinguished');
        same(f.forms[0].updates.length, updates, 'draft not replaced');
    });
    await check('whole-form save cannot overwrite the source pause map', async f => {
        const values = f.forms[0].getFieldsValue();
        await f.save(values);
        const request = f.last();
        same(request.url, '/api/admin/resource-hub/config', 'whole-form save route');
        same(Object.hasOwn(JSON.parse(request.options.body), 'gyingAutoSyncPausedSources'), false, 'independent pause map omitted');
        await f.reply(request, f.config);
    });
    const tmdbProgress = {
        provider: 'TMDB', source: 'POPULAR_MOVIE', startPage: 360, endPage: 500,
        nextPage: 360, nextItem: 1, status: 'SUCCEEDED', observedTotalPages: 359,
        availability: 'EMPTY_RANGE', nextCheckAt: '2026-11-02T22:00:00Z',
    };
    await check('empty TMDB range shows its known last page and retains the configured range', async f => {
        const table = f.all('Table').find(props => props['data-testid'] === 'metadata-crawl-progress');
        const row = table.dataSource[0];
        const range = await f.cell(table, 'range', row);
        same(range.startsWith('360–500'), true, 'range stays configured');
        same(range.includes('resourceHubCrawlObservedLastPage: 359'), true, 'last observed page included with configured bounds');
        const state = await f.cell(table, 'availability', row);
        same(state.includes('resourceHubCrawlAvailability.EMPTY_RANGE'), true, 'specific empty range label');
        same(state.includes('resourceHubCrawlRangeBeyondLastPage'), true, 'specific range reason');
        same(f.translations.find(entry => entry.key === 'resourceHubCrawlRangeBeyondLastPage').values,
            { total: 359, start: 360, end: 500 }, 'reason uses actual source and configured bounds');
        same(state.includes('6:00') || state.includes('06:00'), true, 'UTC timestamp displayed at Shanghai next-day 06:00');
        same(state.includes('resourceHubCrawlNextCheckAt'), true, 'timezone-aware check label');
        same(table.columns.length, 5, 'availability adds only one column');
        same(f.calls.some(call => ['POST', 'PUT'].includes(call.options.method)), false, 'viewing never changes progress');
    }, { progress: [tmdbProgress] });
    await check('exhausted TMDB catalog is distinguished from a wholly empty range', async f => {
        const table = f.all('Table').find(props => props['data-testid'] === 'metadata-crawl-progress');
        const state = await f.cell(table, 'availability', table.dataSource[0]);
        same(state.includes('resourceHubCrawlAvailability.RANGE_EXHAUSTED'), true, 'last page reached');
        same(state.includes('resourceHubCrawlAvailabilityHelp.RANGE_EXHAUSTED'), true, 'new data check explained');
        same(state.includes('EMPTY_RANGE'), false, 'does not claim empty range');
        same(state.includes('resourceHubCrawlNextCheckAt'), false, 'valid range resumes without empty-range cooldown');
    }, { progress: [{ ...tmdbProgress, availability: 'RANGE_EXHAUSTED', observedTotalPages: 449 }] });
    await check('failed or partially failed requests cannot be mislabeled as empty ranges', async f => {
        const table = f.all('Table').find(props => props['data-testid'] === 'metadata-crawl-progress');
        for (const row of table.dataSource) {
            same(await f.cell(table, 'availability', row), '-', 'no empty-range label on a request error');
            same(await f.cell(table, 'status', row), 'resourceHubCrawlStatus.' + row.status, 'actual failure status retained');
        }
    }, { progress: ['FAILED', 'RETRY'].map(status => ({ ...tmdbProgress, status })) });
    await check('task labels require explicit completed metadata crawl markers', async f => {
        const table = f.all('Table').find(props => props.dataSource?.some(row => row.id === 9001));
        const labels = [];
        for (const row of table.dataSource) labels.push(await f.cell(table, 'status', row));
        same(labels, [
            'resourceHubCrawlAvailability.EMPTY_RANGE', 'resourceHubCrawlAvailability.RANGE_EXHAUSTED',
            'resourceHubStatus.SUCCEEDED', 'resourceHubStatus.FAILED', 'resourceHubStatus.SUCCEEDED',
            'resourceHubStatus.SUCCEEDED', 'resourceHubStatus.SUCCEEDED',
        ], 'zero work, failures, malformed payloads and unrelated tasks retain their actual statuses');
    }, { tasks: [
        { id: 9001, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9002, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'RANGE_EXHAUSTED' } }) },
        { id: 9003, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ processed: 0 }) },
        { id: 9004, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'FAILED', lastError: 'TMDB HTTP 429', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9005, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: '{invalid-json' },
        { id: 9006, taskType: 'RESOURCE_DISCOVERY', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9007, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', lastError: 'upstream request failed', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
    ] });
    await check('only automatic TMDB ranges can receive availability task labels', async f => {
        const table = f.all('Table').find(props => props.dataSource?.some(row => row.id === 9101));
        for (const row of table.dataSource) {
            same(await f.cell(table, 'status', row), 'resourceHubStatus.SUCCEEDED', 'ordinary status for non-range or malformed crawl');
        }
    }, { tasks: [
        { id: 9101, taskType: 'METADATA_SYNC', source: 'GYING', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9102, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'PAGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9103, taskType: 'METADATA_SYNC', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'EMPTY_RANGE' } }) },
        { id: 9104, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { availability: 'EMPTY_RANGE' } }) },
        { id: 9105, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: null }) },
        { id: 9106, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: [] }) },
        { id: 9107, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'RANGE', availability: 'AVAILABLE' } }) },
        { id: 9108, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: '[]' },
    ] });
    await check('missing optional availability and malformed check times remain readable', async f => {
        const table = f.all('Table').find(props => props['data-testid'] === 'metadata-crawl-progress');
        const missing = table.dataSource[0], zero = table.dataSource[1];
        same((await f.cell(table, 'range', missing)).includes('resourceHubCrawlObservedLastPage'), false, 'older API without observed total');
        same(await f.cell(table, 'availability', missing), '-', 'older API without availability');
        same((await f.cell(table, 'availability', missing)).includes('resourceHubCrawlNextCheckAt'), false, 'older API without check timestamp');
        same((await f.cell(table, 'range', zero)).includes('resourceHubCrawlObservedLastPage: 0'), true, 'zero-page source is preserved');
        const unavailable = await f.cell(table, 'availability', zero);
        same(unavailable.includes('resourceHubCrawlNextCheckAt: -'), true, 'invalid date becomes a readable placeholder');
        same(unavailable.includes('Invalid Date'), false, 'invalid date does not leak Invalid Date');
    }, { progress: [
        { provider: 'TMDB', source: 'POPULAR_TV', startPage: 1, endPage: 500, nextPage: 1, nextItem: 1, status: 'SUCCEEDED' },
        { ...tmdbProgress, observedTotalPages: 0, nextCheckAt: 'invalid-date' },
    ] });
    return results;
}

test('GYING pause controls: real React, source-scoped mocked API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(pauseSuite, {
        resourceHub: 'src/app/admin/resource-hub/page.tsx',
        crawlRanges: 'src/components/admin/MetadataCrawlRanges.tsx',
        driveSettings: 'src/components/admin/DriveWorkflowSettings.tsx',
        baiduAccount: 'src/components/admin/BaiduAccountConnection.tsx',
        baiduBackfill: 'src/components/admin/BaiduBackfillControls.tsx',
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
    });
    assert.equal(results.length, 14);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
