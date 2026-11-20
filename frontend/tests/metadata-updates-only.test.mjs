import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';

// Exercise the real JSX controls and config-save callback with a small in-memory
// hook/form fixture. No DOM, browser, server, credentials or external requests.
const require = createRequire(import.meta.url);
const jsx = require('react/jsx-runtime');
const frontend = new URL('../', import.meta.url);
const compile = file => ts.transpileModule(readFileSync(new URL(file, frontend), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020,
        jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
}).outputText;
const pageCode = compile('src/app/admin/resource-hub/page.tsx');
const rangesCode = compile('src/components/admin/MetadataCrawlRanges.tsx');
const locales = Object.fromEntries(['zh', 'en'].map(lang => [lang,
    JSON.parse(readFileSync(new URL(`public/locales/${lang}/common.json`, frontend), 'utf8'))]));

async function fixture(patch = {}, language = 'en') {
    let cells = [], cursor = 0, effects = [], tree;
    const forms = [], calls = [], errors = [];
    let config = {
        enabled: true, workerEnabled: true, tmdbConfigured: true, autoApprove: false,
        tmdbAutoSyncEnabled: true, tmdbAutoSyncSources: 'POPULAR_MOVIE',
        tmdbAutoSyncPage: 465, tmdbAutoSyncEndPage: 500,
        tmdbAutoSyncRanges: { POPULAR_MOVIE: { startPage: 465, endPage: 500 } },
        tmdbAutoSyncIntervalHours: 2, tmdbAutoSyncMaxItems: 20,
        tmdbAutoDiscoveryEnabled: false, tmdbDiscoveryMaxResults: 7, tmdbDiscoveryCooldownHours: 72,
        gyingAutoSyncEnabled: true, gyingAutoSyncSources: 'HITS_TV,CSCORE_TV',
        gyingAutoSyncPage: 1, gyingAutoSyncEndPage: 50,
        gyingAutoSyncRanges: { HITS_TV: { startPage: 1, endPage: 2 }, CSCORE_TV: { startPage: 17, endPage: 50 } },
        gyingAutoSyncPausedSources: { HITS_TV: false, CSCORE_TV: true },
        gyingAutoSyncIntervalHours: 1, gyingAutoSyncMaxItems: 20,
        gyingDiscoveryEnabled: false,
        weeklyTransferSchedules: [{ typeCode: 'mv', enabled: false, intervalDays: 7, maxItems: 3 }],
        metadataCrawlProgress: [
            { provider: 'TMDB', source: 'POPULAR_MOVIE', startPage: 465, endPage: 500, nextPage: 487, nextItem: 1, status: 'PENDING' },
            { provider: 'GYING', source: 'HITS_TV', startPage: 1, endPage: 2, nextPage: 1, nextItem: 20, status: 'PENDING' },
        ], ...patch,
    };
    const t = (key, values = {}) => {
        let value = locales[language][key] ?? values.defaultValue ?? key;
        for (const [name, text] of Object.entries(values)) value = value.replaceAll(`{{${name}}}`, String(text));
        return value;
    };
    const React = {
        useState(initial) {
            const index = cursor++;
            if (!(index in cells)) cells[index] = typeof initial === 'function' ? initial() : initial;
            return [cells[index], value => { cells[index] = typeof value === 'function' ? value(cells[index]) : value; }];
        },
        useRef(initial) { return React.useState(() => ({ current: initial }))[0]; },
        useMemo: callback => callback(), useCallback: callback => callback,
        useEffect: callback => effects.push(callback), useSyncExternalStore: (_, snapshot) => snapshot(),
    };
    const component = name => Object.assign(() => null, { fixtureName: name });
    const components = Object.fromEntries(['Alert', 'Button', 'Card', 'Col', 'Descriptions', 'Empty', 'Input', 'InputNumber',
        'Row', 'Select', 'Space', 'Statistic', 'Switch', 'Table', 'Tabs', 'Tag', 'Tooltip'].map(name => [name, component(name)]));
    components.Space.Compact = component('Space.Compact');
    components.Descriptions.Item = component('Descriptions.Item');
    components.Input.Password = component('Input.Password');
    components.Input.TextArea = component('Input.TextArea');
    components.Input.Search = component('Input.Search');
    components.Empty.PRESENTED_IMAGE_SIMPLE = 'empty';
    const Form = component('Form');
    Form.useForm = () => React.useState(() => {
        const form = { values: {}, setFieldsValue: values => Object.assign(form.values, values),
            getFieldsValue: () => structuredClone(form.values) };
        forms.push(form); return form;
    });
    Form.useWatch = key => forms[0].values[key];
    Form.Item = component('Form.Item'); Form.List = component('Form.List');
    const api = async (url, options = {}) => {
        calls.push({ url, options });
        if (url.endsWith('/config') && options.method === 'PUT') config = { ...config, ...JSON.parse(options.body) };
        const data = url.endsWith('/overview')
            ? { config, enabled: true, worker: { enabled: true, publishLimit: 1 }, taskStatusCounts: {} }
            : url.endsWith('/config') ? config : { records: [], total: 0 };
        return { ok: true, json: async () => ({ data }) };
    };
    const mocks = {
        react: React, 'react/jsx-runtime': jsx,
        antd: { ...components, Form, App: { useApp: () => ({ message: { error: value => errors.push(value), success() {}, warning() {} } }) },
            Typography: { Title: component('Title'), Text: component('Text'), Paragraph: component('Paragraph') } },
        '@ant-design/icons': new Proxy({}, { get: () => component('Icon') }),
        '@/lib/api': { api, readApiError: async () => 'fixture request failed' },
        '@/store/authStore': { useAuthStore: () => ({ user: { id: 1, role: 'ADMIN' }, token: 'synthetic-test-token' }) },
        'next/navigation': { useRouter: () => ({ push() {} }) },
        'react-i18next': { useTranslation: () => ({ t }) },
        '@/components/admin/DriveWorkflowSettings': { default: component('DriveWorkflowSettings') },
        '@/components/admin/DuplicateMovieScanner': { default: component('DuplicateMovieScanner') },
        '@/lib/cloudDriveWorkflows': {},
    };
    function load(code) {
        const record = { exports: {} };
        new Function('module', 'exports', 'require', code)(record, record.exports, name => {
            if (!(name in mocks)) throw new Error('Unexpected fixture dependency: ' + name);
            return mocks[name];
        });
        return record.exports;
    }
    mocks['@/components/admin/MetadataCrawlRanges'] = { __esModule: true, ...load(rangesCode) };
    const Page = load(pageCode).default;
    const render = () => { cursor = 0; effects = []; tree = Page(); return tree; };
    function all(kind) {
        const found = [];
        function visit(node) {
            if (!node || typeof node !== 'object') return;
            if (Array.isArray(node)) { node.forEach(visit); return; }
            if (!node.props) return;
            if (node.type.fixtureName === kind || node.type.name === kind) found.push(node);
            if (['MetadataUpdatesOnlySettings', 'MetadataSyncIntervalHelp', 'MetadataCrawlRanges'].includes(node.type.name)) visit(node.type(node.props));
            visit(node.props.children); node.props.items?.forEach(item => visit(item.children));
        }
        visit(tree); return found;
    }
    render(); effects.forEach(effect => effect());
    await new Promise(resolve => setImmediate(resolve)); render();
    function toggle(provider, value) {
        const item = all('Form.Item').find(node => node.props.name === `${provider}AutoSyncUpdatesOnly`);
        assert.equal(item.props.valuePropName, 'checked', 'switch is bound to the boolean checked value');
        assert.equal(item.props.children.type.fixtureName, 'Switch');
        assert.equal(item.props.children.props['data-testid'], `${provider}-updates-only-switch`);
        forms[0].setFieldsValue({ [item.props.name]: value }); render();
    }
    async function save() {
        const form = all('Form').find(node => node.props.form === forms[0]);
        await form.props.onFinish(forms[0].getFieldsValue()); render();
        assert.deepEqual(errors, []);
        return JSON.parse(calls.findLast(call => call.url.endsWith('/config') && call.options.method === 'PUT').options.body);
    }
    const text = node => {
        if (typeof node === 'string' || typeof node === 'number') return String(node);
        if (!node) return '';
        if (Array.isArray(node)) return node.map(text).join(' ');
        if (!node.props) return '';
        return [node.props.children, node.props.title, node.props.description, node.props.extra].map(text).join(' ');
    };
    return { forms, calls, all, toggle, save, render, text, t };
}

