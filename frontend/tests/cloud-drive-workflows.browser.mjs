import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const build = process.env.CLOUD_DRIVE_BUILD_DIR;
const output = process.env.CLOUD_DRIVE_OUTPUT_DIR;
const baseURL = 'http://127.0.0.1:18097';
assert(build && path.isAbsolute(build), 'Provide an isolated production build via CLOUD_DRIVE_BUILD_DIR');
assert(output && path.isAbsolute(output), 'Provide an absolute CLOUD_DRIVE_OUTPUT_DIR');
const publicDir = path.resolve(build, '../public');
const results = [];
const workflows = () => [
    { provider: 'QUARK', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: true,
        shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: true, savePath: '/movies/quark', batchLimit: 5 },
    { provider: 'XUNLEI', supported: true, status: 'AVAILABLE', discoveryEnabled: true, transferEnabled: true,
        shareEnabled: true, gyingPublishEnabled: true, credentialConfigured: false, savePath: '/movies/xunlei', batchLimit: 4 },
    { provider: 'BAIDU', supported: false, status: 'PLANNED', discoveryEnabled: false, transferEnabled: false,
        shareEnabled: false, gyingPublishEnabled: false, credentialConfigured: false, savePath: null, batchLimit: null },
];

async function main() {
    fs.mkdirSync(output, { recursive: true });
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
    try {
        for (const width of [390, 1280]) for (const lang of ['zh', 'en']) for (const theme of ['light', 'dark']) {
            const strings = JSON.parse(fs.readFileSync(path.join(publicDir, 'locales', lang, 'common.json'), 'utf8'));
            const context = await browser.newContext({ viewport: { width, height: 1000 }, locale: lang === 'zh' ? 'zh-CN' : 'en-US', timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce', serviceWorkers: 'block' });
            await context.addInitScript(options => {
                localStorage.setItem('i18nextLng', options.lang); localStorage.setItem('theme', options.theme);
                localStorage.setItem('auth-storage', JSON.stringify({ state: { token: 'fixture-only-placeholder', user: { id: 1, username: 'fixture', role: 'ADMIN' } }, version: 0 }));
            }, { lang, theme });
            const page = await context.newPage(), errors = [], external = [], writes = [];
            let failSave = false;
            const config = {
                enabled: true, workerEnabled: true, tmdbConfigured: true,
                tmdbAutoSyncSources: 'POPULAR_MOVIE', tmdbAutoSyncPage: 1, tmdbAutoSyncEndPage: 500,
                tmdbAutoSyncRanges: { POPULAR_MOVIE: { startPage: 1, endPage: 500 } },
                gyingAutoSyncSources: 'HITS_MOVIE', gyingAutoSyncPage: 1, gyingAutoSyncEndPage: 50,
                gyingAutoSyncRanges: { HITS_MOVIE: { startPage: 1, endPage: 50 } },
                weeklyTransferSchedules: [], metadataCrawlProgress: [], driveWorkflows: workflows(),
            };
            page.on('pageerror', error => errors.push(error.message));
            page.on('console', message => { if (message.type() === 'error' && /hydration|React error|Minified React/i.test(message.text())) errors.push(message.text()); });
            await page.route('**/*', async route => {
                const req = route.request(), url = new URL(req.url());
                if (url.origin !== baseURL) { external.push(url.origin); return route.abort(); }
                if (url.pathname.startsWith('/api/')) {
                    let data = { records: [], total: 0 }, status = 200;
                    if (url.pathname === '/api/admin/resource-hub/overview') data = { enabled: true, tmdbConfigured: true, config,
                        worker: { enabled: true, publishLimit: 20, quarkLimit: 5 }, taskStatusCounts: {}, collectionStats: {} };
                    else if (url.pathname === '/api/auth/me') data = { id: 1, username: 'fixture', role: 'ADMIN' };
                    else if (url.pathname.endsWith('/unread-count')) data = 0;
                    else if (url.pathname === '/api/admin/resource-hub/config' && req.method() === 'PUT') {
                        const body = req.postDataJSON(); writes.push({ path: url.pathname, method: req.method(), body });
                        assert.equal(body.driveWorkflows.length, 1);
                        assert.deepEqual(Object.keys(body), ['driveWorkflows']);
                        if (failSave) { status = 503; data = { message: 'Fixture save failure' }; }
                        else {
                            const update = body.driveWorkflows[0];
                            config.driveWorkflows = config.driveWorkflows.map(row => row.provider === update.provider ? { ...row, ...update } : row);
                            data = config;
                        }
                    } else if (url.pathname === '/api/admin/resource-hub/transfers/run') {
                        writes.push({ path: url.pathname, method: req.method(), body: req.postDataJSON() });
                        data = { submitted: 1, skipped: 0, failed: 0 };
                    } else if (url.pathname.startsWith('/api/admin/resource-hub/') && req.method() !== 'GET') throw new Error('Unexpected Resource Hub write');
                    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(status === 200 ? { data } : data) });
                }
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
            await page.goto(baseURL + '/admin/resource-hub', { waitUntil: 'domcontentloaded' });
            await page.getByRole('tab', { name: strings.cloudDriveWorkflows.title, exact: true }).click();
            await page.getByTestId('drive-QUARK-savePath').waitFor();
            assert.equal(await page.getByTestId('drive-card-BAIDU').getByRole('switch').count(), 0);
            assert.equal(await page.getByTestId('drive-card-QUARK').getByRole('switch').count(), 4);
            for (const provider of ['QUARK', 'XUNLEI']) for (const key of ['discoveryEnabled', 'transferEnabled', 'shareEnabled', 'gyingPublishEnabled']) {
                assert.equal(await page.getByTestId(`drive-${provider}-${key}`).getAttribute('aria-label'), strings.cloudDriveWorkflows.providers[provider] + ' ' + strings.cloudDriveWorkflows.fields[key]);
            }
            for (const provider of ['QUARK', 'XUNLEI']) for (const action of ['save', 'run']) {
                const bounds = await page.getByTestId(`drive-${action}-${provider}`).boundingBox();
                assert.ok(bounds.height >= 44, 'Touch target must be at least 44px high');
            }
            const overflow = await page.evaluate(() => ({ viewport: innerWidth, scroll: document.documentElement.scrollWidth }));
            assert.ok(overflow.scroll <= overflow.viewport + 1, 'Document overflow: ' + JSON.stringify(overflow));
            assert.equal(await page.evaluate(value => document.documentElement.classList.contains(value), theme), true);
            assert.equal(writes.length, 0, 'Viewing does not start a workflow');
            const name = `${lang}-${width}-${theme}`;
            await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true });
            await page.getByTestId('drive-XUNLEI-savePath').fill('/unsaved/xunlei');
            await page.getByTestId('drive-QUARK-savePath').fill('/saved/quark');
            await page.getByTestId('drive-QUARK-shareEnabled').click();
            await page.getByText(strings.cloudDriveWorkflows.shareDisabledHelp, { exact: true }).waitFor();
            await page.getByTestId('drive-save-QUARK').click();
            await page.getByText(strings.cloudDriveWorkflows.saved.replace('{{provider}}', strings.cloudDriveWorkflows.providers.QUARK), { exact: true }).waitFor();
            assert.equal(await page.getByTestId('drive-XUNLEI-savePath').inputValue(), '/unsaved/xunlei');
            assert.equal(await page.getByTestId('drive-QUARK-savePath').inputValue(), '/saved/quark');
            assert.equal(writes.length, 1, 'Save does not run the workflow');
            assert.equal(await page.getByTestId('drive-QUARK-gyingPublishEnabled').getAttribute('aria-checked'), 'true');
            failSave = true;
            await page.getByTestId('drive-XUNLEI-savePath').fill('/retry/xunlei');
            await page.getByTestId('drive-save-XUNLEI').click();
            await page.getByText('Fixture save failure', { exact: true }).waitFor();
            assert.equal(await page.getByTestId('drive-XUNLEI-savePath').inputValue(), '/retry/xunlei');
            assert.deepEqual(errors, []); assert.deepEqual(external, []);
            results.push({ lang, width, theme, overflow, pageErrors: errors.length, externalRequests: external.length,
                onlyMockedWrites: writes, actualProductionAssets: true, passed: true });
            console.log('PASS ' + name);
            await context.close();
        }
    } finally { await browser.close(); }
    fs.writeFileSync(path.join(output, 'browser-qa.json'), JSON.stringify({ actualProductionAssets: true, mockedApi: true,
        productionApiWrites: false, nextServerStarted: false, scenarios: results }, null, 2));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
