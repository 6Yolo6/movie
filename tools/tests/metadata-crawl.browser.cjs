const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseURL = process.env.METADATA_CRAWL_BASE_URL || 'http://127.0.0.1:18080';
const build = process.env.METADATA_CRAWL_BUILD_DIR;
assert(build, 'Use an isolated production build via METADATA_CRAWL_BUILD_DIR');
assert.equal(new URL(baseURL).hostname, '127.0.0.1');
async function main() {
 const browser = await chromium.launch({channel: process.env.BROWSER_CHANNEL || 'msedge', headless:true});
 try {
  for(const scenario of [{lang:'zh',width:1440,theme:'light'},{lang:'en',width:1440,theme:'dark'},{lang:'zh',width:390,theme:'dark'},{lang:'en',width:390,theme:'light',legacy:true}]) {
   const t=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../frontend/public/locales/'+scenario.lang+'/common.json'),'utf8'));
   const context=await browser.newContext({viewport:{width:scenario.width,height:1000}});
   await context.addInitScript(options=>{
    localStorage.setItem('i18nextLng',options.lang);
    localStorage.setItem('theme',options.theme);
    localStorage.setItem('auth-storage',JSON.stringify({state:{token:'test-only-placeholder',user:{id:1,username:'fixture',role:'ADMIN'}},version:0}));
   },scenario);
   const page=await context.newPage(),errors=[],saved=[];
   page.on('pageerror',e=>errors.push(e.message));
   let config={enabled:true,autoApprove:true,tmdbConfigured:true,tmdbAutoSyncEnabled:true,tmdbAutoSyncSources:'POPULAR_MOVIE,POPULAR_TV',tmdbAutoSyncPage:3,tmdbAutoSyncMaxItems:15,tmdbAutoSyncIntervalHours:2,tmdbAutoDiscoveryEnabled:false,tmdbDiscoveryMaxResults:10,tmdbDiscoveryCooldownHours:24,gyingDiscoveryEnabled:true,gyingAutoSyncEnabled:true,gyingAutoSyncSources:'HITS_MOVIE,HITS_TV',gyingAutoSyncPage:3,gyingAutoSyncMaxItems:15,gyingAutoSyncIntervalHours:2,workerEnabled:true,workerTaskLimit:5,workerQuarkLimit:5,workerPublishLimit:20,discoveredRetryEnabled:false,discoveredRetryLimit:20,discoveredRetryDelayMs:5000,discoveredRetryCron:'0 30 8 * * *'};
   if(!scenario.legacy)Object.assign(config,{tmdbAutoSyncEndPage:500,gyingAutoSyncEndPage:300,tmdbAutoSyncRanges:{POPULAR_MOVIE:{startPage:100,endPage:500},POPULAR_TV:{startPage:100,endPage:400}},gyingAutoSyncRanges:{HITS_MOVIE:{startPage:50,endPage:300},HITS_TV:{startPage:40,endPage:200}},weeklyTransferSchedules:['mv','tv','ac'].map(typeCode=>({typeCode,enabled:true,intervalDays:7,maxItems:5,nextRunAt:'2026-10-11T20:00:00',lastStatus:'NOT_STARTED'})),metadataCrawlProgress:[
    {provider:'TMDB',source:'POPULAR_MOVIE',startPage:100,endPage:500,nextPage:150,nextItem:16,status:'SUCCEEDED'},
    {provider:'TMDB',source:'POPULAR_TV',startPage:100,endPage:400,nextPage:121,nextItem:8,status:'FAILED'},
    {provider:'GYING',source:'HITS_MOVIE',startPage:50,endPage:300,nextPage:80,nextItem:1,status:'RETRY'},
    {provider:'GYING',source:'HITS_TV',startPage:40,endPage:200,nextPage:60,nextItem:1,status:'SUCCEEDED'}]});
   // All API requests are mocked; no production API authentication or writes.
   await page.route('**/*',async route=>{
    const url=new URL(route.request().url());
    if(url.origin!==baseURL)return route.abort();
    if(url.pathname.startsWith('/api/'))return route.fallback();
    if(url.pathname==='/admin/resource-hub')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(build,'server/app/admin/resource-hub.html'))});
    if(url.pathname.startsWith('/_next/static/')) {
     const file=path.resolve(build,'static',url.pathname.slice('/_next/static/'.length));
     assert(file.startsWith(path.resolve(build,'static')+path.sep));return route.fulfill({path:file});
    }
    const file=path.resolve(build,'../public','.'+url.pathname);
    if(file.startsWith(path.resolve(build,'../public')+path.sep)&&fs.existsSync(file)&&fs.statSync(file).isFile())return route.fulfill({path:file});
    return route.abort();
   });
   await page.route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url());
    let body={records:[],total:0,current:1,size:20};
    if(url.pathname==='/api/admin/resource-hub/overview')body={enabled:true,autoApprove:true,tmdbConfigured:true,config,worker:{enabled:true,running:false,fixedDelayMs:60000,taskLimit:5,quarkLimit:5,publishLimit:20},taskStatusCounts:{},collectionStats:{}};
    else if(url.pathname==='/api/admin/resource-hub/config'&&req.method()==='PUT'){saved.push(req.postDataJSON());config={...config,...saved.at(-1)};body=config;}
    else if(url.pathname==='/api/auth/me')body={id:1,username:'fixture',role:'ADMIN'};
    else if(url.pathname.endsWith('/unread-count'))body=0;
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
   });
   await page.goto(baseURL+'/admin/resource-hub',{waitUntil:'domcontentloaded'});
   const start=page.locator('#tmdbAutoSyncRanges_POPULAR_MOVIE_startPage'),end=page.locator('#tmdbAutoSyncRanges_POPULAR_MOVIE_endPage');
   await end.waitFor();assert.equal(await end.inputValue(),scenario.legacy?'3':'500');
   assert.equal(await page.locator('#gyingAutoSyncRanges_HITS_MOVIE_endPage').inputValue(),scenario.legacy?'3':'300');
   const weekly=page.getByTestId('weekly-transfer-settings');await weekly.waitFor();
   assert.equal(await weekly.locator('[role=switch]').count(),3);
   assert.equal(await page.locator('#tmdbAutoDiscoveryEnabled, #gyingDiscoveryEnabled, #discoveredRetryEnabled').count(),0);
   for(let i=0;i<3;i++){
    assert.equal(await page.locator('#weeklyTransferSchedules_'+i+'_intervalDays').inputValue(),'7');
    assert.equal(await page.locator('#weeklyTransferSchedules_'+i+'_maxItems').inputValue(),'5');
   }
   await weekly.locator('[role=switch]').nth(1).click();
   await page.locator('#weeklyTransferSchedules_0_intervalDays').fill('14');
   await page.locator('#weeklyTransferSchedules_1_intervalDays').fill('3');
   await page.locator('#weeklyTransferSchedules_2_maxItems').fill('2');
   const progress=page.getByTestId('metadata-crawl-progress');await progress.scrollIntoViewIfNeeded();
   if(!scenario.legacy){
    assert.ok((await progress.innerText()).includes(t.resourceHubCrawlPosition.replace('{{page}}','150').replace('{{item}}','16')));
    assert.ok((await progress.innerText()).includes(t.resourceHubCrawlStatus.RETRY));
    assert.ok((await progress.innerText()).includes(t.resourceHubGyingSource.HITS_MOVIE));
   }
   const days=page.locator('#weeklyTransferSchedules_0_intervalDays');
   await days.fill('');
   await page.locator('form').filter({has:start}).locator('button[type=submit]').click();
   await page.locator('.ant-form-item').filter({has:days}).locator('.ant-form-item-explain-error').first().waitFor();
   assert.equal(saved.length,0,'Missing weekly interval must not be sent');
   await days.fill('14');
   await start.fill('6');await end.fill('5');
   await page.locator('form').filter({has:start}).locator('button[type=submit]').click();
   await page.getByText(t.resourceHubCrawlRangeInvalid,{exact:true}).first().waitFor();
   assert.equal(saved.length,0,'Invalid range must not be sent');
   await start.fill('1');await end.fill('12');
   await page.locator('#gyingAutoSyncRanges_HITS_MOVIE_startPage').fill('2');await page.locator('#gyingAutoSyncRanges_HITS_MOVIE_endPage').fill('20');
   await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/admin/resource-hub/config')&&r.request().method()==='PUT'),page.locator('form').filter({has:start}).locator('button[type=submit]').click()]);
   assert.equal(saved.length,1);assert.deepEqual(saved[0].tmdbAutoSyncRanges.POPULAR_MOVIE,{startPage:1,endPage:12});
   assert.deepEqual(saved[0].tmdbAutoSyncRanges.POPULAR_TV,scenario.legacy?{startPage:3,endPage:3}:{startPage:100,endPage:400});
   assert.equal(saved[0].tmdbAutoSyncPage,undefined);assert.equal(saved[0].tmdbAutoSyncEndPage,undefined);
   assert.deepEqual(saved[0].gyingAutoSyncRanges.HITS_MOVIE,{startPage:2,endPage:20});
   assert.deepEqual(saved[0].gyingAutoSyncRanges.HITS_TV,scenario.legacy?{startPage:3,endPage:3}:{startPage:40,endPage:200});
   assert.equal(saved[0].gyingAutoSyncPage,undefined);assert.equal(saved[0].gyingAutoSyncEndPage,undefined);
   assert.deepEqual(saved[0].weeklyTransferSchedules.map(s=>s.intervalDays),[14,3,7]);
   assert.deepEqual(saved[0].weeklyTransferSchedules.map(s=>s.maxItems),[5,5,2]);
   assert.deepEqual(saved[0].weeklyTransferSchedules.map(s=>s.enabled),scenario.legacy?[false,true,false]:[true,false,true]);
   await page.reload({waitUntil:'domcontentloaded'});await end.waitFor();assert.equal(await end.inputValue(),'12');
   assert.equal(await page.locator('#weeklyTransferSchedules_0_intervalDays').inputValue(),'14');
   assert.equal(await page.locator('#weeklyTransferSchedules_1_intervalDays').inputValue(),'3');
   assert.equal(await page.locator('#weeklyTransferSchedules_2_maxItems').inputValue(),'2');
   assert.equal(await weekly.locator('[role=switch]').nth(1).getAttribute('aria-checked'),scenario.legacy?'true':'false');
   await progress.scrollIntoViewIfNeeded();
   const overflow=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,elements:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth+1&&e.getBoundingClientRect().width>0).sort((a,b)=>a.getBoundingClientRect().width-b.getBoundingClientRect().width).slice(0,12).map(e=>({tag:e.tagName,class:e.className,text:e.textContent.slice(0,100),right:e.getBoundingClientRect().right,parent:e.parentElement?.className}))}));
   if(overflow.scroll>overflow.width+1)await page.screenshot({path:path.resolve('tmp/metadata-crawl-validation/overflow.png'),fullPage:true});
   assert.ok(overflow.scroll<=overflow.width+1,'No document-wide horizontal overflow: '+JSON.stringify(overflow));
   if(process.env.METADATA_CRAWL_OUTPUT_DIR){fs.mkdirSync(process.env.METADATA_CRAWL_OUTPUT_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.METADATA_CRAWL_OUTPUT_DIR,`${scenario.lang}-${scenario.width}-${scenario.theme}${scenario.legacy?'-legacy':''}.png`),fullPage:true});}
   if(process.env.METADATA_CRAWL_SCREENSHOT&&scenario.lang==='zh'&&scenario.width===1440)await page.screenshot({path:process.env.METADATA_CRAWL_SCREENSHOT});
   assert.deepEqual(errors,[]);console.log('PASS '+scenario.lang+'-'+scenario.width+(scenario.legacy?'-legacy':''));
   await context.close();
  }
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