test('legacy config defaults both updates-only switches to false', async () => {
    const f = await fixture();
    assert.equal(f.forms[0].values.tmdbAutoSyncUpdatesOnly, false);
    assert.equal(f.forms[0].values.gyingAutoSyncUpdatesOnly, false);
    assert.ok(f.all('InputNumber').every(node => node.props.disabled !== true));
});

test('switches save independently and preserve full ranges and manual settings', async () => {
    const f = await fixture(); const before = f.forms[0].getFieldsValue();
    f.toggle('tmdb', true); const body = await f.save();
    assert.equal(body.tmdbAutoSyncUpdatesOnly, true); assert.equal(body.gyingAutoSyncUpdatesOnly, false);
    for (const key of ['tmdbAutoSyncRanges', 'gyingAutoSyncRanges', 'tmdbAutoSyncIntervalHours', 'gyingAutoSyncIntervalHours',
        'tmdbAutoSyncMaxItems', 'gyingAutoSyncMaxItems', 'weeklyTransferSchedules', 'tmdbAutoDiscoveryEnabled', 'gyingDiscoveryEnabled'])
        assert.deepEqual(body[key], before[key], key + ' is retained');
    assert.equal(body.tmdbAutoSyncSources, 'POPULAR_MOVIE'); assert.equal(body.gyingAutoSyncSources, 'HITS_TV,CSCORE_TV');
    assert.equal(Object.hasOwn(body, 'gyingAutoSyncPausedSources'), false, 'separate pause map is not overwritten');
    assert.ok(f.calls.every(call => !call.options.method || call.url.endsWith('/config')), 'no manual runs or provider actions');
});

