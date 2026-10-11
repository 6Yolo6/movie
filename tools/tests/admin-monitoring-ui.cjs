// Run against an isolated frontend. All browser APIs are intercepted; no production writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const { base, fixture, json, waitFor, deferred } = require('./fixtures/browser.cjs');
const { overview, logPage } = require('./fixtures/monitoring-resources.cjs');
const out = process.env.SCREENSHOT_DIR;
const results = [];
const queryInput = page => page.locator('[data-log-stream] input.ant-input:visible');
const tab = (page, title) => page.getByRole('tab', { name: title, exact: true });
const filter = async (page, label) => page.locator('[data-status-filter] .ant-segmented-item-label').filter({ hasText: new RegExp('^' + label + '$') }).click();
async function loaded(f) {
    await f.page.goto(base + '/admin/monitoring', { waitUntil: 'domcontentloaded' });
    await f.page.getByRole('heading', { name: f.t.monitoring.title, exact: true }).waitFor();
    await f.page.waitForFunction(() => document.querySelector('[data-log-total]')?.textContent.includes('91'));
}
(async () => {
    if (out) fs.mkdirSync(out, { recursive: true });
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true, args: ['--no-first-run', '--disable-background-networking'] });
    try {
        {
            const f = await fixture(browser); const { page, t } = f; await loaded(f);
            assert.equal(await page.locator('[data-metric-card]').count(), 6);
            assert.ok((await page.locator('[data-metric-card]').allTextContents()).some(text => text.includes(t.monitoring.requests) && text.includes('1,284')));
            assert.equal(await page.locator('[data-trend-bar]').count(), 14);
            assert.equal(await page.locator('[data-trend-bar][data-value="0"]').count(), 12, 'Missing days must be zero, not fabricated traffic');
            assert.equal(await page.locator('[data-trend-total]').innerText(), '1,384');
            assert.ok((await page.locator('[data-response-distribution]').innerText()).includes('1,241'));
            assert.equal(await page.locator('[data-social-rate]').innerText(), '80.0%');
            assert.ok((await page.locator('[data-social-summary]').innerText()).includes(t.monitoring.unknownHint));
            assert.equal(f.requests.filter(r => r.path.endsWith('/overview')).length, 1);
            assert.deepEqual(f.requests.filter(r => r.path.endsWith('/logs')).map(r => r.params.type), ['access']);
            await f.overflow();
            if (out) await page.screenshot({ path: path.join(out, 'monitoring-desktop-zh.png'), fullPage: true });
            results.push('desktop overview, 14-day gaps, honest response and publishing rates');
            const overviewReads = f.requests.filter(r => r.path.endsWith('/overview')).length;
            await queryInput(page).fill('past-fifty'); await page.waitForTimeout(200);
            assert.equal(f.requests.filter(r => r.path.endsWith('/logs')).length, 1, 'Typing does not reload logs or overview');
            await queryInput(page).press('Enter');
            await page.getByText('/api/past-fifty', { exact: true }).first().waitFor();
            assert.equal(f.requests.filter(r => r.path.endsWith('/overview')).length, overviewReads);
            assert.ok((await page.locator('[data-log-total]').innerText()).includes('1'));
            results.push('server-side search reaches records beyond the old 50-row cap');
            await queryInput(page).fill('');
            await page.waitForFunction(() => document.querySelector('[data-log-total]')?.textContent.includes('91'));
            await page.locator('[data-log-pagination] .ant-pagination-item-4').click();
            await waitFor(() => f.requests.some(r => r.params.page === '4'));
            await page.getByText('/api/movies/31', { exact: true }).first().waitFor();
            assert.equal(await page.locator('.ant-table-tbody .ant-table-row').count(), 20);
            results.push('real fourth-page navigation');
            await tab(page, t.monitoring.logs.searches.title).click();
            await filter(page, t.monitoring.noResults);
            await page.getByText('繁花', { exact: true }).filter({ visible: true }).last().waitFor();
            await page.waitForFunction(() => document.querySelector('[data-log-total]')?.textContent.includes('1'));
            assert.equal(await page.locator('.ant-table-tbody .ant-table-row:visible').count(), 1);
            assert.equal(f.requests.filter(r => r.path.endsWith('/overview')).length, overviewReads);
            results.push('site-search result filters and independent loading');
            await page.locator('[data-social-status="attention"]').click();
            await page.getByText('Fixture publishing UNKNOWN', { exact: true }).first().waitFor();
            assert.equal(await queryInput(page).inputValue(), '');
            assert.ok(f.requests.some(r => r.params.type === 'socialPosts' && r.params.status === 'attention'));
            await filter(page, t.monitoring.failure);
            await page.getByText('Fixture publishing PREPARE_FAILED', { exact: true }).filter({ visible: true }).first().waitFor();
            assert.equal(await page.locator('.ant-table-tbody .ant-table-row:visible').count(), 2);
            results.push('unknown / preparation failure / legacy failure filters');
            await page.locator('[data-hot-searches] button').first().click();
            await waitFor(() => f.requests.some(r => r.params.type === 'searches' && r.params.q === '沙丘 2'));
            assert.equal(await queryInput(page).inputValue(), '沙丘 2');
            results.push('hot-search drilldown keeps input and committed filter aligned');
            await f.done();
        }
        {
            const slow = deferred(); let waiting = false;
            const f = await fixture(browser, { ignoreAbort: true, onApi: async (route, url) => {
                if (url.pathname.endsWith('/logs') && url.searchParams.get('q') === 'slow') { waiting = true; await slow.promise; await json(route, { records: [{ id: 999, request_path: '/stale-response', status_code: 200 }], total: 1, page: 1, size: 20 }); return true; }
                return false;
            } });
            await loaded(f); await queryInput(f.page).fill('slow'); await queryInput(f.page).press('Enter'); await waitFor(() => waiting);
            await queryInput(f.page).fill('past-fifty'); await queryInput(f.page).press('Enter');
            await f.page.getByText('/api/past-fifty', { exact: true }).first().waitFor(); slow.resolve(); await f.page.waitForTimeout(300);
            assert.equal(await f.page.getByText('/stale-response', { exact: true }).count(), 0);
            results.push('late responses cannot overwrite newer filters even if cancellation is ignored'); await f.done();
        }
        {
            let failOverview = true;
            const f = await fixture(browser, { onApi: async (route, url) => {
                if (failOverview && url.pathname.endsWith('/overview')) { await json(route, { error: 'fixture unavailable' }, 503); return true; } return false;
            } });
            await loaded(f); await f.page.getByText(f.t.monitoring.overviewError, { exact: true }).waitFor();
            assert.ok((await f.page.locator('[data-metric-value]').allTextContents()).every(text => text === '—'));
            assert.equal(await f.page.locator('[data-overview-updated]').innerText(), f.t.monitoring.notUpdated);
            const before = f.requests.filter(r => r.path.endsWith('/logs')).length; failOverview = false;
            await f.page.getByRole('button', { name: f.t.monitoring.retry, exact: true }).click();
            await f.page.waitForFunction(() => document.querySelector('[data-metric-value]')?.textContent === '402');
            assert.equal(f.requests.filter(r => r.path.endsWith('/logs')).length, before);
            results.push('partial overview failure shows unknown metrics and retries independently'); await f.done();
        }
        {
            let failLogs = false;
            const f = await fixture(browser, { onApi: async (route, url) => {
                if (failLogs && url.pathname.endsWith('/logs')) { await json(route, { error: 'fixture unavailable' }, 503); return true; } return false;
            } });
            await loaded(f); const stamp = await f.page.locator('[data-log-updated]').innerText(); failLogs = true;
            await f.page.getByRole('button', { name: new RegExp(f.t.monitoring.refresh + '$') }).click();
            await f.page.getByText(f.t.monitoring.logsError, { exact: true }).waitFor();
            assert.equal(await f.page.locator('[data-log-updated]').innerText(), stamp);
            assert.equal(await f.page.locator('.ant-table-tbody .ant-table-row').count(), 20);
            assert.ok((await f.page.locator('[data-log-stream]').innerText()).includes(f.t.monitoring.staleHint));
            results.push('refresh failure preserves the last successful rows and timestamp'); await f.done();
        }
        {
            const f = await fixture(browser, { onApi: async (route, url) => {
                if (url.pathname.endsWith('/overview')) { await json(route, { ...overview, requestsToday: 0, visitorsToday: 0, pageViewsToday: 0, clientErrorsToday: 0, serverErrorsToday: 0, searchesToday: 0, resourceOperationsToday: 0, socialPostedToday: 0, socialFailedToday: 0, socialPendingToday: 0, socialUnknownToday: 0, traffic: [], hotSearches: [] }); return true; }
                if (url.pathname.endsWith('/logs')) { await json(route, { records: [], total: 0, page: 1, size: 20 }); return true; } return false;
            } });
            await f.page.goto(base + '/admin/monitoring'); await f.page.getByText(f.t.monitoring.noPosts, { exact: true }).waitFor();
            assert.equal(await f.page.locator('[data-social-rate]').innerText(), '—');
            assert.equal(await f.page.locator('[data-trend-bar]').count(), 0);
            assert.equal(await f.page.locator('[data-log-pagination]').count(), 0);
            await f.overflow(); results.push('zero-state charts and logs do not invent traffic or a success rate'); await f.done();
        }
        {
            const f = await fixture(browser); await loaded(f); await f.page.clock.install();
            await f.page.getByRole('switch', { name: f.t.monitoring.autoRefresh }).click();
            const before = f.requests.filter(r => r.path.endsWith('/overview')).length;
            await f.page.evaluate(() => Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' }));
            await f.page.clock.runFor(31000); assert.equal(f.requests.filter(r => r.path.endsWith('/overview')).length, before);
            await f.page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' }); document.dispatchEvent(new Event('visibilitychange')); });
            await waitFor(() => f.requests.filter(r => r.path.endsWith('/overview')).length > before);
            results.push('automatic refresh pauses in hidden tabs and resumes on visibility'); await f.done();
        }
        for (const options of [{ lang: 'zh', width: 360 }, { lang: 'zh', width: 375 }, { lang: 'en', width: 390, theme: 'dark' }, { lang: 'zh', width: 430 }, { lang: 'en', width: 1440, theme: 'dark' }]) {
            const f = await fixture(browser, options); await loaded(f);
            await f.page.locator('[data-social-status="failure"]').click();
            await f.page.getByText('Fixture publishing PREPARE_FAILED', { exact: true }).filter({ visible: true }).first().waitFor();
            await f.overflow();
            if (options.width < 768) {
                assert.equal(await f.page.locator('[data-log-card]:visible').count(), 2);
                await f.page.locator('[data-log-card] summary').first().click();
                assert.ok((await f.page.locator('[data-log-card] details[open]').first().innerText()).includes('Fixture outcome detail'));
                const inputBox = await f.page.locator('[data-log-stream] .ant-input-search:visible').boundingBox(); assert.ok(inputBox.height >= 44, 'Mobile search must be comfortably tappable');
            }
            if (out && [360, 390, 1440].includes(options.width)) await f.page.screenshot({ path: path.join(out, `monitoring-${options.lang}-${options.width}-${options.theme || 'light'}.png`), fullPage: true });
            results.push(`responsive ${options.lang}/${options.width}/${options.theme || 'light'} with error details`); await f.done();
        }
        console.log(JSON.stringify({ ok: true, scenarios: results.length, results }, null, 2));
        if (out) fs.writeFileSync(path.join(out, 'monitoring-results.json'), JSON.stringify({ ok: true, scenarios: results.length, results }, null, 2));
    } finally { await browser.close(); }
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
