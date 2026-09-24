// Browser regression against an isolated frontend or deployed assets. All APIs are mocked.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require(process.env.PUPPETEER_MODULE_PATH || 'puppeteer-core');
const base = process.env.WEB_TEST_BASE_URL || 'http://127.0.0.1:3008';
const movieReply = '请选择要搜索的影片：\n1. 电影 测试影片 (2024) [GYING]\n2. 剧集 测试影片 第二季 (2025)\n\n直接回复序号即可，例如：1';
const resources = page => `片名：测试影片 第二季 (2025)\n类型：剧情 / 科幻\n地区：中国大陆\n评分：8.6\n简介：一次跨越时间的旅程，从一条线索开始。\n\n请选择资源（回复序号后再返回对应资源）：\n1. 测试影片 第二季 ${page===1?'4K HDR 国语中字':'1080P 中英双字幕 完整版'} [夸克]\n2. 测试影片 第二季 1080P 全集 [迅雷]\n\n还可以继续选择其他资源：回复当前页序号 1-2；当前第 ${page}/2 页，可回复“下一页”或“上一页”翻页`;
(async () => {
    const browser = await puppeteer.launch({executablePath:process.env.BROWSER_EXECUTABLE,headless:true});
    try {
        const page = await browser.newPage(); await page.setViewport({width:1280,height:1000});
        const errors=[]; page.on('pageerror', e=>errors.push(e.message));
        await page.evaluateOnNewDocument(() => localStorage.setItem('auth-storage',JSON.stringify({state:{token:'fixture-only',user:{id:1,username:'fixture',role:'USER'}},version:0})));
        const requests=[]; const jobs=new Map(); let releaseFirst=false;
        await page.setRequestInterception(true);
        page.on('request', request => {
            const url=new URL(request.url());
            if(url.origin!==base)return void request.abort();
            if(!url.pathname.startsWith('/api/'))return void request.continue();
            const respond=(body,status=200)=>void request.respond({status,contentType:'application/json',body:JSON.stringify(body)});
            if(url.pathname==='/api/resource-search/query'){
                const command=JSON.parse(request.postData()).keyword;requests.push(command);
                if(command==='失败保留')return respond({message:'暂时繁忙，请稍后再试'},503);
                const id=`job-${requests.length}`;
                const reply=command==='库内影片'?'片名：库内影片\n资源库已有资源（优先展示，无需重新搜索或转存）：\n- 库内影片 4K [夸克]\nhttps://pan.quark.cn/s/library-fixture\n提取码：1234\n需要其他版本或网盘，可点击“搜索其他资源”继续。':requests.length===1?movieReply:command==='下一页'?resources(2):command==='1'?
                    '片名：测试影片 第二季\n已选择资源：1080P 中英双字幕 完整版\nhttps://pan.quark.cn/s/fixture\n\n还可以继续选择其他资源：回复当前页序号 1-2':resources(1);
                jobs.set(id,{jobId:id,status:'SUCCEEDED',reply,links:command==='库内影片'?[{name:'库内影片 4K',url:'https://pan.quark.cn/s/library-fixture'}]:command==='1'?[{name:'资源链接 1',url:'https://pan.quark.cn/s/fixture'}]:[]});
                return respond({jobId:id,status:'QUEUED'},202);
            }
            if(url.pathname.startsWith('/api/resource-search/jobs/')){
                const id=url.pathname.split('/').pop();
                if(id==='job-1'&&!releaseFirst)return respond({jobId:id,status:'RUNNING'});
                return respond(jobs.get(id));
            }
            return respond(url.pathname.includes('unread-count')?{count:0}:{});
        });
        const input='input[maxlength="80"]';
        const wait=async predicate=>{for(let i=0;i<200;i++){if(predicate())return;await new Promise(r=>setTimeout(r,50));}throw Error('Timed out');};
        const clickChoice=async command=>{
            const selector=`button[data-command="${command}"]:not([disabled])`;
            await page.waitForSelector(selector);await page.click(selector);
        };
        await page.goto(base+'/resource-search');await page.waitForSelector(input);
        await page.type(input,'测试电影');
        await page.evaluate(()=>document.querySelector('input[maxlength="80"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:229,isComposing:true,bubbles:true})));
        assert.equal(requests.length,0,'IME confirmation must not submit');
        await page.keyboard.press('Enter');await wait(()=>requests.length===1);
        await page.waitForFunction(()=>document.querySelector('input[maxlength="80"]').value==='');
        await page.type(input,'下一部想找');await page.keyboard.press('Enter');
        assert.equal(requests.length,1,'busy Enter must not duplicate a job');
        releaseFirst=true;
        await page.waitForSelector('button[data-command="2"]:not([disabled])');
        assert.equal(await page.$eval(input,el=>el.value),'下一部想找','in-flight draft is preserved');
        console.log('PASS auto-clear, IME, next draft and duplicate-submit guard');
        await clickChoice('2');await page.waitForSelector('button[data-command="下一页"]:not([disabled])');
        assert.deepEqual(requests,['测试电影','2']);
        assert.ok(await page.$$eval('[data-testid="search-turn"]:first-child button[data-command]',buttons=>buttons.every(b=>b.disabled)));
        await clickChoice('下一页');await wait(()=>requests.length===3);
        await page.waitForFunction(()=>[...document.querySelectorAll('button[data-command="1"]:not([disabled])')].some(x=>x.textContent.includes('中英双字幕')));
        await clickChoice('1');await page.waitForSelector('button[data-command="资源"]:not([disabled])');
        assert.deepEqual(requests,['测试电影','2','下一页','1']);
        await page.waitForSelector('[data-testid="resource-qr-card"] canvas');
        assert.ok(await page.evaluate(() => {
            const panel = document.querySelector('[data-testid="search-conversation"]');
            return !panel.innerText.includes('https://pan.quark.cn') && !panel.innerText.includes('复制链接')
                && !panel.innerText.includes('打开链接') && !panel.querySelector('a[href*="pan.quark.cn"]');
        }));
        console.log('PASS QR rendered automatically, no plaintext resource URLs/copy/open actions');

        await clickChoice('资源');await page.waitForSelector('button[data-command="下一页"]:not([disabled])');
        assert.equal(requests[4],'资源');assert.equal(await page.$$eval('[data-testid="search-turn"]',x=>x.length),5);
        console.log('PASS clickable movie/resource candidates, pagination and safe continuation');
        if(process.env.WEB_TEST_SCREENSHOT_DIR){fs.mkdirSync(process.env.WEB_TEST_SCREENSHOT_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.WEB_TEST_SCREENSHOT_DIR,'resource-search-desktop.png'),fullPage:true});}
        await page.setViewport({width:390,height:844});
        await page.evaluate(()=>{const panel=document.querySelector('[data-testid="search-conversation"]');panel.scrollTop=panel.scrollHeight;});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'mobile layout must not overflow');
        if(process.env.WEB_TEST_SCREENSHOT_DIR)await page.screenshot({path:path.join(process.env.WEB_TEST_SCREENSHOT_DIR,'resource-search-mobile.png'),fullPage:true});
        console.log('PASS mobile layout without horizontal overflow');
        await page.click(input,{clickCount:3});await page.keyboard.press('Backspace');await page.type(input,'失败保留');await page.keyboard.press('Enter');
        await page.waitForFunction(()=>document.body.textContent.includes('暂时繁忙，请稍后再试'));
        assert.equal(await page.$eval(input,el=>el.value),'失败保留');
        assert.equal(await page.$$eval('[data-testid="search-turn"]',x=>x.length),5);
        await page.reload();await page.waitForSelector('[data-testid="search-turn"]');
        assert.equal(await page.$$eval('[data-testid="search-turn"]',x=>x.length),5);
        assert.ok(await page.$$eval('button[data-command]',buttons=>buttons.length>0&&buttons.every(b=>b.disabled)));
        await page.click(input, {clickCount:3}); await page.keyboard.press('Backspace'); await page.type(input,'库内影片');
        await page.keyboard.press('Enter');
        await page.waitForFunction(() => document.body.textContent.includes('资源库已有资源（优先展示'));
        await page.waitForSelector('button[data-command="资源"]:not([disabled])');
        assert.ok(await page.$eval('button[data-command="资源"]:not([disabled])', el => el.textContent.includes('搜索其他资源')));
        assert.ok(await page.evaluate(() => !document.querySelector('[data-testid="search-conversation"]').innerText.includes('https://')));
        if(process.env.WEB_TEST_SCREENSHOT_DIR) {
            await page.evaluate(()=>{const panel=document.querySelector('[data-testid="search-conversation"]');panel.scrollTop=panel.scrollHeight;});
            await page.screenshot({path:path.join(process.env.WEB_TEST_SCREENSHOT_DIR,'resource-search-qr-mobile.png'),fullPage:true});
            await page.setViewport({width:1280,height:1000});
            await page.evaluate(()=>{const panel=document.querySelector('[data-testid="search-conversation"]');panel.scrollTop=panel.scrollHeight;});
            await page.screenshot({path:path.join(process.env.WEB_TEST_SCREENSHOT_DIR,'resource-search-qr-desktop.png'),fullPage:true});
        }
        console.log('PASS library-first QR reply with explicit search-more action');
        assert.deepEqual(errors,[]);
        console.log('PASS failure preserves input, history survives reload, stale choices disabled');
    } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