test('updates-only may remain selected while automatic collection is fully paused', async () => {
    const f = await fixture({ tmdbAutoSyncEnabled: false, gyingAutoSyncEnabled: false });
    f.toggle('tmdb', true); f.toggle('gying', true);
    const modeAlerts = f.all('Alert').filter(node => node.props.title === 'Automatic update checks are paused');
    assert.equal(modeAlerts.length, 2); assert.ok(modeAlerts.every(node => node.props.type === 'warning'));
    assert.ok(modeAlerts.every(node => f.text(node).includes('update checks are not running')));
    assert.ok(modeAlerts.every(node => !f.text(node).includes('Check every')));
    const body = await f.save();
    assert.equal(body.tmdbAutoSyncEnabled, false); assert.equal(body.gyingAutoSyncEnabled, false);
    assert.equal(body.tmdbAutoSyncUpdatesOnly, true); assert.equal(body.gyingAutoSyncUpdatesOnly, true);
});

test('effective update schedule caps details across all selected lists', async () => {
    const f = await fixture(); f.toggle('tmdb', true);
    const mode = f.all('Alert').find(node => node.props.title === 'Check updates only');
    assert.match(f.text(mode), /every 24 hours/); assert.match(f.text(mode), /at most 10 details/);
    assert.match(f.text(mode), /across all selected lists/);
    assert.match(f.text(mode), /pages 1–2/);
});

test('longer interval and smaller detail budget remain visible', async () => {
    const f = await fixture({ gyingAutoSyncUpdatesOnly: true, gyingAutoSyncIntervalHours: 72, gyingAutoSyncMaxItems: 3 });
    const mode = f.all('Alert').find(node => node.props.title === 'Check updates only');
    assert.match(f.text(mode), /every 72 hours/); assert.match(f.text(mode), /at most 3 details/);
});

test('retained 500-page progress is not presented as active full collection', async () => {
    const f = await fixture({ tmdbAutoSyncUpdatesOnly: true });
    const table = f.all('Table').find(node => node.props['data-testid'] === 'metadata-crawl-progress');
    const tmdb = table.props.dataSource.find(row => row.provider === 'TMDB');
    const gying = table.props.dataSource.find(row => row.provider === 'GYING');
    const column = key => table.props.columns.find(item => item.key === key || item.dataIndex === key);
    assert.match(f.text(column('range').render(null, tmdb)), /465–500/);
    assert.match(f.text(column('range').render(null, tmdb)), /Retained full progress/);
    assert.match(column('next').render(null, tmdb), /Retained full cursor/);
    assert.equal(column('status').render(tmdb.status, tmdb), 'Full collection paused (update mode)');
    assert.notEqual(column('status').render(gying.status, gying), 'Full collection paused (update mode)');
    const rangeInputs = f.all('Form.Item').filter(node => Array.isArray(node.props.name) && node.props.name[0] === 'tmdbAutoSyncRanges');
    assert.equal(rangeInputs.length, 2); assert.ok(rangeInputs.every(node => node.props.children.props.disabled === true));
});

