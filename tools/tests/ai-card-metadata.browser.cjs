// Exercises the actual built AI page with fixture-only API responses. No login or inference.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const base = process.env.CATALOG_BASE_URL || 'http://127.0.0.1:18080';
const origin = new URL(base);
const build = process.env.CATALOG_BUILD_DIR;
const out = process.env.CATALOG_OUTPUT_DIR || path.join(process.cwd(), 'tmp/ai-card-metadata-browser');
assert(build && path.isAbsolute(build), 'CATALOG_BUILD_DIR must point to an absolute isolated production build');
assert.equal(origin.hostname, '127.0.0.1');
assert(!origin.username && !origin.password, 'Fixture origin cannot contain credentials');
const translations = Object.fromEntries(['zh', 'en'].map(lang => [lang, JSON.parse(fs.readFileSync(path.join(__dirname, '../../frontend/public/locales', lang, 'common.json'), 'utf8'))]));
const movies = [
    { id: 'fixture-missing-douban', titleCn: '缺豆瓣评分', titleEn: 'Missing Douban score', rating: null, ratingSource: 'douban', doubanScore: null, imdbScore: 9.7, genres: ['剧情'], runtimeMinutes: 95 },
    { id: 'fixture-zero-imdb', titleCn: '真实零分', titleEn: 'Real zero score', rating: 0, ratingSource: 'imdb', runtimeMinutes: 90, resourceCount: 0 },
    { id: 'fixture-omitted-rating', titleCn: '评分未返回', titleEn: 'Rating omitted', ratingSource: 'tmdb', runtimeMinutes: 125, resourceCount: 2 },
    { id: 'fixture-any-source', titleCn: '通用评分', titleEn: 'Generic rating', rating: 8.1, ratingSource: 'any', genres: ['科幻'], runtimeMinutes: 85 },
    { id: 'fixture-no-source', titleCn: '未指定来源', titleEn: 'Source omitted', rating: 7.2, runtimeMinutes: 100 },
];
const result = {
    answer: 'Fixture catalog answer',
    evidence: [{ tool: 'queryCatalog', queriedAt: '2026-11-03T00:00:00Z', movies }],
    queryPlan: { intent: 'search' },
};
async function staticResponse(route, url) {
    if (url.pathname === '/ai-assistant') return route.fulfill({ contentType: 'text/html', body: fs.readFileSync(path.join(build, 'server/app/ai-assistant.html')) });
    if (url.pathname.startsWith('/_next/static/')) {
        const root = path.resolve(build, 'static');
        const file = path.resolve(root, url.pathname.slice('/_next/static/'.length));
        assert(file.startsWith(root + path.sep));
        return route.fulfill({ path: file });
    }
    const publicRoot = path.resolve(build, '../public');
    const file = path.resolve(publicRoot, '.' + url.pathname);
    if (file.startsWith(publicRoot + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({ path: file });
    return route.abort();
}
(async () => {
    fs.mkdirSync(out, { recursive: true });
    const launch = { headless: true, args: ['--no-first-run', '--disable-background-networking'] };
    if (process.env.FRONTEND_TEST_BROWSER) {
        assert(fs.existsSync(process.env.FRONTEND_TEST_BROWSER), 'Use an existing Chromium executable');
        launch.executablePath = process.env.FRONTEND_TEST_BROWSER;
    } else launch.channel = process.env.BROWSER_CHANNEL || 'msedge';
    const browser = await chromium.launch(launch);
    const results = [];
    try {
        const scenarios = ['zh', 'en'].flatMap(lang => [390, 1280].flatMap(width => ['light', 'dark'].map(theme => ({ lang, width, theme }))));
        for (const options of scenarios) {
            const context = await browser.newContext({ viewport: { width: options.width, height: 900 }, serviceWorkers: 'block', reducedMotion: 'reduce' });
            await context.addInitScript(options => {
                localStorage.setItem('i18nextLng', options.lang);
                localStorage.setItem('theme', options.theme || 'light');
                localStorage.setItem('auth-storage', JSON.stringify({ state: { user: { id: 1, username: 'fixture', role: 'USER' }, token: 'fixture-only' }, version: 0 }));
            }, options);
            const page = await context.newPage();
            const errors = [], external = [], submissions = [];
            page.on('pageerror', error => errors.push(error.message));
            page.on('console', message => { if (message.type() === 'error' && /hydration|React error|Minified React/i.test(message.text())) errors.push(message.text()); });
            await page.route('**/*', async route => {
                const url = new URL(route.request().url());
                if (url.origin !== origin.origin) { external.push(url.origin); return route.abort(); }
                if (url.pathname.startsWith('/api/')) {
                    let body = { records: [], total: 0, unreadCount: 0 };
                    if (url.pathname === '/api/auth/me') body = { id: 1, username: 'fixture', role: 'USER' };
                    else if (url.pathname === '/api/ai/status') body = { available: true, catalogAvailable: true, busy: false };
                    else if (url.pathname === '/api/ai/query') {
                        submissions.push(route.request().postDataJSON());
                        body = { jobId: '11111111-1111-4111-8111-111111111111', status: 'SUCCEEDED', result };
                    }
                    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
                }
                return staticResponse(route, url);
            });
            await page.goto(base + '/ai-assistant');
            const input = page.locator('textarea[aria-label]');
            await input.waitFor();
            await page.waitForFunction(lang => document.documentElement.lang === lang, options.lang === 'zh' ? 'zh-CN' : 'en');
            await input.fill('Fixture metadata request');
            await input.press('Enter');
            await page.getByText(result.answer, { exact: true }).waitFor();
            assert.equal(await page.locator('main a[href^="/movie/fixture-"]').count(), movies.length);
            const metadata = async id => page.locator(`main a[href="/movie/${id}"]`).locator('..').locator('p').first().innerText();
            const t = translations[options.lang];
            const missing = await metadata('fixture-missing-douban');
            assert(missing.includes(`${t.aiRating} (DOUBAN): ${t.aiRatingUnavailable}`), missing);
            assert(!missing.includes('9.7'), 'Other-source score cannot fill the missing selected score');
            const zero = await metadata('fixture-zero-imdb');
            assert(zero.includes(`${t.aiRating} (IMDB): 0.0`), zero);
            assert(zero.includes(`90 ${t.aiMinutes}`), 'Runtime remains visible without genres');
            assert(zero.includes(`${t.aiResourceCount}: 0`), 'Zero resources remain visible without genres');
            const omitted = await metadata('fixture-omitted-rating');
            assert(omitted.includes(`${t.aiRating} (TMDB): ${t.aiRatingUnavailable}`), omitted);
            assert(omitted.includes(`125 ${t.aiMinutes}`), omitted);
            const generic = await metadata('fixture-any-source');
            assert(generic.includes(`${t.aiRating}: 8.1`), generic);
            assert(!/DOUBAN|IMDB|TMDB|ANY/.test(generic), 'An unspecified source cannot be presented as a named provider');
            const noSource = await metadata('fixture-no-source');
            assert(noSource.includes(`${t.aiRating}: 7.2`), noSource);
            assert(noSource.includes(`100 ${t.aiMinutes}`), noSource);
            assert.equal(submissions.length, 1, 'One fixture request per viewport');
            assert.equal(submissions[0].previousJobId, undefined);
            assert.deepEqual(submissions[0].history, []);
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, 'No horizontal overflow');
            assert.deepEqual(errors, [], 'No browser or hydration errors');
            assert.deepEqual(external, [], 'No external requests');
            await page.screenshot({ path: path.join(out, `ai-card-metadata-${options.lang}-${options.width}-${options.theme || 'light'}.png`), fullPage: true });
            results.push({ ...options, status: 'passed', fixtureCards: movies.length, selectedScoreMissing: true, zeroScore: true, omittedScore: true, missingGenres: true, unspecifiedSource: true, horizontalOverflow: false });
            await context.close();
        }
    } finally { await browser.close(); }
    const report = { ok: true, fixtureOnly: true, realLoginRequests: 0, realInferenceRequests: 0, results };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
