// Real React/Ant Design regressions against an isolated production build.
// Offline build mode serves only generated/public files through browser routes: no HTTP server.
// Otherwise use a dedicated loopback test server; never use production SSR or real APIs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const { base, fixture, json, deferred, waitFor } = require('./fixtures/browser.cjs');

const target = new URL(base);
if (target.protocol !== 'http:' || !target.port || Number(target.port) < 10000) {
    throw new Error('Use a dedicated credential-free loopback test server on a high port');
}
const offlineBuild = process.env.ACTIVITY_PAGES_BUILD_DIR ? fs.realpathSync(process.env.ACTIVITY_PAGES_BUILD_DIR) : null;
if (offlineBuild) {
    for (const file of ['BUILD_ID', 'server/app/hot.html', 'server/app/notifications.html']) {
        assert.ok(fs.existsSync(path.join(offlineBuild, file)), 'Expected an isolated .next production build: ' + file);
    }
}
const mime = { '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.rsc': 'text/x-component',
    '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf' };
function safeFile(root, relative) {
    if (!fs.existsSync(root)) return null;
    const realRoot = fs.realpathSync(root);
    const candidate = path.resolve(realRoot, relative);
    if (!candidate.startsWith(realRoot + path.sep) || !fs.existsSync(candidate) || !fs.statSync(candidate).isFile()) return null;
    const real = fs.realpathSync(candidate);
    return real.startsWith(realRoot + path.sep) ? real : null;
}
async function installOfflineBuildRoutes(context) {
    if (!offlineBuild) return;
    // Registered after the shared fixture: API requests fall back to its mocks, never to the network.
    await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== target.origin || url.pathname.startsWith('/api/')) return route.fallback();
        let pathname;
        try { pathname = decodeURIComponent(url.pathname); } catch { return route.fulfill({ status: 400, body: 'Invalid fixture path' }); }
        let file;
        if (pathname.startsWith('/_next/static/')) {
            file = safeFile(path.join(offlineBuild, 'static'), pathname.slice('/_next/static/'.length));
        } else {
            file = safeFile(path.resolve(offlineBuild, '../public'), pathname.slice(1));
            if (!file) {
                const routeName = pathname === '/' ? 'index' : pathname.slice(1).replace(/\/$/, '');
                const extension = route.request().headers().rsc === '1' ? '.rsc' : '.html';
                file = safeFile(path.join(offlineBuild, 'server/app'), routeName + extension);
            }
        }
        if (!file) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'No generated fixture for this route' });
        return route.fulfill({ status: 200, contentType: mime[path.extname(file)] || 'application/octet-stream',
            headers: { 'cache-control': 'no-store' }, body: fs.readFileSync(file) });
    });
}
const output = process.env.ACTIVITY_PAGES_OUTPUT_DIR || path.resolve(__dirname, '../../tmp/activity-pages-browser');
fs.mkdirSync(output, { recursive: true });
const poster = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="30" height="45"><rect width="30" height="45" fill="#888"/></svg>');
const ranking = title => [{ movie: { id: title, titleCn: title, titleEn: title, category: 'MOVIE', year: 2026,
    posterUrl: poster, genres: [], regions: [], doubanScore: 0, imdbScore: 0 }, favoriteCount: 1 }];
const notice = (title, readFlag = false) => ({ id: 1, userId: 1, type: 'COMMENT_REPLY', title,
    content: 'Isolated notification fixture', readFlag, createdAt: '2026-10-12T10:00:00' });
const notificationPage = (title, readFlag = false) => ({ records: [notice(title, readFlag)], total: 40 });
const settle = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
async function open(f, pathname) {
    await f.page.goto(base + pathname, { waitUntil: 'domcontentloaded' });
    await f.page.waitForFunction(() => document.documentElement.lang === (localStorage.getItem('i18nextLng') === 'zh' ? 'zh-CN' : 'en'));
}

async function retry(f) {
    const button = f.page.locator('.ant-alert-error').getByRole('button');
    // Ant Design inserts a visual space into two-character Chinese button labels.
    assert.equal((await button.innerText()).replace(/\s/g, ''), f.t.retry.replace(/\s/g, ''));
    await button.click();
}