test('turning updates-only off restores editable full ranges without resetting them', async () => {
    const f = await fixture({ tmdbAutoSyncUpdatesOnly: true, gyingAutoSyncUpdatesOnly: true });
    const before = f.forms[0].getFieldsValue(); f.toggle('tmdb', false); f.toggle('gying', false);
    assert.ok(f.all('InputNumber').every(node => node.props.disabled !== true));
    const body = await f.save();
    assert.equal(body.tmdbAutoSyncUpdatesOnly, false); assert.equal(body.gyingAutoSyncUpdatesOnly, false);
    assert.deepEqual(body.tmdbAutoSyncRanges, before.tmdbAutoSyncRanges); assert.deepEqual(body.gyingAutoSyncRanges, before.gyingAutoSyncRanges);
});

test('Chinese help explains the two-page range and retained full progress', async () => {
    const f = await fixture({ gyingAutoSyncUpdatesOnly: true }, 'zh');
    const mode = f.all('Alert').find(node => node.props.title === '仅检查更新');
    assert.match(f.text(mode), /24小时/); assert.match(f.text(mode), /10条详情/); assert.match(f.text(mode), /第1–2页/);
    assert.match(f.text(mode), /全量范围不执行/);
});

test('TVmaze legacy defaults keep its independent switch off and use a daily ten-detail budget', async () => {
    const f = await fixture();
    assert.equal(f.forms[0].values.tvmazeAutoSyncEnabled, false);
    assert.equal(f.forms[0].values.tvmazeAutoSyncIntervalHours, 24);
    assert.equal(f.forms[0].values.tvmazeAutoSyncMaxItems, 10);
    const item = f.all('Form.Item').find(node => node.props.name === 'tvmazeAutoSyncEnabled');
    assert.equal(item.props.valuePropName, 'checked');
    assert.equal(item.props.children.props['data-testid'], 'tvmaze-auto-sync-switch');
    assert.ok(f.all('Card').some(node => node.props.title === 'TVmaze show updates'));
});

test('TVmaze saves its own schedule without writing progress or changing existing provider modes', async () => {
    const progress = { taskId: 9, status: 'SUCCEEDED', checkedAt: '2026-11-18T08:00:00Z',
        attempted: 10, processed: 4, pending: 90, deferred: 6, exhausted: 0, coverageGap: false };
    const f = await fixture({ tvmazeAutoSyncEnabled: true, tvmazeAutoSyncIntervalHours: 6,
        tvmazeAutoSyncMaxItems: 3, tvmazeSyncProgress: progress, tmdbAutoSyncUpdatesOnly: true });
    f.forms[0].setFieldsValue({ tvmazeAutoSyncMaxItems: 5 });
    const body = await f.save();
    assert.equal(body.tvmazeAutoSyncEnabled, true); assert.equal(body.tvmazeAutoSyncIntervalHours, 6);
    assert.equal(body.tvmazeAutoSyncMaxItems, 5); assert.equal(body.tmdbAutoSyncUpdatesOnly, true);
    assert.equal(body.gyingAutoSyncUpdatesOnly, false);
    assert.equal(Object.hasOwn(body, 'tvmazeSyncProgress'), false);
    assert.ok(f.text(f.all('Card').find(node => node.props['data-testid'] === 'tvmaze-settings')).includes('Pending 90'));
});

test('TVmaze input limits and Chinese help explain exact identities and bounded discoveries', async () => {
    const f = await fixture({}, 'zh');
    const item = key => f.all('Form.Item').find(node => node.props.name === key).props.children;
    assert.equal(item('tvmazeAutoSyncMaxItems').props.max, 10);
    assert.equal(item('tvmazeAutoSyncIntervalHours').props.min, 1);
    const card = f.all('Card').find(node => node.props['data-testid'] === 'tvmaze-settings');
    assert.match(f.text(card), /100条/); assert.match(f.text(card), /7天/);
    assert.match(f.text(card), /IMDb／TVDB 精确关联/);
});
