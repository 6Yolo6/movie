const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '..', 'patch-openclaw-qqbot-gying.ps1'), 'utf8');
const helper = script.match(/\$desiredHelper = @'\r?\n([\s\S]*?)\r?\n'@/)[1];
const gatewayLine = script.match(/\$gyingMovieSearchCommandLine = '([^\r\n]+)'/)[1];
function load(overrides = {}) {
  return vm.runInNewContext(helper + '\n' + gatewayLine
    + '\n({ extractGyingTextCommand, isGyingMovieSearchCommand, runGyingMovieSearch })', {
    URL, console, process: { env: {} }, ...overrides,
  });
}

test('bare resource commands reach both command routing layers', () => {
  const api = load();
  for (const text of ['资源', '更多', '  资源  ', '资源\u3000']) {
    assert.equal(api.extractGyingTextCommand(text), text.trim());
    assert.equal(api.isGyingMovieSearchCommand(text), true);
  }
});

test('provider filters, count compatibility and candidate numbers are retained', () => {
  const api = load();
  for (const text of ['夸克', '迅雷', '网盘 夸克', '资源 1', '资源10条', '更多 2 个', '1', '10']) {
    assert.equal(api.extractGyingTextCommand(text), text);
    assert.equal(api.isGyingMovieSearchCommand(text), true);
  }
  assert.equal(api.extractGyingTextCommand('搜 测试影片'), '测试影片');
  assert.equal(api.extractGyingTextCommand('找测试影片'), '测试影片');
});

test('arbitrary chat and admin commands are not captured as resource commands', () => {
  const api = load();
  for (const text of ['资源不足', '资源 100', '11', '你好', '/approve', '/stop', '/config']) {
    assert.equal(api.extractGyingTextCommand(text), null);
    assert.equal(api.isGyingMovieSearchCommand(text), false);
  }
});

test('all installer helper copies accept a bare resource command', () => {
  const lines = [...script.matchAll(/const resourcePreferencePattern = (\/\^.*?\$\/iu);/g)];
  assert.equal(lines.length, 4);
  for (const [, literal] of lines) {
    const pattern = vm.runInNewContext(literal);
    assert.equal(pattern.test('资源'), true);
    assert.equal(pattern.test('更多'), true);
    assert.equal(pattern.test('资源9条'), true);
    assert.equal(pattern.test('资源不足'), false);
  }
});

test('library search and bare continuation use the same backend conversation identity', async () => {
  const calls = [];
  const api = load({ fetch: async (url, options) => {
    calls.push({ url: new URL(url), options });
    return { ok: true, text: async () => JSON.stringify({ reply: calls.length === 1 ? '库内网盘资源' : '其他版本候选' }) };
  } });
  const context = { senderId: 'qq-fixture-user', accountConfig: { gyingSearchUrl: 'http://localhost.test/api/qq-bot/search-reply' } };
  assert.equal(await api.runGyingMovieSearch(context, api.extractGyingTextCommand('搜 测试影片')), '库内网盘资源');
  assert.equal(await api.runGyingMovieSearch(context, api.extractGyingTextCommand('资源')), '其他版本候选');
  assert.deepEqual(calls.map(call => call.url.searchParams.get('keyword')), ['测试影片', '资源']);
  assert.deepEqual(calls.map(call => call.url.searchParams.get('userKey')), ['qq-fixture-user', 'qq-fixture-user']);
});

test('expired backend context is returned rather than silently dropping the command', async () => {
  const api = load({ fetch: async () => ({ ok: true, text: async () => JSON.stringify({ reply: '当前没有可继续选择的影片，请先发送“搜 片名”。' }) }) });
  const result = await api.runGyingMovieSearch({ senderId: 'qq-fixture-user', accountConfig: {
    gyingSearchUrl: 'http://localhost.test/api/qq-bot/search-reply',
  } }, api.extractGyingTextCommand('资源'));
  assert.match(result, /请先发送/);
});

test('installer upgrades the gateway matcher even when it was already patched', () => {
  assert.ok(/\$gatewayContent = \[regex\]::Replace\(\s*\$gatewayContent,\s*'\(\?m\)\^.*?const isGyingMovieSearchCommand.*?',\s*\$gyingMovieSearchCommandLine\)/s.test(script), 'canonical matcher must be installed unconditionally');
});
