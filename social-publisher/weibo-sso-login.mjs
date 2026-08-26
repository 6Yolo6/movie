import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { cookieValue, setWeiboRuntimeSession } from './weibo-web.mjs';

const root = path.resolve(process.env.WEIBO_PROFILE_ROOT || '/data/weibo');
const stateFile = path.join(root, 'session.json');
const attempts = new Map();
const userAgent = String(process.env.WEIBO_WEB_USER_AGENT || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0.0.0 Safari/537.36').trim();

function compact(value, limit = 300) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? `${text.slice(0, limit)}...` : text;
}

function jsonp(text) {
  const start = String(text).indexOf('({');
  const end = String(text).lastIndexOf(')');
  if (start < 0 || end <= start) throw new Error('Weibo QR endpoint returned an invalid response');
  return JSON.parse(String(text).slice(start + 1, end));
}

async function requestJsonp(url) {
  const response = await fetch(url, { headers: { referer: 'https://weibo.com/', 'user-agent': userAgent }, signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Weibo QR endpoint returned HTTP ${response.status}`);
  return jsonp(await response.text());
}

async function createQrCode() {
  const callback = `STK_${Date.now()}`;
  const payload = await requestJsonp(`https://login.sina.com.cn/sso/qrcode/image?entry=weibo&size=240&callback=${callback}`);
  if (Number(payload.retcode) !== 20000000 || !payload.data?.qrid || !payload.data?.image) throw new Error(`Weibo QR creation failed: ${compact(payload.msg || payload.retcode)}`);
  const imageUrl = payload.data.image.startsWith('//') ? `https:${payload.data.image}` : payload.data.image;
  const response = await fetch(imageUrl, { headers: { referer: 'https://weibo.com/', 'user-agent': userAgent }, signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Weibo QR image returned HTTP ${response.status}`);
  return { qrid: payload.data.qrid, verificationImage: `data:${response.headers.get('content-type') || 'image/png'};base64,${Buffer.from(await response.arrayBuffer()).toString('base64')}` };
}

async function saveSession(context, page) {
  const cookies = await context.cookies(['https://weibo.com', 'https://www.weibo.com']);
  if (!cookies.some(item => item.name === 'SUB' && item.value) || !cookies.some(item => item.name === 'WBPSESS' && item.value)) throw new Error('Weibo scan completed but the web session was not established');
  const cookie = cookies.map(item => `${item.name}=${item.value}`).join('; ');
  const fingerprint = String(process.env.WEIBO_WEB_FINGERPRINT || '').trim() || 'browser-session';
  const session = { cookie, xsrfToken: cookieValue(cookie, 'XSRF-TOKEN'), fingerprint, userAgent: await page.evaluate(() => navigator.userAgent), updatedAt: new Date().toISOString() };
  await fs.writeFile(stateFile, JSON.stringify(session), { mode: 0o600 });
  setWeiboRuntimeSession(session);
  return session;
}

export async function restoreWeiboSession() {
  try { return setWeiboRuntimeSession(JSON.parse(await fs.readFile(stateFile, 'utf8'))); } catch { return false; }
}

function view(attempt) {
  return { status: attempt.status, verificationImage: attempt.verificationImage || null, expiresAt: attempt.expiresAt, error: attempt.error || null, updatedAt: attempt.updatedAt || null };
}

export async function startWeiboLogin() {
  const current = attempts.get('default');
  if (current?.status === 'WAITING') return view(current);
  if (current?.browser) await current.browser.close().catch(() => {});
  await fs.mkdir(root, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.WEIBO_BROWSER_PATH || '/usr/bin/chromium' });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN', userAgent });
  const page = await context.newPage();
  const qr = await createQrCode();
  const attempt = { status: 'WAITING', browser, context, page, qrid: qr.qrid, verificationImage: qr.verificationImage, expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(), error: null };
  attempts.set('default', attempt);
  void waitForLogin(attempt);
  return view(attempt);
}

async function finishSso(attempt, alt) {
  const callback = `STK_${Date.now()}`;
  const params = new URLSearchParams({ entry: 'weibo', returntype: 'TEXT', crossdomain: '1', cdult: '3', domain: 'weibo.com', alt, savestate: '30', callback });
  const response = await attempt.page.goto(`https://login.sina.com.cn/sso/login.php?${params}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  const payload = jsonp(await response.text());
  if (Number(payload.retcode) !== 0) throw new Error(`Weibo SSO login failed: ${compact(payload.reason || payload.retcode)}`);
  for (const url of payload.crossDomainUrlList || []) await attempt.page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
  await attempt.page.goto('https://weibo.com/', { waitUntil: 'domcontentloaded', timeout: 45000 });
  await attempt.page.waitForTimeout(2000);
  return saveSession(attempt.context, attempt.page);
}

async function waitForLogin(attempt) {
  try {
    while (Date.now() < Date.parse(attempt.expiresAt)) {
      await attempt.page.waitForTimeout(2500);
      const callback = `STK_${Date.now()}`;
      const payload = await requestJsonp(`https://login.sina.com.cn/sso/qrcode/check?entry=weibo&qrid=${encodeURIComponent(attempt.qrid)}&callback=${callback}`);
      const code = Number(payload.retcode);
      if ([50114001, 50114002].includes(code)) continue;
      if (code === 20000000 && payload.data?.alt) {
        const session = await finishSso(attempt, payload.data.alt);
        attempt.status = 'AUTHORIZED';
        attempt.updatedAt = session.updatedAt;
        return;
      }
      if (code === 50114003) { attempt.status = 'EXPIRED'; attempt.error = 'Weibo QR code expired'; return; }
      throw new Error(`Weibo QR login failed: ${compact(payload.msg || code)}`);
    }
    attempt.status = 'EXPIRED';
  } catch (error) {
    attempt.status = 'FAILED';
    attempt.error = compact(error.message);
  } finally {
    await attempt.browser.close().catch(() => {});
    attempt.browser = null;
  }
}

export function weiboLoginStatus() {
  const attempt = attempts.get('default');
  return attempt ? view(attempt) : { status: 'IDLE', verificationImage: null, expiresAt: null, error: null, updatedAt: null };
}
