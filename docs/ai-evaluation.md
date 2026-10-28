# 可比较评测：题集、判分、复核和真实入口

评测框架与冻结结果来自2026-10-23；机制按[当前AI架构](ai-architecture.md)阅读，本文在2026-10-25整理时保留原题集、判分及历史结果边界。回归与独立题分开计数，不使用30题调整prompt，不冻结模型回答。

## 1. 两种入口和结论范围

| 入口 | 题集和用途 | 可证明的范围 |
| --- | --- | --- |
| 本机 Agent `/api/assistant` | 9场景10轮，实施者回归，默认重复2次 | 原生tool_calls、实际工具/参数、来源事实、拒绝、多轮ID；不覆盖网站身份/业务路由/队列 |
| 网站 `/api/ai/query` 与 jobs | 用户独立30题，拆成44轮，重复2次计划88轮 | 普通USER登录、业务直接检索/推荐、模型队列与桥接、网页同形历史；不覆盖浏览器UI操作或真机 |
| 受限元数据快照 | 10,432个有效片目，固定只读事务读出 | 供后续离线检索方案比较；本轮线上仍查询活库，不是冻结语料在线实验 |

独立的是问题作者。补充标注由实施者撰写，事实正确、依据一致、满足需求的人工评分仍须人完成。请求成功率、程序检查、相关性指标、人工评分和生产质量分别报告。

## 2. 本轮文件与默认行为

| 文件 | 职责 |
| --- | --- |
| [comparable_evaluation.py](../tools/ai/comparable_evaluation.py) | 分组题集冻结、六维判分、重复统计、严格报告比较与人工评分汇总 |
| [evaluate_agent.py](../tools/ai/evaluate_agent.py) | 原回归器，增加grader扩展点；原默认行为保持 |
| [evaluate_website.py](../tools/ai/evaluate_website.py) | USER会话、网站原生提交/轮询、44轮题、历史、失败/未执行计数 |
| [export_catalog_snapshot.py](../tools/ai/export_catalog_snapshot.py) | 既有source容器Python/PyMySQL受限读库；只保存元数据与资源数量，不含用户/资源链接 |
| [build_review_page.py](../tools/ai/build_review_page.py) | 从绑定的报告/观测生成本地人工复核HTML，导出0–2分JSON |
| [回归题集](evaluation/comparable-agent-v1/cases.json) | 9场景，实施者回归；独立来源组数量只有3 |
| [独立题集](evaluation/human-holdout-v1/cases.json) | 原问题、用户原标注、实施者补充口径、分组与44轮 |

网络运行必须显式 `--run`。不启Spring/Ollama/数据库/调度器，不重试推理；输出文件已存在即在执行前拒绝，不覆盖历史结果。只接受显式HTTP loopback端口、禁代理/重定向；网站适配器只允许身份、状态、query和jobs读回，不能访问admin、internal、转存、发布、任意URL。

网站需要已有普通USER会话。凭据可通过受限文件或stdin传入，不存在密码命令行参数。实际本轮按用户授权登录test，凭据只留内存，完成后禁用账号。登录和访问会产生正常设备/访问审计、Redis限流及临时AI任务，不制造收藏/浏览偏好、转存或消息发布。

## 3. 冻结什么、如何避免题目泄漏

回归schemaVersion=2，必须有version/provenance/sourceGroup/split。相同来源的英文改写、年份变体、ID追问放在同一组，不能横跨development/regression/holdout。精确归一化重复问句若跨组会报错；它不能自动识别所有语义相近题，来源组需要人工设计。

独立题保留30行原文，以箭头拆分多轮而不改写问题。唐尼问题与其追问共组，幻视系列与错误前提资源请求共组，群像别墅与凶宅线索按保守同源归组；全部保持holdout，不挪入development。没有扩写100–150题后还宣称扩写题独立。

freeze.json绑定题集整体canonical SHA-256，包含原问题、标签和分组。任何标签变更必须建立新version及新manifest；不静默覆盖冻结文件。**冻结题目/口径不等于冻结模型回答**。回答摘要哈希仅将人工评分绑定到一次观测，防止评错另一次回答，不能用于判断回答应逐字相同。

