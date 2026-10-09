// Offline browser regression: every API is mocked; no production credentials or writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.HOME_LOADING_BASE_URL || 'http://127.0.0.1:18083';
const outputDir = process.env.HOME_LOADING_OUTPUT_DIR;
const target = new URL(baseURL);
if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) || target.username || target.password) {
    throw new Error('HOME_LOADING_BASE_URL must be a credential-free loopback test server');
}
const filters = { genres: ['fixture genre'], regions: ['fixture region'], languages: ['fixture language'], years: ['2026'] };
const translations = Object.fromEntries(['en', 'zh'].map(lang => [lang,
    JSON.parse(fs.readFileSync(path.resolve(__dirname, `../../frontend/public/locales/${lang}/common.json`), 'utf8'))]));
const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="450"><rect width="300" height="450" fill="#2563eb"/></svg>');
const movie = id => ({ id, titleCn: id, titleEn: id, category: 'mv', year: 2026, posterUrl: image,
    doubanScore: 8, imdbScore: 8, genres: ['fixture genre'], regions: ['fixture region'], languages: ['fixture language'] });
const pageData = (id, current = 1, total = 1) => ({ records: id ? [movie(id)] : [], total, current, size: 30, pages: Math.ceil(total / 30) });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function within(promise) {
    let timer;
    try {
        return await Promise.race([promise, new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('Timed out waiting for a fixture request')), 15000);
        })]);
    } finally { clearTimeout(timer); }
}
const json = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const movieLink = (page, id) => page.locator(`a[href="/movie/${id}"]`);
const navigate = (page, query) => page.evaluate(query => window.history.pushState(null, '', `/?${query}`), query);

async function fixture(browser, options = {}) {
    const lang = options.lang || 'en';
    const context = await browser.newContext({ viewport: { width: options.width || 1280, height: 900 },
        locale: lang === 'zh' ? 'zh-CN' : 'en-US', serviceWorkers: 'block' });
    await context.addInitScript(({ lang, ignoreAbort }) => {
        localStorage.setItem('i18nextLng', lang);
        if (ignoreAbort) {
            // Simulate a transport that finishes despite cancellation. State must still ignore it.
            const originalFetch = window.fetch.bind(window);
            window.fetch = (input, init) => originalFetch(input, { ...init, signal: undefined });
        }
    }, { lang, ignoreAbort: Boolean(options.ignoreAbort) });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const requests = [], failures = [], errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('requestfailed', request => failures.push(request.url()));
    await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== target.origin) return route.abort();
        if (!url.pathname.startsWith('/api/')) {
            // Do not prefetch dynamic movie pages (and their server-side backend fetches).
            if (url.pathname.startsWith('/movie/')) return route.abort();
            return route.continue();
        }
        requests.push(url);
        if (url.pathname === '/api/movies/list') {
            return options.movies ? options.movies(route, url) : json(route, pageData('fixture-first'));
        }
        if (url.pathname === '/api/movies/filters') {
            return options.filters ? options.filters(route, url) : json(route, filters);
        }
        if (url.pathname === '/api/movies/hot-searches') return json(route, []);
        if (url.pathname.endsWith('/unread-count')) return json(route, { count: 0 });
        return json(route, {});
    });
    return { page, requests, failures, t: translations[lang],
        async close() { await context.close(); assert.deepEqual(errors, [], 'No runtime/hydration errors'); } };
}

