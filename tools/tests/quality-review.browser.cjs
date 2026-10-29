/* Synthetic offline browser regression. Never edits or scores real observations. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const {pathToFileURL} = require('node:url');
const {execFileSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const repo = path.resolve(__dirname, '../..');
const output = fs.mkdtempSync(path.join(process.env.QUALITY_REVIEW_OUTPUT_DIR || os.tmpdir(), 'gying-quality-browser-'));
const python = process.env.PYTHON || 'python';
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const digest = value => crypto.createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const write = (name, value) => {const file = path.join(output, name); fs.writeFileSync(file, JSON.stringify(value, null, 2)); return file;};
const clone = value => JSON.parse(JSON.stringify(value));
const runPython = args => execFileSync(python, ['-X', 'utf8', ...args], {cwd: repo, encoding: 'utf8'});

function fixtures() {
  const dataset = {schemaVersion: 2, version: 'synthetic-browser-only', provenance: 'agent-authored-regression', cases: []};
  for (let i = 1; i <= 44; i++) dataset.cases.push({id: `h${String(i).padStart(2, '0')}`, number: i, category: '合成界面测试', sourceGroup: `synthetic-${i}`, split: 'development', annotation: '仅测试评分交互，不是影片质量观测。', userRubric: '合成测试口径', turns: [{message: `合成问题 ${i}，请保留限制。`, useHistory: false}]});
  const report = {reportSchemaVersion: 2, datasetSha256: digest(dataset), datasetVersion: dataset.version, evaluatedAt: '2026-10-28T10:00:00Z', summary: {tokens: null, monetaryCost: null}, rows: []};
  const observations = [];
  for (let rep = 1; rep <= 2; rep++) for (const c of dataset.cases) {
    const row = {caseId: c.id, repetition: rep, turn: 1, executed: true, passed: c.number <= 42, route: 'synthetic', jobStatus: c.number <= 42 ? 'SUCCEEDED' : 'FAILED'};
    if (row.passed) {
      const answer = `合成回答 ${c.number}/${rep} </script><img src=https://invalid.example/ onerror=alert(1)>`;
      row.answerSha256 = digest(answer);
      observations.push({caseId: c.id, repetition: rep, turn: 1, answerSha256: row.answerSha256, answer, movies: [{id: `fixture-${c.number}`, titleCn: '合成候选', year: 2020}], catalog: {filters: {year: 2020}}, queryPlan: {category: 'movie', inheritContext: false}, evidence: {unverifiedConditions: ['合成未知条件'], webResults: [{snippet: '</script><img src=https://invalid.example/ onerror=alert(1)>'}], retrieval: {method: 'rrf'}}, trace: [{type: 'planning', status: 'completed'}]});
    } else row.failure = 'SYNTHETIC_JOB_FAILED';
    report.rows.push(row);
  }
  const paths = {dataset: write('dataset.json', dataset), report: write('report.json', report), observations: write('observations.json', {reportSha256: digest(report), observations}), html: path.join(output, 'review.html')};
  runPython(['tools/ai/build_quality_review_page.py', '--dataset', paths.dataset, '--report', paths.report, '--observations', paths.observations, '--output', paths.html]);
  return paths;
}

(async () => {
  const paths = fixtures();
  const browser = await chromium.launch({channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true});
  const checks = [];
  try {
    const context = await browser.newContext({viewport: {width: 1280, height: 900}, acceptDownloads: true, serviceWorkers: 'block'});
    const network = [], errors = [];
    await context.route(/^https?:/, async route => {network.push(route.request().url()); await route.abort();});
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(pathToFileURL(paths.html).href);
    await page.waitForFunction(() => !!window.qualityReviewView);
    const getReview = () => page.evaluate(() => window.qualityReviewView.getReview());
    const initial = await getReview();
    assert.equal(initial.reviews.length, 88);
    assert.equal(initial.reviews.filter(row => row.answerSha256).length, 84);
    assert.equal(initial.reviews.filter(row => !row.answerSha256).length, 4);
    assert.equal(initial.reviews.filter(row => Object.values(row.scores).some(score => score !== null)).length, 0);
    assert.equal(await page.locator('[data-key]').count(), 88);
    assert.equal(await page.locator('img').count(), 0);
    checks.push('84 synthetic answers and four failures start pending; hostile answer is text');

    await page.locator('#reviewer-kind').selectOption('human');
    await page.locator('#reviewer-name').fill('Synthetic UI tester');
    const card = page.locator('[data-key="h01:1:1"]');
    const evidenceText = await card.locator('details pre').textContent();
    for (const expected of ['queryPlan', 'inheritContext', '合成未知条件', 'webResults', 'rrf', 'planning']) assert(evidenceText.includes(expected), `missing structured evidence: ${expected}`);
    assert.equal(await card.locator('img').count(), 0);
    checks.push('structured query plan, uncertainty, web evidence and trace render as inert text');
    await card.locator('[data-field="factuality"]').selectOption('2');
    await card.locator('[data-field="grounding"]').selectOption('1');
    await card.locator('[data-field="usefulness"]').selectOption('1');
    await card.locator('[data-field="hardConstraints"]').selectOption('satisfied');
    await card.locator('[data-field="outcome"]').selectOption('usefulApproximation');
    await card.locator('[data-field="failureCategories"][value="candidates"]').check();
    await card.locator('[data-field="notes"]').fill('Synthetic test: hard constraints supported; soft preference remains unmet.');
    const edited = await getReview();
    assert.deepEqual(edited.reviews[0].scores, {factuality: 2, grounding: 1, usefulness: 1});
    assert.deepEqual(edited.reviews[0].failureCategories, ['candidates']);
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download').click();
    const download = await downloadPromise;
    const exportedPath = path.join(output, 'synthetic-export.json');
    await download.saveAs(exportedPath);
    const exported = JSON.parse(fs.readFileSync(exportedPath, 'utf8'));
    assert.deepEqual(exported, edited);
    const summaryPath = path.join(output, 'synthetic-summary.json');
    runPython(['tools/ai/quality_review.py', '--report', paths.report, '--review', exportedPath, '--output', summaryPath]);
    const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
    assert.equal(summary.humanReviewedAnswers, 1);
    assert.equal(summary.pendingAnswers, 83);
    assert.equal(summary.outcomeCounts.usefulApproximation, 1);
    assert.equal(summary.failureCategoryCounts.candidates, 1);
    checks.push('score, diagnosis and attributed export accepted by Python summarizer');

    await page.reload();
    await page.waitForFunction(() => !!window.qualityReviewView);
    await page.locator('#import-review').setInputFiles(exportedPath);
    await page.waitForFunction(() => document.getElementById('status').dataset.state === 'success');
    assert.deepEqual(await getReview(), exported);
    assert.equal(await page.locator('[data-key="h01:1:1"] [data-field="factuality"]').inputValue(), '2');
    assert.equal(await page.locator('[data-key="h01:1:1"] [data-field="notes"]').inputValue(), exported.reviews[0].notes);
    checks.push('JSON import resumes all fields after reload');

    for (const [filter, count] of [['failed', 4], ['reviewed', 1], ['pending', 87], ['all', 88]]) {
      await page.locator('#filter').selectOption(filter);
      assert.equal(await page.locator('[data-key]').count(), count);
    }
    assert.deepEqual(await getReview(), exported);
    checks.push('filters preserve all review data and use distinct failure/review/pending counts');

    const mutations = {
      report: review => {review.reportSha256 = 'f'.repeat(64);},
      answer: review => {review.reviews[0].answerSha256 = 'f'.repeat(64);},
      duplicate: review => {review.reviews.push(clone(review.reviews[0]));},
      missing: review => {review.reviews.pop();},
      boolean: review => {review.reviews[0].scores.factuality = true;},
      categoryArray: review => {review.reviews[0].failureCategories = [['facts']];},
      failedScore: review => {review.reviews.find(row => !row.answerSha256).scores.factuality = 0;},
      noAttribution: review => {review.reviewer = {kind: 'unassigned', name: ''};},
      hardViolation: review => {review.reviews[0].hardConstraints = 'violated';}
    };
    for (const [name, mutate] of Object.entries(mutations)) {
      const invalid = clone(exported); mutate(invalid);
      await page.evaluate(() => {document.getElementById('status').dataset.state = '';});
      await page.locator('#import-review').setInputFiles(write(`invalid-${name}.json`, invalid));
      await page.waitForFunction(() => document.getElementById('status').dataset.state === 'error');
      assert.deepEqual(await getReview(), exported, `invalid ${name} replaced current state`);
      checks.push(`invalid ${name} import rejected without changing current review`);
    }
    const failure = page.locator('[data-key="h43:1:1"]');
    const failureScores = failure.locator('[data-field="factuality"]');
    assert(await failureScores.count() === 0 || await failureScores.isDisabled());
    checks.push('failed rows do not accept answer scores');

    const modelReview = clone(exported); modelReview.reviewer = {kind: 'model', name: 'Synthetic model fixture'};
    const modelPath = write('synthetic-model-review.json', modelReview);
    await page.locator('#import-review').setInputFiles(modelPath);
    await page.waitForFunction(() => document.getElementById('status').dataset.state === 'success');
    assert((await page.locator('#count').textContent()).includes('模型复核'));
    const modelSummaryPath = path.join(output, 'synthetic-model-summary.json');
    runPython(['tools/ai/quality_review.py', '--report', paths.report, '--review', modelPath, '--output', modelSummaryPath]);
    const modelSummary = JSON.parse(fs.readFileSync(modelSummaryPath, 'utf8'));
    assert.equal(modelSummary.humanReviewedAnswers, 0);
    assert.equal(modelSummary.modelReviewedAnswers, 1);
    assert.equal(modelSummary.humanPendingAnswers, 84);
    await page.evaluate(() => {document.getElementById('status').dataset.state = '';});
    await page.locator('#import-review').setInputFiles(exportedPath);
    await page.waitForFunction(() => document.getElementById('status').dataset.state === 'success');
    checks.push('model attribution remains separate from human completion in UI and summary');

    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    await page.screenshot({path: path.join(output, 'review-mobile.png'), fullPage: false});
    await page.setViewportSize({width: 1280, height: 900});
    await page.screenshot({path: path.join(output, 'review-desktop.png'), fullPage: false});
    assert.deepEqual(network, []);
    assert.deepEqual(errors, []);
    checks.push('mobile layout, no external requests and no browser errors');
    await context.close();
  } finally {await browser.close();}
  const result = {syntheticOnly: true, status: 'passed', checks, output};
  write('result.json', result);
  console.log(JSON.stringify(result, null, 2));
})().catch(error => {console.error(error); process.exitCode = 1;});
