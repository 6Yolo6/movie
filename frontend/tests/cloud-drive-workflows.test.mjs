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
    const backfill = patch => ({ taskId: null, enabled: false, status: 'PAUSED', phase: 'GYING_TORRENTS', intervalMinutes: 10, batchLimit: 1,
        processed: 0, succeeded: 0, skipped: 0, failed: 0, nextRunAt: null, blockedReason: null, ...patch });
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
                { provider: 'QUARK', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: true, videoEnabled: true, torrentEnabled: true,
                    shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: true, savePath: '/movies/quark', batchLimit: 5 },
                { provider: 'XUNLEI', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: scenario.transferEnabled ?? true, videoEnabled: true, torrentEnabled: true,
                    shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: false, savePath: '/movies/xunlei', batchLimit: 4 },
                { provider: 'BAIDU', supported: scenario.baiduSupported ?? false, status: scenario.baiduSupported ? 'AVAILABLE' : 'PLANNED',
                    discoveryEnabled: false, transferEnabled: scenario.baiduTransferEnabled ?? false, shareEnabled: scenario.baiduShareEnabled ?? false, gyingPublishEnabled: false, videoEnabled: scenario.baiduVideoEnabled ?? false, torrentEnabled: scenario.baiduTorrentEnabled ?? false,
                    credentialConfigured: scenario.baiduConfigured ?? false, savePath: scenario.baiduSupported ? '/GYing Resource Hub' : null, batchLimit: scenario.baiduSupported ? 5 : null },
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
            else if (url.endsWith('/baidu/session')) call.resolve(new Response(JSON.stringify({ data: { configured: scenario.baiduConfigured ?? false, state: 'DISCONNECTED', ticketId: null, expiresAt: null, accountName: null, lastValidatedAt: null, failureCode: null } }), { status: 200 }));
            else if (url.endsWith('/baidu/backfill') && !options.method) call.resolve(new Response(JSON.stringify({ data: backfill() }), { status: 200 }));
            else if (url.includes('/tasks?')) call.resolve(new Response(JSON.stringify({ data: { records: scenario.tasks || [], total: scenario.tasks?.length || 0 } }), { status: 200 }));
            else if (!url.endsWith('/config') && !url.endsWith('/pause') && !url.endsWith('/transfers/run') && !url.endsWith('/baidu/login') && !url.endsWith('/baidu/backfill') && !url.endsWith('/baidu/backfill/run')) call.resolve(new Response(JSON.stringify({ data: { records: [], total: 0 } }), { status: 200 }));
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
        mocks['@/components/admin/BaiduAccountConnection'] = loadPage('baiduAccount', mocks);
        mocks['@/components/admin/BaiduBackfillControls'] = loadPage('baiduBackfill', mocks);
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
        same(JSON.parse(request.options.body), { driveWorkflows: [{ provider: 'QUARK', discoveryEnabled: false, videoEnabled: true, torrentEnabled: true,
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
    await check('supported Baidu settings can be saved without a session while transfer stays blocked', async f => {
        same(f.byId('drive-BAIDU-transferEnabled').disabled, false, 'supported Baidu can configure');
        await f.change('BAIDU', 'savePath', '/baidu/new');
        await f.change('BAIDU', 'transferEnabled', true);
        await f.saveDrive('BAIDU');
        const save = f.last();
        same(JSON.parse(save.options.body).driveWorkflows[0].provider, 'BAIDU', 'Baidu-only save');
        await f.reply(save, f.updated('BAIDU', { savePath: '/baidu/new', transferEnabled: true }));
        same(f.byId('drive-run-BAIDU').disabled, true, 'session required to transfer');
        await f.act(() => f.byId('drive-run-BAIDU').onClick());
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'direct invoke cannot bypass session check');
        same(f.calls.some(call => call.url.endsWith('/baidu/login')), false, 'configuration save never starts login');
    }, { baiduSupported: true });
    await check('Baidu login starts only from its explicit button and sends no credentials', async f => {
        await f.act(() => { f.byId('baidu-start-login').onClick(); f.byId('baidu-start-login').onClick(); });
        const logins = f.calls.filter(call => call.url.endsWith('/baidu/login'));
        same(logins.length, 1, 'one explicit request');
        same([logins[0].options.method, logins[0].options.body], ['POST', '{}'], 'no password or session payload');
        await f.reply(logins[0], { configured: false, state: 'EXPIRED', ticketId: 'fixture-ticket', expiresAt: Date.now() - 1, accountName: null, lastValidatedAt: null, failureCode: null });
        same(f.byId('drive-BAIDU-transferEnabled').checked, false, 'login does not enable transfer');
    }, { baiduSupported: true });
    await check('a configured Baidu transfer still needs an explicit run action', async f => {
        same(f.byId('drive-run-BAIDU').disabled, false, 'configured enabled provider can run');
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'no automatic transfer');
        await f.act(() => f.byId('drive-run-BAIDU').onClick());
        same(JSON.parse(f.last().options.body), { provider: 'BAIDU', limit: 5 }, 'Baidu-specific manual action');
        await f.reply(f.last(), { submitted: 1 });
    }, { baiduSupported: true, baiduConfigured: true, baiduTransferEnabled: true, baiduVideoEnabled: true });
    await check('account refresh updates only Baidu credentials while preserving newer saved file choices', async f => {
        await f.act(() => f.forms[0].setFieldsValue({ enabled: false })); const updates = f.forms[0].updates.length;
        await f.act(() => f.byId('baidu-start-login').onClick()); const login = f.last();
        await f.reply(login, { configured: true, state: 'CONNECTED', ticketId: 'fixture-ticket', expiresAt: Date.now() + 600000,
            accountName: 'f***e', lastValidatedAt: Date.now(), failureCode: null });
        const accountRead = f.calls.find(call => call.url.endsWith('/config') && !call.options.method);
        same(Boolean(accountRead), true, 'login refreshes persisted account configuration');
        await f.change('BAIDU', 'videoEnabled', true); await f.change('BAIDU', 'savePath', '/new/baidu');
        await f.saveDrive('BAIDU'); await f.reply(f.last(), f.updated('BAIDU', { videoEnabled: true, savePath: '/new/baidu' }));
        await f.reply(accountRead, f.updated('BAIDU', { credentialConfigured: true }));
        same(f.byId('drive-BAIDU-videoEnabled').checked, true, 'newer file type remains');
        same(f.byId('drive-BAIDU-savePath').value, '/new/baidu', 'newer path remains');
        same(f.byId('drive-BAIDU-transferEnabled').checked, false, 'login never enables a stage');
        same(f.forms[0].updates.length, updates, 'unrelated form draft untouched');
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'login never transfers');
    }, { baiduSupported: true });
    await check('per-drive file type choices persist independently and never enable workflow stages', async f => {
        await f.change('XUNLEI', 'torrentEnabled', false);
        await f.change('QUARK', 'videoEnabled', false);
        await f.saveDrive('QUARK'); const request = f.last();
        const payload = JSON.parse(request.options.body).driveWorkflows[0];
        same([payload.videoEnabled, payload.torrentEnabled], [false, true], 'torrent-only Quark payload');
        await f.reply(request, f.updated('QUARK', { videoEnabled: false }));
        same(f.byId('drive-XUNLEI-torrentEnabled').checked, false, 'Xunlei draft retained');
        same(f.byId('drive-QUARK-videoEnabled').checked, false, 'Quark saved selection');
        same(f.byId('drive-QUARK-transferEnabled').checked, true, 'stage kept');
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'type selection never transfers');
    });
    await check('both file types off can save but cannot run a manual cloud transfer', async f => {
        await f.change('QUARK', 'videoEnabled', false); await f.change('QUARK', 'torrentEnabled', false);
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.noFilesSelected'), true, 'no selection explained');
        await f.saveDrive('QUARK'); await f.reply(f.last(), f.updated('QUARK', { videoEnabled: false, torrentEnabled: false }));
        same(f.byId('drive-run-QUARK').disabled, true, 'run disabled');
        await f.act(() => f.byId('drive-run-QUARK').onClick());
        same(f.calls.some(call => call.url.endsWith('/transfers/run')), false, 'guard prevents direct run');
        same(f.byId('drive-QUARK-discoveryEnabled').checked, true, 'metadata discovery settings unchanged');
    });
    await check('backfill reads stay idle and schedule writes use an isolated strict payload', async f => {
        same(f.calls.filter(call => call.url.endsWith('/baidu/backfill')).length, 1, 'one initial GET');
        same(f.byId('baidu-backfill-enabled').checked, false, 'off by default');
        same(f.calls.some(call => ['PUT', 'POST'].includes(call.options.method)), false, 'view is read only');
        await f.act(() => f.byId('baidu-backfill-intervalMinutes').onChange(30));
        await f.act(() => f.byId('baidu-backfill-batchLimit').onChange(2));
        await f.act(() => f.byId('baidu-backfill-save').onClick());
        const request = f.last();
        same([request.url, request.options.method, JSON.parse(request.options.body)],
            ['/api/admin/resource-hub/baidu/backfill', 'PUT', { enabled: false, intervalMinutes: 30, batchLimit: 2 }], 'isolated scheduling API');
        await f.reply(request, backfill({ taskId: 9, intervalMinutes: 30, batchLimit: 2 }));
        same(f.byId('drive-BAIDU-videoEnabled').checked, false, 'schedule keeps videos off');
        same(f.byId('drive-BAIDU-transferEnabled').checked, false, 'schedule keeps transfer stage off');
        same(f.calls.some(call => call.url.endsWith('/baidu/backfill/run')), false, 'saving paused schedule does not run');
    }, { baiduSupported: true });
    await check('manual backfill is explicit and its page callback sends only an empty POST', async f => {
        same(f.byId('baidu-backfill-run').disabled, false, 'saved torrent workflow can backfill without discovery');
        same(f.calls.some(call => call.url.endsWith('/baidu/backfill/run')), false, 'no auto run');
        await f.act(() => { f.byId('baidu-backfill-run').onClick(); f.byId('baidu-backfill-run').onClick(); });
        const runs = f.calls.filter(call => call.url.endsWith('/baidu/backfill/run'));
        same(runs.length, 1, 'one manual batch');
        same([runs[0].options.method, runs[0].options.body], ['POST', '{}'], 'no credential or workflow payload');
        await f.reply(runs[0], backfill({ taskId: 9, processed: 1, succeeded: 1 }));
        same(f.byId('drive-BAIDU-videoEnabled').checked, false, 'torrent-only workflow kept');
    }, { baiduSupported: true, baiduConfigured: true, baiduTransferEnabled: true, baiduShareEnabled: true, baiduTorrentEnabled: true });
    await check('workflow drafts prevent manual backfill and malformed API errors stay fixed', async f => {
        await f.change('BAIDU', 'videoEnabled', true);
        same(f.byId('baidu-backfill-run').disabled, true, 'unsaved file selection blocks backfill');
        await f.act(() => f.byId('baidu-backfill-run').onClick());
        same(f.calls.some(call => call.url.endsWith('/baidu/backfill/run')), false, 'direct click guarded');
        await f.act(() => f.byId('baidu-backfill-refresh').onClick());
        // The GET fixture resolves immediately with safe metadata, so exercise the strict decoder via a write response.
        await f.act(() => f.byId('baidu-backfill-intervalMinutes').onChange(20));
        await f.act(() => f.byId('baidu-backfill-save').onClick());
        await f.reply(f.last(), { ...backfill(), status: 'BDUSS=raw-cookie' });
        same(f.all('Alert').some(props => props.title === 'cloudDriveWorkflows.baiduBackfill.saveFailed'), true, 'fixed invalid response error');
        same(f.all('Alert').some(props => String(props.title).includes('raw-cookie')), false, 'no raw response exposed');
    }, { baiduSupported: true, baiduConfigured: true, baiduTransferEnabled: true, baiduShareEnabled: true, baiduTorrentEnabled: true });
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
        baiduAccount: 'src/components/admin/BaiduAccountConnection.tsx',
        baiduBackfill: 'src/components/admin/BaiduBackfillControls.tsx',
        driveWorkflows: 'src/lib/cloudDriveWorkflows.ts',
    });
    assert.equal(results.length, 20);
    for (const result of results) await t.test(result.name, () => assert.ok(result.ok, result.error));
});
