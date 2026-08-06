import assert from 'node:assert/strict';
import test from 'node:test';
import { cookieValue, publishWeiboWeb, weiboWebHealth } from './weibo-web.mjs';

test('extracts and decodes cookie values', () => {
  assert.equal(cookieValue('A=1; XSRF-TOKEN=hello%20world; B=2', 'XSRF-TOKEN'), 'hello world');
});

test('requires a cookie and browser fingerprint', () => {
  assert.deepEqual(weiboWebHealth({}), {
    mode: 'web-session',
    configured: false,
    authenticated: false,
    ready: false,
    error: 'Missing WEIBO_WEB_COOKIE and WEIBO_WEB_FINGERPRINT',
  });
});

test('posts the captured web form and returns a public URL', async () => {
  let request;
  const result = await publishWeiboWeb('test content', {
    config: {
      cookie: 'SUB=secret',
      xsrfToken: 'xsrf',
      fingerprint: 'browser-fp',
      clientVersion: 'v1',
      userAgent: 'test-agent',
      endpoint: 'https://www.weibo.com/ajax/statuses/update',
    },
    fetchImpl: async (url, options) => {
      request = { url, options };
      return new Response(JSON.stringify({
        ok: 1,
        data: { mblogid: 'AbCd', user: { idstr: '123' } },
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    },
  });

  assert.equal(request.url, 'https://www.weibo.com/ajax/statuses/update');
  assert.equal(request.options.headers.cookie, 'SUB=secret');
  assert.equal(request.options.headers['x-xsrf-token'], 'xsrf');
  assert.equal(request.options.body.get('content'), 'test content');
  assert.equal(request.options.body.get('visible'), '0');
  assert.equal(request.options.body.get('fp'), 'browser-fp');
  assert.equal(result.externalUrl, 'https://weibo.com/123/AbCd');
});

test('classifies an expired web session', async () => {
  await assert.rejects(
    publishWeiboWeb('test', {
      config: {
        cookie: 'SUB=expired',
        xsrfToken: '',
        fingerprint: 'browser-fp',
        clientVersion: 'v1',
        userAgent: 'test-agent',
        endpoint: 'https://www.weibo.com/ajax/statuses/update',
      },
      fetchImpl: async () => new Response('<html>login</html>', { status: 302 }),
    }),
    /session expired/i,
  );
});
