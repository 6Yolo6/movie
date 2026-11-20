// Real rendered search replies with isolated build assets and mocked APIs only.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const build = process.env.RESOURCE_SEARCH_BUILD_DIR;
assert(build, 'RESOURCE_SEARCH_BUILD_DIR must be an isolated production build');
const base = 'http://127.0.0.1:18087';
const output = process.env.RESOURCE_SEARCH_OUTPUT_DIR;
const magnet = 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=fixture';
const torrent = 'https://pan.xunlei.com/s/torrent-fixture';
const long = 'https://pan.quark.cn/s/long-fixture?note=' + 'x'.repeat(1100);
const links = [
    {url:'https://pan.quark.cn/s/video-fixture',name:'正片',type:'DISK'},
    {url:magnet,name:'原始磁力',type:'MAGNET'},
    {url:torrent,name:'种子归档',type:'TORRENT'},
    {url:long,name:'长地址正片',type:'DISK'},
    {url:'javascript:alert(1)',name:'unsafe',type:'DISK'},
];
(async () => {
    const browser = await chromium.launch({channel:'msedge',headless:true});
    try {
        for (const scenario of [{lang:'zh',theme:'light',width:1440},{lang:'zh',theme:'dark',width:390},{lang:'en',theme:'light',width:390}]) {
            const context = await browser.newContext({viewport:{width:scenario.width,height:900},serviceWorkers:'block'});
            await context.addInitScript(s => {
                localStorage.setItem('i18nextLng',s.lang);localStorage.setItem('theme',s.theme);
                localStorage.setItem('auth-storage',JSON.stringify({state:{token:'fixture-only',user:{id:1,username:'fixture',role:'USER'}},version:0}));
            },scenario);
            const page = await context.newPage(), errors=[],requests=[];
            page.on('pageerror',e=>errors.push(e.message));
            await page.route('**/*',async route => {
                const req=route.request(),url=new URL(req.url());
                if(url.origin!==base)return route.abort();
                if(url.pathname.startsWith('/api/')) {
                    let body={};
                    if(url.pathname==='/api/auth/me')body={id:1,username:'fixture',role:'USER'};
                    if(url.pathname==='/api/resource-search/query') {requests.push(req.postDataJSON());body={jobId:'fixture-mixed',status:'QUEUED'};}
                    if(url.pathname.startsWith('/api/resource-search/jobs/'))body={jobId:'fixture-mixed',status:'SUCCEEDED',reply:'资源库已有资源（优先展示，无需重新搜索或转存）：\n网盘资源：\n- 正片\n磁力资源：\n- 原始磁力\n种子资源（仅种子/磁力文件，不含视频）：\n- 种子归档\n需要其他版本或网盘，可点击“搜索其他资源”继续。',links};
                    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
                }
                if(url.pathname==='/resource-search')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(build,'server/app/resource-search.html'))});
                let root,relative;
                if(url.pathname.startsWith('/_next/static/')) {root=path.resolve(build,'static');relative=url.pathname.slice('/_next/static/'.length);}
                else {root=path.resolve(build,'../public');relative='.'+url.pathname;}
                const file=path.resolve(root,relative);if(file===root)return route.abort();assert(file.startsWith(root+path.sep));
                if(fs.existsSync(file)&&fs.statSync(file).isFile())return route.fulfill({path:file});
                return route.abort();
            });
            await page.goto(base+'/resource-search');
            const input=page.locator('input[aria-label="影片名或回复"]');await input.fill('分类资源影片');await input.press('Enter');
            const panel=page.getByTestId('search-conversation');
            const disk=page.getByTestId('resource-links-disk'),magnets=page.getByTestId('resource-links-magnet'),torrents=page.getByTestId('resource-links-torrent');
            await magnets.waitFor();
            assert.equal(await disk.locator('[data-testid="resource-qr-card"]').count(),2);
            assert.equal(await magnets.locator('[data-testid="resource-qr-card"]').count(),1);
            assert.equal(await torrents.locator('[data-testid="resource-qr-card"]').count(),1);
            assert.equal(await panel.locator('a[href^="javascript:"]').count(),0);
            assert.equal(await disk.locator('canvas').count(),1,'long URL retains clickable links without a QR');
            for(const [group,url] of [[magnets,magnet],[torrents,torrent],[disk,long]]) {
                const matching=group.locator('a').filter({hasText:url});assert.equal(await matching.count(),1);
                assert.equal(await matching.getAttribute('href'),url);assert.equal(await matching.getAttribute('target'),'_blank');
                assert((await matching.getAttribute('rel')).includes('noopener'));
            }
            assert((await magnets.innerText()).includes('支持磁力下载'));
            assert(!(await magnets.innerText()).includes('夸克 App'));
            assert((await torrents.innerText()).includes('不含视频'));
            assert.equal(requests.length,1);
            await page.reload();await magnets.waitFor();assert.equal(requests.length,1,'reload preserves history without repeating search');
            assert.equal(await magnets.locator('a').first().getAttribute('href'),magnet);
            assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
            assert.deepEqual(errors,[]);
            if(output){fs.mkdirSync(output,{recursive:true});await panel.evaluate(e=>e.scrollTop=e.scrollHeight);await page.screenshot({path:path.join(output,`links-${scenario.lang}-${scenario.width}-${scenario.theme}.png`),fullPage:true});}
            console.log(`PASS ${scenario.lang}-${scenario.width}-${scenario.theme}: QR, links, cloud/magnet/torrent groups, long URL, safe history`);
            await context.close();
        }
    } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
