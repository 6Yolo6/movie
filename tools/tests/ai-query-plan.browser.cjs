const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const base=process.env.CATALOG_BASE_URL||'http://127.0.0.1:18080';
const build=process.env.CATALOG_BUILD_DIR;
const out=process.env.CATALOG_OUTPUT_DIR||path.join(process.cwd(),'tmp/catalog-browser');
assert(build,'CATALOG_BUILD_DIR must point to an isolated production build');
assert.equal(new URL(base).hostname,'127.0.0.1');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});const results=[];
 try {
  for(const options of [{lang:'zh',theme:'light',width:1440},{lang:'zh',theme:'dark',width:390},{lang:'en',theme:'light',width:390},{lang:'en',theme:'dark',width:1440}]) {
   const context=await browser.newContext({viewport:{width:options.width,height:900},serviceWorkers:'block'});
   await context.addInitScript(o=>{localStorage.setItem('i18nextLng',o.lang);localStorage.setItem('theme',o.theme);localStorage.setItem('auth-storage',JSON.stringify({state:{user:{id:1,username:'fixture',role:'USER'},token:'fixture-only'},version:0}));},options);
   const page=await context.newPage();const errors=[],queries=[],pages=[];let mode='natural',failPage=false;
   page.on('pageerror',e=>errors.push(e.message));
   const natural=p=>({answer:'自然语言片库结果 '+p,jobId:'22222222-2222-4222-8222-222222222222',catalog:{kind:'natural_search',query:'找近几年烧脑科幻电影，不超过两小时',total:21,page:p,pages:2,size:20},evidence:[{tool:'naturalSearchMovies',queriedAt:'2026-10-21T12:00:00Z',movies:Array.from({length:p===1?20:1},(_,i)=>({id:'plot_'+((p-1)*20+i),titleCn:'时间迷宫 '+((p-1)*20+i),year:2024,genres:['科幻'],rating:8.4,resourceCount:i===0?0:2,summary:'真实资料片段：调查员陷入时间循环。',reasons:['主题匹配：烧脑（资料包含“时间循环”）','符合本次已识别的筛选条件']}))}]});
   const recommended={answer:'推荐特征结果',catalog:{kind:'recommendation',personalized:true,total:1},evidence:[{tool:'recommendMovies',unverifiedConditions:['获奖情况'],relevanceChecked:true,queriedAt:'2026-10-21T12:00:00Z',movies:[{id:'recommend_1',titleCn:'星际旅途',genres:['科幻'],runtimeMinutes:95,rating:8.1,ratingSource:'douban',reasons:['与你关注的《远航者》同属科幻']}]}]};
   await page.route('**/*',async route=>{
    const u=new URL(route.request().url());if(u.origin!==base)return route.abort();
    if(u.pathname.startsWith('/api/')) {
     let body={};let status=200;
     if(u.pathname==='/api/ai/status')body={available:false,catalogAvailable:true};
     else if(u.pathname==='/api/ai/query'){queries.push(route.request().postDataJSON());body=mode==='failed'?{jobId:'33333333-3333-4333-8333-333333333333',status:'FAILED',message:'查询失败',result:{answer:'',evidence:[],error:{code:'CONTEXT_TOO_LONG'},trace:[{kind:'planning',name:'query-plan',status:'error'}]}}:{jobId:'11111111-1111-4111-8111-111111111111',status:'SUCCEEDED',result:mode==='natural'?natural(1):recommended};}
     else if(u.pathname==='/api/ai/natural-search'){pages.push(u.searchParams.get('page'));if(failPage){status=503;body={message:'fixture 分页失败'};}else body=natural(Number(u.searchParams.get('page')));assert.equal(u.searchParams.get('remember'),'true');}
     else body={records:[],total:0,unreadCount:0};
     return route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
    }
    if(u.pathname==='/ai-assistant')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(build,'server/app/ai-assistant.html'))});
    if(u.pathname.startsWith('/_next/static/')) {const file=path.resolve(build,'static',u.pathname.slice('/_next/static/'.length));assert(file.startsWith(path.resolve(build,'static')+path.sep));return route.fulfill({path:file});}
    const file=path.resolve(build,'../public','.'+u.pathname);if(file.startsWith(path.resolve(build,'../public')+path.sep)&&fs.existsSync(file)&&fs.statSync(file).isFile())return route.fulfill({path:file});
    return route.abort();
   });
   await page.goto(base+'/ai-assistant');const input=page.locator('textarea[aria-label]');await input.waitFor();
   await input.fill('找近几年烧脑科幻电影，不超过两小时');await input.press('Enter');await page.getByText('自然语言片库结果 1',{exact:true}).waitFor();
   assert.equal(await page.locator('main a[href^="/movie/plot_"]').count(),20);assert.equal(queries.length,1);
   assert.equal(await page.getByText('真实资料片段：调查员陷入时间循环。',{exact:true}).count(),20);
   const first=page.locator('main a[href^="/movie/"]').first();assert.equal(await first.getAttribute('target'),'_blank');assert((await first.getAttribute('rel')).includes('noopener'));
   const resourceLink=page.locator('main a[href^="/resource-search?"]');assert.equal(await resourceLink.count(),1);
   const resourceUrl=new URL(await resourceLink.getAttribute('href'),base);assert.equal(resourceUrl.searchParams.get('q'),'时间迷宫 0');assert.equal(resourceUrl.searchParams.get('auto'),'1');
   assert.equal(await resourceLink.getAttribute('target'),'_blank');
   failPage=true;await page.locator('.ant-pagination-next button').click();await page.getByText('fixture 分页失败',{exact:true}).waitFor();assert.equal(await page.locator('main a[href^="/movie/plot_"]').count(),20);
   failPage=false;await page.locator('.ant-pagination-next button').click();await page.getByText('自然语言片库结果 2',{exact:true}).waitFor();assert.equal(await page.locator('main a[href^="/movie/plot_"]').count(),1);assert.deepEqual(pages,['2','2']);
   await input.fill('第2部是谁主演的');await input.press('Enter');await page.getByText('自然语言片库结果 1',{exact:true}).last().waitFor();assert.equal(queries[1].previousJobId,'22222222-2222-4222-8222-222222222222');
   const newChat=options.lang==='zh'?'新对话':'New chat';await page.getByRole('button',{name:newChat,exact:true}).click();mode='recommend';
   await page.getByRole('button',{name:options.lang==='zh'?'猜你喜欢':'For you',exact:true}).click();await page.getByText('推荐特征结果',{exact:true}).waitFor();assert.equal(queries[2].history.length,0);assert.equal(queries[2].previousJobId,undefined);await page.getByText('获奖情况',{exact:false}).waitFor();mode='failed';await input.fill('验证失败记录');await input.press('Enter');await page.getByText('查询失败 · CONTEXT_TOO_LONG',{exact:false}).last().waitFor();assert.equal(await page.getByText('query-plan · error',{exact:false}).count(),1);mode='recommend';await input.fill('继续');await input.press('Enter');await page.getByText('推荐特征结果',{exact:true}).last().waitFor();assert.equal(queries.at(-1).previousJobId,'11111111-1111-4111-8111-111111111111');
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);assert.deepEqual(errors,[]);
   await page.screenshot({path:path.join(out,`catalog-${options.lang}-${options.theme}-${options.width}.png`),fullPage:true});
   results.push({...options,status:'passed',naturalSearch:true,pagination:true,failurePreservesResults:true,newTab:true,recommendation:true,modelOfflineCatalogWorks:true,resourceSearchFromChat:true});await context.close();
  }
  const context=await browser.newContext();const page=await context.newPage();await page.route('**/*',async route=>{const u=new URL(route.request().url());if(u.origin!==base)return route.abort();if(u.pathname==='/ai-assistant')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(build,'server/app/ai-assistant.html'))});if(u.pathname.startsWith('/_next/static/'))return route.fulfill({path:path.join(build,'static',u.pathname.slice('/_next/static/'.length))});return route.abort();});
  await page.goto(base+'/ai-assistant');await page.locator('a[href="/login?redirect=%2Fai-assistant"]').waitFor();assert.equal(await page.locator('textarea[aria-label]').count(),0);results.push({anonymous:'passed'});await context.close();
 } finally {await browser.close();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(results,null,2));}
 console.log(JSON.stringify(results));
})().catch(e=>{console.error(e);process.exitCode=1;});
