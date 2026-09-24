// 顶部搜索热词与筛选收起验收（所有 API 均被 mock）。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require(process.env.PUPPETEER_MODULE_PATH || 'puppeteer-core');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:18080';
const outDir = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.artifacts');

const hotSearches = [
    { keyword: '沙丘 2', count: 31 },
    { keyword: '繁花', count: 12 },
    { keyword: '庆余年', count: 9 },
];
const filters = {
    genres: ['剧情', '动作'],
    regions: ['中国大陆', '美国'],
    languages: ['国语', '英语'],
    years: ['2024', '2023'],
};

(async () => {
    fs.mkdirSync(outDir, { recursive: true });
    const browser = await puppeteer.launch({ executablePath: process.env.BROWSER_EXECUTABLE, headless: true });
    try {
        const page = await browser.newPage();
        await page.setViewport({ width: 1440, height: 1000 });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        const requested = [];
        await page.setRequestInterception(true);
        page.on('request', request => {
            const url = new URL(request.url());
            if (url.origin !== base) return void request.abort();
            if (!url.pathname.startsWith('/api/')) return void request.continue();
            const respond = (body, status = 200) => void request.respond({ status, contentType: 'application/json', body: JSON.stringify(body) });
            if (url.pathname === '/api/movies/hot-searches') {
                requested.push(`hot:${url.searchParams.get('days')}/${url.searchParams.get('limit')}`);
                return respond(hotSearches);
            }
            if (url.pathname === '/api/movies/filters') { requested.push('filters'); return respond(filters); }
            if (url.pathname === '/api/movies/list') { requested.push('list'); return respond({ records: [], total: 0, current: 1, size: 30, pages: 0 }); }
            if (url.pathname === '/api/notifications/unread-count') return respond({ count: 0 });
            return respond({});
        });

        // 1) 首页顶部搜索：火焰按钮打开“最近热门搜索”面板
        await page.goto(`${base}/`, { waitUntil: 'networkidle0', timeout: 60000 });
        await page.waitForSelector('[aria-label="最近热门搜索"]', { timeout: 30000 });
        assert.equal(await page.$('[data-testid="movie-filters"]'), null, '首页落地页不应显示筛选面板');
        await page.click('[aria-label="最近热门搜索"]');
        await page.waitForSelector('[data-testid="hot-searches"] [data-hot-keyword]', { timeout: 15000 });
        const hotTerms = await page.$$eval('[data-testid="hot-searches"] [data-hot-keyword]', nodes => nodes.map(node => node.textContent.trim()));
        assert.deepEqual(hotTerms, ['1. 沙丘 2', '2. 繁花', '3. 庆余年'], '热词应展示 3 个候选，实际：' + JSON.stringify(hotTerms));
        assert.ok(requested.some(item => item.startsWith('hot:7/8')), '顶部热词应请求近 7 天 8 条，实际：' + JSON.stringify(requested));
        await page.screenshot({ path: path.join(outDir, 'hot-search-panel-desktop.png'), fullPage: false });

        // 2) 点击热词直接搜索
        await page.click('[data-hot-keyword="繁花"]');
        await page.waitForFunction(() => decodeURIComponent(window.location.search).includes('keyword=繁花'), { timeout: 15000 });
        assert.equal(await page.$('[data-testid="hot-searches"]'), null, '跳转后热词面板应关闭');

        // 3) 搜索结果页：筛选默认收起，只保留排序
        await page.waitForSelector('[data-testid="movie-filters-toggle"]', { timeout: 15000 });
        assert.equal(await page.$eval('[data-testid="movie-filters-toggle"]', node => node.getAttribute('aria-expanded')), 'false', '筛选面板默认应为收起');
        let bodyText = await page.$eval('body', node => node.innerText);
        assert.ok(bodyText.includes('排序'), '收起状态仍应显示排序');
        assert.ok(!bodyText.includes('美国') && !bodyText.includes('国语'), '收起状态不应渲染筛选选项，实际：' + bodyText.slice(0, 200));
        await page.click('[data-testid="movie-filters-toggle"]');
        await page.waitForFunction(() => document.querySelector('[data-testid="movie-filters-toggle"]').getAttribute('aria-expanded') === 'true', { timeout: 15000 });
        bodyText = await page.$eval('body', node => node.innerText);
        assert.ok(bodyText.includes('美国') && bodyText.includes('国语') && bodyText.includes('2024'), '展开后应显示地区/语言/年份选项');
        await page.screenshot({ path: path.join(outDir, 'movie-filters-expanded.png'), fullPage: false });

        // 4) 分类页已选筛选：收起时显示可清除摘要
        await page.goto(`${base}/?category=mv&genre=${encodeURIComponent('动作')}`, { waitUntil: 'networkidle0', timeout: 60000 });
        await page.waitForSelector('[data-testid="movie-filters-summary"]', { timeout: 30000 });
        const summary = await page.$eval('[data-testid="movie-filters-summary"]', node => node.innerText);
        assert.ok(summary.includes('动作'), '摘要应显示当前类型筛选，实际：' + summary);
        assert.ok(summary.includes('清除全部'), '摘要应提供清除全部入口，实际：' + summary);
        assert.equal(await page.$eval('[data-testid="movie-filters-toggle"]', node => node.getAttribute('aria-expanded')), 'false', '带筛选的分类页仍应默认收起');
        await page.screenshot({ path: path.join(outDir, 'movie-filters-collapsed.png'), fullPage: false });

        await page.click('[data-testid="movie-filters-summary"] button');
        await page.waitForFunction(() => !decodeURIComponent(window.location.search).includes('genre='), { timeout: 15000 });
        assert.equal(await page.$('[data-testid="movie-filters-summary"]'), null, '清除后不应再显示摘要');

        // 5) 移动端抽屉：搜索框聚焦后直接展示热词
        await page.setViewport({ width: 390, height: 844 });
        await page.goto(`${base}/`, { waitUntil: 'networkidle0', timeout: 60000 });
        await page.click('[aria-label="菜单"]');
        await (await page.waitForSelector('.ant-drawer input', { timeout: 15000 })).click();
        await page.waitForSelector('.ant-drawer [data-testid="hot-searches"] [data-hot-keyword]', { timeout: 15000 });
        const mobileTerms = await page.$$eval('.ant-drawer [data-hot-keyword]', nodes => nodes.map(node => node.textContent.trim()));
        assert.deepEqual(mobileTerms, ['1. 沙丘 2', '2. 繁花', '3. 庆余年'], '移动端抽屉应展示热词，实际：' + JSON.stringify(mobileTerms));
        await page.screenshot({ path: path.join(outDir, 'hot-search-mobile-drawer.png'), fullPage: false });

        assert.equal(errors.length, 0, `页面不应有 JS 错误: ${errors.join('; ')}`);
        console.log(JSON.stringify({ ok: true, requests: requested, hotTerms }, null, 2));
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error.stack || error.message); process.exit(1); });
