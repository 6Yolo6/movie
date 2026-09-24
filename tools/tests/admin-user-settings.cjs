// Browser regression with intercepted API requests only; never creates live users or changes settings.
const assert = require('node:assert/strict');
const puppeteer = require(process.env.PUPPETEER_MODULE_PATH || 'puppeteer-core');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:3008';
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.BROWSER_EXECUTABLE,
    headless: true, args: ['--no-first-run', '--disable-background-networking'] });
  try {
    const page = await browser.newPage();
    const errors = [], creates = [], saves = [];
    let rejectCreate = false;
    page.on('pageerror', error => errors.push(error.message));
    await page.evaluateOnNewDocument(() => localStorage.setItem('auth-storage', JSON.stringify({
      state: { token: 'fixture-session-only', user: { id: 1, username: 'fixture', role: 'ADMIN' } }, version: 0,
    })));
    await page.setRequestInterception(true);
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.origin !== base) return void request.abort();
      if (!url.pathname.startsWith('/api/')) return void request.continue();
      let body = {}, status = 200;
      if (url.pathname === '/api/admin/users') {
        if (request.method() === 'POST') {
          creates.push(JSON.parse(request.postData()));
          status = rejectCreate ? 409 : 201;
          body = rejectCreate ? { message: 'Email already registered' } : { id: 100 + creates.length, ...creates.at(-1), password: undefined, enabled: true };
        } else body = { records: [], total: 0 };
      }
      if (url.pathname === '/api/admin/config') body = [{ id: null,
        configKey: 'resource.search.rate_limit_per_minute', configValue: '5', description: 'fixture' }];
      if (url.pathname === '/api/admin/config/resource.search.rate_limit_per_minute' && request.method() === 'PUT') {
        saves.push(request.postData()); body = { message: 'updated' };
      }
      if (url.pathname === '/api/notifications/unread-count') body = { count: 0 };
      void request.respond({ status, contentType: 'application/json', body: JSON.stringify(body) });
    });
    const clickText = async text => {
      const clicked = await page.evaluate(text => {
        const button = [...document.querySelectorAll('button')].find(el => el.textContent.replace(/\s+/g, '') === text && el.getClientRects().length);
        if (!button) return false; button.click(); return true;
      }, text);
      assert.ok(clicked, `Missing button: ${text}`);
    };
    const waitFor = async predicate => {
      for (let i = 0; i < 100; i++) { if (predicate()) return; await new Promise(r => setTimeout(r, 100)); }
      throw new Error('API capture timeout');
    };
    const fill = async (selector, value) => {
      await page.click(selector, { clickCount: 3 });
      await page.keyboard.press('Backspace');
      await page.type(selector, value);
    };
    await page.goto(`${base}/admin/users`);
    await page.waitForSelector('.ant-table');
    await clickText('新建用户');
    await page.waitForSelector('.ant-modal #username', { visible: true });
    await page.type('#username', 'fixture-user');
    await page.type('#email', 'fixture@example.com');
    await page.type('#password', 'fixture-password-123');
    await page.type('#confirm', 'different-password-123');
    await clickText('创建用户');
    await page.waitForFunction(() => document.body.textContent.includes('两次密码不一致'));
    assert.equal(creates.length, 0);
    await fill('#confirm', 'fixture-password-123');
    rejectCreate = true;
    await clickText('创建用户');
    await waitFor(() => creates.length === 1);
    await page.waitForFunction(() => document.body.textContent.includes('Email already registered'));
    assert.equal(await page.$eval('#username', el => el.value), 'fixture-user');
    rejectCreate = false;
    await clickText('创建用户');
    await waitFor(() => creates.length === 2);
    await page.waitForSelector('.ant-modal', { hidden: true });
    assert.deepEqual(creates[1], { username: 'fixture-user', email: 'fixture@example.com', password: 'fixture-password-123', role: 'USER' });
    console.log('PASS admin create USER, confirm validation, recoverable conflict and no invitation fields');
    await clickText('新建用户');
    await page.waitForSelector('.ant-modal #username', { visible: true });
    assert.equal(await page.$eval('#password', el => el.value), '');
    await page.type('#username', 'fixture-publisher');
    await page.type('#email', 'publisher@example.com');
    await page.type('#password', 'fixture-password-456');
    await page.type('#confirm', 'fixture-password-456');
    await page.click('.ant-modal .ant-select');
    await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    await page.evaluate(() => [...document.querySelectorAll('.ant-select-item-option')]
      .find(el => el.textContent.includes('发布者')).click());
    await clickText('创建用户');
    await waitFor(() => creates.length === 3);
    assert.equal(creates[2].role, 'PUBLISHER');
    await page.waitForSelector('.ant-modal', { hidden: true });
    console.log('PASS admin create PUBLISHER and password cleared between forms');
    await page.goto(`${base}/admin/settings`);
    await page.waitForSelector('.ant-input-number-input');
    assert.equal(await page.$eval('.ant-input-number-input', el => el.value), '5');
    await fill('.ant-input-number-input', '12');
    await page.evaluate(() => {
      const code = [...document.querySelectorAll('code')].find(el => el.textContent === 'resource.search.rate_limit_per_minute');
      const row = code.closest('div.grid');
      const button = row.querySelector('button');
      if (!button || button.disabled) throw new Error('Setting save unavailable');
      button.click();
    });
    await waitFor(() => saves.length === 1);
    assert.equal(saves[0], '12');
    await page.waitForFunction(() => {
      const code = [...document.querySelectorAll('code')].find(el => el.textContent === 'resource.search.rate_limit_per_minute');
      return code.closest('div.grid').querySelector('button').disabled;
    });
    console.log('PASS search frequency shows default and saves numeric text');
    assert.deepEqual(errors, []);
    console.log('PASS no browser runtime errors; all APIs mocked, no production writes');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
