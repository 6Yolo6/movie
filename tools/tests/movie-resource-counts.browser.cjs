// Requires the read-only SSR fixture API and an isolated frontend pointing at it.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const { base, fixture, json, deferred, waitFor } = require('./fixtures/browser.cjs');
const { resource } = require('./fixtures/monitoring-resources.cjs');
const out = process.env.SCREENSHOT_DIR;
const results = [];
async function counts(page) {
    return page.locator('[data-resource-tab]').evaluateAll(nodes => Object.fromEntries(nodes.map(node => [node.dataset.resourceTab, Number(node.dataset.resourceCount)])));
}
async function open(f, id, waitForNetwork = true) {
    await f.page.goto(base + '/movie/' + id, { waitUntil: 'domcontentloaded' });
    await f.page.locator('[data-resource-tab="disk"]').waitFor();
    if (waitForNetwork) await f.page.waitForLoadState('networkidle');
    await f.page.evaluate(() => document.fonts.ready);
    await f.page.waitForFunction(() => document.documentElement.lang === (localStorage.getItem('i18nextLng') === 'zh' ? 'zh-CN' : 'en'));
}
(async () => {
    if (out) fs.mkdirSync(out, { recursive: true });
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true, args: ['--no-first-run', '--disable-background-networking'] });
    try {
        for (const options of [{ lang: 'zh', width: 1440 }, { lang: 'en', width: 1440, theme: 'dark' }, { lang: 'zh', width: 360 }, { lang: 'en', width: 390, theme: 'dark' }, { lang: 'zh', width: 430 }]) {
            const f = await fixture(browser, options); await open(f, 'fixture-mixed'); const n = await counts(f.page);
            assert.equal(n.disk, 3); assert.equal(n.p2p, 4); assert.equal(n.online, 1); assert.equal(n.other, 1);
            assert.equal(n['provider-QUARK'], 2); assert.equal(n['provider-XUNLEI'], 1); assert.equal(n['provider-ALL'], 3);
            const diskText = await f.page.locator('[data-resource-tab="disk"]').innerText(); assert.ok(diskText.includes(f.t.cloudDisk) && diskText.includes('3'));
            const p2pText = await f.page.locator('[data-resource-tab="p2p"]').innerText(); assert.ok(p2pText.includes(f.t.magnetTorrent) && p2pText.includes('4'));
            await f.page.locator('[data-resource-tab="p2p"]').click();
            await f.page.locator('[data-resource-tab="magnet"]').waitFor();
            let sub = await counts(f.page); assert.equal(sub.magnet, 1); assert.equal(sub.torrent, 3);
            assert.equal(await f.page.getByText('Fixture MAGNET 4', { exact: true }).count(), 1);
            assert.equal(await f.page.getByText('Fixture TORRENT 5', { exact: true }).isVisible(), false);
            await f.page.locator('[data-resource-tab="torrent"]').click();
            await f.page.getByText('Fixture TORRENT 5', { exact: true }).waitFor();
            assert.equal(await f.page.getByText('Fixture MAGNET 4', { exact: true }).isVisible(), false);
            await f.overflow();
            if (out) { await f.page.locator('[data-resource-tab="disk"]').scrollIntoViewIfNeeded(); await f.page.screenshot({ path: path.join(out, `resource-counts-${options.lang}-${options.width}-${options.theme || 'light'}.png`), fullPage: true }); }
            results.push(`mixed content and provider counts ${options.lang}/${options.width}/${options.theme || 'light'}`); await f.done();
        }
        for (const [id, expected] of [['fixture-empty', { disk: 0, p2p: 0 }], ['fixture-p2p', { disk: 0, p2p: 4 }], ['fixture-disk', { disk: 3, p2p: 0 }], ['fixture-many', { disk: 0, p2p: 137 }]]) {
            const f = await fixture(browser, { anonymous: true }); await open(f, id); const n = await counts(f.page);
            assert.equal(n.disk, expected.disk); assert.equal(n.p2p, expected.p2p);
            if (expected.p2p && !expected.disk) assert.equal(await f.page.locator('[data-resource-tab="p2p"]').locator('xpath=ancestor::*[@role="tab"]').getAttribute('aria-selected'), 'true', 'P2P-only movies should open their populated tab');
            if (id === 'fixture-many') {
                const sub = await counts(f.page); assert.equal(sub.magnet, 0); assert.equal(sub.torrent, 137);
                assert.equal(await f.page.locator('[data-resource-tab="torrent"]').locator('xpath=ancestor::*[@role="tab"]').getAttribute('aria-selected'), 'true');
            }
            if (id === 'fixture-many') assert.ok((await f.page.locator('[data-resource-tab="p2p"]').innerText()).includes('137'), 'Do not truncate to 99+');
            results.push(`${id}: zeros, default active category and exact large counts`); await f.done();
        }
        {
            const delay = deferred(); let pending = false;
            const f = await fixture(browser, { onApi: async (route, url) => {
                if (url.pathname !== '/api/resources/mine') return false;
                pending = true; await delay.promise;
                await json(route, { records: [resource(1, 'DISK', 'QUARK', 'fixture-mine'), resource(10, 'TORRENT', 'QUARK', 'fixture-mine'), resource(99, 'DISK', 'QUARK', 'wrong-movie')] }); return true;
            } });
            await open(f, 'fixture-mine', false); await waitFor(() => pending);
            assert.equal((await counts(f.page)).p2p, 4); delay.resolve();
            await f.page.waitForFunction(() => document.querySelector('[data-resource-tab="p2p"]')?.dataset.resourceCount === '5');
            assert.equal((await counts(f.page)).disk, 3, 'Own public rows are deduplicated and other-movie rows ignored');
            results.push('private resource merge updates counts without duplicates or cross-movie contamination'); await f.done();
        }
        console.log(JSON.stringify({ ok: true, scenarios: results.length, results }, null, 2));
        if (out) fs.writeFileSync(path.join(out, 'resource-count-results.json'), JSON.stringify({ ok: true, scenarios: results.length, results }, null, 2));
    } finally { await browser.close(); }
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
