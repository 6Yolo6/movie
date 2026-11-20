import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

export async function publishQqPost(row, content, posterPath, {
  beforeSend, resolveDestination, run, environment, parseOutput, onCleanupError = () => {}, files = fs,
}) {
  if (typeof beforeSend !== 'function') throw new Error('Publication boundary is required');
  const destination = await resolveDestination(row);
  const contentFile = path.join(os.tmpdir(), `gying-social-${randomUUID()}.txt`);
  // Exclusive creation: concurrent publications cannot share or overwrite their content file.
  const handle = await files.open(contentFile, 'wx', 0o600);
  try {
    await handle.writeFile(content, 'utf8');
    await handle.close();
    const args = ['feed', 'publish-feed', '--guild-id', String(destination.guildId),
      '--channel-id', String(destination.channelId), '--content-file', contentFile];
    if (posterPath) args.push('--image', posterPath);
    args.push('--json');
    await beforeSend();
    const result = parseOutput(await run('tencent-channel-cli', args, { env: environment(row.account_key) }));
    if (!result.success) throw new Error('QQ channel rejected or did not confirm the publication');
    return { externalUrl: result.data?.share_url || null, result };
  } finally {
    // Cleanup failure must not turn an acknowledged publication into a retryable failure.
    try { await handle.close(); } catch (error) { onCleanupError(error); }
    try { await files.rm(contentFile, { force: true }); } catch (error) { onCleanupError(error); }
  }
}
