const DEFAULT_ENDPOINT = 'https://www.weibo.com/ajax/statuses/update';
const DEFAULT_CLIENT_VERSION = 'v1.1.237';
const DEFAULT_USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36';
let runtimeSession = null;

export function setWeiboRuntimeSession(session) {
  if (!session?.cookie || !session?.fingerprint) return false;
  runtimeSession = {
    cookie: String(session.cookie).trim(),
    xsrfToken: String(session.xsrfToken || cookieValue(session.cookie, 'XSRF-TOKEN')).trim(),
    fingerprint: String(session.fingerprint).trim(),
    userAgent: String(session.userAgent || '').trim(),
    updatedAt: session.updatedAt || new Date().toISOString(),
  };
  return true;
}

export function weiboRuntimeSession() {
  return runtimeSession ? { ...runtimeSession } : null;
}

function compact(value, limit = 300) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? `${text.slice(0, limit)}...` : text;
}

export function cookieValue(cookie, name) {
  const prefix = `${name}=`;
  const part = String(cookie || '')
    .split(';')
    .map(item => item.trim())
    .find(item => item.startsWith(prefix));
  if (!part) return '';
  const value = part.slice(prefix.length);
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

export function weiboWebConfig(env = process.env) {
  const cookie = String(runtimeSession?.cookie || env.WEIBO_WEB_COOKIE || '').trim();
  return {
    cookie,
    xsrfToken: String(runtimeSession?.xsrfToken || env.WEIBO_WEB_XSRF_TOKEN || cookieValue(cookie, 'XSRF-TOKEN')).trim(),
    fingerprint: String(runtimeSession?.fingerprint || env.WEIBO_WEB_FINGERPRINT || '').trim(),
    clientVersion: String(env.WEIBO_WEB_CLIENT_VERSION || DEFAULT_CLIENT_VERSION).trim(),
    userAgent: String(runtimeSession?.userAgent || env.WEIBO_WEB_USER_AGENT || DEFAULT_USER_AGENT).trim(),
    endpoint: DEFAULT_ENDPOINT,
  };
}

export function weiboWebHealth(env = process.env) {
  const config = weiboWebConfig(env);
  const missing = [];
  if (!config.cookie) missing.push('WEIBO_WEB_COOKIE');
  if (!config.fingerprint) missing.push('WEIBO_WEB_FINGERPRINT');
  return {
    mode: 'web-session',
    configured: missing.length === 0,
    authenticated: Boolean(config.cookie),
    ready: missing.length === 0,
    error: missing.length ? `Missing ${missing.join(' and ')}` : undefined,
  };
}

function postUrl(payload) {
  const data = payload?.data || payload || {};
  const user = data.user || payload?.user || {};
  const uid = user.idstr || user.id || data.uid || payload?.uid;
  const mid = data.mblogid || data.bid || data.mid || data.idstr
    || payload?.mblogid || payload?.bid || payload?.mid || payload?.idstr;
  return uid && mid ? `https://weibo.com/${uid}/${mid}` : null;
}

function responseMessage(payload) {
  return compact(payload?.msg || payload?.message || payload?.error || payload?.data?.msg || 'Unknown response');
}

export async function publishWeiboWeb(content, options = {}) {
  const config = options.config || weiboWebConfig(options.env);
  const fetchImpl = options.fetchImpl || fetch;
  if (!config.cookie) throw new Error('Weibo web session is not configured');
  if (!config.fingerprint) throw new Error('Weibo browser fingerprint is not configured');
  if (/[\r\n]/.test(config.cookie)) throw new Error('Weibo web session cookie is invalid');
  if (!String(content || '').trim()) throw new Error('Weibo post content is empty');

  const body = new URLSearchParams({
    content: String(content || '').trim(),
    visible: '0',
    share_id: '',
    vote: '',
    media: '',
    fp: config.fingerprint,
  });
  const response = await fetchImpl(config.endpoint, {
    method: 'POST',
    headers: {
      accept: 'application/json, text/plain, */*',
      'accept-language': 'zh-CN,zh;q=0.9,en;q=0.8',
      'client-version': config.clientVersion,
      'content-type': 'application/x-www-form-urlencoded',
      cookie: config.cookie,
      origin: 'https://www.weibo.com',
      referer: 'https://www.weibo.com/',
      'user-agent': config.userAgent,
      ...(config.xsrfToken ? { 'x-xsrf-token': config.xsrfToken } : {}),
    },
    body,
    redirect: 'manual',
    signal: AbortSignal.timeout(30000),
  });

  const raw = await response.text();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    if ([301, 302, 303, 307, 308, 401, 403].includes(response.status)) {
      throw new Error('Weibo web session expired or was rejected');
    }
    throw new Error(`Weibo returned a non-JSON response (HTTP ${response.status}): ${compact(raw, 180)}`);
  }

  if (response.status === 418 || response.status === 429) {
    throw new Error(`Weibo security control or rate limit: ${responseMessage(payload)}`);
  }
  if (!response.ok || (payload.ok !== 1 && String(payload.code) !== '100000' && payload.success !== true)) {
    const message = responseMessage(payload);
    if (/登录|login|session|cookie/i.test(message) || [401, 403].includes(response.status)) {
      throw new Error(`Weibo web session expired or was rejected: ${message}`);
    }
    if (/验证|频繁|异常|安全|风控|captcha/i.test(message)) {
      throw new Error(`Weibo security verification required: ${message}`);
    }
    throw new Error(`Weibo post failed: ${message}`);
  }

  return {
    externalUrl: postUrl(payload),
    result: payload,
  };
}