async function main() {
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
    const results = [];
    async function run(name, scenario) {
        await scenario();
        results.push({ name, status: 'passed' });
        console.log(`PASS ${name}`);
    }
    try {
        await run('movie requests and carousel do not wait for filters', async () => {
            const release = deferred(), started = deferred();
            const f = await fixture(browser, { filters: async route => {
                started.resolve(); await release.promise; await json(route, filters);
            }, movies: (route, url) => json(route, pageData(url.searchParams.get('sort') === 'recent_hot'
                ? 'featured-fixture' : `fixture-${url.searchParams.get('category')}`)) });
            try {
                await f.page.goto(baseURL, { waitUntil: 'domcontentloaded' });
                await within(started.promise);
                await movieLink(f.page, 'fixture-mv').waitFor();
                await movieLink(f.page, 'fixture-tv').waitFor();
                await movieLink(f.page, 'fixture-ac').waitFor();
                await movieLink(f.page, 'featured-fixture').first().waitFor();
                assert.equal(f.requests.filter(url => url.pathname === '/api/movies/list').length, 4);
            } finally { release.resolve(); await f.close(); }
        });

        for (const ignoreAbort of [false, true]) {
            await run(ignoreAbort ? 'late responses cannot overwrite newer movies or filters' : 'query changes cancel obsolete requests', async () => {
                const release = deferred(), oldMoviesStarted = deferred(), oldFiltersStarted = deferred();
                const oldDone = deferred();
                const f = await fixture(browser, { ignoreAbort,
                    movies: async (route, url) => {
                        if (url.searchParams.get('category') === 'mv') {
                            oldMoviesStarted.resolve(); await release.promise;
                            await json(route, pageData('stale-movie')); oldDone.resolve();
                        } else await json(route, pageData('current-movie'));
                    }, filters: async (route, url) => {
                        if (url.searchParams.get('category') === 'mv') {
                            oldFiltersStarted.resolve(); await release.promise;
                            await json(route, { ...filters, genres: ['stale genre'] });
                        } else await json(route, { ...filters, genres: ['current genre'] });
                    } });
                try {
                    await f.page.goto(`${baseURL}/?category=mv`, { waitUntil: 'domcontentloaded' });
                    await within(Promise.all([oldMoviesStarted.promise, oldFiltersStarted.promise]));
                    await navigate(f.page, 'category=tv');
                    await movieLink(f.page, 'current-movie').waitFor();
                    await f.page.getByTestId('movie-filters-toggle').click();
                    await f.page.getByText('current genre', { exact: true }).waitFor();
                    release.resolve();
                    await within(oldDone.promise);
                    await f.page.waitForLoadState('networkidle');
                    assert.equal(await movieLink(f.page, 'stale-movie').count(), 0);
                    assert.equal(await f.page.getByText('stale genre', { exact: true }).count(), 0);
                    assert.equal(await movieLink(f.page, 'current-movie').count(), 1);
                    if (!ignoreAbort) {
                        assert.ok(f.failures.some(url => url.includes('/api/movies/list?') && url.includes('category=mv')));
                        assert.ok(f.failures.some(url => url.includes('/api/movies/filters?category=mv')));
                    }
                } finally { release.resolve(); await f.close(); }
            });
        }

        await run('load-more is single-flight and an old page cannot append to a new query', async () => {
            const release = deferred(), started = deferred(), finished = deferred();
            let pageTwoRequests = 0;
            const f = await fixture(browser, { ignoreAbort: true, movies: async (route, url) => {
                if (url.searchParams.get('category') === 'tv') return json(route, pageData('new-query'));
                if (url.searchParams.get('page') === '2') {
                    pageTwoRequests++; started.resolve(); await release.promise;
                    await json(route, pageData('stale-page-two', 2, 61)); finished.resolve();
                } else await json(route, pageData('first-page', 1, 61));
            } });
            try {
                await f.page.goto(`${baseURL}/?category=mv`, { waitUntil: 'domcontentloaded' });
                await movieLink(f.page, 'first-page').waitFor();
                await f.page.getByRole('button', { name: f.t.loadMore, exact: true }).evaluate(button => { button.click(); button.click(); });
                await within(started.promise);
                await navigate(f.page, 'category=tv');
                await movieLink(f.page, 'new-query').waitFor();
                release.resolve(); await within(finished.promise);
                await f.page.waitForLoadState('networkidle');
                assert.equal(pageTwoRequests, 1, 'Repeated clicks must not duplicate the pending page');
                assert.equal(await movieLink(f.page, 'stale-page-two').count(), 0);
                assert.equal(await movieLink(f.page, 'first-page').count(), 0);
            } finally { release.resolve(); await f.close(); }
        });

        await run('HTTP errors on the first page are retryable rather than empty success', async () => {
            let attempts = 0;
            const f = await fixture(browser, { movies: (route, url) => {
                assert.equal(url.searchParams.get('page'), '1');
                return ++attempts === 1 ? json(route, { error: 'fixture failure' }, 503) : json(route, pageData('recovered-first'));
            } });
            try {
                await f.page.goto(`${baseURL}/?category=mv`, { waitUntil: 'domcontentloaded' });
                await f.page.getByRole('alert').filter({ hasText: f.t.moviesLoadFailed }).waitFor();
                await f.page.getByRole('button', { name: f.t.retry, exact: true }).click();
                await movieLink(f.page, 'recovered-first').waitFor();
                assert.equal(attempts, 2);
            } finally { await f.close(); }
        });

        await run('failed pagination keeps existing movies and retries the same page', async () => {
            let pageTwoAttempts = 0;
            const f = await fixture(browser, { movies: (route, url) => {
                const current = Number(url.searchParams.get('page'));
                assert.ok(current === 1 || current === 2);
                if (current === 1) return json(route, pageData('retained-first', 1, 60));
                return ++pageTwoAttempts === 1 ? json(route, {}, 500) : json(route, pageData('recovered-second', 2, 60));
            } });
            try {
                await f.page.goto(`${baseURL}/?category=mv`, { waitUntil: 'domcontentloaded' });
                await movieLink(f.page, 'retained-first').waitFor();
                await f.page.getByRole('button', { name: f.t.loadMore, exact: true }).click();
                await f.page.getByRole('alert').filter({ hasText: f.t.moviesLoadFailed }).waitFor();
                assert.equal(await movieLink(f.page, 'retained-first').count(), 1);
                await f.page.getByRole('button', { name: f.t.retry, exact: true }).click();
                await movieLink(f.page, 'recovered-second').waitFor();
                assert.equal(await movieLink(f.page, 'retained-first').count(), 1);
                assert.equal(pageTwoAttempts, 2);
                assert.equal(await f.page.getByRole('button', { name: f.t.loadMore, exact: true }).count(), 0);
            } finally { await f.close(); }
        });

        await run('filter errors stay local and can be retried on mobile', async () => {
            let attempts = 0;
            const f = await fixture(browser, { lang: 'zh', width: 375, filters: route => ++attempts === 1
                ? json(route, {}, 503) : json(route, filters) });
            try {
                await f.page.goto(`${baseURL}/?category=mv`, { waitUntil: 'domcontentloaded' });
                await movieLink(f.page, 'fixture-first').waitFor();
                await f.page.getByTestId('movie-filters-toggle').click();
                const notice = f.page.getByRole('alert').filter({ hasText: f.t.movieFiltersLoadFailed });
                await notice.waitFor();
                await notice.getByRole('button').click();
                await f.page.getByText('fixture language', { exact: true }).waitFor();
                assert.equal(attempts, 2);
                assert.equal(await movieLink(f.page, 'fixture-first').count(), 1);
                assert.ok(await f.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'No mobile horizontal overflow');
                if (outputDir) {
                    fs.mkdirSync(outputDir, { recursive: true });
                    await f.page.screenshot({ path: path.join(outputDir, 'home-loading-mobile.png'), fullPage: true });
                }
            } finally { await f.close(); }
        });
        if (outputDir) {
            fs.mkdirSync(outputDir, { recursive: true });
            fs.writeFileSync(path.join(outputDir, 'home-loading-results.json'), JSON.stringify({ baseURL, results }, null, 2));
        }
        console.log(JSON.stringify({ baseURL, results }, null, 2));
    } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
