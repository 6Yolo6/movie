// Run against an isolated frontend build. Every API request is mocked; no production writes.
// PUPPETEER_MODULE_PATH and BROWSER_EXECUTABLE may point to an existing local installation.
const assert = require('node:assert/strict');
const puppeteer = require(process.env.PUPPETEER_MODULE_PATH || 'puppeteer-core');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:3008';
const fixture = { id: 10, movieId: 'first', movieTitle: '测试影片', name: '测试资源',
  url: 'https://pan.quark.cn/s/fixture', type: 'DISK', provider: 'QUARK', status: 'ACTIVE', auditStatus: 1 };
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.BROWSER_EXECUTABLE,
    headless: true, args: ['--no-first-run', '--disable-background-networking'] });
  try {
    const page = await browser.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push({url:page.url(),message:error.message}));
    let registration, emailRequest, update, searches = 0, polls = 0;
    await page.setRequestInterception(true);
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.origin !== base) return void request.abort();
      if (!url.pathname.startsWith('/api/')) return void request.continue();
      const path = url.pathname;
      let body = {};
      if (path === '/api/auth/registration-policy') body = {
        publicRegistrationEnabled: false, invitationEnabled: true,
        inviteValid: url.searchParams.get('invite') === 'fixture-invite',
        registrationAllowed: url.searchParams.get('invite') === 'fixture-invite',
        emailRequired: true, emailVerificationEnabled: true,
      };
      if (path === '/api/auth/register') registration = JSON.parse(request.postData());
      if (path === '/api/auth/email-code') emailRequest = JSON.parse(request.postData());
      if (path === '/api/resource-search/query') { searches++; body = {jobId: 'fixture-job', status: 'QUEUED'}; }
      if (path === '/api/resource-search/jobs/fixture-job') {
        polls++;
        body = polls < 3 ? {jobId:'fixture-job', status:'RUNNING'} : {
          jobId:'fixture-job', status:'SUCCEEDED', reply:'测试搜索完成',
          links:[{name:'测试分享', url:'https://pan.quark.cn/s/fixture'}],
        };
      }
      if (path === '/api/resources/admin/all') body = {records:[fixture], total:1};
      if (path === '/api/admin/movies') body = {records:[{id:'first',titleCn:'测试影片'}],total:1};
      if (path === '/api/resources/bind-candidates') body = [{id:'second',titleCn:'测试第二季',season:2}];
      if (path === '/api/resources/form-config') body = {quickParams:['1080P']};
      if (path === '/api/resources/10' && request.method() === 'PUT') {
        update = JSON.parse(request.postData()); body = {message:'updated',boundCount:1};
      }
      if (path === '/api/notifications/unread-count') body = {count:0};
      void request.respond({status:200,contentType:'application/json',body:JSON.stringify(body)});
    });
    const waitFor = async predicate => {
      for (let i=0;i<150;i++) { if (predicate()) return; await new Promise(r=>setTimeout(r,100)); }
      throw new Error('Timed out waiting for captured API request');
    };
    const clickText = async text => {
      const clicked = await page.evaluate(text => {
        const button = [...document.querySelectorAll('button')].find(el => el.textContent.replace(/\s+/g, '') === text);
        if (!button) return false; button.click(); return true;
      }, text);
      assert.ok(clicked, `Missing button: ${text}`);
    };
    await page.goto(`${base}/register`);
    await page.waitForSelector('#inviteCode');
    await page.type('#inviteCode', 'fixture-invite');
    await clickText('校验');
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]').disabled);
    await page.type('#username','fixture-user');
    await page.type('#email','fixture@example.com');
    await page.type('#password','fixture-password-123');
    await page.type('#confirm','fixture-password-123');
    await page.type('#emailCode','123456');
    await clickText('发送验证码');
    await waitFor(()=>emailRequest);
    assert.equal(emailRequest.inviteCode,'fixture-invite');
    await page.click('button[type="submit"]');
    await waitFor(()=>registration);
    assert.equal(registration.inviteCode,'fixture-invite');
    assert.equal(registration.emailCode,'123456');
    console.log('PASS invitation and email fields submit actual input values');

    await page.evaluate(() => localStorage.setItem('auth-storage',JSON.stringify({state:{
      token:'fixture-session-only',user:{id:1,username:'fixture',role:'ADMIN'}},version:0})));
    await page.goto(`${base}/resource-search`);
    await page.waitForSelector('input[maxlength="80"]');
    await page.type('input[maxlength="80"]','测试影片');
    await page.keyboard.press('Enter');
    await waitFor(()=>polls>0);
    await page.keyboard.press('Enter');
    await page.reload();
    await page.waitForFunction(() => document.body.textContent.includes('测试搜索完成'));
    assert.equal(searches,1);
    assert.ok(polls>=3);
    console.log('PASS asynchronous polling, reload recovery and duplicate-submit guard');

    await page.goto(`${base}/admin/audit`);
    await page.waitForSelector('button .anticon-edit');
    await page.evaluate(() => document.querySelector('button .anticon-edit').closest('button').click());
    await page.waitForSelector('.ant-modal #bindMovieIds', {visible:true});
    await new Promise(r=>setTimeout(r,300));
    await page.click('.ant-modal #bindMovieIds');
    await page.waitForSelector('.ant-select-item-option[title*="测试第二季"]');
    await page.click('.ant-select-item-option[title*="测试第二季"]');
    await page.click('.ant-modal button[type="submit"]');
    await waitFor(()=>update);
    assert.deepEqual(update.bindMovieIds,['second']);
    assert.equal(update.movieId,'first');
    assert.equal(update.url,fixture.url);
    console.log('PASS editing existing resource submits additional movie binding');
    assert.deepEqual(pageErrors,[]);
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
