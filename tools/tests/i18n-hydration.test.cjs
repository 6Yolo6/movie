const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../frontend/node_modules/typescript');
const compiled = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '../../frontend/i18n.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;

function fixture({ server = false, saved = null, language = 'en-US', storageUnavailable = false } = {}) {
    const exported = {}, listeners = [], changes = [], stored = [];
    const document = { documentElement: { lang: 'en' } };
    let options;
    const i18n = {
        use() { return this; },
        init(value) { options = value; return Promise.resolve(); },
        on(event, listener) { assert.equal(event, 'languageChanged'); listeners.push(listener); },
        changeLanguage(value) { changes.push(value); listeners.forEach(listener => listener(value)); return Promise.resolve(); },
    };
    const window = server ? undefined : {
        navigator: { language },
        localStorage: {
            getItem() { if (storageUnavailable) throw new Error('Storage unavailable'); return saved; },
            setItem(key, value) { if (storageUnavailable) throw new Error('Storage unavailable'); stored.push([key, value]); },
        },
    };
    vm.runInNewContext(compiled, { exports: exported, window, document, require(id) {
        if (id === 'i18next') return { default: i18n };
        if (id === 'react-i18next') return { initReactI18next: {} };
        if (id.endsWith('/common.json')) return { default: {} };
        throw new Error('Unexpected dependency: ' + id);
    } });
    return { ...exported, options, document, i18n, changes, stored };
}

test('server language is deterministic and does not access browser state', () => {
    const f = fixture({ server: true });
    assert.equal(f.options.lng, 'en');
    assert.equal(f.restoreClientLanguage(), undefined);
    assert.deepEqual(f.changes, []);
});

test('saved Chinese does not alter the first client render or get overwritten before hydration', async () => {
    const f = fixture({ saved: 'zh', language: 'zh-CN' });
    assert.equal(f.options.lng, 'en');
    assert.deepEqual(f.stored, []); assert.deepEqual(f.changes, []);
    await f.restoreClientLanguage();
    assert.deepEqual(f.changes, ['zh']);
    assert.equal(f.document.documentElement.lang, 'zh-CN');
    assert.deepEqual(f.stored, [['i18nextLng', 'zh']]);
});

test('post-hydration restoration prefers saved language and otherwise uses browser language', async () => {
    for (const [saved, language, expected] of [[null, 'zh-CN', 'zh'], [null, 'en-US', 'en'], ['en', 'zh-CN', 'en']]) {
        const f = fixture({ saved, language });
        await f.restoreClientLanguage();
        assert.deepEqual(f.changes, [expected]);
    }
});

test('denied browser storage does not break hydration or language restoration', async () => {
    const f = fixture({ storageUnavailable: true, language: 'zh-CN' });
    assert.equal(f.options.lng, 'en');
    await f.restoreClientLanguage();
    assert.deepEqual(f.changes, ['zh']);
    assert.equal(f.document.documentElement.lang, 'zh-CN');
});

test('subsequent language changes still persist preference and update document language', async () => {
    const f = fixture();
    await f.i18n.changeLanguage('zh'); await f.i18n.changeLanguage('en');
    assert.deepEqual(f.stored, [['i18nextLng', 'zh'], ['i18nextLng', 'en']]);
    assert.equal(f.document.documentElement.lang, 'en');
});
