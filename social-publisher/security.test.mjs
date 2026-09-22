import test from 'node:test';
import assert from 'node:assert/strict';
import { authenticated, validateConfiguration } from './security.mjs';

test('empty tokens fail closed, including an explicitly empty request header', () => {
  assert.equal(authenticated('', ''), false);
  assert.equal(authenticated(undefined, undefined), false);
  assert.equal(authenticated('fixture', ''), false);
});
test('only the matching header credential is accepted', () => {
  assert.equal(authenticated('fixture', 'fixture'), true);
  assert.equal(authenticated('fixture', 'other'), false);
  assert.equal(authenticated('fixture', ['fixture']), false);
});
test('production integration credentials are required and root is forbidden', () => {
  assert.throws(() => validateConfiguration({}));
  assert.throws(() => validateConfiguration({ SOCIAL_PUBLISHER_TOKEN: 'x'.repeat(32), DB_USER: 'root', DB_PASSWORD: 'fixture' }));
  assert.doesNotThrow(() => validateConfiguration({ SOCIAL_PUBLISHER_TOKEN: 'x'.repeat(32), DB_USER: 'gying_social', DB_PASSWORD: 'fixture' }));
});
