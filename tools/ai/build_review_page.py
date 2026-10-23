"""Build a local human review page; observations remain outside Git and never become reference answers."""
import argparse
import json
from pathlib import Path
import evaluate_agent as base
import comparable_evaluation as comparable

HTML = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>GYing 独立题人工复核</title>
<style>body{font:16px/1.6 system-ui;margin:0;background:#f3f5f8;color:#182334}header,main{max-width:1050px;margin:auto;padding:24px}header{position:sticky;top:0;background:#f3f5f8;z-index:1;border-bottom:1px solid #ddd}h1{margin:0;font-size:25px}.card{background:white;padding:24px;border-radius:12px;margin:18px 0;box-shadow:0 3px 12px #0001}h2{font-size:20px;margin:0 0 12px}pre{white-space:pre-wrap;word-break:break-word;background:#f5f7fa;padding:14px;border-radius:8px;font:14px/1.7 system-ui}.label{color:#536278;font-size:14px}.scores{display:flex;gap:16px;flex-wrap:wrap}select,button{font:inherit;padding:8px 12px;border:1px solid #a9b8ca;border-radius:6px;background:white}button{background:#135ed1;color:white;cursor:pointer}.warn{color:#8c4026}details{margin-top:10px}.pending{color:#66758a}#count{margin-left:15px}small{display:block}</style>
<header><h1>30 题独立留出：人工复核</h1><p>模型输出是本次观测。题目、标准和回答不用于调 prompt；此页面没有自动判质量。分数：0=失败，1=部分满足，2=满足；证据不够保留“待复核”。</p><button id="download">导出评分 JSON</button><span id="count"></span><small>下载文件仅保存本次评分。导出后用评测器汇总，不代表生产准确率。</small></header><main id="cards"></main>
<script id="data" type="application/json">__DATA__</script><script>
const payload=JSON.parse(document.getElementById('data').textContent);const reviews=payload.review.reviews;const byCase=new Map(payload.dataset.cases.map(x=>[x.id,x]));const byObservation=new Map(payload.observations.map(x=>[[x.caseId,x.repetition,x.turn].join(':'),x]));const root=document.getElementById('cards');
function add(parent,tag,text,klass){let n=document.createElement(tag);n.textContent=text;if(klass)n.className=klass;parent.append(n);return n;}
function count(){document.getElementById('count').textContent='已完成 '+reviews.filter(x=>Object.values(x.scores).every(s=>s!==null)).length+' / '+reviews.length+' 条回答';}
for(const row of payload.report.rows){let c=byCase.get(row.caseId),key=[row.caseId,row.repetition,row.turn].join(':'),obs=byObservation.get(key),review=reviews.find(x=>[x.caseId,x.repetition,x.turn].join(':')===key),card=add(root,'section','', 'card');add(card,'h2','第 '+c.number+' 题 · 重复 '+row.repetition+' · 第 '+row.turn+' 轮');add(card,'div',c.category+' / '+c.sourceGroup+' / '+(row.route||'未执行'), 'label');add(card,'pre',c.turns[row.turn-1].message);add(card,'p','用户考察点：'+c.userRubric);add(card,'p','补充验收口径：'+c.annotation);if(!obs){add(card,'p','无成功回答：'+(row.failure||row.jobStatus||'未执行'),'warn');continue;}add(card,'div','本次观测回答（链接已省略；不作标准答案）','label');add(card,'pre',obs.answer);let d=add(card,'details','');add(d,'summary','候选与实际业务过滤条件');add(d,'pre',JSON.stringify({movies:obs.movies,catalog:obs.catalog},null,2));let scores=add(card,'div','', 'scores');for(const [name,label] of [['factuality','事实正确'],['grounding','依据一致'],['usefulness','满足需求']]){let wrapper=add(scores,'label',label+' ');let select=add(wrapper,'select','');for(const [value,text] of [['','待复核'],['0','0 · 失败'],['1','1 · 部分满足'],['2','2 · 满足']]){let option=add(select,'option',text);option.value=value;}select.onchange=()=>{review.scores[name]=select.value===''?null:Number(select.value);count();};} }
document.getElementById('download').onclick=()=>{const blob=new Blob([JSON.stringify(payload.review,null,2)+'\n'],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='gying-human-review.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);};count();
</script></html>'''


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report",type=Path,required=True)
    parser.add_argument("--dataset",type=Path,required=True)
    parser.add_argument("--observations",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args(argv)
    try:
        if args.output.exists() or args.output.resolve().is_relative_to(base.ROOT): comparable.fail("Review HTML requires a new external file")
        report,dataset,observations=[comparable.read_json(p) for p in (args.report,args.dataset,args.observations)]
        if report.get("datasetSha256")!=base.digest(dataset) or observations.get("reportSha256")!=base.digest(report):
            comparable.fail("Review data must bind to the exact dataset/report")
        expected={(r["caseId"],r["repetition"],r["turn"]):r["answerSha256"] for r in report["rows"] if r.get("answerSha256")}
        seen=set()
        for row in observations["observations"]:
            key=row["caseId"],row["repetition"],row["turn"]
            if key in seen or row["answerSha256"]!=expected.get(key):comparable.fail("Observation does not match a report answer")
            seen.add(key)
        if seen!=set(expected):comparable.fail("Review observations are incomplete")
        payload={"report":report,"dataset":dataset,"observations":observations["observations"],"review":comparable.review_template(report)}
        serialized=json.dumps(payload,ensure_ascii=False).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026")
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open("x",encoding="utf-8") as f:f.write(HTML.replace("__DATA__",serialized))
        print("Human review page created; no scores inferred");return 0
    except (base.EvaluationError,OSError,ValueError,KeyError,TypeError) as exc:
        print(str(exc) if isinstance(exc,base.EvaluationError) else "Review setup invalid: "+type(exc).__name__);return 2


if __name__=="__main__":raise SystemExit(main())
