const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const baseURL = process.env.RESOURCE_BINDINGS_BASE_URL || 'http://127.0.0.1:18080';
const translations = Object.fromEntries(['zh','en'].map(lang => [lang, JSON.parse(fs.readFileSync(path.resolve(__dirname,`../../frontend/public/locales/${lang}/common.json`),'utf8'))]));

async function main() {
 const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });
 try {
  for (const scenario of [
   {name:'nine-existing-zh', lang:'zh'},
   {name:'load-failure-retry-en', lang:'en', failLoad:true},
   {name:'stale-save-zh', lang:'zh', conflict:true},
   {name:'deselect-and-append-en', lang:'en', changeSelection:true},
  ]) {
   const {lang}=scenario,t=translations[lang];
   const context = await browser.newContext({ viewport:{width:1440,height:1100},locale:lang==='zh'?'zh-CN':'en-US' });
   await context.addInitScript(lang=>{
    localStorage.setItem('i18nextLng',lang);localStorage.setItem('theme','light');
    localStorage.setItem('auth-storage',JSON.stringify({state:{token:'test-only-placeholder',user:{id:1,username:'fixture',role:'ADMIN'}},version:0}));
   },lang);
   const page=await context.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
   const movies=Array.from({length:10},(_,index)=>({id:`series-s${index+1}`,titleCn:`测试剧 第${index+1}季`,season:index+1,category:'tv',year:2010+index,status:'ACTIVE'}));
   const resource={id:10,movieId:movies[0].id,movieTitle:movies[0].titleCn,name:'测试剧 第1-9季合集 1080p',type:'DISK',provider:'QUARK',url:'https://pan.quark.cn/s/old-fixture',code:'old1',linkStatus:'INVALID',status:'ACTIVE',auditStatus:1};
   let bindingRequests=0,saved=null;const writes=[];
   await page.route('**/api/**',async route=>{
    const request=route.request(),url=new URL(request.url()),pathname=url.pathname;
    if(['POST','PUT','DELETE','PATCH'].includes(request.method()))writes.push(`${request.method()} ${pathname}`);
    let status=200,body={};
    if(pathname==='/api/resources/admin/all')body={records:[resource],total:1};
    else if(pathname==='/api/admin/movies')body={records:movies,total:movies.length};
    else if(pathname==='/api/resources/form-config')body={quickParams:[]};
    else if(pathname==='/api/resources/bind-candidates')body=url.searchParams.get('keyword')?[movies[9]]:movies.slice(1);
    else if(pathname==='/api/resources/10/bindings') {
     bindingRequests++;
     await new Promise(resolve=>setTimeout(resolve,350));
     if(scenario.failLoad && bindingRequests===1) {status=503;body={message:'fixture unavailable'};}
     else body={resource:{...resource,quality:'1080P'},bindings:movies.slice(1,9),bindMovieIds:movies.slice(1,9).map(m=>m.id),bindingVersion:'a'.repeat(64)};
    } else if(pathname==='/api/resources/10' && request.method()==='PUT') {
     saved=request.postDataJSON();
     if(scenario.conflict){status=409;body='绑定资源已被修改，请关闭编辑窗口后重新打开';}
     else body={message:'Resource updated',updatedBindings:scenario.changeSelection?7:8,boundCount:scenario.changeSelection?1:0};
    } else if(pathname==='/api/auth/me')body={id:1,username:'fixture',role:'ADMIN'};
    else if(pathname.endsWith('/unread-count'))body=0;
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
   });
   await page.goto(`${baseURL}/admin/audit`,{waitUntil:'domcontentloaded'});
   await page.getByRole('button',{name:t.editResource,exact:true}).first().click();
   const modal=page.getByRole('dialog');await modal.waitFor({state:'visible'});
   const save=modal.locator('button[type="submit"]');
   if(scenario.failLoad){
    await modal.getByText(t.resourceBindingsLoadFailed,{exact:true}).waitFor();
    assert.equal(await save.isDisabled(),true,'Cannot save after bindings failed to load');
    await modal.getByRole('button',{name:/retry/i}).click();
   }
   await page.waitForFunction(()=>document.querySelector('#bindingVersion')?.value.length===64);
   assert.equal(await save.isEnabled(),true);
   const select=modal.locator('#bindMovieIds').locator('xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," ant-select ")]');
   for(const movie of movies.slice(1,9))assert.ok((await select.innerText()).includes(movie.titleCn),'Actual existing binding preselected');
   assert.ok(!(await select.innerText()).includes(movies[9].titleCn),'Unbound same-series season must not be preselected');
   assert.equal(await modal.locator('#quality').inputValue(),'1080P','Fresh resource values from binding read');
   if(scenario.changeSelection){
    // Searching candidates must not reset preselected IDs. Toggle one existing and one genuinely new movie.
    await modal.locator('#bindMovieIds').click();
    await page.locator('.ant-select-item-option').filter({hasText:movies[1].titleCn}).click();
    await modal.locator('#bindMovieIds').fill('第10季');
    await page.locator('.ant-select-item-option').filter({hasText:movies[9].titleCn}).click();
    await page.keyboard.press('Escape');
   }
   await modal.locator('#url').fill('https://pan.quark.cn/s/new-fixture');
   await modal.locator('#code').fill('new1');
   await Promise.all([page.waitForResponse(response=>response.url().endsWith('/api/resources/10') && response.request().method()==='PUT'), save.click()]);
   // The route handler records the payload before responding.
   assert.ok(saved);
   const expected=scenario.changeSelection?[...movies.slice(2,9),movies[9]]:movies.slice(1,9);
   assert.deepEqual([...saved.bindMovieIds].sort(),expected.map(m=>m.id).sort());
   assert.equal(saved.bindingVersion,'a'.repeat(64));assert.equal(saved.code,'new1');
   assert.ok(saved.url.includes('/new-fixture'));
   assert.equal(writes.filter(write=>write==='PUT /api/resources/10').length,1);
   assert.ok(writes.every(write=>write==='PUT /api/resources/10' || write==='POST /api/monitoring/page-view'),JSON.stringify(writes));
   if(scenario.conflict)assert.equal(await modal.isVisible(),true,'Conflict preserves the editor');
   else await modal.waitFor({state:'hidden'});
   assert.deepEqual(errors,[],JSON.stringify(errors));
   console.log(`PASS resource binding editor: ${scenario.name}`);
   await context.close();
  }
 } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
