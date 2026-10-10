const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../frontend/node_modules/typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../../frontend/src/lib/socialPublishing.ts'), 'utf8');
const exported = {};
vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText, { exports: exported });
const { SOCIAL_POST_STATUSES, canRetrySocialPost, socialPostStatusColor } = exported;

test('only pending or proven preparation failure permits retry', () => {
    for (const status of ['PENDING', 'PREPARE_FAILED']) assert.equal(canRetrySocialPost(status), true);
});

test('in-flight, uncertain, posted and legacy failed outcomes are protected', () => {
    for (const status of ['PREPARING', 'PUBLISHING', 'UNKNOWN', 'POSTED', 'FAILED']) {
        assert.equal(canRetrySocialPost(status), false, status);
    }
});

test('unknown, missing and malformed statuses fail closed', () => {
    for (const status of [undefined, null, '', 'FUTURE_STATE', 'pending', 'PENDING ', 0, {}, ['PENDING']]) {
        assert.equal(canRetrySocialPost(status), false);
    }
});

test('status colors distinguish preparation failure from uncertain outcomes', () => {
    assert.equal(socialPostStatusColor('POSTED'), 'green');
    assert.equal(socialPostStatusColor('PENDING'), 'blue');
    assert.equal(socialPostStatusColor('PREPARE_FAILED'), 'red');
    for (const status of ['PREPARING', 'PUBLISHING']) assert.equal(socialPostStatusColor(status), 'processing');
    for (const status of ['UNKNOWN', 'FAILED', 'FUTURE_STATE', undefined]) {
        assert.equal(socialPostStatusColor(status), 'orange');
    }
});

test('English and Chinese explain every selectable state and the retry policy', () => {
    assert.deepEqual(Array.from(SOCIAL_POST_STATUSES), [
        'PENDING', 'PREPARING', 'PUBLISHING', 'POSTED', 'PREPARE_FAILED', 'UNKNOWN', 'FAILED',
    ]);
    for (const lang of ['en', 'zh']) {
        const t = JSON.parse(fs.readFileSync(path.resolve(__dirname,
            '../../frontend/public/locales/' + lang + '/common.json'), 'utf8'));
        for (const status of SOCIAL_POST_STATUSES) assert.ok(t.socialPublishingStatus[status], lang + ': ' + status);
        for (const key of ['socialPublishingRetryPolicy', 'socialPublishingRetryBlocked',
            'socialPublishingResultRecorded', 'socialPublishingProcessing', 'socialPublishingUnknown']) {
            assert.equal(typeof t[key], 'string');
            assert.ok(t[key].length > 0);
        }
    }
});
