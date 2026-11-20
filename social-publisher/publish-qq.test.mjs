import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs/promises';
import { publishQqPost } from './publish-qq.mjs';

const row = { account_key: 'fixture' };
const destination = async () => ({ guildId: 'fixture-guild', channelId: 'fixture-channel' });
const environment = key => ({ fixtureAccount: key });
const success = JSON.stringify({ success: true, data: { share_url: 'https://example.test/qq/post' } });

test('parallel posts keep independent complete UTF-8 files and enter the gate before the CLI', async () => {
  const paths = [], bodies = []; let gated = 0;
  const options = { beforeSend: async () => { gated++; }, resolveDestination: destination, environment, parseOutput: JSON.parse,
    run: async (command, args, options) => {
      assert.ok(gated > 0); assert.equal(command, 'tencent-channel-cli');
      assert.deepEqual(args.slice(0, 2), ['feed', 'publish-feed']);
      assert.equal(options.env.fixtureAccount, 'fixture');
      assert.equal(args[args.indexOf('--image') + 1], 'fixture-poster.jpg');
      const file = args[args.indexOf('--content-file') + 1]; paths.push(file);
      bodies.push(await fs.readFile(file, 'utf8'));
      return success;
    } };
  const contents = ['完整正文甲'.repeat(100), '完整正文乙'.repeat(100)];
  const results = await Promise.all(contents.map(content => publishQqPost(row, content, 'fixture-poster.jpg', options)));
  assert.equal(new Set(paths).size, 2); assert.deepEqual(bodies.sort(), contents.sort());
  assert.equal(gated, 2); assert.ok(results.every(result => result.externalUrl === 'https://example.test/qq/post'));
  for (const file of paths) await assert.rejects(fs.access(file), { code: 'ENOENT' });
});

test('a rejected gate never runs the external publication and still cleans its file', async () => {
  const filesCreated = [];
  const files = { open: async (...args) => { filesCreated.push(args[0]); return fs.open(...args); }, rm: fs.rm };
  await assert.rejects(publishQqPost(row, 'fixture content', null, {
    beforeSend: async () => { throw new Error('claim rejected'); }, resolveDestination: destination, environment,
    parseOutput: JSON.parse, files, run: () => assert.fail('must not publish'),
  }), /claim rejected/);
  assert.equal(filesCreated.length, 1);
  await assert.rejects(fs.access(filesCreated[0]), { code: 'ENOENT' });
});

test('cleanup errors cannot invalidate an acknowledged publication', async () => {
  const errors = [];
  const published = await publishQqPost(row, 'fixture', null, {
    beforeSend: async () => {}, resolveDestination: destination, environment, parseOutput: JSON.parse,
    run: async () => success, onCleanupError: error => errors.push(error),
    files: { open: async () => ({ writeFile: async () => {}, close: async () => {} }),
      rm: async () => { throw new Error('fixture cleanup failure'); } },
  });
  assert.equal(published.externalUrl, 'https://example.test/qq/post'); assert.equal(errors.length, 1);
});

test('publication requires an explicit boundary and destination preparation precedes it', async () => {
  await assert.rejects(publishQqPost(row, 'fixture', null, {}), /boundary is required/);
  await assert.rejects(publishQqPost(row, 'fixture', null, {
    beforeSend: () => assert.fail('must not cross boundary'),
    resolveDestination: async () => { throw new Error('not joined'); },
    run: () => assert.fail('must not publish'),
  }), /not joined/);
});
