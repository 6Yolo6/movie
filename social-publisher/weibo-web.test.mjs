import assert from 'node:assert/strict';
import test from 'node:test';
import { cookieValue, publishWeiboWeb as publishWithProbe, weiboWebHealth, WeiboAuthenticationError, WeiboSecurityError } from './weibo-web.mjs';

function publishWeiboWeb(content, options) {
  return publishWithProbe(content, { ...options, fetchImpl: (url, request) =>
    ['https://weibo.com/ajax/side/cards', 'https://weibo.com/ajax/feed/allGroups'].includes(url)
      ? Promise.resolve(new Response(JSON.stringify({ ok: 1, data: [] })))
      : options.fetchImpl(url, request) });
}

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

test('preserves long post content without adding an ellipsis', async () => {
  const content = `影片简介：${'完整简介内容'.repeat(80)}\nhttps://example.com/resource`;
  let postedContent;

  await publishWeiboWeb(content, {
    config: {
      cookie: 'SUB=secret',
      xsrfToken: 'xsrf',
      fingerprint: 'browser-fp',
      clientVersion: 'v1',
      userAgent: 'test-agent',
      endpoint: 'https://www.weibo.com/ajax/statuses/update',
    },
    fetchImpl: async (_url, options) => {
      postedContent = options.body.get('content');
      return new Response(JSON.stringify({
        ok: 1,
        data: { mblogid: 'LongPost', user: { idstr: '123' } },
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    },
  });

  assert.equal(postedContent, content);
  assert.equal(postedContent.endsWith('…'), false);
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

test('reports response fields when Weibo omits an error message', async () => {
  await assert.rejects(
    publishWeiboWeb('test', {
      config: {
        cookie: 'SUB=session',
        xsrfToken: '',
        fingerprint: 'browser-fp',
        clientVersion: 'v1',
        userAgent: 'test',
        endpoint: 'https://www.weibo.com/ajax/statuses/update',
      },
      fetchImpl: async () => new Response(JSON.stringify({ ok: 0, errno: 12345, data: {} }), { status: 200 }),
    }),
    /code=12345; response fields=ok,errno,data/,
  );
});


test('publication boundary is awaited before the Weibo request', async () => {
  const calls = [];
  const result = await publishWeiboWeb('fixture', {
    config: { cookie: 'fixture-cookie', fingerprint: 'fixture-fp', endpoint: 'https://example.test/weibo' },
    beforeSend: async () => { calls.push('gate'); },
    fetchImpl: async () => {
      calls.push('request');
      return new Response(JSON.stringify({ ok: 1, data: { mblogid: 'fixture', user: { idstr: '123' } } }));
    },
  });
  assert.deepEqual(calls, ['gate', 'request']); assert.equal(result.externalUrl, 'https://weibo.com/123/fixture');
});

test('a rejected publication boundary prevents the Weibo request', async () => {
  await assert.rejects(publishWeiboWeb('fixture', {
    config: { cookie: 'fixture-cookie', fingerprint: 'fixture-fp' },
    beforeSend: async () => { throw new Error('claim rejected'); },
    fetchImpl: () => assert.fail('must not publish'),
  }), /claim rejected/);
});

test('Weibo validation fails before the publication boundary', async () => {
  await assert.rejects(publishWeiboWeb('fixture', {
    config: { cookie: '', fingerprint: '' },
    beforeSend: () => assert.fail('must not cross boundary'),
    fetchImpl: () => assert.fail('must not publish'),
  }), /not configured/);
});

test('an expired login is detected by a read-only probe without submitting a post', async () => {
  const calls = [];
  await assert.rejects(publishWithProbe('fixture', {
    config: { cookie: 'fixture', fingerprint: 'fixture', userAgent: 'test' },
    beforeSend: () => assert.fail('must not cross boundary'),
    fetchImpl: async (url, request) => {
      calls.push({ url, method: request.method });
      return new Response(JSON.stringify({ ok: -100 }));
    },
  }), WeiboAuthenticationError);
  assert.deepEqual(calls, [{ url: 'https://weibo.com/ajax/side/cards', method: 'GET' }]);
});

test('only definite login rejection can mark a crossed request as unaccepted', async () => {
  for (const message of ['请先登录', '未登录', '登录已失效']) {
    await assert.rejects(publishWeiboWeb('fixture', {
      config: { cookie: 'fixture', fingerprint: 'fixture', endpoint: 'https://example.test/weibo' },
      fetchImpl: async () => new Response(JSON.stringify({ ok: 0, msg: message })),
    }), error => error instanceof WeiboAuthenticationError && error.rejectedBeforeAcceptance);
  }
  await assert.rejects(publishWeiboWeb('fixture', {
    config: { cookie: 'fixture', fingerprint: 'fixture', endpoint: 'https://example.test/weibo' },
    fetchImpl: async () => new Response(JSON.stringify({ ok: 0, msg: '访问频繁，请稍后重试' })),
  }), error => !(error instanceof WeiboAuthenticationError));
});

test('a successful probe precedes the durable boundary and post request', async () => {
  const calls = [];
  await publishWithProbe('fixture', {
    config: { cookie: 'fixture', fingerprint: 'fixture', endpoint: 'https://example.test/weibo' },
    beforeSend: async () => calls.push('boundary'),
    fetchImpl: async (_url, request) => {
      calls.push(request.method);
      return new Response(JSON.stringify(request.method === 'GET'
        ? { ok: 1, data: [] } : { ok: 1, data: { mblogid: 'post', user: { idstr: '1' } } }));
    },
  });
  assert.deepEqual(calls, ['GET', 'GET', 'boundary', 'POST']);
});

test('an unrecognized probe response never reaches the publication boundary', async () => {
  await assert.rejects(publishWithProbe('fixture', {
    config: { cookie: 'fixture', fingerprint: 'fixture' },
    beforeSend: () => assert.fail('must not publish'),
    fetchImpl: async () => new Response(JSON.stringify({ unexpected: true })),
  }), /Unable to verify/);
});

test('a logged-in account requiring identity verification fails before posting without becoming an auth failure', async () => {
  const calls = [];
  await assert.rejects(publishWithProbe('fixture', {
    config: { cookie: 'fixture', fingerprint: 'fixture', userAgent: 'fixture-agent' },
    beforeSend: () => assert.fail('must not cross boundary'),
    fetchImpl: async (url, request) => {
      assert.equal(request.method, 'GET'); assert.equal(request.headers['user-agent'], 'fixture-agent');
      assert.equal(request.headers['x-requested-with'], 'XMLHttpRequest'); calls.push(url);
      return new Response(JSON.stringify(url.endsWith('/side/cards') ? { ok: 1, data: [] } : {
        ok: 0, error_type: 'confirm', msg: '你的账号暂时无法进行发博、发评、关注、点赞等操作，可在验证身份后恢复。',
      }));
    },
  }), error => error instanceof WeiboSecurityError && !(error instanceof WeiboAuthenticationError));
  assert.deepEqual(calls, ['https://weibo.com/ajax/side/cards', 'https://weibo.com/ajax/feed/allGroups']);
});
