const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { overview, logPage } = require('./monitoring-resources.cjs');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:18080';
const origin = new URL(base);
if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname) || origin.username || origin.password) throw Error('Browser tests require a credential-free loopback frontend');
const json = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const translations = Object.fromEntries(['en', 'zh'].map(lang => [lang, JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../frontend/public/locales/' + lang + '/common.json'), 'utf8'))]));
async function fixture(browser, options = {}) {
    const lang = options.lang || 'zh';
    const context = await browser.newContext({ viewport: { width: options.width || 1440, height: options.height || 1050 }, locale: lang === 'zh' ? 'zh-CN' : 'en-US', timezoneId: 'America/Los_Angeles', reducedMotion: 'reduce', serviceWorkers: 'block' });
    await context.addInitScript(({ lang, theme, ignoreAbort, anonymous }) => {
        localStorage.setItem('i18nextLng', lang); localStorage.setItem('theme', theme);
        if (!anonymous) localStorage.setItem('auth-storage', JSON.stringify({ state: { token: 'fixture-only', user: { id: 1, username: 'fixture', role: 'ADMIN' } }, version: 0 }));
        if (ignoreAbort) { const fetch = window.fetch.bind(window); window.fetch = (input, init) => fetch(input, { ...init, signal: undefined }); }
    }, { lang, theme: options.theme || 'light', ignoreAbort: Boolean(options.ignoreAbort), anonymous: Boolean(options.anonymous) });
    const page = await context.newPage(); page.setDefaultTimeout(15000);
    const errors = [], requests = [], external = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error' && /hydration|React error|Minified React/i.test(message.text())) errors.push(message.text()); });
    await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== origin.origin) { external.push(url.origin); return route.abort(); }
        if (!url.pathname.startsWith('/api/')) return route.continue();
        requests.push({ path: url.pathname, params: Object.fromEntries(url.searchParams), method: route.request().method() });
        if (options.onApi && await options.onApi(route, url, requests)) return;
        if (url.pathname === '/api/admin/monitoring/overview') return json(route, overview);
        if (url.pathname === '/api/admin/monitoring/logs') return json(route, logPage(url.searchParams));
        if (url.pathname === '/api/auth/me') return json(route, { id: 1, username: 'fixture', role: 'ADMIN' });
        if (url.pathname === '/api/resources/mine') return json(route, { records: [] });
        if (url.pathname === '/api/resources/form-config') return json(route, { quickParams: [] });
        if (url.pathname.startsWith('/api/favorites/')) return json(route, { count: 0, favorited: false });
        if (url.pathname.endsWith('/unread-count')) return json(route, 0);
        return json(route, {});
    });
    const overflow = async () => {
        const result = await page.evaluate(() => ({ width: window.innerWidth, document: document.documentElement.scrollWidth,
            overflowing: [...document.querySelectorAll('main *')].filter(node => {
                const rect = node.getBoundingClientRect(); return rect.width > 0 && (rect.right > window.innerWidth + 2 || rect.left < -2) && getComputedStyle(node).position !== 'absolute';
            }).slice(0, 6).map(node => node.tagName + '.' + node.className) }));
        assert.ok(result.document <= result.width + 2, JSON.stringify(result));
    };
    return { page, context, requests, errors, external, t: translations[lang], overflow,
        async done() { assert.deepEqual(errors, [], 'No browser or hydration errors: ' + JSON.stringify(errors)); assert.deepEqual(external, [], 'No external requests'); await context.close(); } };
}
const waitFor = async (predicate, message = 'fixture request') => {
    const deadline = Date.now() + 15000;
    while (!predicate()) { if (Date.now() > deadline) throw Error('Timed out: ' + message); await new Promise(resolve => setTimeout(resolve, 25)); }
};
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
module.exports = { base, fixture, json, waitFor, deferred, translations };
