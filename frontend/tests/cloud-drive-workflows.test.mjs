import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

// Exercise the real page, per-drive controls and API callbacks without credentials or external traffic.
async function driveSuite({ React, createRoot, loadPage, loadModule, readApiError }) {
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
            weeklyTransferSchedules: [{ typeCode: 'mv', enabled: true, intervalDays: 7, maxItems: 5 }],
            driveWorkflows: scenario.noWorkflows ? undefined : [
                { provider: 'QUARK', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: true,
                    shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: true, savePath: '/movies/quark', batchLimit: 5 },
                { provider: 'XUNLEI', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: scenario.transferEnabled ?? true,
                    shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: false, savePath: '/movies/xunlei', batchLimit: 4 },
                { provider: 'BAIDU', supported: scenario.baiduSupported ?? false, status: scenario.baiduSupported ? 'AVAILABLE' : 'PLANNED',
                    discoveryEnabled: false, transferEnabled: false, shareEnabled: false, gyingPublishEnabled: false,
                    credentialConfigured: false, savePath: null, batchLimit: null },
            ],
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
            else if (!url.endsWith('/config') && !url.endsWith('/pause') && !url.endsWith('/transfers/run')) call.resolve(new Response(JSON.stringify({ data: { records: [], total: 0 } }), { status: 200 }));
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
        mocks['@/lib/cloudDriveWorkflows'] = loadModule('driveWorkflows', mocks);
        const Ranges = loadPage('crawlRanges', mocks);
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
        const byId = id => ['Button', 'Switch', 'Input', 'InputNumber'].flatMap(all).find(props => props['data-testid'] === id);
        const change = (provider, key, value) => act(() => byId('drive-' + provider + '-' + key).onChange(key === 'savePath' ? { target: { value } } : value));
        const saveDrive = provider => act(() => byId('drive-save-' + provider).onClick());
        const updated = (provider, patch) => ({ ...config, driveWorkflows: config.driveWorkflows.map(row => row.provider === provider ? { ...row, ...patch } : row) });
        return { calls, errors, successes, warnings, forms, config, translations, all, button, last, act, reply, pause, refreshed,
            byId, change, saveDrive, updated,
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
    await check('real API null path and batch fields render a read-only Baidu plan', async f => {
        same(f.all('Card').filter(props => String(props['data-testid']).startsWith('drive-card-')).length, 3, 'three cards');
        same(f.config.driveWorkflows[2].savePath, null, 'real Baidu path is null');
        same(f.config.driveWorkflows[2].batchLimit, null, 'real Baidu batch is null');
        same(f.byId('drive-save-BAIDU'), undefined, 'Baidu cannot be saved');
        same(f.byId('drive-run-BAIDU'), undefined, 'Baidu cannot run');
        same(f.byId('drive-BAIDU-transferEnabled'), undefined, 'no unsupported toggles');
        same(f.all('Tag').some(props => props.children === 'cloudDriveWorkflows.credentialPresent'), true, 'configured marker');
        same(f.all('Tag').some(props => props.children === 'cloudDriveWorkflows.credentialMissing'), true, 'missing marker');
        same(f.calls.some(call => ['POST', 'PUT'].includes(call.options.method)), false, 'viewing never starts work');
    });
    await check('saving a drive sends only its writable fields and preserves unrelated drafts', async f => {
        await f.act(() => f.forms[0].setFieldsValue({ enabled: false, gyingAutoSyncRanges: { HITS_MOVIE: { startPage: 3, endPage: 44 } },
            weeklyTransferSchedules: [{ typeCode: 'mv', enabled: false, intervalDays: 10, maxItems: 3 }] }));
        const formUpdates = f.forms[0].updates.length;
        await f.change('XUNLEI', 'savePath', '/unsaved/xunlei');
        await f.change('QUARK', 'discoveryEnabled', false);
        await f.change('QUARK', 'savePath', ' /new/quark ');
        await f.change('QUARK', 'batchLimit', 7);
        await f.saveDrive('QUARK');
        const request = f.last();
        same(request.url, '/api/admin/resource-hub/config', 'existing config endpoint');
        same(JSON.parse(request.options.body), { driveWorkflows: [{ provider: 'QUARK', discoveryEnabled: false,
            transferEnabled: true, gyingPublishEnabled: true, shareEnabled: true, savePath: '/new/quark', batchLimit: 7 }] }, 'only writable single-drive payload');
        await f.reply(request, f.updated('QUARK', { discoveryEnabled: false, savePath: '/new/quark', batchLimit: 7 }));
        same(f.forms[0].updates.length, formUpdates, 'metadata form never replaced');
        same(f.forms[0].values.enabled, false, 'master draft retained');
        same(f.forms[0].values.gyingAutoSyncRanges.HITS_MOVIE, { startPage: 3, endPage: 44 }, 'range draft retained');
        same(f.forms[0].values.weeklyTransferSchedules[0].intervalDays, 10, 'weekly draft retained');
        same(f.byId('drive-XUNLEI-savePath').value, '/unsaved/xunlei', 'other drive draft retained');
        same(f.byId('drive-QUARK-savePath').value, '/new/quark', 'saved normalized path');
        same(f.calls.filter(call => ['POST', 'PUT'].includes(call.options.method)).length, 1, 'save does not start queue');
    });
    await check('out-of-order saves for different drives cannot restore stale settings', async f => {
        await f.change('QUARK', 'batchLimit', 7); await f.saveDrive('QUARK'); const quark = f.last();
        await f.change('XUNLEI', 'batchLimit', 9); await f.saveDrive('XUNLEI'); const xunlei = f.last();
        await f.reply(xunlei, f.updated('XUNLEI', { batchLimit: 9 }));
        await f.reply(quark, f.updated('QUARK', { batchLimit: 7 }));
        same(f.byId('drive-XUNLEI-batchLimit').value, 9, 'newer Xunlei setting retained');
        same(f.byId('drive-QUARK-batchLimit').value, 7, 'Quark setting applied');
        await f.act(() => f.byId('drive-run-XUNLEI').onClick());
        same(JSON.parse(f.last().options.body).limit, 9, 'manual execution uses retained settings');
        await f.reply(f.last(), { submitted: 1, skipped: 0, failed: 0 });
    });
    await check('metadata save excludes independently managed drive configuration', async f => {
        same(Object.hasOwn(f.forms[0].values, 'driveWorkflows'), false, 'drive data not in form');
        await f.save(f.forms[0].getFieldsValue());
        same(Object.hasOwn(JSON.parse(f.last().options.body), 'driveWorkflows'), false, 'no stale drive payload');
        await f.reply(f.last(), f.config);
    });
    await check('failed save keeps edits and allows an identical retry', async f => {
        await f.change('XUNLEI', 'savePath', '/retry/path');
        await f.saveDrive('XUNLEI'); const firstBody = f.last().options.body;
        await f.reply(f.last(), {}, 503);
        same(f.byId('drive-XUNLEI-savePath').value, '/retry/path', 'failed edit retained');
        same(f.byId('drive-save-XUNLEI').disabled, false, 'save enabled again');
        same(f.errors.length, 1, 'failure displayed');
        await f.saveDrive('XUNLEI'); same(f.last().options.body, firstBody, 'retry same update');
        await f.reply(f.last(), f.updated('XUNLEI', { savePath: '/retry/path' }));
    });
    await check('invalid paths and batch sizes do not reach the API', async f => {
        for (const value of ['relative/path', '/movies/../outside', '/bad\\path', '/bad' + String.fromCharCode(0), '/' + 'x'.repeat(300)]) {
            await f.change('QUARK', 'savePath', value); await f.saveDrive('QUARK');
        }
        await f.change('QUARK', 'savePath', '/valid/path');
        for (const value of [0, 21, 1.5, null]) { await f.change('QUARK', 'batchLimit', value); await f.saveDrive('QUARK'); }
        same(f.calls.some(call => call.options.method === 'PUT'), false, 'invalid inputs block writes');
        same(f.errors.length, 9, 'each invalid input is explained');
    });
    await check('double-clicking save sends a single request and blocks edits until completion', async f => {
        await f.change('QUARK', 'batchLimit', 6);
        await f.act(() => { f.byId('drive-save-QUARK').onClick(); f.byId('drive-save-QUARK').onClick(); });
        same(f.calls.filter(call => call.options.method === 'PUT').length, 1, 'one save');
        same(f.byId('drive-QUARK-savePath').disabled, true, 'pending save blocks edits');
        await f.reply(f.last(), f.updated('QUARK', { batchLimit: 6 }));
    });
    await check('manual transfer uses the saved provider and batch size only after an explicit click', async f => {
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'no initial run');
        await f.change('XUNLEI', 'batchLimit', 9);
        same(f.byId('drive-run-XUNLEI').disabled, true, 'unsaved draft cannot run');
        await f.saveDrive('XUNLEI'); await f.reply(f.last(), f.updated('XUNLEI', { batchLimit: 9 }));
        await f.act(() => { f.byId('drive-run-XUNLEI').onClick(); f.byId('drive-run-XUNLEI').onClick(); });
        const runs = f.calls.filter(call => call.url.endsWith('/transfers/run'));
        same(runs.length, 1, 'one explicit run');
        same([runs[0].options.method, JSON.parse(runs[0].options.body)], ['POST', { provider: 'XUNLEI', limit: 9 }], 'saved provider and limit');
        await f.reply(runs[0], { submitted: 2, skipped: 1, failed: 1 });
        same(f.warnings, ['cloudDriveWorkflows.transferResult'], 'partial failures visible');
    });
    await check('disabled transfer cannot be invoked even directly', async f => {
        same(f.byId('drive-run-XUNLEI').disabled, true, 'transfer button disabled');
        await f.act(() => f.byId('drive-run-XUNLEI').onClick());
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'no transfer request');
        same(f.byId('drive-XUNLEI-discoveryEnabled').disabled, false, 'discovery remains independently configurable');
    }, { transferEnabled: false });
    await check('share warning describes new-share gating and keeps GYING publishing independent', async f => {
        await f.change('QUARK', 'shareEnabled', false);
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.shareDisabledHelp'), true, 'share gating explained');
        same(f.byId('drive-QUARK-gyingPublishEnabled').checked, true, 'existing shares can still publish');
    });
    await check('Baidu stays read-only even if API mistakenly advertises it as available', async f => {
        same(f.byId('drive-save-BAIDU'), undefined, 'no Baidu save');
        same(f.byId('drive-run-BAIDU'), undefined, 'no Baidu run');
    }, { baiduSupported: true });
    await check('older API without workflows does not invent enabled drive state', async f => {
        same(f.all('Card').some(props => String(props['data-testid']).startsWith('drive-card-')), false, 'no fabricated controls');
        same(f.all('Empty').some(props => props.description === 'cloudDriveWorkflows.unavailable'), true, 'clear unavailable state');
    }, { noWorkflows: true });
    return results;
}

test('Cloud drive configuration: real React page and per-provider API', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(driveSuite, {
        resourceHub: 'src/app/admin/resource-hub/page.tsx',
        crawlRanges: 'src/components/admin/MetadataCrawlRanges.tsx',
        driveSettings: 'src/components/admin/DriveWorkflowSettings.tsx',
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
    });
    assert.equal(results.length, 12);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
