import { createHash, timingSafeEqual } from 'node:crypto';

export function authenticated(expected, supplied) {
  if (typeof expected !== 'string' || !expected || typeof supplied !== 'string' || !supplied) return false;
  const digest = value => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(expected), digest(supplied));
}

export function validateConfiguration(env) {
  if (Buffer.byteLength(env.SOCIAL_PUBLISHER_TOKEN || '') < 32) {
    throw new Error('SOCIAL_PUBLISHER_TOKEN must contain at least 32 bytes');
  }
  if (!env.DB_USER || env.DB_USER.toLowerCase() === 'root' || !env.DB_PASSWORD) {
    throw new Error('Configure non-root DB_USER and DB_PASSWORD');
  }
}
