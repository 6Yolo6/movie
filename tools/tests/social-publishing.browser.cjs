// Offline only: every API request is mocked; external and unrelated dynamic routes are blocked.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.SOCIAL_PUBLISHING_BASE_URL || 'http://127.0.0.1:18083';
const outputDir = process.env.SOCIAL_PUBLISHING_OUTPUT_DIR;
const target = new URL(baseURL);
if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) || target.username || target.password) {
    throw new Error('SOCIAL_PUBLISHING_BASE_URL must be a credential-free loopback test server');
}
const translations = Object.fromEntries(['en', 'zh'].map(lang => [lang, JSON.parse(fs.readFileSync(
    path.resolve(__dirname, '../../frontend/public/locales/' + lang + '/common.json'), 'utf8'))]));
const json = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const pageData = records => ({ records, total: records.length, current: 1, size: 20 });
const states = ['PENDING', 'PREPARE_FAILED', 'PREPARING', 'PUBLISHING', 'POSTED', 'UNKNOWN', 'FAILED', 'FUTURE_STATE'];

async function fixture(browser, { lang = 'en', width = 1440, httpFailure = false, paged = false } = {}) {
    const context = await browser.newContext({ viewport: { width, height: 1000 },
        locale: lang === 'zh' ? 'zh-CN' : 'en-US', serviceWorkers: 'block' });
    const t = translations[lang];
    await context.addInitScript(lang => {
        localStorage.setItem('i18nextLng', lang);
        localStorage.setItem('auth-storage', JSON.stringify({ state: {
            token: 'test-only-placeholder', user: { id: 1, username: 'fixture', role: 'ADMIN' },
        }, version: 0 }));
    }, lang);
    const page = await context.newPage(), errors = [], unexpected = [], mutations = [], saved = [], logQueries = [];
    const reads = { overview: 0, logs: 0 };
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    const targets = [true, false].map((enabled, i) => ({ id: i + 1, platform: 'WEIBO', accountKey: 'fixture',
        name: enabled ? 'Enabled fixture target' : 'Disabled fixture target', enabled, autoPostEnabled: false,
        scheduleTime: '10:00', postsPerRun: 1, postIntervalSeconds: 60, template: '{{title}}' }));
    const logs = states.map((status, i) => ({ id: i + 1, platform: 'WEIBO', targetId: 1,
        resourceLinkId: 100 + i, movieId: 'fixture-movie-' + i, title: 'Fixture ' + status, status }));
    logs.push(...[{ id: 9, targetId: 2, status: 'PENDING' }, { id: 10, targetId: 99, status: 'PENDING' },
        { id: 11, targetId: 1, status: '' }].map(row => ({ platform: 'WEIBO', resourceLinkId: row.id + 100,
            movieId: 'fixture-movie-' + row.id, title: 'Fixture protected ' + row.id, ...row })));
    logs[5].title = '长标题与LongUnbrokenFixtureTitle'.repeat(5);
    logs[5].errorMessage = 'UnconfirmedExternalAcknowledgementFixture'.repeat(7);
    if (paged) for (let id = 12; id <= 23; id++) logs.push({ ...logs[5], id, title: 'Page fixture ' + id });
    await context.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url());
        if (url.origin !== target.origin) return route.abort();
        if (!url.pathname.startsWith('/api/')) {
            if (url.pathname === '/admin/automation' || url.pathname.startsWith('/_next/') ||
                url.pathname.startsWith('/locales/') || url.pathname === '/favicon.ico') return route.continue();
            return route.abort();
        }
        if (url.pathname === '/api/monitoring/page-view' && request.method() === 'POST') return json(route, {});
        if (request.method() !== 'GET') {
            if (request.method() === 'PUT' && url.pathname === '/api/admin/social-publishing/targets/1') {
                mutations.push(url.pathname); saved.push(request.postDataJSON());
                Object.assign(targets[0], saved.at(-1)); return json(route, targets[0]);
            }
            if (request.method() === 'POST' && (url.pathname === '/api/admin/social-publishing/logs/1/retry' ||
                url.pathname === '/api/admin/social-publishing/publish-next')) {
                mutations.push(url.pathname);
                logs[0].status = 'UNKNOWN';
                if (httpFailure) return json(route, { message: 'Fixture transport failed after submission' }, 502);
                return json(route, { code: 'OK', data: { ok: false, status: 'UNKNOWN', retryable: false, blocked: true } });
            }
            unexpected.push(request.method() + ' ' + url.pathname);
            return route.abort();
        }
        if (url.pathname === '/api/admin/social-publishing/overview') {
            reads.overview++;
            return json(route, { targets, posted: 1, failed: 2, pending: 3, postedLast24Hours: 1,
                processing: 2, unknown: logs.filter(row => row.status === 'UNKNOWN').length,
                publisher: { ok: true, qq: { ready: false, accounts: [] }, weibo: { ready: false } } });
        }
        if (url.pathname === '/api/admin/social-publishing/logs') {
            reads.logs++;
            logQueries.push({ page: url.searchParams.get('page'), status: url.searchParams.get('status') });
            const matching = logs.filter(row => !url.searchParams.has('status') || row.status === url.searchParams.get('status'));
            const current = Number(url.searchParams.get('page') || 1);
            return json(route, { records: matching.slice((current - 1) * 20, current * 20), total: matching.length, current, size: 20 });
        }
        if (url.pathname === '/api/admin/qq-automation/overview') return json(route, {
            config: { botMinKeywordLength: 2, botRateLimitPerMinute: 6, botMaxResults: 5,
                botBlockedKeywords: '', botTransferCleanupEnabled: false, botTransferCleanupDelayMinutes: 60,
                botDailyRecommendationEnabled: false, botDailyRecommendationTime: '10:00', botDailyRecommendationCount: 1,
                botDailyRecommendationGroupIds: '', botDailyRecommendationTemplate: '{{title}}',
                channelAutoPostEnabled: false, channelIntervalMinutes: 60, channelMaxPostsPerRun: 1,
                channelDailyTime: '10:00', channelPostTotal: 1, channelPostIntervalSeconds: 60,
                channelPostTemplate: '{{title}}', channelCandidateLimit: 20, channelGuildId: '', channelMovieId: '', channelTvId: '' },
            botStatusCounts: {}, botRecentStatusCounts: {}, channelStatusCounts: {}, channelRecentStatusCounts: {},
            botSummary: { total: 0, last24Hours: 0, succeeded: 0, successRate: 0, noResult: 0, ambiguous: 0, blocked: 0, failed: 0 },
        });
        if (url.pathname.startsWith('/api/admin/qq-automation/')) return json(route, pageData([]));
        if (url.pathname === '/api/auth/me') return json(route, { id: 1, username: 'fixture', role: 'ADMIN' });
        if (url.pathname.endsWith('/unread-count')) return json(route, { count: 0 });
        if (url.pathname === '/api/movies/hot-searches') return json(route, []);
        return json(route, {});
    });
    async function open() {
        const loaded = page.waitForResponse(response => response.url().includes('/api/admin/social-publishing/logs?'));
        await page.goto(baseURL + '/admin/automation', { waitUntil: 'domcontentloaded' });
        await loaded; // Wait for hydration effects before clicking server-rendered tabs.
        await page.getByRole('tab', { name: t.socialPublishingTab, exact: true }).click();
        await page.getByTestId('social-retry-1').waitFor();
    }
    return { page, t, logs, reads, mutations, saved, logQueries, open, async close() {
        await context.close();
        assert.deepEqual(unexpected, [], 'No unexpected mutations: ' + JSON.stringify(unexpected));
        assert.deepEqual(errors, [], 'No runtime/hydration errors: ' + JSON.stringify(errors));
    } };
}