第27题修正点：需先确认评分来源与候选范围；没有获奥斯卡最佳影片本身并非逻辑冲突。涉“最近”“近五/十年”的题记录实际运行日和时间窗；平台可看/版权需要地区和查验日期；儿童适宜/镜头排除需要分级或内容依据。无证据时保留待确认，不设虚构硬标签。

## 4. 六维程序判分

| 维度 | 本机程序检查 | 局限 |
| --- | --- | --- |
| routing | 工具allowlist、required/exact工具、预算、scope、业务成功与执行成功 | 模型必须读真实工具，仍可能选了不合适的关键词 |
| arguments | 实际trace参数是否符合标签，ID由前轮证据取得 | 只评明确标注字段；web真实参数是query/language |
| retrieval | 候选ID、片名、年份/类别硬事实、空结果、查询时间 | 与回答自由文本正确性分开；错误过滤遗漏不能只靠返回值检测 |
| answer | 非空、要求的摘录与URL原样来自工具、显式短语规则 | 非空不代表有用；不做LLM judge，也不设整段冻结答案 |
| rejection | 预期错误码、空回答、未执行模型与工具 | 原回归是校验前拒绝，不覆盖所有业务伦理/不确定性回答 |
| multiTurn | 详情参数ID与返回ID等于前轮真实ID | 当前只证明具体ID链，不证明网站所有跨轮约束都正确 |

相关性标签存在时，只计算**第一张真实searchMovies页面**的Recall@k和截断 reciprocal rank，k<=3，不把重试结果并集计入。回归相关性标签主要来自星际穿越，8个已标注轮次不代表8个独立实体，更不能推断整个片库Recall=100%。没有人工相关性标签时输出null，不猜相关集合。

网站程序仅检查任务成功、非空、授权证据工具和查询时间。它没有把30题原始需求自动判成满足；人物、情绪、内容排除、实际豆瓣、排序/指代等从本轮观测进入人工页面。网站的成功空检索即使工具正确，也可能不能满足用户，须人工判分。

## 5. 重复、未执行、耗时与成本

报告保持每个case/repetition/turn。程序失败后依原回归策略不把失败回答接入下一轮；缺前轮ID/网站任务失败导致后续轮未执行，会填入失败原因。传输或响应契约失败停止整个run，不自动重发POST，以免服务端已推理但回执丢失后重复执行。

统计列出planned/executed/notExecuted/requestAttempts、各维度applicable/passed、每题成功重复次数、passRate/flaky、失败check。两次重复只描述本样本波动，没有统计显著性承诺。

p50/p95由本次请求墙钟时延计算，网站包含队列等待/模型/轮询，直接业务路由混在其中；小样本且冷热缓存混合，不是生产SLA。每次网站提交至少相隔11秒，尊重6次/分钟限流。本机回归与网站运行的最初片段发生重叠，本轮保留干扰证据，不据此进行无负载性能对比。

本机记录modelCalls/toolCalls；提供方真实usage未接入，tokens和monetaryCost保留null。上下文字符估计不拿来编美元成本，网站直接路由也不能和模型推理调用次数混算。

## 6. 版本比较的门槛

`--compare BASELINE CANDIDATE`要求两报告完整执行、题集/freeze/工具schema/selection/repeat/evaluator源码hash一致，逐行证据hash一致，重复间证据不漂移；否则返回非零并说明不满足受控比较。

现行门槛适合在实际工具证据相同的前提下比较执行/回答回归。它非常保守：模型换了检索关键词或候选顺序，证据不同就会拒绝；因此不能用于证明改变retriever后的质量提升。后者需要冻结输入语料、独立相关性标签、专门检索runner。模型回答不会被要求相同。

两次结果做配对时还需记录部署镜像/Agent包、prompt/运行配置、负载和缓存。个性化推荐还需要同一用户偏好快照，本轮未冻结test账号偏好，因此不做个性化提升对比。当前脚本仅hash模型名和执行器/工具定义，尚不能独立证明提供方权重、运行prompt/config完全一致。本轮未生成假的“旧版→新版提升”报告；此前中断run与现行源码hash不一致，仅作历史观测。

