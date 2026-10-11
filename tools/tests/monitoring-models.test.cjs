const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../frontend/node_modules/typescript');
const load = file => {
    const exported = {};
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '../../frontend/src/lib/' + file), 'utf8'),
        { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
        { exports: exported, URLSearchParams, Date, Intl, Map, Set });
    return exported;
};
const { count, trafficSeries, monitoringDate, formatMonitoringTime, statusColor, logUrl, parseOverview, parseLogPage } = load('monitoring.ts');
const { resourceCategory, groupResources, resourceCreatedDate } = load('resourceCategories.ts');

test('numeric values are finite non-negative integers', () => {
    for (const bad of [undefined, null, '', -1, '-9', NaN, Infinity, 'not-a-number']) assert.equal(count(bad), 0);
    assert.equal(count('1234'), 1234);
    assert.equal(count(2.8), 2);
});
test('traffic fills missing calendar days without adding artificial activity', () => {
    const series = trafficSeries([{ day: '2026-10-11', requests: 20 }, { day: '2026-10-05', requests: '8' }], 7, '2026-10-11');
    assert.equal(series.length, 7);
    assert.equal(series[0].day, '2026-10-05');
    assert.equal(series.at(-1).day, '2026-10-11');
    assert.equal(series.filter(point => point.requests === 0).length, 5);
    assert.equal(series.reduce((sum, point) => sum + point.requests, 0), 28);
});
test('empty traffic, range boundaries and invalid values remain zero', () => {
    const series = trafficSeries([{ day: '2026-10-20', requests: 900 }, { day: 'invalid', requests: 999 }, { day: '2026-10-11', requests: -20 }], 14, '2026-10-11');
    assert.equal(series[0].day, '2026-09-28');
    assert.ok(series.every(point => point.requests === 0));
    assert.ok(trafficSeries([], 14, '2026-10-11').every(point => point.visitors === 0));
});
test('timestamps use Beijing time regardless of the test machine timezone', () => {
    const expected = '2026-10-11 08:30:12';
    for (const value of ['2026-10-11 08:30:12', '2026-10-11T08:30:12', '2026-10-11T00:30:12Z', [2026, 10, 11, 8, 30, 12]]) {
        assert.equal(formatMonitoringTime(value), expected);
    }
    assert.equal(formatMonitoringTime('2026-10-10T23:30:00Z'), '2026-10-11 07:30:00');
    assert.equal(formatMonitoringTime(null), '—');
    assert.equal(monitoringDate('invalid'), null);
    assert.equal(monitoringDate({}), null);
});
test('publishing unknown and in-flight states never look successful', () => {
    assert.equal(statusColor('POSTED', 'socialPosts'), 'green');
    assert.equal(statusColor('PREPARE_FAILED', 'socialPosts'), 'red');
    assert.equal(statusColor('UNKNOWN', 'socialPosts'), 'orange');
    assert.equal(statusColor('FAILED', 'socialPosts'), 'orange');
    for (const status of ['PENDING', 'PREPARING', 'PUBLISHING']) assert.equal(statusColor(status), 'processing');
    for (const status of ['SUCCEEDED', 'LIBRARY_RESOURCE', 'RESOURCE_SELECTION']) assert.equal(statusColor(status), 'green');
});
test('log URL preserves literal search text and server-side pagination/filter', () => {
    const url = new URL(logUrl({ type: 'socialPosts', q: ' a&b% + 测试 ', status: 'attention', page: 4, size: 50 }), 'http://localhost');
    assert.equal(url.searchParams.get('q'), 'a&b% + 测试');
    assert.equal(url.searchParams.get('type'), 'socialPosts');
    assert.equal(url.searchParams.get('page'), '4');
    assert.equal(url.searchParams.get('status'), 'attention');
});
test('malformed responses are errors, not fabricated empty successful snapshots', () => {
    for (const value of [null, {}, [], { requestsToday: 1 }]) assert.throws(() => parseOverview(value));
    for (const value of [null, {}, { records: [], total: 0, page: 0, size: 20 }, { records: [{}], total: 1, page: 1, size: 20 }]) assert.throws(() => parseLogPage(value));
    assert.equal(parseLogPage({ records: [], total: 0, page: 1, size: 20 }).total, 0);
});
test('only magnets and torrents count as P2P, even when delivered through cloud drives', () => {
    assert.equal(resourceCategory('DISK'), 'disk');
    for (const type of ['MAGNET', 'TORRENT', ' torrent ']) assert.equal(resourceCategory(type), 'p2p');
    assert.equal(resourceCategory('ONLINE'), 'online');
    for (const type of ['FUTURE_TYPE', '', undefined, null, 123]) assert.equal(resourceCategory(type), 'other');
    const records = [{ id: 1, type: 'DISK', provider: 'QUARK' }, { id: 2, type: 'TORRENT', provider: 'QUARK' }, { id: 3, type: 'MAGNET' }, { id: 4, type: 'ONLINE' }, { id: 5, type: 'FUTURE_TYPE' }];
    const groups = groupResources(records);
    assert.deepEqual(Array.from(groups.disk, row => row.id), [1]);
    assert.deepEqual(Array.from(groups.p2p, row => row.id), [2, 3]);
    assert.deepEqual(Array.from(groups.online, row => row.id), [4]);
    assert.deepEqual(Array.from(groups.other, row => row.id), [5]);
    assert.equal(Object.values(groups).flat().length, records.length);
    assert.deepEqual(records.map(row => row.id), [1, 2, 3, 4, 5]);
});
test('empty and large resource sets retain exact counts without 99+ truncation', () => {
    const empty = groupResources([]);
    assert.ok(Object.values(empty).every(rows => rows.length === 0));
    const many = groupResources(Array.from({ length: 137 }, (_, id) => ({ id, type: 'TORRENT' })));
    assert.equal(many.p2p.length, 137);
    assert.equal(many.disk.length, 0);
});
test('English and Chinese cover identical monitoring keys and placeholders', () => {
    const translations = ['en', 'zh'].map(lang => JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../frontend/public/locales/' + lang + '/common.json'), 'utf8')));
    const flatten = (value, prefix = '') => Object.fromEntries(Object.entries(value).flatMap(([key, entry]) => typeof entry === 'object' ? Object.entries(flatten(entry, prefix + key + '.')) : [[prefix + key, entry]]));
    const [en, zh] = translations.map(value => flatten(value.monitoring));
    assert.deepEqual(Object.keys(en).sort(), Object.keys(zh).sort());
    for (const key of Object.keys(en)) {
        assert.ok(en[key] && zh[key], key);
        assert.deepEqual((en[key].match(/\{\{.*?\}\}/g) || []).sort(), (zh[key].match(/\{\{.*?\}\}/g) || []).sort(), key);
    }
    for (const value of translations) assert.ok(value.resourceCategoryCount && value.resourceOtherCategory);
});

test('resource dates are identical in SSR and browsers regardless of OS locale', () => {
    for (const date of ['2026-10-11T08:30:12', '2026-10-11 08:30:12', '2026-10-11']) assert.equal(resourceCreatedDate(date), '2026-10-11');
    for (const date of [null, undefined, 'invalid', {}]) assert.equal(resourceCreatedDate(date), '—');
});
