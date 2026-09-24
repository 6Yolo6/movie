'use strict';

const fs = require('fs');
const path = require('path');
const puppeteer = require(process.env.PUPPETEER_PATH);
const edgePath = process.env.EDGE_PATH;
const profilePath = process.env.EDGE_PROFILE;
const profileName = process.env.EDGE_PROFILE_NAME || 'Default';
const existingStatePath = process.env.XUNLEI_EXISTING_STATE;
const outputPath = process.env.XUNLEI_OUTPUT_STATE;
const metaPath = process.env.XUNLEI_OUTPUT_META;

function jwtPayload(token) {
    try {
        const part = token.split('.')[1];
        if (!part) return {};
        const normalized = part.replace(/-/g, '+').replace(/_/g, '/');
        return JSON.parse(Buffer.from(normalized, 'base64').toString('utf8'));
    } catch {
        return {};
    }
}

function normalizeExpiry(value) {
    const number = Number(value || 0);
    if (!Number.isFinite(number) || number <= 0) return 0;
    return number < 100000000000 ? number * 1000 : number;
}

function readJson(file) {
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
        return {};
    }
}

function writeMeta(meta) {
    if (!metaPath) return;
    fs.mkdirSync(path.dirname(metaPath), { recursive: true });
    fs.writeFileSync(metaPath, JSON.stringify(meta), { encoding: 'utf8', mode: 0o600 });
}

(async () => {
    if (!edgePath || !profilePath || !outputPath) {
        throw new Error('Xunlei Edge sync paths are not configured');
    }
    const current = existingStatePath ? readJson(existingStatePath) : {};
    const currentToken = typeof current.access_token === 'string' ? current.access_token : '';
    const currentExpiry = normalizeExpiry(current.expires_at);
    let browser;
    const candidates = new Map();
    let refreshTokenPresent = false;
    let storageCredentialCount = 0;
    let refreshHttpStatus = null;
    const remember = (token, claims = {}, fallbackExpiry = 0, source = 'request') => {
        if (typeof token !== 'string' || !token.trim()) return;
        const value = token.trim();
        const jwt = Object.keys(claims).length ? claims : jwtPayload(value);
        const expiresAt = normalizeExpiry(jwt.exp ? Number(jwt.exp) * 1000 : fallbackExpiry);
        const previous = candidates.get(value);
        if (!previous || expiresAt > previous.expiresAt) {
            candidates.set(value, { token: value, claims: jwt, expiresAt, source });
        }
    };
    try {
        browser = await puppeteer.launch({
            headless: true,
            executablePath: edgePath,
            userDataDir: profilePath,
            args: ['--no-first-run', `--profile-directory=${profileName}`, '--disable-background-networking'],
        });
        const pages = await browser.pages();
        const page = pages[0] || await browser.newPage();
        page.on('response', response => {
            try {
                const url = new URL(response.url());
                if (url.hostname === 'xluser-ssl.xunlei.com' && url.pathname === '/v1/auth/token') {
                    refreshHttpStatus = response.status();
                }
            } catch {
                // Only retain the HTTP status; never log request/response authentication data.
            }
        });
        page.on('request', request => {
            try {
                const headers = request.headers();
                const authorization = headers.authorization || headers.Authorization;
                if (!authorization || !/^Bearer\s+\S+$/i.test(authorization)) return;
                const token = authorization.replace(/^Bearer\s+/i, '').trim();
                remember(token, jwtPayload(token), 0, 'request');
            } catch {
                // Ignore unrelated browser requests.
            }
        });
        await page.goto('https://pan.xunlei.com/', { waitUntil: 'domcontentloaded', timeout: 45000 });
        await new Promise(resolve => setTimeout(resolve, 8000));
        // Ask the official web client to use its stored refresh_token. Merely observing
        // an old authenticated request is not a refresh and must not be treated as one.
        const refreshKey = await page.evaluate(() => {
            for (let index = 0; index < localStorage.length; index += 1) {
                const key = localStorage.key(index);
                if (!key || !key.startsWith('credentials_')) continue;
                try {
                    const value = JSON.parse(localStorage.getItem(key) || '{}');
                    if (value.refresh_token) {
                        value.expires_at = 0;
                        value.expires_in = 0;
                        localStorage.setItem(key, JSON.stringify(value));
                        return key;
                    }
                } catch {
                    // Ignore malformed application storage entries.
                }
            }
            return null;
        });
        refreshTokenPresent = Boolean(refreshKey);
        if (refreshKey) {
            candidates.clear();
            await page.reload({ waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => undefined);
            await new Promise(resolve => setTimeout(resolve, 15000));
        } else {
            await page.reload({ waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => undefined);
            await new Promise(resolve => setTimeout(resolve, 8000));
        }
        const storageCredentials = await page.evaluate(() => {
            const values = [];
            for (let index = 0; index < localStorage.length; index += 1) {
                const key = localStorage.key(index);
                if (!key || !key.startsWith('credentials_')) continue;
                try {
                    const value = JSON.parse(localStorage.getItem(key) || '{}');
                    if (value.access_token) {
                        values.push({
                            accessToken: value.access_token,
                            refreshTokenPresent: Boolean(value.refresh_token),
                            expiresAt: value.expires_at || value.expire_time || 0,
                            userId: value.user_id || value.sub || null,
                        });
                    }
                } catch {
                    // Ignore malformed application storage entries.
                }
            }
            return values;
        });
        storageCredentialCount = (storageCredentials || []).length;
        for (const value of storageCredentials || []) {
            remember(value.accessToken, jwtPayload(value.accessToken), normalizeExpiry(value.expiresAt), 'storage');
        }
    } finally {
        if (browser) await browser.close().catch(() => undefined);
    }

    const now = Date.now();
    const usable = [...candidates.values()]
        .filter(candidate => candidate.expiresAt > now)
        .sort((left, right) => right.expiresAt - left.expiresAt)[0];
    if (!usable) {
        const diagnostics = { candidateCount: candidates.size, storageCredentialCount, refreshTokenPresent, refreshHttpStatus };
        writeMeta({ status: 'failed', reason: 'no_usable_authenticated_token', ...diagnostics });
        console.log(`XUNLEI_EDGE_SYNC=no_usable_authenticated_token;CANDIDATES=${diagnostics.candidateCount};STORED=${storageCredentialCount};REFRESH_PRESENT=${refreshTokenPresent};REFRESH_HTTP=${refreshHttpStatus}`);
        process.exit(2);
    }

    const changed = usable.token !== currentToken;
    const expiresAt = usable.expiresAt || currentExpiry;
    const state = {
        ...current,
        token_type: 'Bearer',
        access_token: usable.token,
        expires_at: expiresAt,
        user_id: usable.claims.sub || current.user_id || null,
        client_id: current.client_id || usable.claims.aud || 'xunlei-open-api',
        device_id: current.device_id || 'gying-edge-session',
        client_version: current.client_version || '1.82.0',
        package_name: current.package_name || 'pan.xunlei.com',
    };
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, JSON.stringify(state), { encoding: 'utf8', mode: 0o600 });
    writeMeta({ status: changed ? 'updated' : 'unchanged', changed, expiresAt, source: usable.source });
    console.log(`XUNLEI_EDGE_SYNC=${changed ? 'updated' : 'unchanged'};EXPIRES=${new Date(expiresAt).toISOString()}`);
})().catch(error => {
    const type = error && error.name ? error.name : 'Error';
    writeMeta({ status: 'failed', reason: type });
    console.log(`XUNLEI_EDGE_SYNC=failed;TYPE=${type}`);
    process.exit(1);
});