async function main() {
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
    const results = [];
    async function run(name, scenario) {
        await scenario(); results.push({ name, status: 'passed' }); console.log('PASS ' + name);
    }
    try {
        for (const settings of [{ lang: 'zh', width: 1440 }, { lang: 'en', width: 1440 }, { lang: 'zh', width: 360 }, { lang: 'en', width: 360 }, { lang: 'zh', width: 390 }, { lang: 'zh', width: 430 }]) {
            await run('status protection ' + settings.lang + '-' + settings.width, async () => {
                const f = await fixture(browser, settings);
                try {
                    await f.open();
                    assert.equal(await f.page.locator('html').getAttribute('lang'), settings.lang === 'zh' ? 'zh-CN' : 'en');
                    for (let id = 1; id <= 11; id++) {
                        const button = f.page.getByTestId('social-retry-' + id);
                        assert.equal(await button.isEnabled(), id <= 2, 'Retry policy for log ' + id);
                        if (id > 2) await button.evaluate(element => element.click());
                    }
                    assert.deepEqual(f.mutations, [], 'Protected rows cannot submit retries');
                    if (settings.width < 768) {
                        assert.equal(await f.page.getByTestId('social-target-mobile-list').isVisible(), true);
                        assert.equal(await f.page.getByTestId('social-log-mobile-list').isVisible(), true);
                        assert.ok((await f.page.getByTestId('social-retry-1').boundingBox()).height >= 44);
                        assert.ok((await f.page.getByTestId('social-log-mobile-6').innerText()).includes(f.t.socialPublishingRetryBlocked));
                    }
                    const panel = f.page.getByRole('tabpanel', { name: f.t.socialPublishingTab });
                    await panel.getByText(f.t.socialPublishingRetryPolicy, { exact: true }).waitFor();
                    for (const [key, value] of [['socialPublishingProcessing', '2'], ['socialPublishingUnknown', '1']]) {
                        const stat = panel.locator('.ant-statistic').filter({ hasText: f.t[key] });
                        assert.equal(await stat.locator('.ant-statistic-content-value').innerText(), value);
                    }
                    for (const [i, status] of states.entries()) {
                        assert.ok((await (settings.width < 768 ? f.page.getByTestId('social-log-mobile-' + (i + 1)) : f.page.getByTestId('social-retry-' + (i + 1)).locator('xpath=ancestor::tr')).innerText())
                            .includes(f.t.socialPublishingStatus[status] || status));
                    }
                    assert.ok(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
                        'No document-wide horizontal overflow');
                    if (outputDir) {
                        fs.mkdirSync(outputDir, { recursive: true });
                        await panel.scrollIntoViewIfNeeded();
                        await f.page.screenshot({ path: path.join(outputDir, 'social-policy-' + settings.lang + '-' + settings.width + '.png') });
                        if (settings.width < 768) {
                            await f.page.getByTestId('social-log-mobile-6').scrollIntoViewIfNeeded();
                            await f.page.screenshot({ path: path.join(outputDir, 'social-log-' + settings.lang + '-' + settings.width + '.png') });
                        }
                    }
                } finally { await f.close(); }
            });
        }
        for (const { httpFailure, width } of [{ httpFailure: false, width: 1440 }, { httpFailure: true, width: 1440 }, { httpFailure: false, width: 360 }, { httpFailure: true, width: 390 }]) {
            await run((httpFailure ? 'HTTP failure still refreshes protected outcomes ' : 'HTTP 200 UNKNOWN is not published success ') + width, async () => {
                const f = await fixture(browser, { httpFailure, width });
                try {
                    await f.open();
                    const initialReads = { ...f.reads };
                    await f.page.getByTestId('social-retry-1').click();
                    const notification = f.page.locator(httpFailure ? '.ant-message-error' : '.ant-message-info');
                    await notification.filter({ hasText: httpFailure ? 'Fixture transport failed after submission' : f.t.socialPublishingResultRecorded }).waitFor();
                    await f.page.waitForFunction(() => document.querySelector('[data-testid="social-retry-1"]')?.disabled);
                    assert.equal(await f.page.locator('.ant-message-success').count(), 0);
                    assert.deepEqual(f.mutations, ['/api/admin/social-publishing/logs/1/retry']);
                    assert.ok(f.reads.logs > initialReads.logs && f.reads.overview > initialReads.overview);
                    await f.open();
                    assert.equal(await f.page.getByTestId('social-retry-1').isEnabled(), false, 'Reload cannot release UNKNOWN');
                } finally { await f.close(); }
            });
        }
        await run('mobile publish-now uncertain result is informational and refreshes logs', async () => {
            const f = await fixture(browser, { width: 430 });
            try {
                await f.open();
                await f.page.getByRole('button').filter({ hasText: f.t.socialPublishingPublishAll }).click();
                await f.page.locator('.ant-message-info').filter({ hasText: f.t.socialPublishingResultRecorded }).waitFor();
                await f.page.waitForFunction(() => document.querySelector('[data-testid="social-retry-1"]')?.disabled);
                assert.equal(await f.page.locator('.ant-message-success').count(), 0);
                assert.deepEqual(f.mutations, ['/api/admin/social-publishing/publish-next']);
            } finally { await f.close(); }
        });
        await run('mobile target editing survives responsive layout changes and saves without publishing', async () => {
            const f = await fixture(browser, { lang: 'zh', width: 360 });
            try {
                await f.open();
                const card = f.page.getByTestId('social-target-mobile-1');
                await card.locator('summary').click();
                const title = '手机端长目标名称与LongTargetName'.repeat(3);
                await card.getByRole('textbox', { name: f.t.socialPublishingTarget, exact: true }).fill(title);
                await card.getByRole('spinbutton', { name: f.t.socialPublishingPosts, exact: true }).fill('2');
                await f.page.setViewportSize({ width: 1024, height: 1000 });
                await f.page.getByTestId('social-target-mobile-list').waitFor({ state: 'hidden' });
                await f.page.setViewportSize({ width: 360, height: 1000 });
                await card.waitFor(); await card.locator('summary').click();
                assert.equal(await card.getByRole('textbox', { name: f.t.socialPublishingTarget, exact: true }).inputValue(), title);
                assert.equal(await card.getByRole('spinbutton', { name: f.t.socialPublishingPosts, exact: true }).inputValue(), '2');
                await f.page.getByTestId('social-target-save-1').click();
                await f.page.locator('.ant-message-success').filter({ hasText: f.t.socialPublishingTargetSaved }).waitFor();
                assert.deepEqual(f.mutations, ['/api/admin/social-publishing/targets/1']);
                assert.equal(f.saved[0].name, title); assert.equal(f.saved[0].postsPerRun, 2);
                assert.equal(f.saved[0].autoPostEnabled, false);
                assert.ok(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
            } finally { await f.close(); }
        });
        await run('mobile status filter remains usable and cannot enable uncertain retries', async () => {
            const f = await fixture(browser, { lang: 'zh', width: 390 });
            try {
                await f.open();
                await f.page.getByTestId('social-log-filters').getByRole('combobox', { name: f.t.filterByStatus, exact: true }).click();
                await f.page.getByTitle(f.t.socialPublishingStatus.UNKNOWN, { exact: true }).click();
                await f.page.getByTestId('social-retry-1').waitFor({ state: 'detached' });
                assert.equal(await f.page.getByTestId('social-retry-6').isEnabled(), false);
                assert.deepEqual(f.logQueries.at(-1), { page: '1', status: 'UNKNOWN' });
                assert.deepEqual(f.mutations, []);
            } finally { await f.close(); }
        });
        await run('mobile history pagination loads the requested page', async () => {
            const f = await fixture(browser, { width: 430, paged: true });
            try {
                await f.open();
                await f.page.locator('.ant-pagination-next').click();
                await f.page.getByTestId('social-retry-21').waitFor();
                assert.equal(await f.page.getByTestId('social-retry-1').count(), 0);
                assert.deepEqual(f.logQueries.at(-1), { page: '2', status: null });
                assert.equal(await f.page.getByTestId('social-retry-21').isEnabled(), false);
                assert.deepEqual(f.mutations, []);
            } finally { await f.close(); }
        });
    } finally {
        await browser.close();
        if (outputDir) {
            fs.mkdirSync(outputDir, { recursive: true });
            fs.writeFileSync(path.join(outputDir, 'social-publishing-browser-results.json'), JSON.stringify(results, null, 2));
        }
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
