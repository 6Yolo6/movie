'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const sync = require('../xunlei-edge-token-sync.cjs');
const now = Date.now();
const jwt = (claims) => ['header',Buffer.from(JSON.stringify({ sub:'test-user',aud:'test-client',exp:Math.floor((now+3600000)/1000),...claims })).toString('base64url'),'synthetic'].join('.');
const token = jwt({});
const current = { access_token:token, user_id:'test-user', client_id:'test-client', device_id:'test-device', refresh_token:'synthetic-old-refresh', captcha_token:'synthetic-old-captcha' };
const candidate = (props={}) => sync.candidateFrom({ access_token:token,client_id:'test-client',device_id:'test-device',...props });

test('URL trust rejects lookalikes, userinfo, non-HTTPS, wrong ports and routes',()=>{
    assert.equal(sync.driveUrl('https://api-pan.xunlei.com/drive/v1/files'),true);
    for(const u of ['http://api-pan.xunlei.com/drive/v1/files','https://api-pan.xunlei.com.evil.test/drive/v1/files','https://name@api-pan.xunlei.com/drive/v1/files','https://api-pan.xunlei.com:444/drive/v1/files','https://api-pan.xunlei.com/drive/v10/files','not a url']) assert.equal(sync.driveUrl(u),false,u);
    assert.equal(sync.authUrl('https://xluser-ssl.xunlei.com/v1/auth/token'),true);
    assert.equal(sync.authUrl('https://xluser-ssl.xunlei.com/v1/auth/token/evil'),false);
});
test('expiry accepts seconds/milliseconds and rejects unknown values',()=>{
    assert.equal(sync.normalizeExpiry(1700000000),1700000000000);
    assert.equal(sync.normalizeExpiry(1700000000000),1700000000000);
    for(const n of [null,undefined,'bad',-1,Infinity]) assert.equal(sync.normalizeExpiry(n),0);
});
test('expiry and account guard exclude unsafe candidates',()=>{
    const candidates=new Map();
    for(const claims of [{sub:'other-user'},{exp:Math.floor((now+60000)/1000)},{exp:1}]) sync.rememberCandidate(candidates,sync.candidateFrom({access_token:jwt(claims)}));
    assert.deepEqual(sync.eligibleCandidates(candidates,current,now),[]);
});
test('matching request headers and complete stored credentials are combined by exact access token',()=>{
    const candidates=new Map();
    sync.rememberCandidate(candidates,sync.candidateFrom({access_token:token,refresh_token:'synthetic-new-refresh'}));
    sync.rememberCandidate(candidates,sync.candidateFrom({access_token:token},{'x-client-id':'test-client','x-device-id':'test-device','x-captcha-token':'synthetic-new-captcha'},'request'));
    const s=sync.buildState(candidates.get(token),current);
    assert.equal(s.refresh_token,'synthetic-new-refresh'); assert.equal(s.captcha_token,'synthetic-new-captcha');
});
test('rotated access never borrows stale refresh/captcha values',()=>{
    const next=candidate({access_token:jwt({exp:Math.floor(now/1000)+7200})});
    const state=sync.buildState(next,current);
    assert.equal(state.refresh_token,''); assert.equal(state.captcha_token,'');
});
test('same access plus changed device does not borrow old credentials',()=>{
    const state=sync.buildState(candidate({device_id:'another-device'}),current);
    assert.equal(state.refresh_token,''); assert.equal(state.captcha_token,'');
});
test('same access retains fields only with the same client/device identity',()=>{
    assert.equal(sync.buildState(candidate(),current).refresh_token,current.refresh_token);
});
test('same access with new refresh triggers update',()=>{
    const before=sync.buildState(candidate(),current);
    const after=sync.buildState(candidate({refresh_token:'synthetic-new-refresh'}),current);
    assert.equal(sync.changedState(after,before),true);
    assert.equal(sync.changedState(before,{...before}),false);
});
test('missing identity and account switch fail closed',()=>{
    assert.throws(()=>sync.buildState(sync.candidateFrom({access_token:jwt({sub:'other'})}),current),/account_mismatch/);
    assert.throws(()=>sync.buildState(sync.candidateFrom({access_token:jwt({exp:Math.floor(now/1000)+7200})}),current),/missing_client_identity/);
});
test('HTTP/business rejection or transport exceptions never produce a state',async()=>{
    const candidates=new Map([[token,candidate()]]);
    for(const validate of [async()=>({ok:false,status:401}),async()=>({ok:false,status:200}),async()=>{throw Error('synthetic')}]) {
        assert.equal((await sync.selectValidated(candidates,current,validate,now)).state,null);
    }
});
test('only successful validation selects a usable state',async()=>{
    const result=await sync.selectValidated(new Map([[token,candidate()]]),current,async()=>({ok:true,status:200}),now);
    assert.equal(result.state.access_token,token); assert.equal(result.validationHttpStatus,200);
});
test('business validation handles numeric/string zero and requires files array',async()=>{
    const oldFetch=global.fetch;
    try {
        const page={url:()=> 'https://pan.xunlei.com/',evaluate:(fn,arg)=>fn(arg)};
        for(const body of [{files:[]},{files:[],error_code:0},{files:[],error_code:'0'},{data:{files:[]}}]) {
            global.fetch=async(url,opts)=>{assert.equal(opts.method,'GET');assert.equal(opts.redirect,'error');return{status:200,json:async()=>body}};
            assert.equal((await sync.validateState(sync.buildState(candidate(),current))).ok,true);
        }
        for(const body of [{files:[],error_code:401},{ok:true},{files:'wrong'},{files:[],error:'expired'}]) {
            global.fetch=async()=>({status:200,json:async()=>body});
            assert.equal((await sync.validateState(sync.buildState(candidate(),current))).ok,false);
        }
    } finally {global.fetch=oldFetch;}
});
test('validation sends credentials only to the fixed official endpoint and rejects redirects',async()=>{
    let checked=false;
    const response=await sync.validateState(current,async(url,options)=>{
        assert.equal(url,'https://api-pan.xunlei.com/drive/v1/files?parent_id=&usage=DISPLAY&limit=1');
        assert.equal(options.redirect,'error'); checked=true; throw Error('synthetic redirect rejection');
    });
    assert.equal(checked,true);assert.equal(response.ok,false);
});
test('storage extraction is allowlisted, read-only and ignores unrelated credentials',async()=>{
    const prior=global.localStorage;
    const data={'credentials_test':JSON.stringify({access_token:token,refresh_token:'synthetic',password:'not-copyable'}),'elsewhere':JSON.stringify({access_token:'wrong'})};
    global.localStorage={length:2,key:i=>Object.keys(data)[i],getItem:k=>data[k],setItem:()=>assert.fail('must not mutate storage')};
    try { const values=await sync.storageCredentials({evaluate:fn=>fn()}); assert.equal(values.length,1);assert.equal('password' in values[0],false); }
    finally {global.localStorage=prior;}
});
test('capture closes own browser on navigation failure',async()=>{
    let closed=false;const page=new EventEmitter();page.setBypassServiceWorker=async()=>{};page.setRequestInterception=async()=>{};page.goto=async()=>{throw Error('synthetic navigation')};
    const browser={pages:async()=>[page],close:async()=>{closed=true}};
    await assert.rejects(sync.capture({puppeteer:{launch:async()=>browser},current,delay:async()=>{}})); assert.equal(closed,true);
});
test('disposable capture blocks refresh/signin and Drive writes',async()=>{
    const page=new EventEmitter();page.setBypassServiceWorker=async()=>{};page.setRequestInterception=async()=>{};
    page.goto=async()=>{
        for(const [url,method,blocked] of [['https://xluser-ssl.xunlei.com/v1/auth/token','POST',true],['https://api-pan.xunlei.com/drive/v1/files','POST',true],['https://api-pan.xunlei.com/drive/v1/files','GET',false]]) {
            let action='';page.emit('request',{url:()=>url,method:()=>method,headers:()=>({}),abort:async()=>{action='abort'},continue:async()=>{action='continue'}});assert.equal(action,blocked?'abort':'continue');
        }
        throw Error('done');
    };
    await assert.rejects(sync.capture({puppeteer:{launch:async()=>({pages:async()=>[page],close:async()=>{}})}}));
});
test('wrapper snapshots only web storage and never copies password/cookie databases',()=>{
    const source=fs.readFileSync(path.join(__dirname,'../sync-xunlei-edge-token.ps1'),'utf8');
    assert.match(source,/foreach \(\$dir in @\('Local Storage','Session Storage','IndexedDB'\)\)/);
    assert.doesNotMatch(source,/Copy-Item|Remove-Item.*-Recurse/);
    assert.match(source,/Save-XunleiEncryptedBackup/);assert.match(source,/exit 75/);
    assert.ok(source.indexOf('$live = Invoke-XunleiLiveRefresh') < source.indexOf('& robocopy'));
});