## 7. 人工评分怎样录入

成功回答生成独立review.json，每条绑定reportSha256、case/repetition/turn、answerSha256。三项score为空，0–2整数或null，boolean/越界/重复/错报告/错回答会拒绝。只有三项都填完才计入reviewedAnswers；其余计pendingAnswers。人工均分单独报告，不改机械pass。

| 分数 | 事实正确 factuality | 依据一致 grounding | 满足需求 usefulness |
| --- | --- | --- | --- |
| 0 | 杜撰/关键错误 | 核心论断与证据冲突或缺依据却保证 | 关键硬条件未满足或答非所问 |
| 1 | 核心正确但部分无法核查/有次要错误 | 有支持但混入不明依据/不充分声明 | 部分满足，清楚解释局限或合理澄清 |
| 2 | 关键事实都有可核查依据 | 回答限定在证据范围且说明未知 | 满足硬条件；或无解时有效说明与澄清 |
| 待复核 | 未掌握相关事实 | 没有足够出处 | 需要用户判断偏好/是否可接受放宽 |

本地review.html展示原问题、补充验收口径、本次回答、候选和实际过滤条件；全部用textContent，不执行回答里的HTML。凭据替换、链接省略，页面存仓库外受限目录。可以逐题展开并导出JSON，再用 `comparable_evaluation.py --review-report ... --review ...` 汇总。模型回答只作观测，评分与标准答案是不同东西。

## 8. 复现命令

在仓库根目录执行：

```powershell
python -X utf8 tools/ai/comparable_evaluation.py
python -X utf8 tools/ai/comparable_evaluation.py --run --repeat 2 --output tmp/comparable-agent/new-run.json
python -X utf8 tools/ai/comparable_evaluation.py --review-report tmp/comparable-agent/new-run.json --output tmp/comparable-agent/new-review.json
python -X utf8 tools/ai/comparable_evaluation.py --review-report tmp/comparable-agent/new-run.json --review tmp/comparable-agent/new-review.json --output tmp/comparable-agent/new-scores.json
python -X utf8 tools/ai/comparable_evaluation.py --compare tmp/comparable-agent/baseline.json tmp/comparable-agent/candidate.json --output tmp/comparable-agent/comparison.json

python -X utf8 tools/ai/evaluate_website.py
python -X utf8 tools/ai/evaluate_website.py --run --session-file E:/private/website-session.json --repeat 2 --output tmp/website-holdout/new-run.json --observations E:/private/new-observations.json
python -X utf8 tools/ai/export_catalog_snapshot.py
python -X utf8 tools/ai/build_review_page.py --dataset docs/evaluation/human-holdout-v1/cases.json --report docs/evaluation/human-holdout-v1/website-report.json --observations E:/gying-tools/releases/ai-evaluation-20261023/private/website-observations.json --output E:/private/new-review.html
python -X utf8 -m unittest discover -s tools/tests -p 'test*evaluation.py'
python -X utf8 -m unittest discover -s tools/tests -p test_evaluation_artifacts.py
```

`E:/private`只是需要替换的受限外部路径，不是随仓库提供的登录文件。重跑需另取专用USER会话；本轮test在完成后按用户要求禁用，不自动重新启用。运行前确认无其他模型任务，将本机与网站评测串行执行。

## 9. 阶段完成度

本轮完成框架、题集/分组/口径冻结、真实回归、普通账号网站运行、元数据快照、人工评分流程和详细链路文档。完整“独立质量验收”尚需人工评分；同冻结语料的独立相关性检索对比、100–150题扩展、真人点击/真机、持久usage审计和文档RAG仍待。2026-10-25已部署BGE-M3/RRF，不能继续写成embedding未实现。

最终计数和失败明细见[本机摘要](evaluation/comparable-agent-v1/README.md)、[独立题验收](evaluation/human-holdout-v1/README.md)与[机器验证摘要](evaluation/human-holdout-v1/validation-summary.json)。

