import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import mysql from 'mysql2/promise';
import { publishWeiboWeb, weiboWebHealth } from './weibo-web.mjs';
import { restoreWeiboSession, startWeiboLogin, weiboLoginStatus } from './weibo-sso-login.mjs';

const port = Number(process.env.SOCIAL_PUBLISHER_PORT || 8093);
const internalToken = process.env.SOCIAL_PUBLISHER_TOKEN || process.env.APP_INTERNAL_TOKEN || '';
const qqAccountsRoot = path.resolve(process.env.QQ_CHANNEL_ACCOUNTS_ROOT || '/data/qq-accounts');
const qqAccountKeyPattern = /^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/;
const qqLoginAttempts = new Map();
const pool = mysql.createPool({
  host: process.env.DB_HOST || 'host.docker.internal',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || '',
  database: process.env.DB_NAME || 'gying',
  charset: 'utf8mb4',
  connectionLimit: 4,
});

function writeJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
  });
  res.end(body);
}

function readJsonBody(req, limit = 8192) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => {
      body += chunk;
      if (body.length > limit) {
        reject(new Error('Request body is too large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!body.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(body));
      } catch {
        reject(new Error('Request body must be valid JSON'));
      }
    });
    req.on('error', reject);
  });
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      env: { ...process.env, ...(options.env || {}) },
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    options.onChild?.(child);
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`${command} timed out`));
    }, options.timeoutMs || 120000);
    child.stdout.on('data', chunk => { stdout += chunk.toString(); });
    child.stderr.on('data', chunk => { stderr += chunk.toString(); });
    child.on('error', reject);
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0) {
        reject(new Error(`${command} exited ${code}: ${compact(stderr || stdout)}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

function validateQqAccountKey(value) {
  const key = String(value || '').trim();
  if (!qqAccountKeyPattern.test(key)) {
    throw new Error('QQ account key must be 2-32 characters using letters, numbers, _ or -');
  }
  return key;
}

function qqAccountHome(accountKey) {
  const key = validateQqAccountKey(accountKey);
  return path.join(qqAccountsRoot, key);
}

function qqEnvironment(accountKey) {
  return {
    QQ_AI_CONNECT_TOKEN: '',
    HOME: qqAccountHome(accountKey),
  };
}

function qqStatusReady(result) {
  const data = result?.data || result || {};
  return Boolean(result?.success && (data.valid === true || data.status === 'authorized'));
}

function qqAttemptView(attempt) {
  return {
    accountKey: attempt.accountKey,
    status: attempt.status,
    verificationUri: attempt.verificationUri || null,
    expiresInSeconds: attempt.expiresInSeconds || null,
    expiresAt: attempt.expiresAt || null,
    error: attempt.error || null,
  };
}

async function readQqStatus(accountKey) {
  const result = parseJsonOutput(await run(
    'tencent-channel-cli',
    ['login', 'status', '--json'],
    { env: qqEnvironment(accountKey), timeoutMs: 30000 },
  ));
  const data = result.data || {};
  return {
    accountKey,
    status: qqStatusReady(result) ? 'AUTHORIZED' : 'UNAUTHORIZED',
    ready: qqStatusReady(result),
    valid: Boolean(data.valid),
    message: data.message || null,
    tokenSource: data.tokenSource || null,
  };
}

async function qqAccountKeys() {
  await fs.mkdir(qqAccountsRoot, { recursive: true });
  const entries = await fs.readdir(qqAccountsRoot, { withFileTypes: true });
  const keys = entries.filter(entry => entry.isDirectory() && qqAccountKeyPattern.test(entry.name)).map(entry => entry.name);
  for (const key of qqLoginAttempts.keys()) if (!keys.includes(key)) keys.push(key);
  return keys.sort();
}

async function qqAccountList() {
  const keys = await qqAccountKeys();
  return Promise.all(keys.map(async accountKey => {
    const attempt = qqLoginAttempts.get(accountKey);
    if (attempt && ['WAITING', 'AUTHORIZED', 'FAILED', 'EXPIRED'].includes(attempt.status)) {
      return { accountKey, status: attempt.status, ready: attempt.status === 'AUTHORIZED', error: attempt.error || null };
    }
    try {
      return await readQqStatus(accountKey);
    } catch (error) {
      return { accountKey, status: 'UNAUTHORIZED', ready: false, error: compact(error.message) };
    }
  }));
}

async function startQqLogin(accountKeyValue, force = false) {
  const accountKey = validateQqAccountKey(accountKeyValue);
  const current = qqLoginAttempts.get(accountKey);
  if (current?.status === 'WAITING') return qqAttemptView(current);
  if (!force) {
    try {
      if ((await readQqStatus(accountKey)).ready) {
        throw new Error(`QQ account ${accountKey} is already authorized`);
      }
    } catch (error) {
      if (String(error.message).includes('already authorized')) throw error;
    }
  }
  const home = qqAccountHome(accountKey);
  await fs.mkdir(home, { recursive: true });
  const qrPath = path.join(os.tmpdir(), `gying-qq-${accountKey}-${Date.now()}.png`);
  const login = parseJsonOutput(await run(
    'tencent-channel-cli',
    ['login', '--json', '--qrcode-path', qrPath, ...(force ? ['--yes'] : [])],
    { env: qqEnvironment(accountKey), timeoutMs: 30000 },
  ));
  const data = login.data || login;
  await fs.rm(qrPath, { force: true });
  const expiresInSeconds = Number(data.expires_in_s || 600);
  const attempt = {
    accountKey,
    status: 'WAITING',
    verificationUri: data.verification_uri || null,
    expiresInSeconds,
    expiresAt: new Date(Date.now() + expiresInSeconds * 1000).toISOString(),
    error: null,
    child: null,
  };
  qqLoginAttempts.set(accountKey, attempt);
  void run(
    'tencent-channel-cli',
    ['login', 'poll-token', '--json'],
    {
      env: qqEnvironment(accountKey),
      timeoutMs: Math.max(expiresInSeconds + 60, 120) * 1000,
      onChild: child => { attempt.child = child; },
    },
  ).then(output => {
    const result = parseJsonOutput(output);
    attempt.status = qqStatusReady(result) ? 'AUTHORIZED' : 'FAILED';
    attempt.error = attempt.status === 'FAILED' ? compact(result.data?.message || result.message || 'QQ authorization failed') : null;
  }).catch(error => {
    attempt.status = attempt.status === 'WAITING' ? 'EXPIRED' : 'FAILED';
    attempt.error = compact(error.message);
  }).finally(() => {
    attempt.child = null;
  });
  return qqAttemptView(attempt);
}

async function qqLoginStatus(accountKeyValue) {
  const accountKey = validateQqAccountKey(accountKeyValue);
  const attempt = qqLoginAttempts.get(accountKey);
  if (attempt) return qqAttemptView(attempt);
  return readQqStatus(accountKey);
}

async function removeQqAccount(accountKeyValue) {
  const accountKey = validateQqAccountKey(accountKeyValue);
  const attempt = qqLoginAttempts.get(accountKey);
  if (attempt?.child) attempt.child.kill();
  qqLoginAttempts.delete(accountKey);
  await fs.rm(qqAccountHome(accountKey), { recursive: true, force: true });
  return { accountKey, deleted: true };
}

function compact(value, limit = 1000) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? text.slice(0, limit) : text;
}

function parseJsonOutput(output) {
  const text = String(output || '').trim();
  try {
    return JSON.parse(text);
  } catch {
    const lines = text.split(/\r?\n/).reverse();
    for (const line of lines) {
      try {
        return JSON.parse(line);
      } catch {
        // Continue until a JSON line is found.
      }
    }
  }
  throw new Error(`Command returned non-JSON output: ${compact(text)}`);
}

function truncate(value, limit) {
  const chars = Array.from(String(value || '').trim());
  return chars.length > limit ? `${chars.slice(0, limit - 1).join('')}…` : chars.join('');
}

function render(template, row) {
  const type = ['tv', 'series', 'show', 'drama'].includes(String(row.media_type || '').toLowerCase())
    ? '剧集'
    : '电影';
  const link = row.platform === 'QQ_CHANNEL'
    ? `[查看资源](${row.resource_url})`
    : row.resource_url;
  const summary = String(row.summary || '').trim() || '暂无简介';
  const intro = row.platform === 'WEIBO' ? summary : truncate(summary, 600);
  return String(template || '{{title}}\n{{link}}')
    .replaceAll('{{title}}', row.title || row.movie_id)
    .replaceAll('{{year}}', row.release_year || '')
    .replaceAll('{{type}}', type)
    .replaceAll('{{link}}', link)
    .replaceAll('{{intro}}', intro);
}

async function loadPost(logId) {
  const [rows] = await pool.query(
    `SELECT l.id, l.target_id, l.platform, l.resource_link_id, l.movie_id,
            t.account_key, t.name AS target_name, t.target_ref, t.channel_ref, t.template,
            rl.url AS resource_url,
            COALESCE(NULLIF(m.title_cn, ''), NULLIF(m.title_en, ''), m.id) AS title,
            COALESCE(m.year, '') AS release_year,
            LOWER(COALESCE(NULLIF(m.tmdb_type, ''), NULLIF(m.category, ''), 'movie')) AS media_type,
            COALESCE(m.summary, '') AS summary,
            COALESCE(m.poster_url, '') AS poster_url
       FROM social_post_log l
       JOIN social_publish_target t ON t.id = l.target_id
       JOIN resource_link rl ON rl.id = l.resource_link_id
       JOIN movie_metadata m ON m.id = l.movie_id
      WHERE l.id = ?
      LIMIT 1`,
    [logId],
  );
  if (!rows.length) throw new Error('Social post log not found');
  return rows[0];
}

async function preparePoster(posterUrl) {
  if (!posterUrl) return null;
  const prefix = String(process.env.MINIO_URL_PREFIX || '').replace('host.docker.internal', 'host.docker.internal');
  const url = /^https?:\/\//i.test(posterUrl)
    ? posterUrl.replace('127.0.0.1', 'host.docker.internal')
    : `${prefix.replace(/\/$/, '')}/${String(posterUrl).replace(/^\//, '')}`;
  if (!url) return null;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Poster download failed: HTTP ${response.status}`);
  const contentType = response.headers.get('content-type') || '';
  const extension = contentType.includes('png') ? '.png' : contentType.includes('webp') ? '.webp' : '.jpg';
  const file = path.join(os.tmpdir(), `gying-social-${Date.now()}${extension}`);
  await fs.writeFile(file, Buffer.from(await response.arrayBuffer()));
  return file;
}

async function resolveQqDestination(row) {
  const env = qqEnvironment(row.account_key);
  const joined = parseJsonOutput(await run(
    'tencent-channel-cli',
    ['manage', 'get-my-join-guild-info', '--json'],
    { env },
  ));
  const guilds = [
    ...(joined.data?.created_guilds || []),
    ...(joined.data?.managed_guilds || []),
    ...(joined.data?.joined_guilds || []),
  ];
  const guild = guilds.find(item =>
    String(item.guild_number || '').toLowerCase() === String(row.target_ref || '').toLowerCase()
    || String(item.guild_id || '') === String(row.target_ref || ''));
  if (!guild) throw new Error(`QQ account has not joined target channel ${row.target_ref}`);
  if (row.channel_ref) {
    return { guildId: guild.guild_id, channelId: row.channel_ref };
  }
  const channels = parseJsonOutput(await run(
    'tencent-channel-cli',
    ['manage', 'get-guild-channel-list', '--guild-id', String(guild.guild_id), '--json'],
    { env },
  )).data?.channels || [];
  const channel = channels.find(item => ['全部', '帖子广场'].includes(item.channel_name));
  if (!channel) throw new Error(`No post board found in QQ channel ${row.target_ref}`);
  return { guildId: guild.guild_id, channelId: channel.channel_id };
}

async function publishQq(row, content, posterPath) {
  const destination = await resolveQqDestination(row);
  const contentFile = path.join(os.tmpdir(), `gying-social-${Date.now()}.txt`);
  await fs.writeFile(contentFile, content, 'utf8');
  try {
    const args = [
      'feed', 'publish-feed',
      '--guild-id', String(destination.guildId),
      '--channel-id', String(destination.channelId),
      '--content-file', contentFile,
    ];
    if (posterPath) args.push('--image', posterPath);
    args.push('--json');
    const result = parseJsonOutput(await run('tencent-channel-cli', args, { env: qqEnvironment(row.account_key) }));
    if (!result.success) throw new Error(compact(result.message || result.error || JSON.stringify(result)));
    return {
      externalUrl: result.data?.share_url || null,
      result,
    };
  } finally {
    await fs.rm(contentFile, { force: true });
  }
}

async function publishWeibo(row, content) {
  return publishWeiboWeb(content);
}

async function publish(logId) {
  const row = await loadPost(logId);
  const content = render(row.template, row);
  let posterPath = null;
  try {
    if (row.platform === 'QQ_CHANNEL') {
      try {
        posterPath = await preparePoster(row.poster_url);
      } catch {
        posterPath = null;
      }
    }
    const published = row.platform === 'QQ_CHANNEL'
      ? await publishQq(row, content, posterPath)
      : row.platform === 'WEIBO'
        ? await publishWeibo(row, content)
        : (() => { throw new Error(`Unsupported social platform ${row.platform}`); })();
    await pool.query(
      `UPDATE social_post_log
          SET status = 'POSTED', external_url = ?, error_message = NULL, posted_at = NOW(), updated_at = NOW()
        WHERE id = ?`,
      [published.externalUrl, logId],
    );
    return { ok: true, logId, platform: row.platform, externalUrl: published.externalUrl };
  } catch (error) {
    await pool.query(
      `UPDATE social_post_log
          SET status = 'FAILED', error_message = ?, updated_at = NOW()
        WHERE id = ?`,
      [compact(error.message), logId],
    );
    throw error;
  } finally {
    if (posterPath) await fs.rm(posterPath, { force: true });
  }
}

async function health() {
  const result = { ok: true, qq: {}, weibo: {} };
  try {
    const accounts = await qqAccountList();
    result.qq.accounts = accounts;
    result.qq.configured = accounts.length > 0;
    result.qq.ready = accounts.some(account => account.ready);
  } catch (error) {
    result.qq.configured = false;
    result.qq.ready = false;
    result.qq.error = compact(error.message);
  }
  result.weibo = weiboWebHealth();
  return result;
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://social-publisher');
    if (req.method === 'GET' && url.pathname === '/health') {
      writeJson(res, 200, await health());
      return;
    }
    if (req.headers['x-internal-token'] !== internalToken) {
      writeJson(res, 401, { error: 'Unauthorized' });
      return;
    }
    if (req.method === 'GET' && url.pathname === '/accounts/qq') {
      writeJson(res, 200, { accounts: await qqAccountList() });
      return;
    }
    if (req.method === 'POST' && url.pathname === '/accounts/qq/login') {
      const body = await readJsonBody(req);
      writeJson(res, 200, await startQqLogin(body.accountKey, Boolean(body.force)));
      return;
    }
    const qqLoginMatch = url.pathname.match(/^\/accounts\/qq\/([^/]+)\/login-status$/);
    if (req.method === 'GET' && qqLoginMatch) {
      writeJson(res, 200, await qqLoginStatus(decodeURIComponent(qqLoginMatch[1])));
      return;
    }
    const qqAccountMatch = url.pathname.match(/^\/accounts\/qq\/([^/]+)$/);
    if (req.method === 'DELETE' && qqAccountMatch) {
      writeJson(res, 200, await removeQqAccount(decodeURIComponent(qqAccountMatch[1])));
      return;
    }
    if (req.method === 'POST' && url.pathname === '/accounts/weibo/login') {
      const body = await readJsonBody(req);
      writeJson(res, 200, await startWeiboLogin(Boolean(body.force)));
      return;
    }
    if (req.method === 'GET' && url.pathname === '/accounts/weibo/login-status') {
      writeJson(res, 200, weiboLoginStatus());
      return;
    }
    const postMatch = url.pathname.match(/^\/posts\/(\d+)$/);
    if (req.method === 'POST' && postMatch) {
      writeJson(res, 200, await publish(Number(postMatch[1])));
      return;
    }
    writeJson(res, 404, { error: 'Not found' });
  } catch (error) {
    writeJson(res, 502, { error: compact(error.message) });
  }
});

server.listen(port, '0.0.0.0', () => {
  void restoreWeiboSession();
  console.log(`social publisher listening on ${port}`);
});
