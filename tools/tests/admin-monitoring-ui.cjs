// 后台监控页验收：统计卡片、可视化图表、日志列命名与筛选（所有 API 均被 mock）。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require(process.env.PUPPETEER_MODULE_PATH || 'puppeteer-core');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:3009';
const outDir = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.artifacts');

const traffic = [];
for (let i = 13; i >= 0; i -= 1) {
    const day = new Date(2026, 8, 24 - i);
    traffic.push({
        day: day.toISOString().slice(0, 10),
        requests: 300 + ((i * 97) % 900),
        visitors: 60 + ((i * 13) % 120),
        pageViews: 90 + ((i * 31) % 300),
        serverErrors: i % 5,
    });
}
const overview = {
    requestsToday: 1284, visitorsToday: 173, pageViewsToday: 402,
    clientErrorsToday: 37, serverErrorsToday: 6,
    searchesToday: 96, resourceOperationsToday: 214,
    socialPostedToday: 12, socialFailedToday: 3,
    traffic,
    hotSearches: [
        { keyword: '沙丘 2', count: 31 }, { keyword: '周处除三害', count: 24 },
        { keyword: '流浪地球 3', count: 18 }, { keyword: '繁花', count: 12 },
        { keyword: '庆余年', count: 9 }, { keyword: '热辣滚烫', count: 5 },
    ],
};
const logs = {
    access: [
        { id: 3, request_path: '/api/movies?page=1', method: 'GET', status_code: 200, duration_ms: 42, event_type: 'API', created_at: '2026-09-24T21:40:11' },
        { id: 2, request_path: '/resource-search', method: 'POST', status_code: 500, duration_ms: 1320, event_type: 'PAGE_VIEW', created_at: '2026-09-24T21:39:02' },
        { id: 1, request_path: '/api/admin/users', method: 'GET', status_code: 403, duration_ms: 180, event_type: 'API', created_at: '2026-09-24T21:38:47' },
    ],
    searches: [
        { id: 2, keyword: '沙丘 2', source: 'WEB', result_count: 3, created_at: '2026-09-24T21:35:10' },
        { id: 1, keyword: '繁花', source: 'WEB', result_count: 0, created_at: '2026-09-24T21:30:00' },
    ],
    resourceOperations: [
        { id: 2, movie_id: '1421', resource_link_id: '88213', operation_type: 'VIEW', provider: 'quark', status: 'SUCCESS', error_message: null, created_at: '2026-09-24T21:20:33' },
        { id: 1, movie_id: '995', resource_link_id: '51002', operation_type: 'SAVE', provider: 'xunlei', status: 'FAILED', error_message: '分享链接已失效', created_at: '2026-09-24T21:10:01' },
    ],
    qqSearches: [
        { id: 2, keyword: '流浪地球', status: 'SUCCESS', movie_id: '88', resource_count: 5, failure_reason: null, created_at: '2026-09-24T20:58:22' },
        { id: 1, keyword: '测试无结果', status: 'NO_RESULT', movie_id: null, resource_count: 0, failure_reason: null, created_at: '2026-09-24T20:40:09' },
    ],
    socialPosts: [
        { id: 2, platform: 'weibo', title: '今日更新：沙丘 2', status: 'POSTED', posted_at: '2026-09-24T20:30:00', error_message: null, created_at: '2026-09-24T20:29:40' },
        { id: 1, platform: 'qq-channel', title: '今日更新：繁花', status: 'FAILED', posted_at: null, error_message: '频控 20063', created_at: '2026-09-24T20:05:00' },
    ],
};

