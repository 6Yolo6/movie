'use strict';
(() => {
  const payload = JSON.parse(document.getElementById('data').textContent);
  let review = structuredClone(payload.review), dirty = false;
  const scores = ['factuality', 'grounding', 'usefulness'];
  const failureLabels = {routing:'分流',conditions:'条件',candidates:'候选',facts:'事实',clarification:'澄清',multiTurn:'多轮'};
  const constraints = {pending:'待核对',satisfied:'已满足',violated:'明确违规',unknown:'证据不足',notApplicable:'没有适用的硬条件'};
  const outcomes = {pending:'待判断',exact:'满足需求',usefulApproximation:'有用近似',clarification:'澄清 / 无解说明',unhelpful:'未满足需求'};
  const key = row => [row.caseId, row.repetition, row.turn].join(':');
  const rows = new Map(payload.report.rows.map(row => [key(row), row]));
  const expected = new Map(payload.template.reviews.map(row => [key(row), row]));
  const observations = new Map(payload.observations.map(row => [key(row), row]));
  const cases = new Map(payload.dataset.cases.map(row => [row.id, row]));
  const turnMessage = turn => turn.message ?? turn.request?.message ?? '';
  const root = document.getElementById('cards');
  const status = document.getElementById('status');
  const kind = document.getElementById('reviewer-kind'), name = document.getElementById('reviewer-name');
  const complete = row => scores.every(s => row.scores[s] !== null);
  const diagnosed = row => row.notes.trim() && (row.failureCategories.length || (row.outcome !== 'pending' && row.hardConstraints !== 'pending'));
  const scorable = row => row.executed && row.answerSha256 != null;
  const sameFields = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join('|') === [...fields].sort().join('|');
  function reject(message) { throw new Error(message); }
  function validateReview(value) {
    if (!sameFields(value,['schemaVersion','reportSha256','reviewer','reviews']) || value.schemaVersion !== 2 || value.reportSha256 !== payload.template.reportSha256 || !Array.isArray(value.reviews)) reject('评分文件必须绑定本次报告，且使用 v2 格式。');
    const author = value.reviewer;
    if (!sameFields(author,['kind','name']) || !['unassigned','human','model'].includes(author.kind) || typeof author.name !== 'string' || author.name.length > 120) reject('评分来源或复核者称呼无效。');
    if (author.kind === 'unassigned' ? author.name !== '' : !author.name.trim()) reject('请选择评分来源并填写复核者称呼。');
    const seen = new Set();
    for (const row of value.reviews) {
      if (!sameFields(row,Object.keys(payload.template.reviews[0])) || typeof row.caseId !== 'string' || !Number.isInteger(row.repetition) || !Number.isInteger(row.turn)) reject('评分记录字段或身份无效。');
      const id = key(row), original = expected.get(id), reportRow = rows.get(id);
      if (!original || seen.has(id) || original.answerSha256 !== row.answerSha256) reject('评分记录重复，或回答与本次报告不匹配。');
      seen.add(id);
      if (!sameFields(row.scores,scores) || scores.some(s => row.scores[s] !== null && (!Number.isInteger(row.scores[s]) || row.scores[s] < 0 || row.scores[s] > 2))) reject('三维分数只能是 0、1、2 或待评。');
      if (!Array.isArray(row.failureCategories) || row.failureCategories.some(x => typeof x !== 'string' || !Object.hasOwn(failureLabels,x)) || new Set(row.failureCategories).size !== row.failureCategories.length) reject('失败分类无效或重复。');
      if (typeof row.hardConstraints !== 'string' || !Object.hasOwn(constraints,row.hardConstraints) || typeof row.outcome !== 'string' || !Object.hasOwn(outcomes,row.outcome)) reject('硬条件状态或结论无效。');
      if (typeof row.notes !== 'string' || row.notes.length > 5000) reject('备注最长 5000 字。');
      const started = row.failureCategories.length || row.hardConstraints !== 'pending' || row.outcome !== 'pending';
      const scored = scores.some(s => row.scores[s] !== null);
      if ((started || scored || row.notes.trim()) && author.kind === 'unassigned') reject('已填写内容需要明确评分来源和复核者称呼。');
      if (started && !row.notes.trim()) reject(id + '：请在备注中写明判断依据或不确定之处。');
      if (!scorable(reportRow) && (scored || row.hardConstraints !== 'pending' || row.outcome !== 'pending')) reject('没有回答的记录只能填写失败分类和备注。');
      if (row.outcome === 'exact' && !['satisfied','notApplicable'].includes(row.hardConstraints)) reject(id + '：满足需求需要先核对硬条件。');
      if (row.outcome === 'usefulApproximation' && row.hardConstraints === 'violated') reject(id + '：明确的硬条件违规不能归为有用近似。');
      if (row.hardConstraints === 'violated' && row.scores.usefulness === 2) reject(id + '：硬条件违规不能把满足需求评为 2 分。');
    }
    if (seen.size !== expected.size) reject('请保留全部记录，包括失败和未执行轮次。');
    return true;
  }
  function add(parent,tag,text='',className='') {
    const element = document.createElement(tag); element.textContent = text;
    if (className) element.className = className;
    parent.append(element); return element;
  }
  function message(text,state) { status.textContent = text; status.dataset.state = state; }
  function updateCount() {
    const finished = review.reviews.filter(r => scorable(rows.get(key(r))) && complete(r)).length;
    const diagnosedCount = review.reviews.filter(diagnosed).length;
    const source = review.reviewer.kind === 'model' ? '模型复核' : review.reviewer.kind === 'human' ? '人工复核' : '待指定来源';
    document.getElementById('count').textContent = source + '：三维已评 ' + finished + ' / ' + payload.summary.scorableAnswerCount + '；诊断已完成 ' + diagnosedCount + ' / ' + rows.size;
  }
  function changed() { dirty = true; updateCount(); message('有未导出的更改。','pending'); }
  function selectField(parent,row,field,title,options,disabled=false) {
    const wrapper = add(parent,'label',title), select = add(wrapper,'select'); select.dataset.field = field; select.disabled = disabled;
    for (const [value,label] of Object.entries(options)) { const opt = add(select,'option',label); opt.value = value; }
    select.value = scores.includes(field) ? (row.scores[field] ?? '') : row[field];
    select.addEventListener('change',() => { if (scores.includes(field)) row.scores[field] = select.value === '' ? null : Number(select.value); else row[field] = select.value; changed(); });
  }
  function render() {
    root.replaceChildren(); kind.value = review.reviewer.kind; name.value = review.reviewer.name;
    const filter = document.getElementById('filter').value;
    for (const reportRow of payload.report.rows) {
      const id = key(reportRow), row = review.reviews.find(r => key(r) === id), hasAnswer = scorable(reportRow);
      if (filter === 'pending' && (hasAnswer ? complete(row) && diagnosed(row) : diagnosed(row))) continue;
      if (filter === 'failed' && reportRow.executed && reportRow.passed) continue;
      if (filter === 'reviewed' && !(hasAnswer && complete(row))) continue;
      const c = cases.get(row.caseId), observation = observations.get(id), card = add(root,'section','','card'); card.dataset.key = id;
      add(card,'h2',(c.number ? '第 ' + c.number + ' 题' : c.id) + ' · 重复 ' + row.repetition + ' · 第 ' + row.turn + ' 轮');
      add(card,'div',[c.category,c.sourceGroup,reportRow.route,reportRow.executed ? (reportRow.passed ? '程序通过' : '程序失败') : '未执行'].filter(Boolean).join(' / '),'badge');
      add(card,'pre',turnMessage(c.turns[row.turn - 1]));
      const rubric = c.turns[row.turn - 1].reviewRubric;
      if (rubric) add(card,'pre',JSON.stringify(rubric,null,2),'rubric');
      if (c.userRubric) add(card,'p','考察点：' + c.userRubric,'rubric');
      if (c.annotation) add(card,'p','验收口径：' + c.annotation,'rubric');
      if (row.turn > 1) {
        const history = add(card,'details'); add(history,'summary','查看同一遍运行的前文');
        for (let turn = 1; turn < row.turn; turn++) {
          const prior = observations.get(key({...row,turn}));
          add(history,'pre','用户：' + turnMessage(c.turns[turn - 1]) + '\n回答：' + (prior ? prior.answer : '没有可复核的回答'));
        }
      }
      if (!reportRow.passed) add(card,'p','执行记录：' + (reportRow.failure || reportRow.jobStatus || '未执行'),'warning');
      if (observation) {
        add(card,'p','本次回答（观测记录，不能当作标准答案）','muted'); add(card,'pre',observation.answer);
        const evidence = add(card,'details'); add(evidence,'summary','候选、业务条件与执行轨迹');
        add(evidence,'pre',JSON.stringify({movies:observation.movies,catalog:observation.catalog,trace:reportRow.trace,checks:reportRow.checks},null,2));
      } else add(card,'p','没有可评分的回答；可记录失败原因。','warning');
      const scoring = add(card,'div','','scores');
      for (const [field,title] of [['factuality','事实正确'],['grounding','依据一致'],['usefulness','满足需求']]) selectField(scoring,row,field,title,{'':'待评',0:'0 · 失败',1:'1 · 部分满足',2:'2 · 满足'},!hasAnswer);
      const diagnosis = add(card,'div','','diagnosis');
      selectField(diagnosis,row,'hardConstraints','硬条件核对',constraints,!hasAnswer);
      selectField(diagnosis,row,'outcome','本条结论',outcomes,!hasAnswer);
      add(card,'div','失败分类（可多选；不能凭程序失败直接确定质量原因）','muted');
      const failures = add(card,'div','','failure-options');
      for (const [value,label] of Object.entries(failureLabels)) {
        const wrapper = add(failures,'label'); const input = add(wrapper,'input'); input.type = 'checkbox'; input.value = value; input.dataset.field = 'failureCategories'; input.checked = row.failureCategories.includes(value); add(wrapper,'span',label);
        input.addEventListener('change',() => { row.failureCategories = Object.keys(failureLabels).filter(x => x === value ? input.checked : row.failureCategories.includes(x)); changed(); });
      }
      const label = add(card,'label','判断依据 / 待核查事项'); const notes = add(label,'textarea'); notes.dataset.field = 'notes'; notes.maxLength = 5000; notes.value = row.notes;
      notes.placeholder = '记录哪条条件、哪处证据支持判断；未知的部分明确写出。'; notes.addEventListener('input',() => { row.notes = notes.value; changed(); });
    }
    if (!root.children.length) add(root,'p','当前筛选没有记录。','empty');
    updateCount();
  }
  kind.addEventListener('change',() => { review.reviewer.kind = kind.value; if (kind.value === 'unassigned') { review.reviewer.name = ''; name.value = ''; } changed(); });
  name.addEventListener('input',() => { review.reviewer.name = name.value; changed(); });
  document.getElementById('filter').addEventListener('change',render);
  document.getElementById('download').addEventListener('click',() => {
    try {
      validateReview(review);
      const blob = new Blob([JSON.stringify(review,null,2) + '\n'],{type:'application/json'}), url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = 'gying-quality-review-v2.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
      dirty = false; message('已发起下载。请确认评分文件保存成功；下次可导入继续。','success');
    } catch (error) { message(error.message,'error'); }
  });
  document.getElementById('import-review').addEventListener('change',async event => {
    const file = event.target.files[0]; if (!file) return;
    try {
      if (file.size > 4 * 1024 * 1024) reject('评分文件超过 4 MiB。');
      const imported = JSON.parse(await file.text()); validateReview(imported);
      if (dirty && !window.confirm('导入将替换尚未导出的更改，继续吗？')) { message('已取消导入。','pending'); return; }
      review = structuredClone(imported); dirty = false; render(); message('评分已导入，可继续填写。','success');
    } catch (error) { message('导入失败：' + error.message,'error'); }
    finally { event.target.value = ''; }
  });
  window.addEventListener('beforeunload',event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
  document.getElementById('run-info').textContent = '题集 ' + (payload.dataset.version || '未注明版本') + ' · 报告时间 ' + (payload.report.evaluatedAt || '未注明') + ' · ' + rows.size + ' 轮 / ' + payload.summary.answerCount + ' 条回答 / ' + payload.summary.mechanicallyFailedTurns + ' 次程序失败';
  window.qualityReviewView = {getReview:() => structuredClone(review),validateReview};
  render();
})();
