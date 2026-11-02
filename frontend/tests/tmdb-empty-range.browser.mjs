import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.METADATA_CRAWL_BASE_URL || 'http://127.0.0.1:18080';
const build = process.env.METADATA_CRAWL_BUILD_DIR;
const output = process.env.METADATA_CRAWL_OUTPUT_DIR;
assert(build && path.isAbsolute(build), 'Provide an isolated production build via METADATA_CRAWL_BUILD_DIR');
assert.equal(new URL(baseURL).hostname, '127.0.0.1');
const publicDir = path.resolve(build, '../public');
const nextCheckAt = '2026-11-03T10:30:00Z';
const progressRows = [
    { provider: 'TMDB', source: 'TOP_RATED_TV', startPage: 360, endPage: 500, nextPage: 360, nextItem: 1, status: 'SUCCEEDED', observedTotalPages: 131, availability: 'EMPTY_RANGE', nextCheckAt },
    { provider: 'TMDB', source: 'TOP_RATED_MOVIE', startPage: 1, endPage: 500, nextPage: 1, nextItem: 1, status: 'SUCCEEDED', observedTotalPages: 131, availability: 'RANGE_EXHAUSTED' },
    { provider: 'TMDB', source: 'POPULAR_TV', startPage: 1, endPage: 500, nextPage: 100, nextItem: 4, status: 'FAILED', observedTotalPages: 131, availability: 'EMPTY_RANGE', nextCheckAt },
    { provider: 'TMDB', source: 'POPULAR_MOVIE', startPage: 1, endPage: 500, nextPage: 100, nextItem: 4, status: 'RETRY', observedTotalPages: 131, availability: 'EMPTY_RANGE', nextCheckAt },
];
const rangePayload = availability => JSON.stringify({ crawl: { mode: 'RANGE', availability } });
const tasks = [
    { id: 9001, taskType: 'METADATA_SYNC', source: 'TMDB', keyword: 'TOP_RATED_TV', status: 'SUCCEEDED', payload: rangePayload('EMPTY_RANGE') },
    { id: 9002, taskType: 'METADATA_SYNC', source: 'TMDB', keyword: 'TOP_RATED_MOVIE', status: 'SUCCEEDED', payload: rangePayload('RANGE_EXHAUSTED') },
    { id: 9003, taskType: 'METADATA_SYNC', source: 'GYING', status: 'SUCCEEDED', payload: rangePayload('EMPTY_RANGE') },
    { id: 9004, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: JSON.stringify({ crawl: { mode: 'PAGE', availability: 'EMPTY_RANGE' } }) },
    { id: 9005, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', payload: '{malformed-json' },
    { id: 9006, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'FAILED', lastError: 'TMDB HTTP 429', payload: rangePayload('EMPTY_RANGE') },
    { id: 9007, taskType: 'METADATA_SYNC', source: 'TMDB', status: 'SUCCEEDED', lastError: 'upstream request failed', payload: rangePayload('EMPTY_RANGE') },
];
const scenarios = [
    { lang: 'zh', width: 1440, theme: 'light' }, { lang: 'en', width: 1440, theme: 'dark' },
    { lang: 'zh', width: 390, theme: 'dark' }, { lang: 'en', width: 390, theme: 'light' },
    { lang: 'zh', width: 390, theme: 'light', legacy: true }, { lang: 'en', width: 1440, theme: 'light', legacy: true },
];

async function main() {
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
    const results = [];
    try {
        for (const scenario of scenarios) {
            const t = JSON.parse(fs.readFileSync(path.join(publicDir, 'locales', scenario.lang, 'common.json'), 'utf8'));
            const context = await browser.newContext({ viewport: { width: scenario.width, height: 1000 }, timezoneId: 'UTC' });
            await context.addInitScript(options => {
                localStorage.setItem('i18nextLng', options.lang);
                localStorage.setItem('theme', options.theme);
                localStorage.setItem('auth-storage', JSON.stringify({ state: { token: 'fixture-only-placeholder', user: { id: 1, username: 'fixture', role: 'ADMIN' } }, version: 0 }));
            }, scenario);
            const page = await context.newPage(), errors = [], writes = [], telemetry = [];
            page.on('pageerror', error => errors.push(error.message));
            const config = {
                enabled: true, autoApprove: true, tmdbConfigured: true, workerEnabled: true,
                tmdbAutoSyncEnabled: true, tmdbAutoSyncSources: 'TOP_RATED_TV,TOP_RATED_MOVIE',
                tmdbAutoSyncPage: 360, tmdbAutoSyncEndPage: 500, tmdbAutoSyncMaxItems: 20, tmdbAutoSyncIntervalHours: 2,
                tmdbAutoSyncRanges: { TOP_RATED_TV: { startPage: 360, endPage: 500 }, TOP_RATED_MOVIE: { startPage: 1, endPage: 500 } },
                gyingAutoSyncEnabled: false, gyingAutoSyncSources: 'HITS_MOVIE', gyingAutoSyncPage: 1, gyingAutoSyncEndPage: 50,
                gyingAutoSyncMaxItems: 20, gyingAutoSyncIntervalHours: 2, tmdbDiscoveryMaxResults: 10,
                workerTaskLimit: 5, workerQuarkLimit: 5, workerPublishLimit: 20, weeklyTransferSchedules: [],
                metadataCrawlProgress: progressRows.map(row => scenario.legacy
                    ? Object.fromEntries(Object.entries(row).filter(([key]) => !['observedTotalPages', 'availability', 'nextCheckAt'].includes(key))) : row),
            };
            // Render real Next/React/AntD assets, with every API mocked and external requests blocked.
            await page.route('**/*', async route => {
                const url = new URL(route.request().url());
                if (url.origin !== baseURL) return route.abort();
                if (url.pathname.startsWith('/api/')) return route.fallback();
                if (url.pathname === '/admin/resource-hub') return route.fulfill({ contentType: 'text/html', body: fs.readFileSync(path.join(build, 'server/app/admin/resource-hub.html')) });
                if (url.pathname.startsWith('/_next/static/')) {
                    const file = path.resolve(build, 'static', url.pathname.slice('/_next/static/'.length));
                    assert(file.startsWith(path.resolve(build, 'static') + path.sep));
                    return route.fulfill({ path: file });
                }
                const file = path.resolve(publicDir, '.' + url.pathname);
                if (file.startsWith(publicDir + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({ path: file });
                return route.abort();
            });
            await page.route('**/api/**', async route => {
                const req = route.request(), url = new URL(req.url());
                if (req.method() !== 'GET') {
                    const write = { path: url.pathname, method: req.method() };
                    if (url.pathname.startsWith('/api/admin/resource-hub/')) writes.push(write);
                    else telemetry.push(write);
                }
                let body = { records: [], total: 0, current: 1, size: 20 };
                if (url.pathname === '/api/admin/resource-hub/overview') body = { enabled: true, autoApprove: true, tmdbConfigured: true, config, worker: { enabled: true, running: false, fixedDelayMs: 60000, taskLimit: 5, quarkLimit: 5, publishLimit: 20 }, taskStatusCounts: {}, collectionStats: {} };
                else if (url.pathname === '/api/admin/resource-hub/tasks') body = { records: tasks, total: tasks.length, current: 1, size: 20 };
                else if (url.pathname === '/api/auth/me') body = { id: 1, username: 'fixture', role: 'ADMIN' };
                else if (url.pathname.endsWith('/unread-count')) body = 0;
                await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
            });
            await page.goto(baseURL + '/admin/resource-hub', { waitUntil: 'domcontentloaded' });
            const start = page.locator('#tmdbAutoSyncRanges_TOP_RATED_TV_startPage'), end = page.locator('#tmdbAutoSyncRanges_TOP_RATED_TV_endPage');
            await end.waitFor();
            assert.equal(await start.inputValue(), '360'); assert.equal(await end.inputValue(), '500');
            assert.equal(await page.evaluate(theme => document.documentElement.classList.contains(theme), scenario.theme), true);
            const progress = page.getByTestId('metadata-crawl-progress'); await progress.scrollIntoViewIfNeeded();
            const row = source => progress.locator(`[data-row-key="TMDB:${source}"]`);
            const empty = row('TOP_RATED_TV'), exhausted = row('TOP_RATED_MOVIE');
            assert.ok((await empty.locator('td').nth(1).innerText()).includes('360–500'));
            assert.equal(await progress.locator('thead th').count(), 5);
            if (scenario.legacy) {
                assert.equal(await empty.locator('td').nth(4).innerText(), '-');
                assert.equal((await progress.innerText()).includes(t.resourceHubCrawlObservedLastPage), false);
            } else {
                assert.ok((await empty.locator('td').nth(1).innerText()).includes(t.resourceHubCrawlObservedLastPage + ': 131'));
                const state = await empty.locator('td').nth(4).innerText();
                assert.ok(state.includes(t.resourceHubCrawlAvailability.EMPTY_RANGE));
                assert.ok(state.includes(t.resourceHubCrawlRangeBeyondLastPage.replace('{{total}}', '131').replace('{{start}}', '360').replace('{{end}}', '500')));
                const expectedTime = await page.evaluate(value => new Date(value).toLocaleString(undefined, { timeZone: 'Asia/Shanghai' }), nextCheckAt);
                assert.ok(state.includes(t.resourceHubCrawlNextCheckAt + ': ' + expectedTime), state);
                assert.equal(await empty.locator('td').nth(4).locator('.ant-tag-green').count(), 0);
                const reached = await exhausted.locator('td').nth(4).innerText();
                assert.ok(reached.includes(t.resourceHubCrawlAvailability.RANGE_EXHAUSTED));
                assert.ok(reached.includes(t.resourceHubCrawlAvailabilityHelp.RANGE_EXHAUSTED));
                assert.equal(reached.includes(t.resourceHubCrawlNextCheckAt), false);
            }
            for (const [source, status] of [['POPULAR_TV', 'FAILED'], ['POPULAR_MOVIE', 'RETRY']]) {
                assert.equal(await row(source).locator('td').nth(3).innerText(), t.resourceHubCrawlStatus[status]);
                assert.equal(await row(source).locator('td').nth(4).innerText(), '-');
            }
            const name = `${scenario.lang}-${scenario.width}-${scenario.theme}${scenario.legacy ? '-legacy' : ''}`;
            const overflow = async () => page.evaluate(() => ({ viewport: innerWidth, scroll: document.documentElement.scrollWidth }));
            const progressOverflow = await overflow();
            assert.ok(progressOverflow.scroll <= progressOverflow.viewport + 1, 'Progress document overflow: ' + JSON.stringify(progressOverflow));
            if (scenario.width < 600) {
                const visible = await empty.locator('td').nth(4).evaluate(cell => ({ column: cell.getBoundingClientRect().width, viewport: cell.closest('.ant-table-content').clientWidth }));
                assert.ok(visible.column <= visible.viewport, 'Availability column must fit the mobile table viewport: ' + JSON.stringify(visible));
            }
            if (output) { fs.mkdirSync(output, { recursive: true }); await progress.screenshot({ path: path.join(output, name + '-progress-overview.png') }); }
            await progress.locator('.ant-table-content').evaluate(node => { node.scrollLeft = node.scrollWidth; });
            if (output) { fs.mkdirSync(output, { recursive: true }); await progress.screenshot({ path: path.join(output, name + '-progress.png') }); }
            await page.getByRole('tab', { name: t.resourceHubTasks, exact: true }).click();
            const taskRow = id => page.locator(`.ant-table-tbody [data-row-key="${id}"]`);
            await taskRow(9001).waitFor();
            for (const [id, availability] of [[9001, 'EMPTY_RANGE'], [9002, 'RANGE_EXHAUSTED']]) {
                assert.equal(await taskRow(id).locator('td').nth(5).innerText(), t.resourceHubCrawlAvailability[availability]);
                assert.equal(await taskRow(id).locator('td').nth(5).locator('.ant-tag-green').count(), 0);
            }
            for (const id of [9003, 9004, 9005, 9007]) assert.equal(await taskRow(id).locator('td').nth(5).innerText(), t.resourceHubStatus.SUCCEEDED);
            assert.equal(await taskRow(9006).locator('td').nth(5).innerText(), t.resourceHubStatus.FAILED);
            assert.ok((await taskRow(9006).innerText()).includes('TMDB HTTP 429'));
            const taskOverflow = await overflow();
            assert.ok(taskOverflow.scroll <= taskOverflow.viewport + 1, 'Task document overflow: ' + JSON.stringify(taskOverflow));
            if (scenario.width < 600) await taskRow(9001).locator('td').nth(5).scrollIntoViewIfNeeded();
            if (output) await page.screenshot({ path: path.join(output, name + '-tasks.png'), fullPage: true });
            assert.deepEqual(errors, []); assert.deepEqual(writes, []);
            results.push({ ...scenario, progressOverflow, taskOverflow, pageErrors: errors.length, resourceHubWrites: writes.length, mockedOtherWrites: telemetry, passed: true });
            console.log('PASS ' + name);
            await context.close();
        }
    } finally { await browser.close(); }
    if (output) fs.writeFileSync(path.join(output, 'browser-qa.json'), JSON.stringify({ actualProductionAssets: true, mockApi: true, realLoginOrApiWrites: false, allRequestsMocked: true, scenarios: results }, null, 2));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