(async () => {
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true,
        args: ['--no-first-run', '--disable-background-networking'] });
    const results = [];
    async function run(name, options, onApi, body) {
        const f = await fixture(browser, { ...options, onApi });
        try {
            await installOfflineBuildRoutes(f.context);
            await body(f);
            await f.overflow();
            await f.page.screenshot({ path: path.join(output, name + '.png'), fullPage: true });
            await f.done();
            results.push({ name, status: 'PASS' });
            console.log('PASS ' + name);
        } catch (error) {
            results.push({ name, status: 'FAIL', error: error.message });
            await f.page.screenshot({ path: path.join(output, name + '-failure.png'), fullPage: true }).catch(() => {});
            await f.context.close();
            throw error;
        }
    }
    try {
        for (const options of [{ lang: 'en', width: 1440, theme: 'light' }, { lang: 'zh', width: 390, theme: 'dark' }]) {
            const prefix = options.lang + '-' + options.width;
            {
                const old = deferred(); let delivered = 0; let held = 0;
                try {
                    await run(prefix + '-hot-stale-response', { ...options, ignoreAbort: true }, async (route, url) => {
                        if (url.pathname !== '/api/favorites/hot') return false;
                        if (url.searchParams.get('period') === 'all') {
                            held++; await old.promise; await json(route, ranking('Old ranking')); delivered++; return true;
                        }
                        await json(route, ranking('Current ranking')); return true;
                    }, async f => {
                        await open(f, '/hot'); await waitFor(() => held > 0);
                        await f.page.getByText(f.t.hotWeek, { exact: true }).click();
                        await f.page.getByText('Current ranking', { exact: true }).waitFor();
                        old.resolve(); await waitFor(() => delivered === held); await settle(f.page);
                        assert.equal(await f.page.getByText('Old ranking', { exact: true }).count(), 0);
                        assert.equal(await f.page.getByText('Current ranking', { exact: true }).count(), 1);
                        assert.equal(await f.page.locator('.ant-spin-spinning').count(), 0);
                    });
                } finally { old.resolve(); }
            }
            {
                let failed = true;
                await run(prefix + '-hot-error-retry', options, async (route, url) => {
                    if (url.pathname !== '/api/favorites/hot') return false;
                    await json(route, failed ? { message: 'Ranking fixture unavailable' } : ranking('Recovered ranking'), failed ? 503 : 200);
                    return true;
                }, async f => {
                    await open(f, '/hot'); await f.page.locator('.ant-alert-error').waitFor();
                    assert.equal(await f.page.getByText(f.t.hotEmpty, { exact: true }).count(), 0, 'Failure is not a successful empty ranking');
                    failed = false; await retry(f);
                    await f.page.getByText('Recovered ranking', { exact: true }).waitFor();
                    assert.equal(await f.page.locator('.ant-alert-error').count(), 0);
                });
            }
            {
                const old = deferred(); let delivered = 0; let held = 0;
                try {
                    await run(prefix + '-notifications-stale-filter', { ...options, ignoreAbort: true }, async (route, url) => {
                        if (url.pathname !== '/api/notifications') return false;
                        if (url.searchParams.get('unreadOnly') === 'false') {
                            held++; await old.promise; await json(route, notificationPage('Old notifications')); delivered++; return true;
                        }
                        await json(route, notificationPage('Current unread notifications')); return true;
                    }, async f => {
                        await open(f, '/notifications'); await waitFor(() => held > 0);
                        await f.page.locator('.ant-card').getByRole('switch').click();
                        await f.page.getByText('Current unread notifications', { exact: true }).waitFor();
                        old.resolve(); await waitFor(() => delivered === held); await settle(f.page);
                        assert.equal(await f.page.getByText('Old notifications', { exact: true }).count(), 0);
                        assert.equal(await f.page.getByText('Current unread notifications', { exact: true }).count(), 1);
                    });
                } finally { old.resolve(); }
            }
            {
                let allRead = false; const afterMutation = [];
                await run(prefix + '-notifications-read-all-page', options, async (route, url) => {
                    if (url.pathname === '/api/notifications/read-all') {
                        assert.equal(route.request().method(), 'PUT'); allRead = true;
                        await json(route, { updated: 40 }); return true;
                    }
                    if (url.pathname !== '/api/notifications') return false;
                    const page = url.searchParams.get('page');
                    if (allRead) afterMutation.push(page);
                    await json(route, notificationPage(allRead ? 'After all read page ' + page : 'Initial page ' + page, allRead)); return true;
                }, async f => {
                    await open(f, '/notifications'); await f.page.getByText('Initial page 1', { exact: true }).waitFor();
                    await f.page.locator('.ant-pagination-item-2').click();
                    await f.page.getByText('Initial page 2', { exact: true }).waitFor();
                    await f.page.getByText(f.t.markAllRead, { exact: true }).click();
                    await f.page.getByText('After all read page 1', { exact: true }).waitFor();
                    assert.ok(afterMutation.length > 0);
                    assert.ok(afterMutation.every(page => page === '1'), 'No stale page-2 refresh after read-all');
                    assert.equal(await f.page.locator('.ant-pagination-item-active').getAttribute('title'), '1');
                    assert.equal(await f.page.getByText('Initial page 2', { exact: true }).count(), 0);
                    assert.equal(await f.page.getByText(f.t.markRead, { exact: true }).count(), 0);
                });
            }
            {
                let failed = true;
                await run(prefix + '-notifications-page-error-retry', options, async (route, url) => {
                    if (url.pathname !== '/api/notifications') return false;
                    const page = url.searchParams.get('page');
                    if (page === '2' && failed) await json(route, { message: 'Notification fixture unavailable' }, 503);
                    else await json(route, notificationPage('Notification page ' + page));
                    return true;
                }, async f => {
                    await open(f, '/notifications'); await f.page.getByText('Notification page 1', { exact: true }).waitFor();
                    await f.page.locator('.ant-pagination-item-2').click();
                    await f.page.locator('.ant-alert-error').waitFor();
                    assert.equal(await f.page.getByText('Notification page 1', { exact: true }).count(), 0);
                    assert.equal(await f.page.getByText(f.t.noNotifications, { exact: true }).count(), 0);
                    failed = false; await retry(f);
                    await f.page.getByText('Notification page 2', { exact: true }).waitFor();
                    assert.equal(await f.page.locator('.ant-alert-error').count(), 0);
                });
            }
        }
        console.log(results.length + ' browser scenarios passed; all API traffic was mocked.');
    } finally {
        fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({ base, transport: offlineBuild ? 'offline-production-build' : 'loopback-test-server', results }, null, 2) + '\n');
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
