const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.SITE_FOOTER_BASE_URL || 'http://127.0.0.1:18080';
(async () => {
  const browser = await chromium.launch({channel: process.env.BROWSER_CHANNEL || 'msedge', headless:true});
  try {
    for (const outcome of ['COMPLETED','PARTIAL','SKIPPED']) {
      const context = await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
      await context.addInitScript(() => {
        localStorage.setItem('i18nextLng','zh'); localStorage.setItem('theme','light');
        localStorage.setItem('auth-storage',JSON.stringify({state:{token:'test-only-placeholder',user:{id:123,username:'fixture',role:'ADMIN'}},version:0}));
      });
      const page = await context.newPage(); const posts=[];
      await page.route('**/api/**', async route => {
        const request=route.request(); const pathname=new URL(request.url()).pathname;
        if(request.method()==='POST') posts.push(pathname);
        let body={};
        if(pathname==='/api/admin/movies') body={records:[{id:'fixture-series',titleCn:'破产姐妹 第一季',category:'tv',season:1,status:'ACTIVE',resourceStatus:'AVAILABLE',year:2011}],total:1,current:1,size:20};
        else if(pathname==='/api/auth/me') body={id:123,username:'fixture',role:'ADMIN'};
        else if(pathname.endsWith('/seasons/ensure')) body={jobId:'fixture-job',status:'RUNNING'};
        else if(pathname==='/api/admin/gying-source/jobs/fixture-job') body={jobId:'fixture-job',status:'SUCCEEDED',result:{mode:'METADATA_AND_EXISTING_COLLECTION',status:outcome,metadataCreated:outcome==='SKIPPED'?0:5,bound:outcome==='SKIPPED'?0:5,warnings:outcome==='PARTIAL'?['第6季尚未找到可信元数据']:[]}};
        else if(pathname.endsWith('/unread-count')) body=0;
        // Every API is mocked; no login, transfers, metadata writes or publishing reaches production.
        await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
      });
      await page.goto(`${baseURL}/admin/movies`,{waitUntil:'domcontentloaded'});
      const button=page.getByRole('button',{name:'补齐剩余季',exact:true});
      await button.waitFor({state:'visible'}); await button.click();
      const count=outcome==='SKIPPED'?0:5;
      await page.getByText(`新增 ${count} 季元数据，复用合集绑定 ${count} 条资源`,{exact:true}).waitFor();
      if(outcome==='PARTIAL') await page.getByText('第6季尚未找到可信元数据',{exact:true}).waitFor();
      assert.equal(posts.filter(p=>p.endsWith('/seasons/ensure')).length,1);
      assert.ok(posts.every(p=>p.endsWith('/seasons/ensure') || p.includes('telemetry') || p.includes('site-access') || p === '/api/monitoring/page-view'), JSON.stringify(posts));
      console.log(`PASS season completion UI: ${outcome}`);
      await context.close();
    }
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
