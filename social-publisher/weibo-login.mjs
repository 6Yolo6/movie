import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { cookieValue, setWeiboRuntimeSession } from './weibo-web.mjs';

const root = path.resolve(process.env.WEIBO_PROFILE_ROOT || '/data/weibo');
const profile = path.join(root, 'profile');
const stateFile = path.join(root, 'session.json');
const attempts = new Map();

function compact(value, limit = 300) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? `${text.slice(0, limit)}...` : text;
}

function fingerprint(page) {
  return page.evaluate(() => {
    const value = [navigator.userAgent, navigator.language, screen.width, screen.height, Intl.DateTimeFormat().resolvedOptions().timeZone].join('|');
    let hash = 2166136261;
    for (let i = 0; i < value.length; i += 1) hash = Math.imul(hash ^ value.charCodeAt(i), 16777619);
    return `fp-${(hash >>> 0).toString(16)}`;
  });
}

async function sessionFromPage(page) {
  const cookies = await page.context().cookies('https://weibo.com');
  const cookie = cookies.map(item => `${item.name}=${item.value}`).join('; ');
  const sub = cookies.find(item => item.name === 'SUB' || item.name === 'SUBP');
  if (!sub) return null;
  const fp = String(process.env.WEIBO_WEB_FINGERPRINT || '').trim() || await fingerprint(page);
  const session = { cookie, xsrfToken: cookieValue(cookie, 'XSRF-TOKEN'), fingerprint: fp, userAgent: await page.evaluate(() => navigator.userAgent), updatedAt: new Date().toISOString() };
  await fs.mkdir(root, { recursive: true });
  await fs.writeFile(stateFile, JSON.stringify(session), { mode: 0o600 });
  setWeiboRuntimeSession(session);
  return session;
}

export async function restoreWeiboSession() {
  try {
    const session = JSON.parse(await fs.readFile(stateFile, 'utf8'));
    return setWeiboRuntimeSession(session) ? true : false;
  } catch {
    return false;
  }
}

function view(attempt) {
  return {
    status: attempt.status,
    verificationImage: attempt.verificationImage || null,
    expiresAt: attempt.expiresAt,
    error: attempt.error || null,
    updatedAt: attempt.updatedAt || null,
  };
}

export async function startWeiboLogin(force = false) {
  const current = attempts.get('default');
  if (current?.status === 'WAITING') return view(current);
  if (current?.context) await current.context.close().catch(() => {});
  await fs.mkdir(root, { recursive: true });
  const context = await chromium.launchPersistentContext(profile, {
    headless: true,
    executablePath: process.env.WEIBO_BROWSER_PATH || '/usr/bin/chromium',
    viewport: { width: 1280, height: 900 },
    locale: 'zh-CN',
  });
  const page = context.pages()[0] || await context.newPage();
  const attempt = { status: 'WAITING', context, page, expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(), error: null, verificationImage: null };
  attempts.set('default', attempt);
  try {
    await page.goto('https://weibo.com/', { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(2500);
    attempt.verificationImage = `data:image/png;base64,${(await page.screenshot({ type: 'png' })).toString('base64')}`;
    void waitForLogin(attempt);
    return view(attempt);
  } catch (error) {
    attempt.status = 'FAILED';
    attempt.error = compact(error.message);
    await context.close().catch(() => {});
    return view(attempt);
  }
}

async function waitForLogin(attempt) {
  try {
    while (Date.now() < Date.parse(attempt.expiresAt)) {
      await attempt.page.waitForTimeout(3000);
      const session = await sessionFromPage(attempt.page);
      if (session) {
        attempt.status = 'AUTHORIZED';
        attempt.updatedAt = session.updatedAt;
        await attempt.context.close().catch(() => {});
        attempt.context = null;
        return;
      }
    }
    attempt.status = 'EXPIRED';
  } catch (error) {
    attempt.status = 'FAILED';
    attempt.error = compact(error.message);
  } finally {
    if (attempt.context) await attempt.context.close().catch(() => {});
    attempt.context = null;
  }
}

export function weiboLoginStatus() {
  const attempt = attempts.get('default');
  return attempt ? view(attempt) : { status: 'IDLE', verificationImage: null, expiresAt: null, error: null, updatedAt: null };
}
