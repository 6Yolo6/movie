const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../frontend/node_modules/typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../../frontend/src/lib/resourceSearch.ts'), 'utf8');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText,
    { exports: exportsObject, URL });
const { parseSearchReply, safeSearchLinks, readSearchHistory } = exportsObject;

test('movie suggestions become exact numbered commands', () => {
    const result = parseSearchReply('请选择要搜索的影片：\n1. 电影 测试 (2024) [GYING]\n2. 剧集 测试 (2025)\n\n直接回复序号即可，例如：1');
    const choices = result.parts.filter(x => x.type === 'choice');
    assert.equal(choices.length, 2); assert.equal(choices[1].command, '2');
    assert.equal(choices[0].badge, 'GYING'); assert.equal(choices[0].kind, 'movie');
});
test('resource pagination uses page-local numbers and preserves metadata', () => {
    const result = parseSearchReply('片名：测试\n简介：内容\n\n请选择资源（回复序号后再返回对应资源）：\n1. 测试 4K [夸克]\n2. 测试 1080P [迅雷]\n\n还可以继续选择其他资源：回复当前页序号 1-2；当前第 2/3 页，可回复“下一页”或“上一页”翻页');
    assert.equal(result.page.current, 2); assert.equal(result.page.total, 3);
    assert.equal(result.parts.filter(x => x.type === 'choice')[1].command, '2');
    assert.ok(result.parts.some(x => x.type === 'text' && x.text.includes('简介：内容')));
});
test('owned URLs are hidden, only explicit resource section becomes clickable', () => {
    const result = parseSearchReply('资源库已有资源（可直接使用）：\n- 测试\nhttps://pan.quark.cn/s/fixture\n\n如需其他版本或网盘，请继续回复下面的资源序号：\n1. 其他版本 [夸克]');
    assert.equal(result.parts.filter(x => x.type === 'choice').length, 1);
    assert.ok(result.parts.every(x => x.type !== 'text' || !x.text.includes('https://')));
});
test('post-transfer continuation never invents buttons from stale candidate numbers', () => {
    const result = parseSearchReply('已选择资源：测试\nhttps://pan.quark.cn/s/fixture\n\n还可以继续选择其他资源：回复当前页序号 1-9');
    assert.equal(result.hasMoreResources, true); assert.equal(result.hasChoices, false);
});
test('arbitrary lists and unrelated paragraphs are not executable commands', () => {
    assert.equal(parseSearchReply('操作提示：\n1. 不要重复转存').hasChoices, false);
    const result = parseSearchReply('请选择资源（回复当前页序号）：\n1. 测试 [夸克]\n\n2. 后续说明');
    assert.equal(result.parts.filter(x => x.type === 'choice').length, 1);
});
test('unsafe links and duplicates are dropped', () => {
    const result = safeSearchLinks([{url:'javascript:alert(1)'},{url:'data:text/html,unsafe'},
        {url:'https://pan.quark.cn/s/fixture',name:'资源链接 1'},{url:'https://pan.quark.cn/s/fixture'}]);
    assert.equal(result.length, 1); assert.equal(result[0].name, '夸克分享');
});
test('history parsing rejects corrupt storage and limits retained turns', () => {
    assert.equal(readSearchHistory('{broken').length, 0);
    const turns = Array.from({length:30}, (_,i)=>({id:`job-${i}`,command:'测试',label:'测试',status:'done',reply:'回复',links:[],createdAt:i}));
    const result = readSearchHistory(JSON.stringify(turns));
    assert.equal(result.length, 20); assert.equal(result[0].id, 'job-10');
    assert.equal(readSearchHistory(JSON.stringify([{id:'invalid'}])).length, 0);
});

test('library-first reply offers explicit external search and removes all resource URLs', () => {
    const result = parseSearchReply('资源库已有资源（优先展示，无需重新搜索或转存）：\n- 人工分享\nhttps://pan.quark.cn/s/fixture\n提取码：1234\n磁力 magnet:?xt=urn:btih:fixture\n需要其他版本或网盘，可点击“搜索其他资源”继续。');
    assert.equal(result.libraryFirst, true); assert.equal(result.hasMoreResources, true);
    assert.equal(result.hasChoices, false); assert.equal(result.page, null);
    const text = result.parts.map(x => x.text || x.title).join('');
    assert.ok(!text.includes('https://') && !text.includes('magnet:'));
    assert.ok(text.includes('1234'));
});
