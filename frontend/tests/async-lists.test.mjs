import assert from 'node:assert/strict';
import test from 'node:test';
import { runOfflineReact } from './helpers/offline-react.mjs';

// Run the actual pages with real React effects/state. Ant Design, auth and API
// are fixture adapters; the API deliberately ignores abort to expose stale commits.
async function regressionSuite({ React, createRoot, loadPage, readApiError }) {
    const results = [];
    const same = (actual, expected, label) => {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) {
            throw new Error(label + ': expected ' + JSON.stringify(expected) + ', got ' + JSON.stringify(actual));
        }
    };
    const deferred = () => {
        let resolve, reject;
        const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
        return { promise, resolve, reject };
    };
    const hot = id => [{ movie: { id }, favoriteCount: 1 }];
    const notification = id => ({ id, title: 'fixture-' + id, type: 'RESOURCE_AUDIT', readFlag: false, targetType: 'RESOURCE' });
    const pageData = (id, total = 1) => ({ records: id === null ? [] : [notification(id)], total });
    const params = call => Object.fromEntries(new URL(call.url, 'https://fixture.invalid').searchParams);

    async function fixture(pageName, strict = false) {
        const container = document.createElement('div');
        document.body.append(container);
        const root = createRoot(container);
        const calls = [], errors = [], successes = [], refreshEvents = [];
        let token = 'fixture-account-a';
        let mounted = true;
        const t = key => key;
        const message = { error: text => errors.push(text), success: text => successes.push(text) };
        const api = (url, options = {}) => {
            const request = { url, options, ...deferred() };
            calls.push(request);
            return request.promise;
        };
        const capture = name => function FixtureComponent(props) {
            return React.createElement('div', {
                'data-fixture': name,
                ref: node => { if (node) node.fixtureProps = props; },
            }, props.children, props.action);
        };
        const components = Object.fromEntries(['Alert', 'Badge', 'Button', 'Card', 'Empty', 'List', 'Space', 'Switch', 'Tag', 'Spin', 'Segmented'].map(name => [name, capture(name)]));
        components.Empty.PRESENTED_IMAGE_SIMPLE = 'fixture-empty';
        components.List.Item = capture('List.Item');
        components.List.Item.Meta = capture('List.Item.Meta');
        const icons = Object.fromEntries(['BellOutlined', 'CheckOutlined', 'CloudUploadOutlined', 'CommentOutlined', 'FireOutlined', 'HeartFilled'].map(name => [name, () => null]));
        const Page = loadPage(pageName, {
            antd: { ...components, App: { useApp: () => ({ message }) }, Typography: { Title: capture('Title'), Text: capture('Text'), Paragraph: capture('Paragraph') } },
            '@ant-design/icons': icons,
            '@/lib/api': { api, readApiError },
            '@/store/authStore': { useAuthStore: () => ({ token }) },
            '@/components/MovieCard': capture('MovieCard'),
            'next/link': capture('Link'),
            'react-i18next': { useTranslation: () => ({ t }) },
        });
        const act = async action => { await React.act(async () => { action(); }); };
        const render = () => root.render(strict ? React.createElement(React.StrictMode, null, React.createElement(Page)) : React.createElement(Page));
        const onRefresh = () => refreshEvents.push('refresh');
        window.addEventListener('notifications:refresh', onRefresh);
        const all = name => Array.from(container.querySelectorAll('[data-fixture="' + name + '"]'), node => node.fixtureProps);
        const props = name => {
            const found = all(name)[0];
            if (!found) throw new Error('Missing rendered ' + name);
            return found;
        };
        const f = {
            calls, errors, successes, refreshEvents, all, props, act,
            last: () => calls[calls.length - 1],
            hotIds: () => all('MovieCard').map(p => p.movie.id),
            notificationIds: () => all('List').flatMap(p => p.dataSource.map(item => item.id)),
            changeMode: value => act(() => props('Segmented').onChange(value)),
            changePeriod: value => act(() => all('Segmented')[1].onChange(value)),
            changePage: value => act(() => props('List').pagination.onChange(value)),
            changeUnread: value => act(() => props('Switch').onChange(value)),
            changeToken: value => act(() => { token = value; render(); }),
            markAll: () => act(() => all('Button').find(p => p.children === 'markAllRead').onClick()),
            markFirst: () => act(() => {
                const list = props('List');
                const item = list.renderItem(list.dataSource[0]);
                item.props.actions.find(action => action?.key === 'read').props.onClick();
            }),
            retry: () => act(() => props('Alert').action.props.onClick()),
            reply: (call, data, status = 200) => act(() => call.resolve(new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } }))),
            fail: call => act(() => call.reject(new Error('fixture-network-failure'))),
            unmount: async () => { if (mounted) { await act(() => root.unmount()); mounted = false; } },
            dispose: async () => { await f.unmount(); window.removeEventListener('notifications:refresh', onRefresh); container.remove(); },
        };
        await act(render);
        return f;
    }
    async function check(name, pageName, run, strict = false) {
        let f;
        try {
            f = await fixture(pageName, strict);
            await run(f);
            results.push({ name, ok: true });
        } catch (error) {
            results.push({ name, ok: false, error: error.stack || String(error) });
        } finally {
            if (f) await f.dispose();
        }
    }

    await check('hot: a late previous-mode success cannot overwrite the selected ranking', 'hot', async f => {
        const old = f.last();
        await f.changeMode('tmdb');
        await f.reply(f.last(), hot('tmdb-new'));
        await f.reply(old, hot('favorite-old'));
        same(f.hotIds(), ['tmdb-new'], 'selected ranking');
        same(old.options.signal?.aborted, true, 'superseded request cancelled');
    });

    await check('hot: stale completion cannot stop the current loading indicator', 'hot', async f => {
        const old = f.last();
        await f.changePeriod('week');
        const current = f.last();
        await f.reply(old, hot('old'));
        same(f.props('Spin').spinning, true, 'current request remains loading');
        same(f.hotIds(), [], 'no results from a different period');
        await f.reply(current, hot('week'));
        same(f.hotIds(), ['week'], 'current results');
    });

    await check('hot: stale rejection and late JSON cannot clear or replace current results', 'hot', async f => {
        const old = f.last();
        const body = deferred();
        await f.act(() => old.resolve({ ok: true, json: () => body.promise }));
        await f.changePeriod('week');
        const middle = f.last();
        await f.changePeriod('day');
        await f.reply(f.last(), hot('day'));
        await f.fail(middle);
        await f.act(() => body.resolve(hot('old-body')));
        same(f.hotIds(), ['day'], 'only the latest period commits');
        same(f.all('Alert').length, 0, 'no obsolete errors');
    });

    await check('hot: HTTP failure is not empty data and retry uses the selected period', 'hot', async f => {
        await f.reply(f.last(), hot('all'));
        await f.changePeriod('week');
        const failed = f.last();
        await f.reply(failed, { message: 'fixture-hot-unavailable' }, 503);
        same(f.props('Alert').title, 'fixture-hot-unavailable', 'API error is retained');
        same(f.hotIds(), [], 'previous-period rows hidden');
        same(f.all('Empty').length, 0, 'failure is not a successful empty result');
        await f.retry();
        same(f.last().url, failed.url, 'retry retains the selected query');
        await f.reply(f.last(), hot('week-retry'));
        same(f.hotIds(), ['week-retry'], 'retry recovers');
        same(f.all('Alert').length, 0, 'successful retry clears the error');
    });

    await check('hot: network/JSON failures can recover to a genuine empty state', 'hot', async f => {
        await f.fail(f.last());
        same(f.props('Alert').title, 'networkError', 'network error');
        await f.retry();
        await f.act(() => f.last().resolve({ ok: true, json: async () => { throw new SyntaxError('fixture-invalid-json'); } }));
        same(f.props('Alert').title, 'networkError', 'malformed body is an error');
        await f.retry();
        await f.reply(f.last(), []);
        same(f.props('Empty').description, 'hotEmpty', 'successful empty result');
        same(f.all('Alert').length, 0, 'no error after recovery');
    });

    await check('hot: StrictMode cleanup and unmount cancel requests without late commits', 'hot', async f => {
        same(f.calls.length, 2, 'StrictMode replays the effect');
        same(f.calls[0].options.signal?.aborted, true, 'StrictMode cleanup cancels its request');
        await f.reply(f.calls[1], hot('strict-current'));
        await f.reply(f.calls[0], hot('strict-old'));
        same(f.hotIds(), ['strict-current'], 'StrictMode current result');
        await f.changePeriod('day');
        const pending = f.last();
        await f.unmount();
        same(pending.options.signal?.aborted, true, 'unmount cancels pending request');
        await f.fail(pending);
    }, true);

    await check('notifications: filter changes isolate rows and totals from a late response', 'notifications', async f => {
        const old = f.last();
        await f.changeUnread(true);
        await f.reply(f.last(), pageData(2, 1));
        await f.reply(old, pageData(1, 75));
        same(f.notificationIds(), [2], 'unread rows');
        same(f.props('List').pagination.total, 1, 'unread total');
        same(old.options.signal?.aborted, true, 'obsolete query cancelled');
    });

    await check('notifications: stale rejection cannot stop loading or display an error', 'notifications', async f => {
        const old = f.last();
        await f.changeUnread(true);
        const current = f.last();
        await f.fail(old);
        same(f.props('List').loading, true, 'current notification request remains loading');
        same(f.errors, [], 'no stale toast');
        same(f.all('Alert').length, 0, 'no stale inline error');
        await f.reply(current, pageData(2));
    });

    await check('notifications: failed page does not retain the previous page and can retry', 'notifications', async f => {
        await f.reply(f.last(), pageData(1, 45));
        await f.changePage(2);
        same(f.notificationIds(), [], 'old page hidden during transition');
        const failed = f.last();
        await f.reply(failed, { message: 'fixture-notifications-unavailable' }, 503);
        same(f.notificationIds(), [], 'old page hidden after failure');
        same(f.props('Alert').title, 'fixture-notifications-unavailable', 'persistent page error');
        await f.retry();
        same(params(f.last()).page, '2', 'retry keeps requested page');
        await f.reply(f.last(), pageData(2, 45));
        same(f.notificationIds(), [2], 'page two recovers');
        same(f.props('List').pagination.current, 2, 'requested page retained');
    });

    await check('notifications: mark-all from page two performs only a page-one refresh', 'notifications', async f => {
        await f.reply(f.last(), pageData(1, 45));
        await f.changePage(2);
        await f.reply(f.last(), pageData(2, 45));
        await f.markAll();
        const mutation = f.last();
        same(mutation.options.method, 'PUT', 'mark-all is a mocked write');
        const before = f.calls.length;
        await f.reply(mutation, {});
        same(f.calls.slice(before).map(call => params(call).page), ['1'], 'no old-page refresh');
        await f.reply(f.last(), pageData(1, 45));
        same(f.props('List').pagination.current, 1, 'returns to page one');
        same(f.refreshEvents, ['refresh'], 'navbar notified once');
    });

    await check('notifications: a delayed mark-read refreshes the currently selected filter', 'notifications', async f => {
        await f.reply(f.last(), pageData(1, 45));
        await f.markFirst();
        const mutation = f.last();
        await f.changeUnread(true);
        await f.reply(f.last(), pageData(2, 3));
        await f.reply(mutation, {});
        same(params(f.last()).unreadOnly, 'true', 'mutation completion uses current filter');
        await f.reply(f.last(), pageData(3, 2));
        same(f.notificationIds(), [3], 'current filtered rows refreshed');
    });

    await check('notifications: marking the last item of the last unread page retreats one page', 'notifications', async f => {
        await f.reply(f.last(), pageData(1, 21));
        await f.changeUnread(true);
        await f.reply(f.last(), pageData(1, 21));
        await f.changePage(2);
        await f.reply(f.last(), pageData(21, 21));
        await f.markFirst();
        await f.reply(f.last(), {});
        const refresh = f.last();
        await f.reply(refresh, pageData(null, 20));
        same(params(f.last()).page, '1', 'invalid last page corrected');
        same(f.last() === refresh, false, 'corrected page is fetched');
        await f.reply(f.last(), pageData(1, 20));
        same(f.props('List').pagination.current, 1, 'valid page displayed');
    });

    await check('notifications: account change discards old rows, reads and mutation side effects', 'notifications', async f => {
        await f.reply(f.last(), pageData(1));
        await f.markFirst();
        const mutation = f.last();
        await f.changeUnread(true);
        const oldRead = f.last();
        await f.changeToken('fixture-account-b');
        same(f.notificationIds(), [], 'no previous-account records');
        const current = f.last();
        const before = f.calls.length;
        await f.reply(mutation, {});
        await f.reply(oldRead, pageData(99));
        same(f.calls.length, before, 'old mutation cannot refresh the new account');
        same(f.successes, [], 'old mutation cannot toast for the new account');
        same(f.refreshEvents, [], 'old mutation cannot signal the new account');
        await f.reply(current, pageData(2));
        same(f.notificationIds(), [2], 'new account only');
    });

    await check('notifications: unmount suppresses late read errors and mutation refreshes', 'notifications', async f => {
        await f.reply(f.last(), pageData(1));
        await f.markFirst();
        const mutation = f.last();
        await f.changeUnread(true);
        const read = f.last();
        const before = f.calls.length;
        await f.unmount();
        same(read.options.signal?.aborted, true, 'read cancelled on unmount');
        same(mutation.options.signal?.aborted, true, 'mutation response cancelled on unmount');
        await f.fail(read);
        await f.reply(mutation, {});
        same(f.calls.length, before, 'no post-unmount refresh');
        same(f.errors, [], 'no post-unmount error toast');
        same(f.successes, [], 'no post-unmount success toast');
        same(f.refreshEvents, [], 'no post-unmount navbar event');
    });

    await check('notifications: pagination matches the fixed API page size', 'notifications', async f => {
        await f.reply(f.last(), pageData(1, 100));
        same(params(f.last()).size, '20', 'API page size');
        same(f.props('List').pagination.pageSize, 20, 'UI page size');
        same(f.props('List').pagination.showSizeChanger, false, 'no nonfunctional page-size control');
    });

    return results;
}

test('offline async list regressions (real React, mocked API)', { timeout: 60000 }, async t => {
    const results = await runOfflineReact(regressionSuite);
    assert.equal(results.length, 15, 'all scenarios executed');
    for (const result of results) {
        await t.test(result.name, () => assert.ok(result.ok, result.error));
    }
});