## 10. 结构化查询上线后的评测范围

2026-10-25已有指令4B、BM25/BGE-M3、RRF与真实Bing搜索。旧回归器的三项原生工具清单、k<=3等判分约束属于原冻结协议；当前研究trace还包含queryCatalog/planning/retrieval。若扩展评测器，应建立新schema/题集版本并说明适用范围，不能静默改旧工具hash、题目或历史报告再宣称提升。

原30题用户复核发现大量需求未命中，这说明程序84/88通过只覆盖执行边界。后续开发对照各8轮completed也有海上场景偏题；可用性补丁真实条件/语义/Bing/队列成功，仍有人物/年份解析偏差。按[台账](history/releases.md#ai-query-20261025)读原证据，不把不同语料/模型/负载结果混算。

有用近似可在人类usefulness评分中说明；明确人物排除、身份、评分来源等硬条件仍单独核对。新的检索比较必须固定同一公共语料和人工相关性标签，串行记录加载、冷/温缓存和内存；现有严格配对工具不适用于直接证明改变retriever后的质量收益。

## 11. 2026-10-28质量复核v2与新开发题

第一阶段新增独立[quality_review.py](../tools/ai/quality_review.py)、[页面生成器](../tools/ai/build_quality_review_page.py)与[开发题v3](evaluation/development-v3/README.md)。旧comparable/website评测器、旧题集和历史报告不改写，原v1评分协议继续适用；v2用单独命令和输出目录，不能把两种评分文件混用。

v2绑定同一report/case/repetition/turn/answer哈希，保留所有计划轮次。三维分数分别统计已评/待评/均值，三项齐全才算完整评分；程序失败但有可观察回答仍能人工评分。无回答的失败或未执行轮次只允许填写失败分类及备注，不生成质量分。六类诊断允许重叠，不能把分类计数相加当作失败总数。诊断完成表示有依据备注并填写原因，或同时填写结论和硬条件核对；它与三维评分完成度分别报告。

复核文件须明确human/model来源和称呼；来源由填写者声明，工具不验证真实身份。模型复核不计入人工完成数。硬条件有违规时拒绝“有用近似”或usefulness=2；“满足需求”需要硬条件已核对或确无适用条件。诊断要写依据，未知保持待评或证据不足，不能通过猜分完成进度。

新版本地页面支持导出、导入续填、待评/程序失败/已评筛选、多轮前文和候选证据。页面无网络请求、不自动保存，离开前导出；现有输出拒绝覆盖。原观测经过链接/凭据省略，answerSha256是与原回答的绑定声明，不能对脱敏文字重算该hash来证明内容完整；来源缺失时仍须另行核查或保留待评。

本轮[汇总与验收](evaluation/quality-review-v2/README.md)仍为0人工已评/84待评、4次程序失败，88条诊断待填。新开发题10场景/15轮仅完成口径和分组冻结，未执行、未评分；它不能直接交给旧网站runner，也不能声称独立质量提升。

```powershell
# 生成全空v2模板；所有输出路径必须是新文件。
python -X utf8 tools/ai/quality_review.py --report docs/evaluation/human-holdout-v1/website-report.json --output E:/private/quality-v2-template.json
# 用户导出评分后汇总；模型与人工来源分别记录。
python -X utf8 tools/ai/quality_review.py --report docs/evaluation/human-holdout-v1/website-report.json --review E:/private/quality-v2-scored.json --output E:/private/quality-v2-summary.json
# 可加 --review E:/private/quality-v2-scored.json 生成恢复进度的新页面。
python -X utf8 tools/ai/build_quality_review_page.py --dataset docs/evaluation/human-holdout-v1/cases.json --report docs/evaluation/human-holdout-v1/website-report.json --observations E:/gying-tools/releases/ai-evaluation-20261023/private/website-observations.json --output E:/private/quality-v2-review.html
```

`E:/private`仅为需替换的受限外部输出路径。复核和生成页面均不登录网站、不启动模型、不重发任何历史请求；完成评分后再以共性失败类别决定下一轮开发验证。
