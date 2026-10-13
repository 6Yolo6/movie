import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

const require = createRequire(import.meta.url);
const frontend = fileURLToPath(new URL('../../', import.meta.url));

function bootstrap(modules, suite) {
    // Only fixture modules run: no Next server, persisted auth, dotenv or real API.
    window.fetch = () => { throw new Error('Network access is forbidden in this fixture'); };
    window.IS_REACT_ACT_ENVIRONMENT = true;
    const cache = {};
    const process = { env: { NODE_ENV: 'development' } };
    function load(id) {
        if (!cache[id]) {
            const record = { exports: {} };
            cache[id] = record;
            if (!modules[id]) throw new Error('Unexpected fixture dependency: ' + id);
            modules[id](record, record.exports, load, process);
        }
        return cache[id].exports;
    }
    const runtime = {
        React: load('react'),
        createRoot: load('react-dom/client').createRoot,
        readApiError: load('api').readApiError,
        loadPage(id, mocks) {
            const record = { exports: {} };
            modules[id](record, record.exports, name => Object.hasOwn(mocks, name) ? mocks[name] : load(name), process);
            return record.exports.default;
        },
    };
    const finish = result => {
        document.body.replaceChildren();
        const output = document.createElement('pre');
        output.id = 'fixture-result';
        output.textContent = JSON.stringify(result);
        document.body.append(output);
    };
    suite(runtime).then(finish, error => finish({ fatal: error.stack || String(error) }));
}

export async function runOfflineReact(suite) {
    const browser = [
        process.env.FRONTEND_TEST_BROWSER,
        'C:/Program Files/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
        'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
        '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome',
        '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    ].find(candidate => candidate && existsSync(candidate));
    assert.ok(browser, 'An existing Chromium browser is required; set FRONTEND_TEST_BROWSER. Nothing is installed automatically.');

    const modules = {};
    for (const [name, file] of Object.entries({
        react: 'react/cjs/react.development.js',
        'react/jsx-runtime': 'react/cjs/react-jsx-runtime.development.js',
        'react-dom': 'react-dom/cjs/react-dom.development.js',
        'react-dom/client': 'react-dom/cjs/react-dom-client.development.js',
        scheduler: 'scheduler/cjs/scheduler.development.js',
    })) {
        const packageName = file.split('/')[0];
        const packageRoot = path.dirname(require.resolve(packageName + '/package.json'));
        modules[name] = await readFile(path.join(packageRoot, file.slice(packageName.length + 1)), 'utf8');
    }
    for (const [name, file] of Object.entries({
        hot: 'src/app/hot/page.tsx', notifications: 'src/app/notifications/page.tsx', api: 'src/lib/api.ts',
    })) {
        const source = await readFile(path.join(frontend, file), 'utf8');
        modules[name] = ts.transpileModule(source, { compilerOptions: {
            module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020,
            jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
        } }).outputText;
    }
    const definitions = Object.entries(modules).map(([name, source]) =>
        JSON.stringify(name) + ': function(module, exports, require, process) {\n' + source + '\n}'
    ).join(',\n');
    const script = '(' + bootstrap.toString() + ')({' + definitions + '}, ' + suite.toString() + ');';
    const html = '<!doctype html><html><head><meta charset="utf-8">' +
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; connect-src \'none\'">' +
        '</head><body><script>' + script.replace(/<\/script/gi, '<\\/script') + '</script></body></html>';
    // The temporary profile and HTML stay under frontend, never use a real browser profile.
    const directory = await mkdtemp(path.join(frontend, '.async-list-test-'));
    try {
        const file = path.join(directory, 'fixture.html');
        await writeFile(file, html);
        const output = await new Promise((resolve, reject) => {
            const child = spawn(browser, [
                '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
                '--disable-extensions', '--disable-background-networking', '--disable-component-update',
                '--disable-sync', '--metrics-recording-only', '--no-proxy-server',
                '--host-resolver-rules=MAP * ~NOTFOUND', '--disable-crash-reporter',
                '--user-data-dir=' + path.join(directory, 'profile'),
                '--dump-dom', '--virtual-time-budget=15000', pathToFileURL(file).href,
            ], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
            let stdout = '';
            const timer = setTimeout(() => { child.kill(); reject(new Error('Offline browser fixture timed out')); }, 45000);
            child.stdout.setEncoding('utf8');
            child.stdout.on('data', chunk => { stdout += chunk; });
            child.once('error', error => { clearTimeout(timer); reject(error); });
            child.once('close', code => {
                clearTimeout(timer);
                if (code !== 0) reject(new Error('Offline browser exited with code ' + code));
                else resolve(stdout);
            });
        });
        const match = output.match(/<pre id="fixture-result">([\s\S]*?)<\/pre>/);
        assert.ok(match, 'Offline React fixture did not produce a result (' + output.length + ' bytes): ' + output.slice(-1200));
        const result = JSON.parse(match[1].replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&'));
        assert.ok(!result.fatal, result.fatal);
        return result;
    } finally {
        // Verify the exact, newly created workspace child before recursive cleanup.
        assert.equal(path.dirname(path.resolve(directory)), path.resolve(frontend));
        assert.ok(path.basename(directory).startsWith('.async-list-test-'));
        await rm(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    }
}
