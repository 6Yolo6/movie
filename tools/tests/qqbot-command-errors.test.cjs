const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const installer = fs.readFileSync(path.join(__dirname, '..', 'patch-openclaw-qqbot-gying.ps1'), 'utf8');
function installerHereString(name) {
  const match = installer.match(new RegExp('\\$' + name + " = @'\\r?\\n([\\s\\S]*?)\\r?\\n'@"));
  assert.ok(match, 'installer must define ' + name);
  return match[1].replace(/\r\n/g, '\n');
}
const fallback = installerHereString('slashCommandErrorFallback');
const terminal = installerHereString('slashCommandErrorTerminal');

// The upstream gateway's relevant control flow, with all external services supplied
// by the test. Keep the null/delegate/direct-send paths to test the error boundary.
const legacyGateway = [
  'async function trySlashCommandOrEnqueue(msg) {',
  '    const content = (msg.content ?? "").trim();',
  '    const cmdCtx = { rawContent: content, args: "" };',
  '    try {',
  '        const reply = await matchSlashCommand(cmdCtx);',
  '        if (reply === null) {',
  '            msgQueue.enqueue(msg);',
  '            return;',
  '        }',
  '        const isDelegateResult = typeof reply === "object" && reply !== null && "delegatePrompt" in reply;',
  '        if (isDelegateResult) {',
  '            msg.content = reply.delegatePrompt;',
  '            msgQueue.enqueue(msg);',
  '            return;',
  '        }',
  '        log?.info?.("plugin command matched");',
  '        const token = await getAccessToken(account.appId, account.clientSecret);',
  '        await sendGroupMessage(token, msg.groupOpenid, reply, msg.messageId);',
  '    }',
  fallback,
  '}',
  '({ trySlashCommandOrEnqueue })',
].join('\n');
const patchedGateway = legacyGateway.replace(fallback, terminal);

function loadGateway(source, options = {}) {
  const events = { matches: 0, sends: 0, errors: [], queued: [] };
  const api = vm.runInNewContext(source, {
    account: { accountId: 'fixture', appId: 'fixture', clientSecret: 'fixture' },
    log: { info() {}, error(error) { events.errors.push(String(error)); } },
    matchSlashCommand: async () => {
      events.matches++;
      if (options.matchError) throw options.matchError;
      return Object.hasOwn(options, 'reply') ? options.reply : 'fixture search result';
    },
    getAccessToken: async () => {
      if (options.tokenError) throw options.tokenError;
      return 'fixture';
    },
    sendGroupMessage: async () => {
      events.sends++;
      if (options.sendError) throw options.sendError;
    },
    msgQueue: { enqueue(message) { events.queued.push({ ...message }); } },
  });
  return { ...api, events };
}
function searchMessage() {
  return {
    type: 'group',
    content: '搜 测试影片',
    senderId: 'fixture',
    groupOpenid: 'fixture',
    messageId: 'fixture',
  };
}
async function run(options = {}, source = patchedGateway) {
  const gateway = loadGateway(source, options);
  await gateway.trySlashCommandOrEnqueue(searchMessage());
  return gateway.events;
}

test('a successfully matched search replies directly without entering the agent queue', async () => {
  const events = await run();
  assert.equal(events.matches, 1);
  assert.equal(events.sends, 1);
  assert.equal(events.errors.length, 0);
  assert.equal(events.queued.length, 0);
});

for (const [name, options] of [
  ['QQ fetch failure', { sendError: new TypeError('fetch failed') }],
  ['QQ content rejection', { sendError: new Error('QQ message rejected: content policy') }],
  ['QQ token acquisition failure', { tokenError: new Error('QQ authentication unavailable') }],
  ['plugin matcher exception', { matchError: new Error('plugin search failed') }],
]) {
  test(name + ' is logged and cannot trigger an unrelated model call', async () => {
    const events = await run(options);
    assert.equal(events.matches, 1);
    assert.equal(events.errors.length, 1);
    assert.equal(events.queued.length, 0);
  });
}

test('the fixture reproduces the original send-failure-to-agent regression', async () => {
  const events = await run({ sendError: new TypeError('fetch failed') }, legacyGateway);
  assert.equal(events.sends, 1);
  assert.equal(events.errors.length, 1);
  assert.equal(events.queued.length, 1);
  assert.equal(events.queued[0].content, '搜 测试影片');
});

test('an unmatched command still enters the existing agent queue', async () => {
  const events = await run({ reply: null });
  assert.equal(events.sends, 0);
  assert.equal(events.errors.length, 0);
  assert.equal(events.queued.length, 1);
  assert.equal(events.queued[0].content, '搜 测试影片');
});

test('an explicit delegate result still queues its processed prompt', async () => {
  const events = await run({ reply: { delegatePrompt: 'fixture delegated prompt' } });
  assert.equal(events.sends, 0);
  assert.equal(events.errors.length, 0);
  assert.equal(events.queued.length, 1);
  assert.equal(events.queued[0].content, 'fixture delegated prompt');
});

const patchStart = installer.indexOf('$slashCommandErrorFallback =');
const patchEnd = installer.indexOf('[System.IO.File]::WriteAllText($gatewayPath', patchStart);
assert.ok(patchStart >= 0 && patchEnd > patchStart, 'error patch must precede writing the gateway');
const patchCode = installer.slice(patchStart, patchEnd);
const powershell = ['pwsh', 'powershell.exe'].find(executable => {
  const result = spawnSync(executable, ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.ToString()'], {
    encoding: 'utf8', windowsHide: true,
  });
  return result.status === 0;
});
function applyInstallerPatch(source, count = 1) {
  const base64 = Buffer.from(source, 'utf8').toString('base64');
  const command = [
    '$ErrorActionPreference = "Stop"',
    "$gatewayContent = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + base64 + "'))",
    ...Array.from({ length: count }, () => patchCode),
    '[System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($gatewayContent))',
  ].join('\n');
  const result = spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-Command', command], {
    encoding: 'utf8', windowsHide: true,
  });
  return {
    ...result,
    source: result.status === 0 ? Buffer.from(result.stdout.trim(), 'base64').toString('utf8').replace(/\r\n/g, '\n') : null,
  };
}

test('the real PowerShell installer replaces only the error fallback and remains idempotent', {
  skip: !powershell && 'PowerShell is unavailable',
}, () => {
  const once = applyInstallerPatch(legacyGateway);
  assert.equal(once.status, 0, once.stderr);
  assert.equal(once.source, patchedGateway);
  const windowsSource = applyInstallerPatch(legacyGateway.replace(/\n/g, '\r\n'));
  assert.equal(windowsSource.status, 0, windowsSource.stderr);
  assert.equal(windowsSource.source, once.source);
  const twice = applyInstallerPatch(legacyGateway, 2);
  assert.equal(twice.status, 0, twice.stderr);
  assert.equal(twice.source, once.source);
  assert.equal((twice.source.match(/msgQueue\.enqueue\(msg\)/g) ?? []).length, 2,
    'the unmatched and delegate branches must remain');
});

test('the real installer refuses an unknown upstream error handler before a runtime write', {
  skip: !powershell && 'PowerShell is unavailable',
}, () => {
  const changedUpstream = legacyGateway.replace(fallback, [
    '    catch (err) {',
    '        log?.error?.("upstream handler changed");',
    '        msgQueue.enqueue(msg);',
    '    }',
  ].join('\n'));
  const result = applyInstallerPatch(changedUpstream);
  assert.notEqual(result.status, 0);
  assert.equal(result.source, null);
  assert.match(result.stderr, /gateway|fallback|command|handler|patch/i);
});