(async () => {
    fs.mkdirSync(outDir, { recursive: true });
    const browser = await puppeteer.launch({ executablePath: process.env.BROWSER_EXECUTABLE, headless: true });
    try {
        const page = await browser.newPage();
        await page.setViewport({ width: 1440, height: 1400 });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.evaluateOnNewDocument(() => localStorage.setItem('auth-storage', JSON.stringify({ state: { token: 'fixture-only', user: { id: 1, username: 'fixture', role: 'ADMIN' } }, version: 0 })));
        const requested = [];
        await page.setRequestInterception(true);
        page.on('request', request => {
            const url = new URL(request.url());
            if (url.origin !== base) return void request.abort();
            if (!url.pathname.startsWith('/api/')) return void request.continue();
            const respond = (body, status = 200) => void request.respond({ status, contentType: 'application/json', body: JSON.stringify(body) });
            if (url.pathname === '/api/admin/monitoring/overview') { requested.push('overview'); return respond(overview); }
            if (url.pathname === '/api/admin/monitoring/logs') { requested.push(`logs:${url.searchParams.get('q') || ''}`); return respond(logs); }
            return respond({});
        });

        await page.goto(`${base}/admin/monitoring`, { waitUntil: 'networkidle0', timeout: 60000 });
        await page.waitForSelector('[data-metric-card]', { timeout: 30000 });

        // 1) 统计卡片
        const cards = await page.$$eval('[data-metric-card]', nodes => nodes.map(node => node.innerText.replace(/\s+/g, ' ').replace(/,/g, '').trim()));
        assert.equal(cards.length, 6, '应有 6 个统计卡片');
        assert.ok(cards.some(text => text.includes('今日 API 请求') && text.includes('1284')), 'API 请求卡片应显示 1284');
        assert.ok(cards.some(text => text.includes('服务端错误') && text.includes('6')), '服务端错误卡片应显示 6');

        // 2) 趋势图：14 根柱、可切换指标
        const bars = await page.$$('[data-trend-bar]');
        assert.equal(bars.length, 14, '趋势图应有 14 根柱子');
        const metricOptions = await page.$$eval('[data-trend-metric] .ant-segmented-item-label', nodes => nodes.map(n => n.innerText.trim()));
        assert.deepEqual(metricOptions, ['请求数', '访客数', '页面浏览'], '趋势指标切换项应为请求数/访客数/页面浏览');

        // 3) 响应分布
        const distribution = await page.$eval('[data-response-distribution]', node => node.innerText.replace(/\s+/g, ' '));
        assert.ok(distribution.includes('正常响应') && distribution.includes('客户端错误') && distribution.includes('服务端错误'), '响应分布应包含三类图例');
        assert.ok(distribution.replace(/,/g, '').includes('1241'), '正常响应数应为 1284-37-6=1241');

        // 4) 热门搜索与发布概览
        const hot = await page.$eval('[data-hot-searches]', node => node.innerText);
        assert.ok(hot.includes('沙丘 2') && hot.includes('31 次'), '热门搜索应显示关键词与次数');
        const social = await page.$eval('[data-social-summary]', node => node.innerText.replace(/\s+/g, ' '));
        assert.ok(social.includes('12') && social.includes('3') && social.includes('成功'), '发布概览应显示成功/失败与成功率');

        // 5) 日志 tab 中文列名
        const tabLabels = await page.$$eval('.ant-tabs-tab', nodes => nodes.map(n => n.innerText.replace(/\s+/g, ' ').trim()));
        assert.deepEqual(tabLabels.map(t => t.replace(/\s*\d+$/, '')), ['访问日志', '站内搜索', '资源操作', 'QQ 搜索', '平台发布'], '日志页签名称应明确');
        const headerOf = () => page.$$eval('.ant-tabs-tabpane-active .ant-table-thead th', nodes => nodes.map(n => n.innerText.trim()));
        assert.deepEqual(await headerOf(), ['时间', '方法', '请求路径', '状态码', '耗时', '事件类型'], '访问日志列名应为中文且明确');

        // 6) 状态筛选「仅失败」
        await page.evaluate(() => {
            const option = [...document.querySelectorAll('.ant-segmented-item-label')].find(node => node.textContent.trim() === '仅失败');
            option?.click();
        });
        await new Promise(resolve => setTimeout(resolve, 400));
        const failedRows = await page.$$eval('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row', nodes => nodes.length);
        assert.equal(failedRows, 2, '仅失败应筛出 2 条访问日志（500 与 403）');
        await page.evaluate(() => {
            const option = [...document.querySelectorAll('.ant-segmented-item-label')].find(node => node.textContent.trim() === '全部');
            option?.click();
        });
        await new Promise(resolve => setTimeout(resolve, 300));

        // 7) 资源操作页签：中文列名与失败原因
        await page.evaluate(() => {
            const tab = [...document.querySelectorAll('.ant-tabs-tab')].find(node => node.textContent.includes('资源操作'));
            tab?.click();
        });
        await page.waitForFunction(() => [...document.querySelectorAll('.ant-tabs-tabpane-active .ant-table-thead th')].some(node => node.innerText.trim() === '错误信息'), { timeout: 15000 });
        const resourceHeaders = await headerOf();
        assert.deepEqual(resourceHeaders, ['时间', '影片 ID', '资源 ID', '操作', '网盘', '状态', '错误信息'], '资源操作列名应为中文且明确，实际：' + JSON.stringify(resourceHeaders));
        const operationBody = await page.$eval('.ant-tabs-tabpane-active .ant-table-tbody', node => node.innerText);
        assert.ok(operationBody.includes('成功') && operationBody.includes('失败') && operationBody.includes('分享链接已失效'), '资源操作应显示中文状态与失败原因');

        // 8) 搜索与自动刷新控件
        const searchPlaceholder = await page.$eval('main input.ant-input', node => node.placeholder);
        assert.ok(/过滤/.test(searchPlaceholder), '搜索框应提示可过滤的字段，实际：' + searchPlaceholder);
        assert.ok((await page.$eval('body', node => node.innerText)).includes('每 30 秒自动刷新'), '应提供自动刷新开关说明');
        assert.equal(errors.length, 0, `页面不应有 JS 错误: ${errors.join('; ')}`);

        await page.screenshot({ path: path.join(outDir, 'admin-monitoring-desktop.png'), fullPage: true });
        await page.setViewport({ width: 390, height: 900 });
        await page.reload({ waitUntil: 'networkidle0' });
        await page.waitForSelector('[data-metric-card]', { timeout: 30000 });
        await page.screenshot({ path: path.join(outDir, 'admin-monitoring-mobile.png'), fullPage: true });
        console.log(JSON.stringify({ ok: true, requests: requested, cards, tabLabels }, null, 2));
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error.stack || error.message); process.exit(1); });