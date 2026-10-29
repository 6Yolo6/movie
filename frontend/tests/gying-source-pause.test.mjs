import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

// Exercise the real admin page and range controls, without a server or real credentials.
async function pauseSuite({ React, createRoot, loadPage, readApiError }) {
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
    async function fixture() {
        const container = document.createElement('div');
        document.body.append(container);
        const root = createRoot(container);
        const calls = [], errors = [], successes = [], warnings = [], forms = [];
        const user = { id: 1, role: 'ADMIN' };
        const t = key => key;
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
            metadataCrawlProgress: [{ provider: 'GYING', source: 'HITS_MOVIE', startPage: 1, endPage: 50, nextPage: 7, nextItem: 11, status: 'PENDING' }],
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
        const Ranges = loadPage('crawlRanges', mocks);
        const Page = loadPage('resourceHub', { ...mocks, '@/components/admin/MetadataCrawlRanges': Ranges });
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const button = source => all('Button').find(props => props['data-testid'] === 'gying-pause-' + source);
        const last = () => calls[calls.length - 1];
        const reply = (call, data, status = 200) => act(() => call.resolve(new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } })));
        const pause = source => act(() => button(source).onClick());
        const refreshed = paused => ({ ...config, gyingAutoSyncPausedSources: { ...config.gyingAutoSyncPausedSources, HITS_MOVIE: paused },
            metadataCrawlProgress: config.metadataCrawlProgress.map(row => ({ ...row, status: paused ? 'PAUSED' : 'PENDING' })) });
        await act(() => root.render(React.createElement(Page)));
        await reply(calls.find(call => call.url.endsWith('/overview')), overview);
        return { calls, errors, successes, warnings, forms, config, all, button, last, act, reply, pause, refreshed,
            save: values => act(() => all('Form').find(props => props.form === forms[0]).onFinish(values)),
            dispose: async () => { await act(() => root.unmount()); container.remove(); },
        };
    }
    async function check(name, run) {
        let f;
        try { f = await fixture(); await run(f); results.push({ name, ok: true }); }
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
    return results;
}

test('GYING pause controls: real React, source-scoped mocked API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(pauseSuite, {
        resourceHub: 'src/app/admin/resource-hub/page.tsx',
        crawlRanges: 'src/components/admin/MetadataCrawlRanges.tsx',
    });
    assert.equal(results.length, 8);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
