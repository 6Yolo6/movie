// Real production-build UI with all requests fulfilled locally; no account or production writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const build = fs.realpathSync(process.env.RESOURCE_SEARCH_BUILD_DIR);
const base = 'http://127.0.0.1:18086';
const output = process.env.RESOURCE_SEARCH_OUTPUT_DIR;
if (output) fs.mkdirSync(output, { recursive: true });
const translations = lang => JSON.parse(fs.readFileSync(path.resolve(build, '../public/locales', lang, 'common.json'), 'utf8'));
const mime = { '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.rsc': 'text/x-component', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' };
function safeFile(root, relative) {
    const file = path.resolve(root, relative);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return null;
    return fs.realpathSync(file).startsWith(root + path.sep) ? file : null;
}
async function fixture(browser, lang, width, theme) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, locale: lang === 'zh' ? 'zh-CN' : 'en-US', serviceWorkers: 'block' });
    await context.addInitScript(({ lang, theme }) => { localStorage.setItem('i18nextLng', lang); localStorage.setItem('theme', theme); }, { lang, theme });
    const requests = [], errors = [];
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && /hydration|Minified React/i.test(m.text())) errors.push(m.text()); });
    await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== base) return route.abort();
        if (url.pathname.startsWith('/api/')) {
            requests.push({ path: url.pathname, method: route.request().method(), body: route.request().postDataJSON() });
            let body = {};
            if (url.pathname === '/api/auth/login') body = { token: 'fixture-only' };
            if (url.pathname === '/api/auth/me') body = { id: 73, username: 'fixture-user', role: 'USER' };
            if (url.pathname === '/api/movies/list') body = { records: [], total: 0, pages: 0 };
            if (url.pathname === '/api/movies/filters') body = { genres: [], regions: [], languages: [], years: [] };
            if (url.pathname === '/api/movies/hot-searches') body = [];
            if (url.pathname === '/api/resource-search/query') body = { jobId: 'fixture-job', status: 'DONE', reply: '测试搜索完成', links: [] };
            if (url.pathname.startsWith('/api/resource-search/jobs/')) body = { jobId: 'fixture-job', status: 'DONE', reply: '测试搜索完成', links: [] };
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        }
        let file;
        const pathname = decodeURIComponent(url.pathname);
        if (pathname.startsWith('/_next/static/')) file = safeFile(path.join(build, 'static'), pathname.slice('/_next/static/'.length));
        else {
            file = safeFile(path.resolve(build, '../public'), pathname.slice(1));
            if (!file) file = safeFile(path.join(build, 'server/app'), (pathname === '/' ? 'index' : pathname.slice(1).replace(/\/$/, '')) + (route.request().headers().rsc === '1' ? '.rsc' : '.html'));
        }
        if (!file) return route.fulfill({ status: 404, body: 'No generated fixture route' });
        return route.fulfill({ status: 200, contentType: mime[path.extname(file)] || 'application/octet-stream', body: fs.readFileSync(file) });
    });
    return { page, context, requests, errors, t: translations(lang) };
}
(async () => {
    const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-background-networking', '--no-first-run'] });
    const results = [];
    try {
        for (const [lang, width, theme] of [['zh', 390, 'dark'], ['zh', 1440, 'light'], ['en', 390, 'light'], ['en', 1440, 'dark']]) {
            const f = await fixture(browser, lang, width, theme);
            try {
                await f.page.goto(base, { waitUntil: 'domcontentloaded' });
                await f.page.waitForFunction(() => document.documentElement.lang === (localStorage.getItem('i18nextLng') === 'zh' ? 'zh-CN' : 'en'));
                await f.page.getByTestId('drawer-trigger').click();
                const menu = f.page.locator('.ant-drawer a[href="/resource-search"]');
                await menu.waitFor();
                assert.ok((await menu.innerText()).includes(f.t.navSearchResources));
                await menu.click();
                const gate = f.page.getByTestId('resource-search-login');
                await gate.getByText(f.t.resourceSearchLoginRequired, { exact: true }).waitFor();
                assert.equal(await f.page.locator('input[aria-label="影片名或回复"]').count(), 0);
                assert.equal(f.requests.filter(r => r.path.startsWith('/api/resource-search/')).length, 0);
                const returnPath = '/resource-search?q=%E6%B5%8B%E8%AF%95&auto=1';
                await f.page.goto(base + returnPath, { waitUntil: 'domcontentloaded' });
                await gate.waitFor();
                const login = gate.locator('a');
                assert.equal(new URL(await login.getAttribute('href'), base).searchParams.get('redirect'), returnPath);
                await login.click();
                await f.page.getByPlaceholder(f.t.loginIdentifier, { exact: true }).fill('fixture-user');
                await f.page.getByPlaceholder(f.t.password, { exact: true }).fill('fixture-only-password');
                await f.page.locator('form button[type="submit"]').click();
                await f.page.waitForURL(url => url.pathname === '/resource-search' && url.searchParams.get('q') === '测试');
                await f.page.locator('input[aria-label="影片名或回复"]').waitFor();
                await f.page.waitForFunction(() => document.querySelector('[data-testid="search-turn"]'));
                assert.equal(await gate.count(), 0);
                assert.equal(f.requests.filter(r => r.path === '/api/resource-search/query').length, 1);
                assert.equal(f.requests.find(r => r.path === '/api/resource-search/query').body.keyword, '测试');
                assert.deepEqual(f.errors, []);
                assert.equal(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
                if (output) { fs.mkdirSync(output, { recursive: true }); await f.page.screenshot({ path: path.join(output, `${lang}-${width}-${theme}.png`), fullPage: true }); }
                results.push({ lang, width, theme, status: 'passed' });
                console.log(`PASS ${lang} ${width}px ${theme}: anonymous menu, login gate, return URL, authenticated query`);
            } finally { await f.context.close(); }
        }
    } finally {
        await browser.close();
        if (output) fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2));
    }
})().catch(e => { console.error(e); process.exitCode = 1; });
