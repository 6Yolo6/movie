import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import mysql from 'mysql2/promise';

const port = Number(process.env.SOCIAL_PUBLISHER_PORT || 8093);
const internalToken = process.env.SOCIAL_PUBLISHER_TOKEN || process.env.APP_INTERNAL_TOKEN || '';
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

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      env: { ...process.env, ...(options.env || {}) },
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
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
  const intro = truncate(row.summary || '暂无简介', row.platform === 'WEIBO' ? 120 : 600);
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

function qqEnvironment() {
  return {
    QQ_AI_CONNECT_TOKEN: process.env.QQ_CHANNEL_SECONDARY_TOKEN || process.env.QQ_AI_CONNECT_TOKEN || '',
    HOME: process.env.QQ_CHANNEL_SECONDARY_HOME || '/root',
  };
}

async function resolveQqDestination(row) {
  const env = qqEnvironment();
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
    const result = parseJsonOutput(await run('tencent-channel-cli', args, { env: qqEnvironment() }));
    if (!result.success) throw new Error(compact(result.message || result.error || JSON.stringify(result)));
    return {
      externalUrl: result.data?.share_url || null,
      result,
    };
  } finally {
    await fs.rm(contentFile, { force: true });
  }
}

async function discoverWeiboCommand() {
  const catalog = parseJsonOutput(await run(
    'weibo',
    ['commands', 'list', '--group', 'statuses', '--available', '--output', 'json'],
  ));
  const commands = catalog.commands || catalog.data?.commands || [];
  const priorities = ['share', 'upload_url_text', 'upload', 'update', 'publish', 'create'];
  for (const name of priorities) {
    const command = commands.find(item => String(item.action || item.name || '').toLowerCase() === name);
    if (command) return command.action || command.name;
  }
  const command = commands.find(item => /(share|publish|create|update|upload)/i.test(item.action || item.name || ''));
  if (!command) throw new Error('Current Weibo account has no available status publishing command');
  return command.action || command.name;
}

async function publishWeibo(row, content, posterPath) {
  const action = process.env.WEIBO_PUBLISH_ACTION || await discoverWeiboCommand();
  const details = parseJsonOutput(await run(
    'weibo',
    ['commands', 'show', 'statuses', action, '--output', 'json'],
  ));
  const flags = details.command?.flags || details.data?.command?.flags || [];
  const names = new Set(flags.map(flag => flag.name));
  const args = ['statuses', action];
  if (names.has('status')) args.push('--status', content);
  else if (names.has('text')) args.push('--text', content);
  else if (names.has('content')) args.push('--content', content);
  else throw new Error(`Weibo command statuses ${action} has no supported text flag`);
  if (names.has('url')) args.push('--url', row.resource_url);
  if (posterPath && names.has('pic')) args.push('--pic', posterPath);
  if (posterPath && names.has('image')) args.push('--image', posterPath);
  args.push('--output', 'json');
  const result = parseJsonOutput(await run('weibo', args));
  return {
    externalUrl: result.share_url || result.url || result.data?.share_url || null,
    result,
  };
}

async function publish(logId) {
  const row = await loadPost(logId);
  const content = render(row.template, row);
  let posterPath = null;
  try {
    try {
      posterPath = await preparePoster(row.poster_url);
    } catch {
      posterPath = null;
    }
    const published = row.platform === 'QQ_CHANNEL'
      ? await publishQq(row, content, posterPath)
      : row.platform === 'WEIBO'
        ? await publishWeibo(row, content, posterPath)
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
  const result = { ok: true, qq: { configured: Boolean(process.env.QQ_CHANNEL_SECONDARY_TOKEN) }, weibo: {} };
  try {
    const status = parseJsonOutput(await run(
      'tencent-channel-cli',
      ['login', 'status'],
      { env: qqEnvironment(), timeoutMs: 30000 },
    ));
    result.qq.ready = Boolean(status.success && status.data?.valid);
    result.qq.tokenSource = status.data?.tokenSource;
  } catch (error) {
    result.qq.ready = false;
    result.qq.error = compact(error.message);
  }
  try {
    await run('weibo', ['auth', 'whoami', '--output', 'json'], { timeoutMs: 30000 });
    result.weibo.ready = true;
  } catch (error) {
    result.weibo.ready = false;
    result.weibo.error = compact(error.message);
  }
  return result;
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      writeJson(res, 200, await health());
      return;
    }
    if (req.headers['x-internal-token'] !== internalToken) {
      writeJson(res, 401, { error: 'Unauthorized' });
      return;
    }
    const match = req.url?.match(/^\/posts\/(\d+)$/);
    if (req.method !== 'POST' || !match) {
      writeJson(res, 404, { error: 'Not found' });
      return;
    }
    writeJson(res, 200, await publish(Number(match[1])));
  } catch (error) {
    writeJson(res, 502, { error: compact(error.message) });
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`social publisher listening on ${port}`);
});
