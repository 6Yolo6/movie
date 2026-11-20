const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
// Use an existing Playwright installation; no production dependency is needed.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.SITE_FOOTER_BASE_URL || 'http://127.0.0.1:18080';
const outputDir = process.env.SITE_FOOTER_OUTPUT_DIR;
const translations = Object.fromEntries(['zh', 'en'].map(lang => [lang,
    JSON.parse(fs.readFileSync(path.resolve(__dirname, `../../frontend/public/locales/${lang}/common.json`), 'utf8'))]));

async function main() {
    const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
    const results = [];
    try {
        for (const scenario of [
            { name: 'desktop-light-zh', width: 1440, height: 1000, theme: 'light', lang: 'zh' },
            { name: 'mobile-dark-zh', width: 375, height: 812, theme: 'dark', lang: 'zh' },
            { name: 'small-mobile-light-en', width: 320, height: 640, theme: 'light', lang: 'en' },
            { name: 'desktop-dark-en', width: 1280, height: 900, theme: 'dark', lang: 'en' },
        ]) {
            const { name, width, height, theme, lang } = scenario;
            const t = translations[lang];
            const context = await browser.newContext({ viewport: { width, height }, locale: lang === 'zh' ? 'zh-CN' : 'en-US', colorScheme: theme });
            await context.addInitScript(({ theme, lang }) => {
                localStorage.setItem('theme', theme);
                localStorage.setItem('i18nextLng', lang);
            }, { theme, lang });
            const page = await context.newPage();
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            const response = await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
            assert.equal(response.status(), 200);
            await page.locator('nav button').first().waitFor({ state: 'visible' });
            await page.waitForFunction(() => !document.querySelector('nav button')?.disabled);
            const footer = page.getByRole('contentinfo');
            const trigger = footer.getByRole('button', { name: t.contactUs, exact: true });
            await trigger.waitFor({ state: 'visible' });
            assert.equal(await page.locator('nav a[href="/messages"]').count(), 0, 'No header message link');
            await page.locator('nav button').first().click();
            const drawer = page.locator('.ant-drawer').getByRole('dialog');
            await drawer.waitFor({ state: 'visible' });
            assert.equal(await drawer.locator('a[href="/messages"]').count(), 0, 'No drawer message link');
            await page.locator('.ant-drawer-close').click();
            await drawer.waitFor({ state: 'hidden' });
            await trigger.scrollIntoViewIfNeeded();
            await page.mouse.move(0, 0);
            const messageLink = footer.getByRole('link', { name: t.messageBoard, exact: true });
            const colorOf = locator => locator.evaluate(element => getComputedStyle(element).color);
            const defaultColor = await colorOf(trigger);
            assert.equal(await colorOf(messageLink), defaultColor, 'Message link uses the same neutral default color as Contact');
            await messageLink.hover();
            await page.waitForFunction(({ selector, defaultColor }) => getComputedStyle(document.querySelector(selector)).color !== defaultColor,
                { selector: 'footer a[href="/messages"]', defaultColor });
            await page.mouse.move(0, 0);
            await page.waitForFunction(({ selector, defaultColor }) => getComputedStyle(document.querySelector(selector)).color === defaultColor,
                { selector: 'footer a[href="/messages"]', defaultColor });
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'No page overflow');
            if (outputDir) {
                fs.mkdirSync(outputDir, { recursive: true });
                await page.screenshot({ path: path.join(outputDir, `${name}-footer.png`) });
            }
            await trigger.click();
            const modal = page.getByRole('dialog', { name: t.contactUs, exact: true });
            await modal.waitFor({ state: 'visible' });
            assert.equal(await modal.getByText(t.contactQqPriority, { exact: true }).count(), 1);
            assert.equal(await modal.getByText(t.contactEmailHint, { exact: true }).count(), 1);
            assert.equal(await modal.getByRole('link', { name: '2031798793@qq.com' }).getAttribute('href'), 'mailto:2031798793@qq.com');
            const image = modal.getByRole('img', { name: t.contactQqQrAlt, exact: true });
            await image.waitFor({ state: 'visible' });
            await image.evaluate(image => image.decode());
            assert.equal(await image.evaluate(image => image.naturalWidth), 1327, 'Original JPEG loaded');
            const bounds = await modal.boundingBox();
            assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width + 1, 'Modal fits viewport');
            assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= height + 1, 'Modal fits viewport vertically');
            if (outputDir) await page.screenshot({ path: path.join(outputDir, `${name}-modal.png`) });
            await modal.getByRole('link', { name: '2031798793@qq.com' }).scrollIntoViewIfNeeded();
            await page.keyboard.press('Escape');
            await modal.waitFor({ state: 'hidden' });
            await page.waitForFunction(() => document.activeElement?.getAttribute('aria-haspopup') === 'dialog');
            await trigger.press('Enter');
            await modal.waitFor({ state: 'visible' });
            await modal.locator('.ant-modal-close').click();
            await modal.waitFor({ state: 'hidden' });
            await footer.getByRole('link', { name: t.messageBoard, exact: true }).click();
            await page.waitForURL('**/messages');
            await page.getByRole('heading', { name: t.messageBoard, exact: true }).waitFor();
            assert.equal(await footer.count(), 1, 'Shared footer remains on messages page');
            await page.locator('nav a[href="/login"]').click();
            await page.waitForURL('**/login');
            await trigger.waitFor({ state: 'visible' });
            assert.equal(await footer.count(), 1, 'Shared footer remains on login page');
            if (errors.length) console.error(JSON.stringify({ scenario: name, errors }, null, 2));
            assert.deepEqual(errors, [], `${name}: no runtime/hydration errors`);
            results.push({ scenario: name, status: 'passed' });
            await context.close();
        }
        console.log(JSON.stringify({ baseURL, results }, null, 2));
    } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
