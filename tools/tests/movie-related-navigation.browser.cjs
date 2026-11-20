const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const { base, fixture, json } = require('./fixtures/browser.cjs');
const { movieData } = require('./fixtures/monitoring-resources.cjs');
(async () => {
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const results = [];
    try {
        for (const options of [{lang:'zh',width:1440},{lang:'en',width:390,theme:'dark'}]) {
            let fail = false;
            const related = [{ id:'fixture-p2p', titleCn:'相关影片', year:2026, rating:8.1, genres:['科幻'], posterUrl:movieData('fixture-p2p').movie.posterUrl }];
            const f = await fixture(browser, {...options, onApi: async (route, url) => {
                if (url.pathname.endsWith('/recommendations')) { await json(route, related, fail ? 503 : 200); return true; }
                if (url.pathname === '/api/ai/status') { await json(route, {available:true,catalogAvailable:true}); return true; }
                if (url.pathname === '/api/ai/query') { await json(route, {status:'SUCCEEDED',result:{answer:'fixture recommendation',catalog:{kind:'recommendation',total:1},evidence:[{tool:'recommendMovies',queriedAt:'2026-10-21T00:00:00Z',movies:related}]}}); return true; }
                return false;
            }});
            await f.page.goto(base+'/ai-assistant');
            await f.page.getByRole('button',{name:f.t.aiGuessYouLike,exact:true}).click();
            await f.page.getByText('fixture recommendation',{exact:true}).waitFor();
            const link = f.page.locator('main a[href="/movie/fixture-p2p"]');
            assert.equal(await link.getAttribute('target'),'_blank');
            const popupPromise = f.page.waitForEvent('popup'); await link.click(); const popup = await popupPromise;
            await popup.waitForLoadState('domcontentloaded');
            assert.equal(f.page.url(),base+'/ai-assistant');
            await popup.close(); assert.equal(await f.page.getByText('fixture recommendation',{exact:true}).count(),1);
            await f.page.goto(base+'/movie/fixture-mixed');
            const section = f.page.getByTestId('related-movies');
            await section.getByText('相关影片',{exact:true}).waitFor();
            assert.equal(await section.locator('a').getAttribute('target'),'_blank');
            await f.overflow();
            fail=true; await f.page.reload(); await f.page.getByText(f.t.relatedMoviesFailed,{exact:true}).waitFor();
            fail=false; await section.getByRole('button').click();
            await section.getByText('相关影片',{exact:true}).waitFor();
            results.push({...options,newTabPreservesChat:true,relatedTitles:true,retry:true}); await f.done();
        }
        console.log(JSON.stringify(results));
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
